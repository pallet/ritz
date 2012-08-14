(ns ritz.nrepl.debug-eval
  "nREPL middleware for debug evaluation"
  (:require
   [clojure.tools.nrepl.transport :as transport]
   [clojure.main :as main]
   ritz.nrepl.commands
   ritz.nrepl.debug
   ritz.repl-utils.doc) ;; ensure commands are loaded
  (:use
   [clojure.tools.nrepl.misc :only [response-for]]
   [clojure.tools.nrepl.middleware.interruptible-eval :only [*msg*]]
   [ritz.connection :only [bindings bindings-assoc!]]
   [ritz.logging :only [trace]]))

(defn evaluate
  [{:keys [op code ns session transport] :as msg}]
  (let [connection (:ritz.nrepl/connection msg)
        bindings (merge (bindings connection)
                        (when ns {#'*ns* (-> ns symbol find-ns)}))
        out (bindings #'*out*)
        err (bindings #'*err*)]
    (with-bindings bindings
      (binding [*msg* msg]
        (try
          (trace "Evaluating %s in %s" code ns)
          (let [connection (:ritz.nrepl/connection msg)
                form (read-string code)
                op (resolve (first form))
                args (map eval (rest form))
                _ (trace "op %s args %s" op (vec args))
                value (apply op connection args)]
            (trace "value %s" value)
            (.flush ^java.io.Writer err)
            (.flush ^java.io.Writer out)
            (transport/send
             transport
             (response-for msg :value value :ns (-> *ns* ns-name str)))
            (transport/send transport (response-for msg :status :done))
            (trace "Evaluation complete %s" value)
            ;; (when-not (or (= *1 value) (#{'*1 '*2 '*3 '*e} form))
            ;;   (bindings-assoc! connection #'*3 *2 #'*2 *1 #'*1 value))
            )
          (catch Exception e
            (bindings-assoc! connection #'*e e)
            (main/repl-caught e)
            (transport/send
             transport
             (response-for
              msg
              :status :eval-error
              :ex (-> e class str)
              :root-ex (-> (#'clojure.main/root-cause e) class str)))))))))


(defn debug-eval*
  [handler {:keys [code op transport] :as msg}]
  (let [connection (:ritz.nrepl/connection msg)]
    (cond
      (#{"jpda"} op)
      (if-not code
        (transport/send
         transport (response-for msg :status #{:error :no-code}))
        (evaluate msg))

      (= "break-on-exception" op)
      (ritz.nrepl.debug/break-on-exception connection (or (:enable msg) true))

      (= "continue" op)
      (do
        (ritz.nrepl.debug/continue connection (read-string (:thread-id msg)))
        (transport/send transport (response-for msg :status :done)))

      (= "abort-level" op)
      (do
        (ritz.nrepl.debug/abort-level connection (read-string (:thread-id msg)))
        (transport/send transport (response-for msg :status :done)))

      (= "quit-to-top-level" op)
      (do
        (ritz.nrepl.debug/quit-to-top-level
         connection (read-string (:thread-id msg)))
        (transport/send transport (response-for msg :status :done)))

      :else (handler msg))))

(defn debug-eval
  "nREPL Middleware for debug evaluation."
  [handler]
  (fn [msg]
    (debug-eval* handler msg)))
