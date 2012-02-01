(ns ritz.swank.core
  "Base namespace for swank"
  (:require
   [ritz.connection :as connection]
   [ritz.hooks :as hooks]
   [ritz.logging :as logging]
   [ritz.repl-utils.helpers :as helpers]
   [ritz.swank.commands :as commands]
   [ritz.swank.messages :as messages]
   [ritz.swank.utils :as utils]
   [clojure.string :as string]))

;; Protocol version
(defonce protocol-version "20101113")

(hooks/defhook pre-reply-hook)
(hooks/defhook new-connection-hook)

(defprotocol ReplResult
  (write-result [value connection options] "Write the given value to the repl"))

(extend-type Object
  ReplResult
  (write-result [value connection options]
    (connection/send-to-emacs connection (messages/default-repl-result value options))))

(defn write-result-to-emacs
  [connection value & {:keys [terminator] :as options}]
  (if (nil? value)
    (connection/send-to-emacs
     connection (messages/default-repl-result value options))
    (write-result value connection options)))

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
  :ritz.swank/abort)

(defmacro with-namespace [connection & body]
  `(binding [*ns* (connection/request-ns ~connection)]
     ~@body))

;; (defn map-swank-ns
;;   [form]
;;   (if (and (list? form) (= 'quote (first form)) (symbol? (second form)))
;;     (let [sym (second form)]
;;       (if (= "swank" (namespace sym))
;;         `'~(symbol "ritz.swank.commands" (name sym))
;;         form))
;;     form))

(defn execute-slime-fn*
  [connection f args-form buffer-package]
  (letfn [(process-arg [arg]
            (logging/trace "execute-slime-fn* process-arg %s" (pr-str arg))
            (if (and (sequential? arg) (not= 'quote (first arg)))
              (let [[sub-fn sub-args]

                    (let [[f & args] arg]
                      (logging/trace
                       "execute-slime-fn* checking %s"
                       (pr-str f))
                      (when (and (symbol? f) (= "swank" (namespace f)))
                        [(commands/slime-fn (symbol (name f))) args]))]
                (logging/trace
                 "execute-slime-fn* process-arg %s %s %s"
                 (pr-str arg) (pr-str sub-fn) (pr-str sub-args))
                (if sub-fn
                  (execute-slime-fn* connection sub-fn sub-args buffer-package)
                  (eval (vec arg))))
              (eval arg)))]
    (logging/trace
     "execute-slime-fn* %s %s %s" (pr-str f) (meta f) (pr-str args-form))
    (with-namespace connection
      (if (:ritz.swank.commands/swank-fn (meta f))
        (apply f connection (map process-arg args-form))
        (apply f (eval (vec args-form)))))))

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
  (.split ^String s (System/getProperty "line.separator")))
