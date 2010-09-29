(ns swank-clj.swank.core
  "Core of swank implementation"
  (:require
   [swank-clj.connection :as connection]))

;; Protocol version
(defonce *protocol-version* (atom "20100404"))

(def *current-package*)
(def *current-connection*)


(defn send-to-emacs
  "Sends a message (msg) to emacs."
  [msg]
  (connection/send-to-emacs *current-connection* msg))

(defn send-repl-results-to-emacs [val]
  (send-to-emacs `(:write-string ~(str (pr-str val) "\n") :repl-result))
  nil)

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
