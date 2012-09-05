(ns ritz.nrepl.simple-eval
  "Simple eval middleware for nrepl"
  (:require
   [clojure.string :as string]
   [clojure.tools.nrepl.transport :as transport]
   [clojure.main :as main])
  (:use
   [clojure.tools.nrepl.misc :only [response-for]]
   [clojure.tools.nrepl.middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.middleware.interruptible-eval :only [*msg*]]
   [ritz.debugger.connection :only [bindings bindings-assoc!]]
   [ritz.logging :only [trace]]))

(defn evaluate
  [{:keys [code ns transport] :as msg}]
  (let [connection (:ritz.nrepl/connection msg)
        bindings (merge (bindings connection)
                        (when ns {#'*ns* (-> ns symbol find-ns)}))
        out (get bindings #'*out*)
        err (get bindings #'*err*)]
    (with-bindings bindings
      (binding [*msg* msg]
        (try
          (trace "simple-eval connection %s" connection)
          (trace "Evaluating %s in %s" code ns)
          (trace "*e is %s" *e)
          (let [form (if (string/blank? code) nil (read-string code))
                value (eval form)]
            (trace "value %s" value)
            (.flush ^java.io.Writer err)
            (.flush ^java.io.Writer out)
            (transport/send
             transport
             (response-for msg :value value :ns (-> *ns* ns-name str)))
            (transport/send transport (response-for msg :status :done))
            (trace "Evaluation complete %s" value)
            (when-not (or (= *1 value) (#{'*1 '*2 '*3 '*e} form))
              (bindings-assoc! connection #'*3 *2 #'*2 *1 #'*1 value)))
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

(defn simple-eval
  "nREPL Middleware for simple evaluation."
  [handler]
  (fn [{:keys [code op transport] :as msg}]
    (if (= "eval" op)
      (if-not code
        (transport/send transport (response-for msg :status #{:error :no-code}))
        (evaluate msg))
      (handler msg))))

(set-descriptor!
 #'simple-eval
 {:handles
  {"jpda-eval"
   {:doc
    (str "Evaluate code in the debugger's own VM.")
    :requires
    {"code" "A boolean true or false"}
    :returns {"status" "done"}}}})
