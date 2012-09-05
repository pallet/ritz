(ns ritz.swank.debug
  "Debugger interaction for swank"
  (:use
   [ritz.jpda.debug
    :only [step-request
           ignore-exception-type ignore-exception-message
           ignore-exception-location ignore-exception-catch-location
           remove-breakpoint
           build-backtrace
           break-for-exception?
           nth-thread stop-thread threads]]
   [ritz.logging :only [trace trace-str]]
   [ritz.debugger.connection
    :only [debug-context debug-assoc! debug-update-in! vm-context]])
  (:require
   [clojure.string :as string]
   [ritz.debugger.break :as break]
   [ritz.debugger.executor :as executor]
   [ritz.swank.hooks :as hooks]
   [ritz.jpda.debug :as debug]
   [ritz.jpda.jdi :as jdi]
   [ritz.jpda.jdi-clj :as jdi-clj]
   [ritz.jpda.jdi-vm :as jdi-vm]
   [ritz.jpda.swell.impl :as swell-impl]
   [ritz.repl-utils.find :as find]
   [ritz.repl-utils.helpers :as helpers]
   [ritz.swank.rpc-socket-connection :as rpc-socket-connection]
   [ritz.swank.connection :as connection]
   [ritz.swank.core :as core]
   [ritz.swank.inspect :as inspect]
   [ritz.swank.messages :as messages])
  (:import
   java.io.File
   java.net.Socket
   java.net.InetSocketAddress
   java.net.InetAddress
   com.sun.jdi.event.BreakpointEvent
   com.sun.jdi.event.ExceptionEvent
   com.sun.jdi.event.StepEvent
   com.sun.jdi.request.ExceptionRequest
   com.sun.jdi.event.VMStartEvent
   com.sun.jdi.event.VMDeathEvent
   com.sun.jdi.VirtualMachine
   com.sun.jdi.ObjectReference
   com.sun.jdi.ThreadReference
   com.sun.jdi.StackFrame
   (com.sun.jdi
    BooleanValue ByteValue CharValue DoubleValue FloatValue IntegerValue
    LongValue ShortValue StringReference)
   ritz.jpda.debug.InvocationExceptionEvent))

(def ^{:dynamic true} *sldb-initial-frames* 10)

(defonce first-eval-seen (atom false))

(defn log-exception [e]
  (trace
   "Caught exception %s %s"
   (pr-str e)
   (helpers/stack-trace-string e)))


;;; # VM Startup
;;; debugee function for starting a thread that may be used from the debugger
(defn- vm-swank-main
  [options]
  `(try
     (require '~'ritz.swank.socket-server)
     ((resolve '~'ritz.swank.socket-server/start) ~options)
     (catch Exception e#
       (spit (str (name 'ritz-startup-error)) e#)
       (println e#)
       (.printStackTrace e#))))

;;; functions for acquiring the control thread in the proxy
(defn launch-vm-with-swank
  "Launch and configure the vm for the debugee."
  [{:keys [port announce log-level classpath] :as options}]
  (apply
   jdi-vm/launch-vm
   (or classpath (jdi-vm/current-classpath))
   (vm-swank-main {:port port
                   :announce announce
                   :server-ns `(quote ritz.swank.repl)
                   :log-level (keyword log-level)})
   (mapcat identity options)))

;; (defn launch-vm-without-swank
;;   "Launch and configure the vm for the debugee."
;;   [classpath {:as options}]
;;   (trace "launch-vm-without-swank %s" classpath)
;;   (jdi-vm/launch-vm classpath ))


(defn connect-to-repl-on-vm [port]
  (trace "debugger/connect-to-repl-on-vm port %s" port)
  (Socket. "localhost" (int port)))

(defn create-connection [options]
  (trace "debugger/create-connection: connecting to proxied connection")
  (->
   (connect-to-repl-on-vm (:port options))
   (rpc-socket-connection/create options)
   (connection/create options)))

