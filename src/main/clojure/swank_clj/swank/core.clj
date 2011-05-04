(ns swank-clj.swank.core
  "Base namespace for swank"
  (:require
   [swank-clj.connection :as connection]
   [swank-clj.hooks :as hooks]
   [swank-clj.logging :as logging]
   [swank-clj.repl-utils.helpers :as helpers]
   [swank-clj.swank.utils :as utils]
   [clojure.string :as string]))

;; Protocol version
(defonce protocol-version "20101113")

(hooks/defhook pre-reply-hook)

(defmacro with-namespace-tracking [connection & body]
  `(let [last-ns# *ns*]
     (try
      ~@body
      (finally
       (when-not (= last-ns# *ns*)
         (connection/send-to-emacs
          ~connection
          `(:new-package ~(str (ns-name *ns*))
                         ~(str (ns-name *ns*)))))))))

(defn command-not-found [connection form buffer-package id _]
  (logging/trace "swank/eval-for-emacs: could not find fn %s" (first form))
  :swank-clj.swank/abort)

(defmacro with-namespace [connection & body]
  `(binding [*ns* (connection/request-ns ~connection)]
     ~@body))

(defn execute-slime-fn*
  [connection f args-form buffer-package]
  (with-namespace connection
    (apply f connection (eval (vec args-form)))))

(defn execute-slime-fn
  [handler]
  (fn [connection form buffer-package id f]
    (if f
      (execute-slime-fn* connection f (rest form) buffer-package)
      (handler connection form buffer-package id f))))

(defn update-history
  [connection last-form value exception]
  (when (and last-form (not (#{'*1 '*2 '*3 '*e} last-form)))
    (let [history (drop
                   1 (connection/add-result-to-history connection value))]
      (set! *3 (fnext history))
      (set! *2 (first history))
      (set! *1 value)))
  (when exception
    (set! *e exception)))

(defn lines
  "Split a string in"
  [s]
  string/join
  (.split #^String s (System/getProperty "line.separator")))
