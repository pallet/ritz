(ns ritz.commands.basic
  (:refer-clojure :exclude [load-file])
  (:use
   [ritz.swank.commands :only [defslimefn]])
  (:require
   [ritz.clj-contrib.macroexpand :as macroexpand]
   [ritz.connection :as connection]
   [ritz.logging :as logging]
   [ritz.repl-utils.arglist :as arglist]
   [ritz.repl-utils.compile :as compile]
   [ritz.repl-utils.doc :as doc]
   [ritz.repl-utils.find :as find]
   [ritz.repl-utils.format :as format]
   [ritz.repl-utils.helpers :as helpers]
   [ritz.repl-utils.io :as io]
   [ritz.repl-utils.sys :as sys]
   [ritz.repl-utils.trace :as trace]
   [ritz.swank.core :as core]
   [ritz.swank.messages :as messages]
   [ritz.swank.utils :as utils]
   [clojure.string :as string])
  (:import
   (java.io StringReader File)
   (java.util.zip ZipFile)
   (clojure.lang LineNumberingPushbackReader)))

;; Note: For debugging purposes, keep bindings and with- macros out of commands
;; as they create catch sites.

;;;; Connection

(defslimefn connection-info [connection]
  (messages/connection-info
    (sys/get-pid)
    (clojure-version)
    (name (ns-name *ns*))
    core/protocol-version))

(defslimefn quit-lisp [connection]
  (shutdown-agents)
  (System/exit 0))

