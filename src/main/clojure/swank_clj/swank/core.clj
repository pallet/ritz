(ns swank-clj.swank.core
  "Core of swank implementation"
  (:require
   [swank-clj.connection :as connection]
   [swank-clj.logging :as logging]
   [swank-clj.hooks :as hooks]))

;; Protocol version
(defonce *protocol-version* (atom "20100404"))

(def *current-package*)
(def *current-connection*)

(hooks/defhook *pre-reply-hook*)

(defn send-to-emacs
  "Sends a message (msg) to emacs."
  [msg]
  (connection/send-to-emacs *current-connection* msg))


(defmacro with-package-tracking [& body]
  `(let [last-ns# *ns*]
     (try
      ~@body
      (finally
       (when-not (= last-ns# *ns*)
         (send-to-emacs `(:new-package ~(str (ns-name *ns*))
                                       ~(str (ns-name *ns*)))))))))
(defn exception-causes [#^Throwable t]
  (lazy-seq
    (cons t (when-let [cause (.getCause t)]
              (exception-causes cause)))))

(defn maybe-ns [package]
  (cond
   (symbol? package) (or (find-ns package) (maybe-ns 'user))
   (string? package) (maybe-ns (symbol package))
   (keyword? package) (maybe-ns (name package))
   (instance? clojure.lang.Namespace package) package
   :else (maybe-ns 'user)))

(defmacro with-package [package & body]
  `(binding [*ns* (maybe-ns ~package)
             *current-package* (maybe-ns ~package)]
     ~@body))

(defn command-not-found [connection form buffer-package id _]
  (logging/trace "swank/eval-for-emacs: could not find fn %s" (first form))
  :swank-clj.swank/abort)

(defn execute-slime-fn*
  [connection f args-form buffer-package]
  (with-package buffer-package
    (apply f connection (eval (vec args-form)))))

(defn execute-slime-fn
  [handler]
  (fn [connection form buffer-package id f]
    (if f
      (execute-slime-fn* connection f (rest form) buffer-package)
      (handler connection form buffer-package id f))))

(defn stack-trace-string
  [throwable]
  (with-out-str
    (with-open [out-writer (java.io.PrintWriter. *out*)]
      (.printStackTrace throwable out-writer))))
