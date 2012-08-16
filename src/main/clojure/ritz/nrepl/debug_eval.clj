(ns ritz.nrepl.debug-eval
  "nREPL middleware for debug evaluation"
  (:require
   [clojure.tools.nrepl.transport :as transport]
   [clojure.main :as main]
   [ritz.jpda.debug :as debug]
   [ritz.nrepl.debug :as nrepl-debug]
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

(defmulti transform-value "Transform a value for output" type)

(defmethod transform-value :default [v] v)

(defmethod transform-value clojure.lang.PersistentVector
  [v]
  (list* v))

(defn args-for-map
  "Return a value list based on a map. The keys are converted to strings."
  [m]
  (trace "args-for-map %s" m)
  (list* (mapcat #(vector (name (key %)) (transform-value (val %))) m)))

(defn read-when
  "Read from the string passed if it is not nil"
  [s]
  (when s (read-string s)))

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

      (= "debugger-info" op)
      (do
        (ritz.nrepl.debug/invoke-restart
         connection (read-string (:thread-id msg))
         (read-when (:restart-number msg))
         (read-when (:restart-name msg)))
        (transport/send transport (response-for msg :status :done)))

      (= "invoke-restart" op)
      (do
        (ritz.nrepl.debug/invoke-restart
         connection (read-string (:thread-id msg))
         (read-when (:restart-number msg))
         (read-when (:restart-name msg)))
        (transport/send transport (response-for msg :status :done)))

      (= "frame-eval" op)
      (let [v (nrepl-debug/frame-eval
               connection
               (read-string (:thread-id msg))
               (read-string (:frame-number msg))
               (read-string (:code msg))
               (read-when (:pprint msg)))]
        (transport/send transport (response-for msg :value (args-for-map v)))
        (transport/send transport (response-for msg :status :done)))

      (= "frame-source" op)
      (let [v (nrepl-debug/frame-source
               connection
               (read-string (:thread-id msg))
               (read-string (:frame-number msg)))]
        (transport/send
         transport
         (response-for
          msg :value
          (if v
            (args-for-map v)
            (list :error "Could not find source location"))))
        (transport/send transport (response-for msg :status :done)))

      (= "frame-locals" op)
      (let [v (nrepl-debug/frame-locals
               connection
               (read-string (:thread-id msg))
               (read-string (:frame-number msg)))]
        (transport/send transport (response-for msg :value (args-for-map v)))
        (transport/send transport (response-for msg :status :done)))

      (= "disassemble-frame" op)
      (let [v (nrepl-debug/disassemble-frame
               connection
               (read-string (:thread-id msg))
               (read-string (:frame-number msg)))]
        (transport/send transport (response-for msg :value (args-for-map v)))
        (transport/send transport (response-for msg :status :done)))


      :else (handler msg))))

(defn debug-eval
  "nREPL Middleware for debug evaluation."
  [handler]
  (fn [msg]
    (debug-eval* handler msg)))
