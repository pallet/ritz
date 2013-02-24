(ns ritz.nrepl.debug-eval
  "nREPL middleware for debug evaluation"
  (:require
   [clojure.tools.nrepl.transport :as transport]
   [clojure.main :as main]
   [ritz.debugger.exception-filters :as exception-filters]
   [ritz.jpda.debug :as debug]
   [ritz.nrepl.debug :as nrepl-debug]
   ritz.nrepl.commands
   ritz.repl-utils.doc) ;; ensure commands are loaded
  (:use
   [clojure.java.io :only [writer]]
   [clojure.tools.nrepl.misc :only [response-for]]
   [clojure.tools.nrepl.middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.middleware.interruptible-eval :only [*msg*]]
   [ritz.debugger.connection :only [bindings bindings-assoc!]]
   [ritz.logging :only [trace]]
   [ritz.nrepl.connections :only [add-message-reply-hook]]
   [ritz.nrepl.middleware :only [args-for-map read-when]]
   [ritz.nrepl.project :only [load-project reload reset-repl lein]]
   [ritz.nrepl.rexec :only [rexec]]
   [ritz.repl-utils.io :only [streams-for-out]]))

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


(defn breakpoints-recreate [connection msg reply]
  (nrepl-debug/breakpoints-recreate connection msg))

