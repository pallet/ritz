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
   [clojure.tools.nrepl.middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.middleware.interruptible-eval :only [*msg*]]
   [ritz.debugger.connection :only [bindings bindings-assoc!]]
   [ritz.logging :only [trace]]
   [ritz.nrepl.middleware :only [args-for-map read-when]]
   [ritz.nrepl.project :only [reload reset-repl]]))

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

      (= "reload-project" op)
      (let [f #(transport/send transport (response-for msg :status :done))]
        (reload connection)
        (trace "reload-project done")
        (f))

      (= "reset-repl" op)
      (do
        (reset-repl connection)
        (trace "reset-repl done")
        (transport/send transport (response-for msg :status :done)))

      :else (handler msg))))

(defn debug-eval
  "nREPL Middleware for debug evaluation."
  [handler]
  (fn [msg]
    (debug-eval* handler msg)))

(set-descriptor!
 #'debug-eval
 {:handles
  {"break-on-exception"
   {:doc
    (str "Flag to control whether exceptions break into the debugger.")
    :requires
    {"flag" "A boolean true or false"}
    :returns {"status" "done"}}
   "invoke-restart"
   {:doc
    (str "Invoke the specified restart number or name for the specified "
         "thread.")
    :requires
    {"thread-id" "The thread executing the code to disassemble."
     "restart-number" "The (ordinal) number of the restart to invoke"
     "restart-name" "The name of the restart to invoke."}
    :returns {"status" "done"}}
   "frame-eval"
   {:doc
    (str "Evaluate code with in the locals environment of the specified frame.")
    :requires
    {"thread-id" "The thread executing the code to disassemble."
     "frame-number" "The stack frame to return locals for."
     "code" "The expression to evaluate"}
    :returns {"status" "done"}
    :optional {"pprint" "Flag to specify pretty-printing of the result"}}
   "frame-source"
   {:doc
    (str "Locate the source code for the specified frame. The location is "
         "passed back in a name value list specifying file, zip, and line "
         "number.")
    :requires
    {"thread-id" "The thread executing the code to disassemble."
     "frame-number" "The stack frame to return locals for."}
    :returns {"status" "done"}}
   "frame-locals"
   {:doc
    (str "List the local variables visible in the specified frame.  "
         "Each local is presented in a list with name and value "
         "components.")
    :requires
    {"thread-id" "The thread executing the code to disassemble."
     "frame-number" "The stack frame to return locals for."}
    :returns {"status" "done"}}
   "disassemble-frame"
   {:doc "Disassemble the code associated with the specified frame."
    :requires
    {"thread-id" "The thread executing the code to disassemble."
     "frame-number" "The stack frame to disassemble."}
    :returns {"status" "done"}}
   "jpda"
   {:doc
    (str "Evaluate code in the debugger's own VM.")
    :requires
    {"code" "A boolean true or false"}
    :returns {"status" "done"}}}})
