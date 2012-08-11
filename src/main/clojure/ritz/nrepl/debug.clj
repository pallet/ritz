(ns ritz.nrepl.debug
  "Debugger implementation for nREPL"
  (:use
   [clojure.tools.nrepl.misc :only [response-for]]
   [ritz.jpda.debug
    :only [break-for-exception? add-exception-event-request user-threads]]
   [ritz.jpda.jdi :only [discard-event-request handle-event silent-event?]]
   [ritz.logging :only [trace trace-str]]
   [ritz.nrepl.connections :only [all-connections primary-connection]])
  (:require
   [clojure.tools.nrepl.transport :as transport]
   [ritz.connection :as connection]
   [ritz.jpda.jdi :as jdi]
   [ritz.jpda.jdi-clj :as jdi-clj]
   [ritz.jpda.jdi-vm :as jdi-vm])
  (:import
   com.sun.jdi.event.BreakpointEvent
   com.sun.jdi.event.ExceptionEvent
   com.sun.jdi.event.StepEvent
   com.sun.jdi.request.ExceptionRequest
   com.sun.jdi.event.VMStartEvent
   com.sun.jdi.event.VMDeathEvent
   (com.sun.jdi
    BooleanValue ByteValue CharValue DoubleValue FloatValue IntegerValue
    LongValue ShortValue StringReference)
   ritz.jpda.debug.InvocationExceptionEvent))

;;; Threads
(defn threads
  "Provide a list of threads. The list is cached in the context
   to allow it to be retrieved by index."
  [context]
  (vec (ritz.jpda.debug/thread-list (:vm-context context))))

;;; restarts
(defn continue
  "Continue from exception"
  [connection]
  (jdi/resume-threads (user-threads (-> connection :vm-context :vm))))

;;; debugger
;;; exceptions
(defn break-on-exception
  "Enable or disable break-on-exception. This captures the message id, so that
the events can be delivered back."
  [{:keys [transport] :as connection} flag]
  (if flag
    (do
      (swap! (:debug connection)
             assoc-in [:breakpoint :msg] (-> connection :msg))
      (swap! (:debug connection)
             assoc-in [:breakpoint :break] flag)
      (jdi/enable-exception-request-states (-> connection :vm-context :vm)))
    (do
      (jdi/disable-exception-request-states (-> connection :vm-context :vm))
      (swap! (:debug connection) assoc-in [:breakpoint :break] flag)
      (transport/send
       transport
       (response-for (-> connection :breakpoint :msg) :status :done)))))

(defn breakpoint-list
  [connection]
  (trace "breakpoint-list message is %s" (-> connection :msg))
  (transport/send
   (:transport connection)
   (response-for
    (-> connection :msg)
    :value
    (vec (:breakpoints
          (ritz.jpda.debug/breakpoint-list (:vm-context connection))))))
  (vec
   (:breakpoints (ritz.jpda.debug/breakpoint-list (:vm-context connection)))))

(defn invoke-debugger
  [{:keys [transport] :as connection} event]
  {:pre [transport]}
  (trace "invoke-debugger")
  (transport/send
   transport
   (response-for (-> connection :breakpoint deref :msg) :value "Exception")))

(defmethod handle-event ExceptionEvent
  [^ExceptionEvent event context]
  (let [exception (.exception event)
        thread (.thread event)
        silent? (silent-event? event)]
    (when (and
           (:control-thread context)
           (:RT context))
      (if (not silent?)
        ;; (trace "EXCEPTION %s" event)
        ;; assume a single connection for now
        (do
          (trace "EVENT %s" (.toString event))
          (trace "EXCEPTION %s" exception)
          ;; would like to print this, but can cause hangs
          ;;    (jdi-clj/exception-message context event)
          (if-let [connection (primary-connection)]
            (when (break-for-exception? event connection)
                (trace "Activating sldb")
                (invoke-debugger connection event))

            ;; (if (aborting-level? connection)
            ;;   (trace "Not activating sldb (aborting)")
            ;;   (when (break-for-exception? event connection)
            ;;     (trace "Activating sldb")
            ;;     (invoke-debugger connection event)))

            ;; (trace "Not activating sldb (no connection)")
            ))
        (do
          (trace-str "@")
          ;; (trace
          ;;  "handle-event ExceptionEvent: Silent EXCEPTION %s %s"
          ;;  event
          ;;  (.. exception referenceType name)
          ;;  ;; (jdi-clj/exception-message context event)
          ;;  ;; (exception-event-string context event)
          ;;  )
          )))))

(defmethod handle-event BreakpointEvent
  [^BreakpointEvent event context]
  (trace "BREAKPOINT")
  (let [thread (.thread event)]
    (when (and (:control-thread context) (:RT context))
      (if-let [connection (primary-connection)]
        (do
          (trace "Activating sldb for breakpoint")
          (invoke-debugger connection event))
        (trace "Not activating sldb (no connection)")))))

(defmethod handle-event StepEvent
  [^StepEvent event context]
  (trace "STEP")
  (let [thread (.thread event)]
    (when (and (:control-thread context) (:RT context))
      (if-let [connection (primary-connection)]
        (do
          (trace "Activating sldb for stepping")
          ;; The step event is completed, so we discard it's request
          (discard-event-request (:vm context) (.. event request))
          (invoke-debugger connection event))
        (trace "Not activating sldb (no connection)")))))

(defmethod handle-event VMDeathEvent
  [event context-atom]
  ;; (doseq [connection (all-connections)]
  ;;   (connection/close connection))
  (System/exit 0))