(defn debug-eval*
  [handler {:keys [code op transport] :as msg}]
  (let [connection (:ritz.nrepl/connection msg)]
    (cond
     (#{"jpda"} op)
     (if-not code
       (transport/send
        transport (response-for msg :status #{:error :no-code}))
       (evaluate msg))

     ;; The load file op needs to be proxied, so we can keep track of file
     ;; loads to reset breakpoints, etc.
     (= "load-file" op)
     (do
       (trace "LOAD FILE")
       (add-message-reply-hook connection msg breakpoints-recreate)
       (rexec (:vm-context connection) msg))

     (= "break-on-exception" op)
     (ritz.nrepl.debug/break-on-exception connection (:enable msg true))

     (= "exception-filters" op)
     (do
       (trace (pr-str (exception-filters/exception-filters connection)))
       (transport/send
          transport
          (response-for
           msg
           :value (args-for-map
                   {:filters
                    (map
                     #(assoc %1 :id %2)
                     (exception-filters/exception-filters connection)
                     (range))})))
       (transport/send transport (response-for msg :status :done)))

     (= "exception-filters-save" op)
     (do
       (exception-filters/spit-exception-filters connection)
       (transport/send
        transport
        (response-for
         msg :value (args-for-map
                     {:saved (exception-filters/exception-filters-file)})))
       (transport/send transport (response-for msg :status :done)))

     (= "exception-filters-enable" op)
     (let [id (read-when (:filter-id msg))]
       (if (not id)
         (transport/send
          transport (response-for
                     msg :status #{:error :missing-id} :value {:filter-id id}))
         (do
           (exception-filters/exception-filter-enable! connection id)
           (transport/send
            transport (response-for msg :value (args-for-map {:enabled id})))
           (transport/send transport (response-for msg :status :done)))))

     (= "exception-filters-disable" op)
     (let [id (read-when (:filter-id msg))]
       (if (not id)
         (transport/send
          transport (response-for
                     msg :status #{:error :missing-id} :value {:filter-id id}))
         (do
           (exception-filters/exception-filter-disable! connection id)
           (transport/send
            transport (response-for msg :value (args-for-map {:disabled id})))
           (transport/send transport (response-for msg :status :done)))))

     (= "exception-filters-kill" op)
     (let [id (read-when (:filter-id msg))]
       (if (not id)
         (transport/send
          transport (response-for
                     msg :status #{:error :missing-id} :value {:filter-id id}))
         (do
           (exception-filters/exception-filter-kill! connection id)
           (transport/send
            transport (response-for msg :value (args-for-map {:killed id})))
           (transport/send transport (response-for msg :status :done)))))

     (= "break-at" op)
     (let [filename (read-when (:file msg))
           line (read-when (:line msg))
           namespace (read-when (:ns msg))]
       (trace "break-at %s" msg)
       (if (not (and filename line namespace))
         (transport/send
          transport (response-for
                     msg
                     :status #{:error :missing-file-line}
                     :value {:file filename :line line :ns namespace}))
         (let [value (nrepl-debug/break-at connection namespace filename line)]
           (transport/send transport
                           (response-for msg :value (args-for-map value)))
           (transport/send transport (response-for msg :status :done)))))

     (= "breakpoints-move" op)
     (let [filename (read-when (:file msg))
           lines (read-when (:lines msg))
           namespace (read-when (:ns msg))]
       (trace "breakpoints-move %s" msg)
       (trace "breakpoints-move lines %s" lines)
       (if (not (and filename lines namespace))
         (transport/send
          transport (response-for
                     msg
                     :status #{:error :missing-file-line}
                     :value {:file filename
                             :lines lines
                             :ns namespace}))
         (let [value (nrepl-debug/breakpoints-move
                      connection namespace filename lines)]
           (transport/send transport
                           (response-for msg :value (args-for-map value)))
           (transport/send transport (response-for msg :status :done)))))

     (= "breakpoint-remove" op)
     (let [filename (read-when (:file msg))
           line (read-when (:line msg))]
       (trace "breakpoint-remove %s" msg)
       (if (not (and filename line))
         (transport/send
          transport (response-for
                     msg
                     :status #{:error :missing-file-line}
                     :value {:file filename :line line :ns namespace}))
         (let [value (nrepl-debug/breakpoint-remove connection filename line)]
           (transport/send transport
                           (response-for msg :value (args-for-map value)))
           (transport/send transport (response-for msg :status :done)))))

     (= "breakpoint-list" op)
     (do
       (trace "breakpoint-list %s" msg)
       (let [value (nrepl-debug/breakpoint-list connection)]
         (trace "breakpoint-list is %s"
                (with-out-str (clojure.pprint/pprint value)))
         (transport/send transport
                         (response-for
                          msg :value (args-for-map {:breakpoints value})))
         (transport/send transport (response-for msg :status :done))))

     (= "resume-all" op)
     (do
       (trace "resume-all")
       (let [value (nrepl-debug/resume-all connection)]
         (transport/send transport (response-for msg :status :done))))

     (= "debugger-info" op)
     (let [value
           (ritz.nrepl.debug/debugger-info
            connection
            (read-string (:thread-id msg))
            (read-when (:level msg))
            (read-when (:frame-min msg))
            (read-when (:frame-max msg)))]
       (transport/send transport
                       (response-for msg :value (args-for-map value)))
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

     (= "load-project" op)
     (let [project-file (:project-file msg)
           f #(transport/send transport (response-for msg :status :done))]
       (load-project connection project-file)
       (trace "load-project done")
       (f))

     (= "reset-repl" op)
     (do
       (reset-repl connection)
       (trace "reset-repl done")
       (transport/send transport (response-for msg :status :done)))

     (= "lein" op)
     (let [args (read-when (:args msg))
           ]
       (trace "lein %s" args)
       (let [[os is] (streams-for-out)
             buffer-size (* 1024 10)    ; bytes
             period 500                 ; ms
             bytes (byte-array buffer-size)
             read-ouput (fn []
                          (when (pos? (.available is))
                            (let [num-read (.read is bytes 0 buffer-size)
                                  s (String. bytes 0 num-read "UTF-8")]
                              (transport/send
                               transport (response-for msg :out s)))))
             f (future
                 (binding [*out* (writer os)]
                   (lein connection args)))]
         (while (not (future-done? f))
           (Thread/sleep period)
           (read-ouput))
         (while (read-ouput)))
       (trace "lein done")
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