(defn remote-swank-port
  "Obtain the swank port on the remote vm"
  [context]
  (letfn [(get-port []
            (try
              (jdi-clj/control-eval
               context
               ;; NB very important that this doesn't throw
               ;; as that can cause hangs in the startup
               `(try
                  (when (find-ns '~'ritz.swank.socket-server)
                    (when-let [v# (ns-resolve
                                   '~'ritz.swank.socket-server
                                   '~'acceptor-port)]
                      (when-let [a# (var-get v#)]
                        (when (instance? clojure.lang.Atom a#)
                          (deref a#)))))
                  (catch Exception _#)))
              (catch Exception _)))]
    (loop []
      (trace "debug/remote-swank-port: loop")
      (if-let [port (get-port)]
        port
        (do
          (trace "debug/remote-swank-port: no port yet ...")
          (Thread/sleep 1000)
          (recur))))))


;;; # SWANK message and reply forwarding

;; interactive form tracking
(defn swank-peek
  [connection form buffer-package id f]
  (when (= (first form) 'swank/listener-eval)
    (find/source-form! id (second form))))

;;; execute functions and forwarding don't belong in this
;;; namespace
(defn execute-if-inspect-frame-var
  [handler]
  (fn [connection form buffer-package id f]
    (if (and f (= "inspect-frame-var" (name (first form))))
      (core/execute-slime-fn* connection f (rest form) buffer-package)
      (handler connection form buffer-package id f))))

(defn execute-inspect-if-inspector-active
  [handler]
  (fn [connection form buffer-package id f]
    (trace
     "inspect-if-inspector-active %s %s %s"
     f
     (re-find #"inspect" (name (first form)))
     (inspect/inspecting? (connection/inspector connection)))
    (if (and f
             (re-find #"inspect" (name (first form)))
             (inspect/inspecting? (connection/inspector connection)))
      (core/execute-slime-fn* connection f (rest form) buffer-package)
      (handler connection form buffer-package id f))))

(defn execute-peek
  [handler]
  (fn [connection form buffer-package id f]
    (trace "execute-peek %s" f)
    (swank-peek connection form buffer-package id f)
    (handler connection form buffer-package id f)))

(defn execute-unless-inspect
  "If the function has resolved (in the debug proxy) then execute it,
otherwise pass it on."
  [handler]
  (fn [connection form buffer-package id f]
    (trace "execute-unless-inspect %s %s" f form)
    (if (and f
         (not (re-find #"inspect" (name (first form))))
         (not (re-find #"nth-value" (name (first form)))) ;; hack
         (:ritz.swank.commands/swank-fn (meta f)))
  (core/execute-slime-fn* connection f (rest form) buffer-package)
  (handler connection form buffer-package id f))))

(declare format-thread)

(defn forward-command
  [handler]
  (fn [connection form buffer-package id f]
    (let [proxied-connection (:proxy-to connection)]
      (trace
       "debugger/forward-command: forwarding %s to proxied connection"
       (first form))
      (trace
       "VM threads:\n%s"
       (string/join
        "\n"
        (map format-thread (threads (vm-context connection)))))
      (break/clear-abort-for-current-level
       connection (:request-thread connection))
      (executor/execute-request
       (partial
        connection/send-to-emacs
        proxied-connection (list :emacs-rex form buffer-package true id)))
      :ritz.swank/pending)))

(defn forward-rpc
  [connection rpc]
  (let [proxied-connection (:proxy-to connection)]
    (trace
     "debugger/forward-command: forwarding %s to proxied connection" rpc)
    (executor/execute-request
     (partial connection/send-to-emacs proxied-connection rpc))))

(defn forward-reply
  [connection]
  (trace
   "debugger/forward-command: waiting reply from proxied connection")
  (let [proxied-connection (:proxy-to connection)]
    (let [reply (connection/read-from-connection proxied-connection)
          id (last reply)]
      (when (or (not (number? id)) (not (zero? id))) ; filter (= id 0)
        (executor/execute-request
         (partial connection/send-to-emacs connection reply))
        (trace "removing pending-id %s" id)
        (connection/remove-pending-id connection id)))))

;;; # Breakpoints

;;; ## State
(defn breakpoints
  [connection]
  (:breakpoints (debug-context connection)))

(defn breakpoints-set!
  [connection breakpoints]
  (debug-assoc! connection :breakpoints breakpoints))

(defn breakpoints-add!
  [connection breakpoints]
  (debug-update-in! connection [:breakpoints] concat breakpoints))

(defn breakpoint
  [connection breakpoint-id]
  (nth (breakpoints connection) breakpoint-id nil))

;;; ## Logic
(defn breakpoint-list
  "Update the context with a list of breakpoints. The list is cached in the
   context to allow it to be retrieveb by index."
  [connection]
  (let [breakpoints (map
                     #(assoc %1 :id %2)
                     (debug/breakpoints (vm-context connection))
                     (iterate inc 0))]
    (breakpoints-set! connection breakpoints)
    breakpoints))

(defn line-breakpoint
  "Set a breakpoint at the specified line. Updates the vm-context in the
   connection."
  [connection namespace filename line]
  (let [filename (when filename
                   (string/replace filename #" \(.*jar\)" ""))
        breakpoints (debug/line-breakpoint
                     (vm-context connection) namespace filename line)]
    (breakpoints-add! connection breakpoints)
    (count breakpoints)))

(defn breakpoint-kill
  [connection breakpoint-id]
  (let [breakpoint (breakpoint connection breakpoint-id)]
    (debug/breakpoint-kill connection (:file breakpoint) (:line breakpoint))))

(defn breakpoint-enable
  [connection breakpoint-id]
  (let [breakpoint (breakpoint connection breakpoint-id)]
    (debug/breakpoint-enable connection (:file breakpoint) (:line breakpoint))))

(defn breakpoint-disable
  [connection breakpoint-id]
  (let [breakpoint (breakpoint connection breakpoint-id)]
    (debug/breakpoint-disable
     connection (:file breakpoint) (:line breakpoint))))

(defn breakpoint-location
  [connection breakpoint-id]
  (let [breakpoint (breakpoint connection breakpoint-id)]
    (debug/breakpoint-location
     connection (:file breakpoint) (:line breakpoint))))


;;; Backtrace
(defn backtrace
  "Create a backtrace for the specified frames.
   TODO: seperate out return message generation."
  [connection start end]
  (let [thread-id (:request-thread connection)]
    (when-let [[level-info level] (break/break-level-info connection thread-id)]
      (build-backtrace (:thread level-info) start end))))


;;; Threads
(defn format-thread
  [^ThreadReference thread-reference]
  (format
   "%s %s (suspend count %s)"
   (.name thread-reference)
   (jdi/thread-states (.status thread-reference))
   (.suspendCount thread-reference)))

(defn thread-list
  "Provide a list of threads. The list is cached in the context
   to allow it to be retrieved by index."
  [connection]
  (let [context (vm-context connection)
        threads (ritz.jpda.debug/thread-list context)
        context (swap! (:debug connection) assoc :thread-list threads)]
    threads))

(defn kill-nth-thread
  [connection index]
  (when-let [thread (nth-thread (debug-context connection) index)]
    (stop-thread (vm-context connection) (:id thread))))

;;; breaks
(defmethod debug/dismiss-break-level :swank
  [connection thread-id level]
  ;; there seems to be an issue with sldb-quit trying to reselect an inactive
  ;; buffer as we send this before sending a return value for sldb-quit
  (connection/send-to-emacs
   connection (messages/debug-return thread-id level)))

(defmethod debug/display-break-level :swank
  [connection
   {:keys [thread thread-id condition event restarts] :as level-info}
   level]
  (trace "display-break-level: :swank")
  (let [backtrace (if (instance? InvocationExceptionEvent event)
                    [{:function "Unavailble"
                      :source "UNKNOWN" :line "UNKNOWN"}]
                    (build-backtrace thread 0 *sldb-initial-frames*))]
    (connection/send-to-emacs
     connection
     (messages/debug
      thread-id level condition restarts backtrace
      (connection/pending connection)))
    (connection/send-to-emacs
     connection (messages/debug-activate thread-id level))))

(defn debugger-info-for-emacs
  "Calculate debugger display information"
  [connection start end]
  (trace "debugger-info")
  (let [thread-id (:request-thread connection)
        [level-info level] (break/break-level-info connection thread-id)
        thread (:thread level-info)
        event (:event level-info)]
    (trace "invoke-debugger: send-to-emacs")
    (messages/debug-info
     (:exception-info level-info)
     (:restarts level-info)
     (if (instance? InvocationExceptionEvent event)
       [{:function "Unavailble" :source "UNKNOWN" :line "UNKNOWN"}]
       (build-backtrace thread start end))
     (connection/pending connection))))


(defn lazy-seq? [^ObjectReference object-reference]
  (= "clojure.lang.LazySeq" (.. object-reference referenceType name)))

(defmethod inspect/object-content-range com.sun.jdi.PrimitiveValue
  [context ^com.sun.jdi.PrimitiveValue object start end]
  (trace "inspect/object-content-range: PrimitiveValue %s" object)
  (inspect/object-content-range context (.value object) start end))

(defmethod inspect/object-content-range com.sun.jdi.Value
  [context object start end]
  (trace
   "inspect/object-content-range com.sun.jdi.Value %s %s" start end)
  (jdi-clj/read-arg
   context
   (:current-thread context)
   (jdi-clj/invoke-clojure-fn
    context (:current-thread context)
    {};  {:threading jdi/invoke-multi-threaded}
    "ritz.swank.inspect" "object-content-range"
    nil object
    (jdi-clj/eval-to-value context (:current-thread context) {} start)
    (jdi-clj/eval-to-value context (:current-thread context) {} end))))

(defmethod inspect/object-nth-part com.sun.jdi.Value
  [context object n max-index]
  (trace "inspect/object-nth-part: Value %s" object)
  (jdi-clj/invoke-clojure-fn
   context (:current-thread context) {}
   "ritz.swank.inspect" "object-nth-part"
   nil object
   (jdi-clj/eval-to-value context (:current-thread context) {} n)
   (jdi-clj/eval-to-value context (:current-thread context) {} max-index)))

(defmethod inspect/object-call-nth-action :default ; com.sun.jdi.Value
  [context object n max-index args]
  (trace "inspect/object-nth-part: default Value %s" object)
  (jdi-clj/read-arg
   context
   (:current-thread context)
   (jdi-clj/invoke-clojure-fn
    context
    (:current-thread context)
    ; {:threading jdi/invoke-multi-threaded}
    "ritz.swank.inspect" "object-call-nth-action"
    object
    (jdi-clj/eval-to-value context (:current-thread context) {} n)
    (jdi-clj/eval-to-value context (:current-thread context) {} max-index)
    (jdi-clj/eval-to-value context (:current-thread context) {} args))))

(defn setup-debugee
  "Forward info to the debuggee. This uses request id 0, which will be
   filtered from returning a reply to slime."
  [connection]
  ((forward-command nil) connection
   `(~'swank:interactive-eval
     ~(str
       `(do
          (require '~'ritz.repl-utils.compile)
          (reset!
           (var-get (resolve 'ritz.repl-utils.compile/compile-path))
           ~*compile-path*)))) "user" 0 nil))

(hooks/add core/new-connection-hook setup-debugee)

(defn disassemble-frame
  [context ^ThreadReference thread frame-index]
  (ritz.jpda.debug/disassemble-frame context thread frame-index))

(defn disassemble-symbol
  "Dissasemble a symbol var"
  [context ^ThreadReference thread sym-ns sym-name]
  (ritz.jpda.debug/disassemble-symbol context thread sym-ns sym-name))

(defn add-exception-event-request
  [vm-context]
  (ritz.jpda.debug/add-exception-event-request vm-context))
