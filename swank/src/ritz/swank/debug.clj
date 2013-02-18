(ns ritz.swank.debug
  "Debugger interaction for swank"
  (:use
   [clojure.stacktrace :only [print-cause-trace]]
   [ritz.jpda.debug
    :only [step-request
           ignore-exception-type ignore-exception-message
           ignore-exception-location ignore-exception-catch-location
           remove-breakpoint
           build-backtrace
           break-for-exception?
           nth-thread stop-thread threads exit-vm]]
   [ritz.logging :only [trace trace-str]]
   [ritz.debugger.connection
    :only [debug-context debug-assoc! debug-update-in! vm-context]]
   [ritz.debugger.breakpoint]
   [ritz.repl-utils.source-forms :only [source-form!]]
   [ritz.swank.rexec :only [rexec rread-msg]])
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
   (java.net Socket InetSocketAddress InetAddress)
   (com.sun.jdi
    BooleanValue ByteValue CharValue DoubleValue FloatValue IntegerValue
    LongValue ShortValue StringReference
    VirtualMachine ObjectReference ThreadReference StackFrame)
   (com.sun.jdi.event
    BreakpointEvent ExceptionEvent StepEvent VMStartEvent VMDeathEvent)
   com.sun.jdi.request.ExceptionRequest
   ritz.jpda.debug.InvocationExceptionEvent))

(def ^{:dynamic true} *sldb-initial-frames* 10)

(defonce first-eval-seen (atom false))

(defn log-exception [e]
  (trace
   "Caught exception %s %s"
   (pr-str e)
   (helpers/stack-trace-string e)))

;;; # SWANK message and reply forwarding

;; interactive form tracking
(defn swank-peek
  [connection form buffer-package id f]
  (when (= (first form) 'swank/listener-eval)
    (source-form! id (second form))))

;;; execute functions and forwarding don't belong in this
;;; namespace
(defn execute-if-quit-lisp
  [handler]
  (fn [connection form buffer-package id f]
    (if (= 'swank/quit-lisp (first form))
      (exit-vm (vm-context connection))
      (handler connection form buffer-package id f))))

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
     (partial rexec connection (list :emacs-rex form buffer-package true id)))
    :ritz.swank/pending))

(defn forward-rpc
  [connection rpc]
  (let [proxied-connection (:proxy-to connection)]
    (trace
     "debugger/forward-rpc: forwarding %s to proxied connection" rpc)
    (executor/execute-request
     (partial connection/send-to-emacs proxied-connection rpc))))

(defn forward-reply
  [connection]
  (trace
   "debugger/forward-reply: waiting reply from proxied connection")
  (try
    (when-let [p @ritz.swank.exec/wait-for-reinit]
      @p)
    (let [vm-context (vm-context connection)
          thread (:msg-pump-thread vm-context)
          _ (assert thread)
          _ (trace "debugger/forward-reply: thread %s" thread)
          reply (rread-msg vm-context thread)
          id (last reply)]
      (trace "debugger/forward-reply: reply received %s" reply)
      (when (and
             (not= '(:ritz/release-read-msg) reply)
             (or (not (number? id))
                 (not (zero? id)))) ; filter (= id 0)
        (executor/execute-request
         (partial connection/send-to-emacs connection reply))
        (trace "removing pending-id %s" id)
        (connection/remove-pending-id connection id)))
    (catch Exception e
      (trace "debugger/forward-reply failed %s" e)
      (trace "debugger/forward-reply %s" (print-cause-trace e))
      (throw e))))

;;; # Breakpoints

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
