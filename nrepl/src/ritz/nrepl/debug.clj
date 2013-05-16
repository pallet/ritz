(ns ritz.nrepl.debug
  "Debugger implementation for nREPL"
  (:use
   [clojure.tools.nrepl.misc :only [response-for]]
   [ritz.debugger.breakpoint :only [breakpoints-add! breakpoints-remove!]]
   [ritz.debugger.connection :only [vm-context debug-context]]
   [ritz.jpda.debug
    :only [add-exception-event-request break-for-exception? breakpoint-kill
           breakpoint-move breakpoint-set-line build-backtrace
           dismiss-break-level display-break-level eval-string-in-frame
           event-break-info frame-locals-with-string-values
           frame-source-location invoke-named-restart
           pprint-eval-string-in-frame]]
   [ritz.jpda.jdi :only [discard-event-request handle-event silent-event?]]
   [ritz.logging :only [trace trace-str]]
   [ritz.nrepl.connections :only [all-connections]])
  (:require
   clojure.pprint
   [clojure.string :as string]
   [clojure.tools.nrepl.transport :as transport]
   [ritz.debugger.break :as break]
   [ritz.debugger.connection :as connection]
   [ritz.debugger.exception-filters :as exception-filters]
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
(defn resume-all [connection]
  (.resume (:vm (connection/vm-context connection))))

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

;;; breakpoints
(defn break-at
  "Enable a breakpoint at a given line"
  [{:keys [transport] :as connection} namespace filename line]
  (trace "break-at %s %s" filename line)
  (let [filename (when filename
                   (string/replace filename #" \(.*jar\)" ""))
        breakpoints (breakpoint-set-line connection namespace filename line)]
    (breakpoints-add! connection breakpoints)
    {:count (count breakpoints)}))

(defn breakpoints-move
  "Move a breakpoint from from-line to to-line."
  [{:keys [transport] :as connection} namespace filename lines]
  (trace "breakpoints-move %s %s" filename lines)
  (let [filename (when filename
                   (string/replace filename #" \(.*jar\)" ""))]
    (doseq [[from-line to-line] lines]
      (let [[add remove] (breakpoint-move
                          connection namespace filename from-line to-line)]
        (trace "breakpoints-move remove %s" remove)
        (trace "breakpoints-move add %s" add)
        (breakpoints-remove! connection remove)
        (breakpoints-add! connection add)))
    {:count (count lines)}))

(defn breakpoint-remove
  "Remove breakpoints at a given line."
  [{:keys [transport] :as connection} filename line]
  (trace "breakpoint-remove %s %s" filename line)
  (let [filename (when filename
                   (string/replace filename #" \(.*jar\)" ""))]
    (breakpoint-kill connection filename line)
    (breakpoints-remove! connection {:filename filename :line line})
    {:removed 1}))

(defn breakpoint-list
  [connection]
  (vec (ritz.jpda.debug/breakpoints (vm-context connection))))

(defn breakpoints-recreate
  [connection {:keys [namespace file file-name file-path] :as msg}]
  (trace "breakpoints-recreate for f %s f-n %s f-p %s" file file-name file-path)
  (trace "breakpoints-recreate existing %s" (breakpoint-list connection))
  (doseq [{:keys [file line enabled] :as bp}
          (filter #(= file-path (:file %)) (breakpoint-list connection))
          :when enabled]
    (let [b (breakpoint-set-line connection nil file line)]
      (trace "breakpoints-recreate recreated %s breakboints" (count b)))))

(defn stacktrace-frames
  [frames start]
  (map
   #(list
     %1
     (format "%s (%s:%s)" (:function %2) (:source %2) (:line %2))
     (list :stratum (:stratum %2)))
   (iterate inc start)
   frames))


(defn debugger-data
  [{:keys [thread thread-id condition event restarts] :as level-info}
   level frame-min frame-max]
  `("exception" ~(list (:message condition "No message")
                       (:type condition ""))
    "thread-id" ~thread-id
    "frames" ~(vec (stacktrace-frames (build-backtrace thread) frame-min))
    "restarts" ~(vec (map
                      (fn [{:keys [name description]}]
                        (list name description))
                      restarts))
    "level" ~level))

(defn debugger-info
  [connection thread-id level frame-min frame-max]
  (let [[level-info lvl] (break/break-level-info connection thread-id)]
    (trace "debugger-info %s %s %s" thread-id level level-info)
    (debugger-data level-info lvl (or frame-min 0) (or frame-max 100))))

(defmethod display-break-level :nrepl
  [{:keys [transport] :as connection}
   {:keys [thread thread-id condition event restarts] :as level-info}
   level]
  (trace "display-break-level: thread-id %s level %s" thread-id level)
  (if-let [msg (-> (debug-context connection) :breakpoint :msg)]
    (let [value (debugger-data level-info level 0 100)]
      (trace "display-break-level: reply to %s" msg)
      (trace "display-break-level: reply %s" value)
      (transport/send transport (response-for msg :value value))
      (trace "display-break-level: sent message"))
    (trace "ERROR: No debug context %s" (pr-str (debug-context connection)))))

(defmethod dismiss-break-level :nrepl
  [connection
   {:keys [thread thread-id condition event restarts] :as level-info}
   level]
  (trace "dismiss-break-level: :nrepl"))

(defn frame-eval
  [connection thread-id frame-number code pprint]
  (trace "frame-eval %s %s %s" thread-id (pr-str code) (pr-str pprint))
  (let [[level-info level] (break/break-level-info connection thread-id)
        thread (:thread level-info)]
    (trace "frame-eval level-info %s" level-info)
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
