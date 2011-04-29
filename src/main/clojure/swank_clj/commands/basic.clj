(ns swank-clj.commands.basic
  (:refer-clojure :exclude [load-file])
  (:use
   ;; (swank util)
   ;; (swank.util.concurrent thread)
   ;; (swank.util string clojure)
   swank-clj.commands)
  (:require
   [swank-clj.logging :as logging]
   [swank-clj.util.sys :as sys]
   [swank-clj.messages :as messages]
   [swank-clj.swank.core :as core]
   [swank-clj.swank.find :as find]
   [swank-clj.swank.basic :as basic]
   [swank-clj.connection :as connection]
   [swank-clj.clj-contrib.pprint :as pprint]
   [swank-clj.clj-contrib.macroexpand :as macroexpand])
  (:import
   (java.io StringReader File)
   (java.util.zip ZipFile)
   (clojure.lang LineNumberingPushbackReader)))

;; Note: For debugging purposes, keep bindings and with- macros out of commands,
;; as they create catch sites.

;;;; Connection

(defslimefn connection-info [connection]
  `(:pid ~(sys/get-pid)
         :style :spawn
         :lisp-implementation (:type "Clojure"
                                     :name "clojure"
                                     :version ~(clojure-version))
         :package (:name ~(name (ns-name *ns*))
                         :prompt ~(name (ns-name *ns*)))
         :version ~(deref core/*protocol-version*)))

(defslimefn quit-lisp [connection]
  (System/exit 0))

(defslimefn toggle-debug-on-swank-error [connection]
  ;; (alter-var-root #'swank.core/*debug-swank-clojure* not)
  )

;;;; Evaluation
(defslimefn interactive-eval-region [connection string]
  (pr-str (first (basic/eval-region string))))

(defslimefn interactive-eval [connection string]
  (pr-str (first (basic/eval-region string))))

(defslimefn listener-eval [connection form]
  (let [[result exception] (basic/eval-form connection form)]
    (logging/trace "listener-eval: %s %s" result exception)
    (if exception
      [:swank-clj.swank/abort exception]
      (connection/send-to-emacs connection (messages/repl-result result)))))

(defslimefn eval-and-grab-output [connection string]
  (with-local-vars [retval nil]
    (list (with-out-str
            (var-set retval (pr-str (first (basic/eval-region string)))))
          (var-get retval))))

(defslimefn pprint-eval [connection string]
  (pprint/pretty-pr-code (first (basic/eval-region string))))

;;;; Macro expansion

(defn- apply-macro-expander [expander string]
  (pprint/pretty-pr-code (expander (read-string string))))

(defslimefn swank-macroexpand-1 [connection string]
  (apply-macro-expander macroexpand-1 string))

(defslimefn swank-macroexpand [connection string]
  (apply-macro-expander macroexpand string))

;; not implemented yet, needs walker
(defslimefn swank-macroexpand-all [connection string]
  (apply-macro-expander macroexpand/macroexpand-all string))

;;;; Compiler / Execution

(def *compiler-exception-location-re* #"Exception:.*\(([^:]+):([0-9]+)\)")
(defn- guess-compiler-exception-location [#^Throwable t]
  (when (instance? clojure.lang.Compiler$CompilerException t)
    (let [[match file line] (re-find *compiler-exception-location-re* (str t))]
      (when (and file line)
        `(:location (:file ~file) (:line ~(Integer/parseInt line)) nil)))))