(defslimefn toggle-debug-on-swank-error [connection]
  ;; (alter-var-root #'swank.core/*debug-swank-clojure* not)
  )

;;;; Evaluation
(defn eval-region
  "Evaluate a string with source form tracking"
  [connection string]
  (compile/eval-region
   string
   (find/source-form-path (connection/request-id connection))
   0))

(defn interactive-eval* [connection string]
  (logging/trace "basic/interactive-eval* %s" string)
  (pr-str
   (first
    (core/with-namespace-tracking connection
      (eval-region connection string)))))

(defslimefn interactive-eval-region [connection string]
  (interactive-eval* connection string))

(defslimefn interactive-eval [connection string]
  (interactive-eval* connection string))

(defn eval-form
  "Evaluate form. maintaining recent result history."
  [connection form]
  (let [[value last-form exception] (try
                                      (eval-region connection form)
                                      (catch Exception e [nil nil e]))]
    (logging/trace "eval-form: value %s" value)
    (core/update-history connection last-form value exception)
    [value exception]))

(defslimefn listener-eval [connection form]
  (logging/trace "listener-eval %s" form)
  (let [[result exception] (eval-form
                            connection
                            (string/replace
                             form "#.(swank:" "(ritz.swank.commands/"))]
    (logging/trace "listener-eval: %s %s" result exception)
    (if exception
      (do
        (.printStackTrace exception)
        [:ritz.swank/abort exception])
      ((:send-repl-results-function @connection) connection [result]))))

(defmacro with-out-str-and-value
  [& body]
  `(let [s# (new java.io.StringWriter)]
     (binding [*out* s#]
       (let [result# (do ~@body)]
         [(str s#) result#]))))

(defslimefn eval-and-grab-output [connection string]
  (let [value (promise)]
    (list
     (with-out-str
       (binding [*err* *out*]
         (deliver value (eval-region connection string))))
     (pr-str (first @value)))))

(defslimefn pprint-eval [connection string]
  (format/pprint-code (first (eval-region connection string))))

;;;; Macro expansion
(defn- apply-macro-expander [expander string]
  (format/pprint-code (expander (read-string string)) false))

;; (defmacro apply-macro-expander* [expander string]
;;   `(~expander ~string))

;; (defn- apply-macro-expander [expander string]
;;   (format/pprint-code (read-string (apply-macro-expander* expander string)) false))

(defslimefn swank-macroexpand-1 [connection string]
  (apply-macro-expander macroexpand-1 string))

(defslimefn swank-expand-1 [connection string]
  (apply-macro-expander macroexpand-1 string))

(defslimefn swank-macroexpand [connection string]
  (apply-macro-expander macroexpand string))

(defslimefn swank-expand [connection string]
  (apply-macro-expander macroexpand string))

;; not implemented yet, needs walker
(defslimefn swank-macroexpand-all [connection string]
  (apply-macro-expander macroexpand/macroexpand-all string))

;;;; Compiler / Execution

;; plist of message, severity, location, references, short-message
(defn- exception-message [^Throwable t]
  {:message (.toString t)
   :severity :error
   :location (find/find-compiler-exception-location t)})

(defn secs-for-ns [ns]
  (/ ns 1000000000.0))

(defn- compile-file-for-emacs*
  "Compiles a file for emacs. Because clojure doesn't compile, this is
   simple an alias for load file w/ timing and messages."
  [file-name]
  (let [start (System/nanoTime)]
    (try
      (let [ret (or (clojure.core/load-file file-name) file-name)
            delta (- (System/nanoTime) start)]
        (messages/compilation-result nil ret (secs-for-ns delta)))
      (catch Throwable t
        (.printStackTrace t) ;; prints to *inferior-lisp*
        (messages/compilation-result
         [(exception-message t)]                        ; notes
         nil                                            ; results
         (secs-for-ns (- (System/nanoTime) start))))))) ; durations

(defn possibly-relative-path
  "If file-path can be expressed relative tot he classloader root, then
   do so"
  [file-path]
  (try
    (let [base (File. (System/getProperty "user.dir"))
          file (File. file-path)]
      (if (.startsWith (.getCanonicalPath file) (.getCanonicalPath base))
        (.substring
         (.getAbsolutePath file) (inc (count (.getAbsolutePath base))))
        file-path))))

(defslimefn compile-file-for-emacs
  [connection file-name load? & compile-options]
  (when load?
    (compile-file-for-emacs* file-name)))

(defslimefn load-file [connection file-name]
  (pr-str (clojure.core/load-file file-name)))

(defslimefn compile-string-for-emacs
  [connection string buffer position buffer-path debug]
  (let [start (System/nanoTime)
        file (java.io.File. buffer-path)
        line (io/read-position-line file position)
        ret (binding [*ns* (or (io/guess-namespace file) *ns*)]
              (compile/compile-region string buffer-path line))
        delta (- (System/nanoTime) start)]
    (messages/compilation-result nil ret (/ delta 1000000000.0))))

;;;; Describe

(defn- describe-symbol* [connection symbol-name]
  (if-let [v (try
               (ns-resolve
                (connection/request-ns connection) (symbol symbol-name))
               (catch ClassNotFoundException e nil))]
    (with-out-str (doc/print-doc v))
    (str "Unknown symbol " symbol-name)))

(defslimefn describe-symbol [connection symbol-name]
  (describe-symbol* connection symbol-name))

(defslimefn describe-function [connection symbol-name]
  (describe-symbol* connection symbol-name))

;; lisp-1, so no kinds
(defslimefn describe-definition-for-emacs [connection name kind]
  (describe-symbol* connection name))

;; lisp-1, so no kinds
(defslimefn documentation-symbol
  ([connection symbol-name default]
     (documentation-symbol connection symbol-name))
  ([connection symbol-name]
     (describe-symbol* connection symbol-name)))

;;;; Documentation
(defn- briefly-describe-symbol-for-emacs [var]
  (messages/describe
   (update-in (doc/describe var) [:doc]
              (fn [doc]
                (when doc
                  (first (string/split-lines doc)))))))

(defslimefn apropos-list-for-emacs
  ([connection name]
     (apropos-list-for-emacs connection name nil))
  ([connection name external-only?]
     (apropos-list-for-emacs connection name external-only? nil))
  ([connection name external-only? case-sensitive?]
     (apropos-list-for-emacs
      connection name external-only? case-sensitive? nil))
  ([connection name external-only? case-sensitive? package]
     (logging/trace
      "apropos-list-for-emacs: %s %s %s %s"
      name external-only? case-sensitive? package)
     (let [package (when package
                     (or (find-ns (symbol package))
                         'user))]
       (list* (map briefly-describe-symbol-for-emacs
                   (doc/apropos-list
                    (when package (utils/maybe-ns package))
                    name
                    external-only?
                    case-sensitive?
                    (connection/request-ns connection)))))))

;;;; Operator messages
(defslimefn operator-arglist [connection name package]
  (try
    (logging/trace "operator-arglist %s %s" name package)
    (when-let [arglist (arglist/arglist
                        (read-string name) (utils/maybe-ns package))]
      (pr-str arglist))
    (catch Throwable t nil)))

;;;; Package Commands
(defslimefn list-all-package-names
  ([connection] (map (comp str ns-name) (all-ns)))
  ([connection nicknames?] (list-all-package-names connection)))

(defslimefn set-package [connection name]
  (let [ns (utils/maybe-ns name)]
    (in-ns (ns-name ns))
    (list (str (ns-name ns)) (str (ns-name ns)))))

;;;; Tracing
(defslimefn swank-toggle-trace [connection fname]
  (when-let [v (ns-resolve (connection/request-ns connection) (symbol fname))]
    (if (trace/toggle-trace! v)
      (str (helpers/symbol-name-for-var v) " traced.")
      (str (helpers/symbol-name-for-var v) " untraced."))))

(defslimefn untrace-all [connection]
  (trace/untrace-all!))

;;;; Source Locations
(comment
  "Sets the default directory (java's user.dir). Note, however, that
   this will not change the search path of load-file. ")
(defslimefn set-default-directory
  ([directory & ignore]
     (System/setProperty "user.dir" directory)
     directory))


;;;; meta dot find
(defn- find-class-definition [sym]
  (or
   (when-let [ns (namespace sym)]
     (when-let [location (find/source-location-for-class (symbol ns))]
       `((~ns ~(messages/location location)))))
   (when-let [location (find/source-location-for-class sym)]
     `((~(name sym) ~(messages/location location))))))

(defn- find-ns-definition [ns]
  (when-let [location (find/source-location-for-namespace-sym ns)]
    `((~(str ns) ~(messages/location location)))))

(defn- find-var-definition [ns sym]
  (try
    (when-let [sym-var (ns-resolve ns sym)]
      (when-let [location (find/source-location-for-var sym-var)]
        `((~(str "(defn " (:name (meta sym-var)) ")")
           ~(messages/location location)))))
    (catch java.lang.ClassNotFoundException e nil)))

(defslimefn find-definitions-for-emacs [connection name]
  (let [sym (symbol name)]
    (or
     (find-var-definition (connection/request-ns connection) sym)
     (find-ns-definition ((ns-aliases (connection/request-ns connection)) sym))
     (find-ns-definition (find-ns sym))
     (find-class-definition sym)
     `((~name (:error "Source definition not found."))))))

(defslimefn throw-to-toplevel [connection]
  ;; (throw *debug-quit-exception*)
  )

(defn invoke-restart [restart]
  ((nth restart 2)))

(defslimefn invoke-nth-restart-for-emacs [connection level n]
  ;; ((invoke-restart (*sldb-restarts* (nth (keys *sldb-restarts*) n))))
  )

(defslimefn throw-to-toplevel [connection]
  ;; (if-let [restart (*sldb-restarts* :quit)]
  ;;   (invoke-restart restart))
  )

(defslimefn sldb-continue [connection]
  ;; (if-let [restart (*sldb-restarts* :continue)]
  ;;   (invoke-restart restart))
  )

(defslimefn sldb-abort [connection]
  ;; (if-let [restart (*sldb-restarts* :abort)]
  ;;   (invoke-restart restart))
  )


(defslimefn backtrace [connection start end]
  ;; (build-backtrace start end)
  )

(defslimefn buffer-first-change [connection file-name] nil)

(defn locals-for-emacs [m]
  (sort-by second
           (map #(list :name (name (first %)) :id 0
                       :value (str (second %))) m)))

(defslimefn frame-catch-tags-for-emacs [connection n] nil)
(defslimefn frame-locals-for-emacs [connection n]
  ;; (if (and (zero? n) (seq *current-env*))
  ;;   (locals-for-emacs *current-env*))
  )

(defslimefn frame-locals-and-catch-tags [connection n]
  (list (frame-locals-for-emacs n)
        (frame-catch-tags-for-emacs n)))

(defslimefn debugger-info-for-emacs [connection start end]
  ;; (build-debugger-info-for-emacs start end)
  )

(defslimefn eval-string-in-frame [connection expr n]
  ;; (if (and (zero? n) *current-env*)
  ;;   (with-bindings *current-env* (eval expr)))
  )

(defslimefn frame-source-location [connection n]
  ;; (source-location-for-frame
  ;;  (nth (.getStackTrace *current-exception*) n))
  )

;; Older versions of slime use this instead of the above.
(defslimefn frame-source-location-for-emacs [connection n]
  ;; (source-location-for-frame
  ;;  (nth (.getStackTrace *current-exception*) n))
  )

(defslimefn create-repl [connection target] '("user" "user"))

;;; Threads
(def ^{:private true} thread-list (atom []))

(defn- get-root-group [^java.lang.ThreadGroup tg]
  (if-let [parent (.getParent tg)]
    (recur parent)
    tg))

(defn- get-thread-list []
  (let [rg (get-root-group (.getThreadGroup (Thread/currentThread)))
        arr (make-array Thread (.activeCount rg))]
    (.enumerate rg arr true)
    (seq arr)))

(defn- extract-info [^Thread t]
  (map str [(.getId t) (.getName t) (.getPriority t) (.getState t)]))

(defslimefn list-threads
  "Return a list (LABELS (ID NAME STATUS ATTRS ...) ...).
LABELS is a list of attribute names and the remaining lists are the
corresponding attribute values per thread."
  [connection]
  (reset! thread-list (get-thread-list))
  (let [labels '(id name priority state)]
    (cons labels (map extract-info @thread-list))))

;;; TODO: Find a better way, as Thread.stop is deprecated
(defslimefn kill-nth-thread [connection index]
  (when index
    (when-let [thread (nth @thread-list index nil)]
      (println "Thread: " thread)
      (.stop thread))))

(defslimefn quit-thread-browser [connection]
  (reset! thread-list []))
