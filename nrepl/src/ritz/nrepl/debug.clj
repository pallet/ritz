(ns ritz.nrepl.debug
  "Debugger implementation for nREPL"
  (:use
   [clojure.tools.nrepl.misc :only [response-for]]
   [ritz.debugger.connection :only [vm-context debug-context]]
   [ritz.jpda.debug
    :only [break-for-exception? add-exception-event-request event-break-info
           display-break-level dismiss-break-level
           invoke-named-restart build-backtrace frame-source-location
           frame-locals-with-string-values
           eval-string-in-frame pprint-eval-string-in-frame]]
   [ritz.jpda.jdi :only [discard-event-request handle-event silent-event?]]
   [ritz.logging :only [trace trace-str]]
   [ritz.nrepl.connections :only [all-connections]])
  (:require
   clojure.pprint
   [clojure.string :as string]
   [clojure.tools.nrepl.transport :as transport]
   [ritz.debugger.break :as break]
   [ritz.debugger.connection :as connection]
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

(defn invoke-restart
  [connection thread-id restart-number restart-name]
  (trace "invoke-restart %s" thread-id)
  (if restart-name
    (invoke-named-restart connection thread-id (keyword restart-name))
    (ritz.jpda.debug/invoke-restart connection thread-id nil restart-number)))

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

(defn stacktrace-frames
  [frames start]
  (map
   #(list
     %1
     (format "%s (%s:%s)" (:function %2) (:source %2) (:line %2)))
   (iterate inc start)
   frames))

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
      :value
      `("exception" ~(list (:exception-message condition)
                           (:type condition))
        "thread-id" ~thread-id
        "frames" ~(stacktrace-frames (build-backtrace thread) 0)
        "restarts" ~(map
                     (fn [{:keys [name description]}] (list name description))
                     restarts)
        "level" ~level)))
    (trace "display-break-level: sent message")))

(defmethod dismiss-break-level :nrepl
  [connection
   {:keys [thread thread-id condition event restarts] :as level-info}
   level]
  (trace "dismiss-break-level: :nrepl"))

(defn frame-eval
  [connection thread-id frame-number code pprint]
  (trace "invoke-restart %s" thread-id)
  (let [[level-info level] (break/break-level-info connection thread-id)
        thread (:thread level-info)]
    {:result (if pprint
               (pprint-eval-string-in-frame
                connection (vm-context connection) thread code frame-number)
               (eval-string-in-frame
                connection (vm-context connection) thread code frame-number))}))

(defn frame-source
  [connection thread-id frame]
  (let [[level-info level] (break/break-level-info connection thread-id)
        [buffer position] (frame-source-location (:thread level-info) frame)]
    (trace "frame-source %s %s" buffer position)
    (when buffer (merge buffer position))))

(defn frame-locals-msg
  "Message to return frame locals for slime."
  [locals-map]
  (seq
   (map
    #(list :name (:unmangled-name %) :id 0 :value (:string-value %))
    locals-map)))

(defn frame-locals
  [connection thread-id frame]
  (let [[level-info level] (break/break-level-info connection thread-id)]
    {:locals (or (frame-locals-msg
                  (frame-locals-with-string-values
                    (:vm-context connection) (:thread level-info) frame))
                 '())}))

(defn disassemble-frame
  [connection thread-id frame-number]
  (let [[level-info level] (break/break-level-info connection thread-id)
        thread (:thread level-info)]
    {:result (string/join \newline
                          (ritz.jpda.debug/disassemble-frame
                           (vm-context connection) thread frame-number))}))
