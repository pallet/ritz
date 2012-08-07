(ns ritz.nrepl.debug-eval
  "nREPL middleware for debug evaluation"
  (:require
   [clojure.tools.nrepl.transport :as transport]
   [clojure.main :as main]
   ritz.nrepl.commands) ;; ensure commands are loaded
  (:use
   [clojure.tools.nrepl.misc :only [response-for]]
   [clojure.tools.nrepl.middleware.interruptible-eval :only [*msg*]]
   [ritz.logging :only [trace]]))

(defn evaluate
  [{:keys [code ns session transport] :as msg}]
  (let [connection (:ritz.nrepl/connection msg)
        bindings (merge @(:bindings connection)
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
            (when-not (or (= *1 value) (#{'*1 '*2 '*3 '*e} form))
              (swap! (:bindings connection) assoc #'*3 *2 #'*2 *1 #'*1 value)))
          (catch Exception e
            (swap! (:bindings connection) assoc #'*e e)
            (main/repl-caught e)
            (transport/send
             transport
             (response-for
              msg
              :status :eval-error
              :ex (-> e class str)
              :root-ex (-> (#'clojure.main/root-cause e) class str)))))))))

(defn debug-eval
  "nREPL Middleware for debug evaluation."
  [handler]
  (fn [{:keys [code op transport] :as msg}]
    (if (= "jpda" op)
      (if-not code
        (transport/send transport (response-for msg :status #{:error :no-code}))
        (evaluate msg))
      (handler msg))))