;; TODO: Make more and better guesses
(defn- exception-location [#^Throwable t]
  (or (guess-compiler-exception-location t)
      '(:error "No error location available")))

;; plist of message, severity, location, references, short-message
(defn- exception-to-message [#^Throwable t]
  `(:message ~(.toString t)
             :severity :error
             :location ~(exception-location t)
             :references nil
             :short-message ~(.toString t)))

(defn- compile-file-for-emacs*
  "Compiles a file for emacs. Because clojure doesn't compile, this is
   simple an alias for load file w/ timing and messages. This function
   is to reply with the following:
     (:swank-compilation-unit notes results durations)"
  [file-name]
  (let [start (System/nanoTime)]
    (try
      (let [ret (clojure.core/load-file file-name)
            delta (- (System/nanoTime) start)]
        `(:compilation-result nil ~(pr-str ret) ~(/ delta 1000000000.0)))
      (catch Throwable t
        (let [delta (- (System/nanoTime) start)
              causes (core/exception-causes t)
              num (count causes)]
          (.printStackTrace t) ;; prints to *inferior-lisp*
          `(:compilation-result
            ~(map exception-to-message causes) ;; notes
            nil                                ;; results
            ~(/ delta 1000000000.0)            ;; durations
            ))))))

(defslimefn compile-file-for-emacs
  [connection file-name load? & compile-options]
  (when load?
    (compile-file-for-emacs* file-name)))

(defslimefn load-file [connection file-name]
  (pr-str (clojure.core/load-file file-name)))

(defn- line-at-position [file position]
  (try
    (with-open [f (java.io.LineNumberReader. (java.io.FileReader. file))]
      (.skip f position)
      (.getLineNumber f))
    (catch Exception e 1)))

(defslimefn compile-string-for-emacs
  [connection string buffer position directory debug]
  (let [start (System/nanoTime)
        line (line-at-position directory position)
        ret (do
              (when-not (= (name (ns-name *ns*)) core/*current-package*)
                (throw (clojure.lang.Compiler$CompilerException.
                        directory line
                        (Exception. (str "No such namespace: "
                                         core/*current-package*)))))
              (basic/compile-region string directory line))
        delta (- (System/nanoTime) start)]
    `(:compilation-result nil ~(pr-str ret) ~(/ delta 1000000000.0))))

;;;; Describe

(defn- describe-to-string [var]
  (with-out-str
    (print-doc var)))

(defn- describe-symbol* [symbol-name]
  (if-let [v (try
               (ns-resolve
                (core/maybe-ns core/*current-package*) (symbol symbol-name))
               (catch ClassNotFoundException e nil))]
    (describe-to-string v)
    (str "Unknown symbol " symbol-name)))

(defslimefn describe-symbol [connection symbol-name]
  (describe-symbol* symbol-name))

(defslimefn describe-function [connection symbol-name]
  (describe-symbol* symbol-name))

;; Only one namespace... so no kinds
(defslimefn describe-definition-for-emacs [connection name kind]
  (describe-symbol* name))

;; Only one namespace... so only describe symbol
(defslimefn documentation-symbol
  ([connection symbol-name default] (documentation-symbol symbol-name))
  ([connection symbol-name] (describe-symbol* symbol-name)))

;;;; Documentation

(defn- briefly-describe-symbol-for-emacs [var]
  (let [lines (fn [s] (.split #^String s (System/getProperty "line.separator")))
        [_ symbol-name arglists d1 d2 & __] (lines (describe-to-string var))
        macro? (= d1 "Macro")]
    (list :designator symbol-name
          (cond
           macro? :macro
           (:arglists (meta var)) :function
           :else :variable)
          (apply str (concat arglists (if macro? d2 d1))))))

(defn- make-apropos-matcher [pattern case-sensitive?]
  (let [pattern (java.util.regex.Pattern/quote pattern)
        pat (re-pattern (if case-sensitive?
                          pattern
                          (format "(?i:%s)" pattern)))]
    (fn [var] (re-find pat (pr-str var)))))

(defn- apropos-symbols [string external-only? case-sensitive? package]
  (let [packages (or (when package [package]) (all-ns))
        matcher (make-apropos-matcher string case-sensitive?)
        lister (if external-only? ns-publics ns-interns)]
    (filter matcher
            (apply concat (map (comp (partial map second) lister)
                               packages)))))

(defn- present-symbol-before
  "Comparator such that x belongs before y in a printed summary of symbols.
Sorted alphabetically by namespace name and then symbol name, except
that symbols accessible in the current namespace go first."
  [x y]
  (let [accessible?
        (fn [var] (= (ns-resolve (core/maybe-ns core/*current-package*)
                                 (:name (meta var)))
                     var))
        ax (accessible? x) ay (accessible? y)]
    (cond
     (and ax ay) (compare (:name (meta x)) (:name (meta y)))
     ax -1
     ay 1
     :else (let [nx (str (:ns (meta x))) ny (str (:ns (meta y)))]
             (if (= nx ny)
               (compare (:name (meta x)) (:name (meta y)))
               (compare nx ny))))))

(defslimefn apropos-list-for-emacs
  ([connection name]
     (apropos-list-for-emacs connection name nil))
  ([connection name external-only?]
     (apropos-list-for-emacs connection name external-only? nil))
  ([connection name external-only? case-sensitive?]
     (apropos-list-for-emacs
      connection name external-only? case-sensitive? nil))
  ([connection name external-only? case-sensitive? package]
     (let [package (when package
                     (or (find-ns (symbol package))
                         'user))]
       (map briefly-describe-symbol-for-emacs
            (sort present-symbol-before
                  (apropos-symbols name external-only? case-sensitive?
                                   package))))))

;;;; Operator messages
(defslimefn operator-arglist [connection name package]
  (try
    (let [f (read-string name)]
      (cond
       (keyword? f) "([map])"
       (symbol? f) (let [var (ns-resolve (core/maybe-ns package) f)]
                     (if-let [args (and var (:arglists (meta var)))]
                       (pr-str args)
                       nil))
       :else nil))
    (catch Throwable t nil)))

;;;; Package Commands

(defslimefn list-all-package-names
  ([connection] (map (comp str ns-name) (all-ns)))
  ([connection nicknames?] (list-all-package-names)))

(defslimefn set-package [connection name]
  (let [ns (core/maybe-ns name)]
    (in-ns (ns-name ns))
    (list (str (ns-name ns))
          (str (ns-name ns)))))

;;;; Tracing

(defonce traced-fn-map {})

(defn- trace-fn-call [sym f args]
  (let [fname (symbol (str (.name (.ns sym)) "/" (.sym sym)))]
    (println (str "Calling")
             (apply str (take 240 (pr-str (when fname (cons fname args)) ))))
    (let [result (apply f args)]
      (println (str fname " returned " (apply str (take 240 (pr-str result)))))
      result)))

(defslimefn swank-toggle-trace [connection fname]
  (when-let [sym (ns-resolve
                  (core/maybe-ns core/*current-package*) (symbol fname))]
    (if-let [f# (get traced-fn-map sym)]
      (do
        (alter-var-root #'traced-fn-map dissoc sym)
        (alter-var-root sym (constantly f#))
        (str " untraced."))
      (let [f# @sym]
        (alter-var-root #'traced-fn-map assoc sym f#)
        (alter-var-root sym
                        (constantly
                         (fn [& args]
                           (trace-fn-call sym f# args))))
        (str " traced.")))))

(defslimefn untrace-all [connection]
  (doseq [sym (keys traced-fn-map)]
    (swank-toggle-trace (.sym sym))))

;;;; Source Locations
(comment
  "Sets the default directory (java's user.dir). Note, however, that
   this will not change the search path of load-file. ")
(defslimefn set-default-directory
  ([directory & ignore]
     (System/setProperty "user.dir" directory)
     directory))


;;;; meta dot find


(defn- namespace-to-path [ns]
  (let [#^String ns-str (name (ns-name ns))
        last-dot-index (.lastIndexOf ns-str ".")]
    (if (pos? last-dot-index)
      (-> (.substring ns-str 0 last-dot-index)
          (.replace \- \_)
          (.replace \. \/)))))

(defn- classname-to-path [class-name]
  (namespace-to-path
   (symbol (.replace class-name \_ \-))))

(defn source-location-for-frame [#^StackTraceElement frame]
  (let [line     (.getLineNumber frame)
        filename (if (.. frame getFileName (endsWith ".java"))
                   (.. frame getClassName (replace \. \/)
                       (substring 0 (.lastIndexOf (.getClassName frame) "."))
                       (concat (str File/separator (.getFileName frame))))
                   (let [ns-path (classname-to-path
                                  ((re-find #"(.*?)\$"
                                            (.getClassName frame)) 1))]
                     (if ns-path
                       (str ns-path File/separator (.getFileName frame))
                       (.getFileName frame))))
        path     (find/slime-find-file filename)]
    `(:location ~path (:line ~line) nil)))

(defn- namespace-to-filename [ns]
  (str (-> (str ns)
           (.replaceAll "\\." File/separator)
           (.replace \- \_ ))
       ".clj"))

(defn- find-ns-definition [ns]
  (when-let [path (and ns (find/slime-find-file (namespace-to-filename ns)))]
    `((~(str ns) (:location ~path (:line 1) nil)))))

(defn- find-var-definition [sym-name]
  (try
   (let [sym-var (ns-resolve (core/maybe-ns core/*current-package*) sym-name)]
     (if-let [meta (and sym-var (meta sym-var))]
       (if-let [path (find/slime-find-file (:file meta))]
         `((~(str "(defn " (:name meta) ")")
            (:location
             ~path
             (:line ~(:line meta))
             nil)))
         `((~(str (:name meta))
            (:error "Source definition not found."))))))
   (catch java.lang.ClassNotFoundException e nil)))

(defslimefn find-definitions-for-emacs [connection name]
  (let [sym-name (read-string name)]
    (or (find-var-definition sym-name)
        (find-ns-definition
         ((ns-aliases (core/maybe-ns core/*current-package*)) sym-name))
        (find-ns-definition (find-ns sym-name))
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
  ;;   (with-bindings *current-env*
  ;;     (eval expr)))
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

(def #^{:private true} thread-list (atom []))

(defn- get-root-group [#^java.lang.ThreadGroup tg]
  (if-let [parent (.getParent tg)]
    (recur parent)
    tg))

(defn- get-thread-list []
  (let [rg (get-root-group (.getThreadGroup (Thread/currentThread)))
        arr (make-array Thread (.activeCount rg))]
    (.enumerate rg arr true)
    (seq arr)))

(defn- extract-info [#^Thread t]
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
