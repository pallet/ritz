(ns ritz.nrepl.simple-eval
  "Simple eval middleware for nrepl"
  (:require
   [clojure.tools.nrepl.transport :as transport]
   [clojure.main :as main])
  (:use
   [clojure.tools.nrepl.misc :only [response-for]]
   [ritz.logging :only [trace]]))

(defn evaluate
  [{:keys [code ns transport] :as msg}]
  (main/with-bindings
    (try
      (trace "Evaluating %s in %s" code ns)
      (binding [*ns* (the-ns (symbol ns))]
        (let [value (eval (read-string code))]
          (transport/send
           transport
           (response-for msg :value value :ns (-> *ns* ns-name str)))
          (transport/send transport (response-for msg :status :done))))
      (catch Exception e
        (transport/send
         transport
         (response-for
          msg
          :status :eval-error
          :ex (-> e class str)
          :root-ex (-> (#'clojure.main/root-cause e) class str)))))))

(defn simple-eval
  "nREPL Middleware for simple evaluation."
  [handler]
  (fn [{:keys [code op transport] :as msg}]
    (if (= "eval" op)
      (if-not code
        (transport/send transport (response-for msg :status #{:error :no-code}))
        (evaluate msg))
      (handler msg))))
