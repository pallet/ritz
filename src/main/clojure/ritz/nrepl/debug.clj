(ns ritz.nrepl.debug
  "Debugger implementation for nREPL"
  (:use
   [clojure.tools.nrepl.misc :only [response-for]]
   [ritz.connection :only [vm-context debug-context]]
   [ritz.jpda.debug
    :only [break-for-exception? add-exception-event-request event-break-info
           display-break-level dismiss-break-level
           invoke-named-restart]]
   [ritz.jpda.jdi :only [discard-event-request handle-event silent-event?]]
   [ritz.logging :only [trace trace-str]]
   [ritz.nrepl.connections :only [all-connections]])
  (:require
   clojure.pprint
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
  [context & {:keys [no-format]}]
  (let [threads (ritz.jpda.debug/thread-list (:vm-context context))]
    (if no-format
      (vec threads)
      (if-let [p-t (ns-resolve 'clojure.pprint 'print-table)]
        (p-t
         [:id :name :status :at-breakpoint? :suspended? :suspend-count]
         threads)
        (clojure.pprint/pprint threads)))))

;;; restarts
(defn continue
  [connection thread-id]
  (trace "continue %s" thread-id)
  (invoke-named-restart connection thread-id :continue))

(defn abort-level
  [connection thread-id]
  (trace "quit-level %s" thread-id)
  (invoke-named-restart connection thread-id :abort))

(defn quit-to-top-level
  [connection thread-id]
  (trace "quit-to-top-level %s" thread-id)
  (invoke-named-restart connection thread-id :quit))

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
       (response-for
        (-> (debug-context connection) :breakpoint :msg)
        :status :done)))))

(defn breakpoint-list
  [connection]
  (vec (ritz.jpda.debug/breakpoints (vm-context connection))))

(defmethod display-break-level :nrepl
  [{:keys [transport] :as connection}
   {:keys [thread thread-id condition event restarts] :as level-info}
   level]
  (trace "display-break-level: :nrepl")
  (when-let [msg (-> (debug-context connection) :breakpoint :msg)]
    (trace "display-break-level: reply to %s" msg)
    (transport/send
     transport
     (response-for
      msg
      :value (format "Exception %s in thread %s" condition thread-id)))
    (trace "display-break-level: sent message")))

(defmethod dismiss-break-level :nrepl
  [connection
   {:keys [thread thread-id condition event restarts] :as level-info}
   level]
  (trace "dismiss-break-level: :nrepl"))
