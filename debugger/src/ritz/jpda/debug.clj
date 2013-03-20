(ns ritz.jpda.debug
  "Debug functions using jpda/jdi, used to implement debugger commands via jpda.
   The aim is to move all return messaging back up into ritz.commands.*
   and to accept only vm-context aguments, rather than a connection"
  (:require
   [ritz.debugger.break :as break]
   [ritz.debugger.connection :as connection]
   [ritz.debugger.executor :as executor]
   [ritz.jpda.disassemble :as disassemble]
   [ritz.jpda.jdi :as jdi]
   [ritz.jpda.jdi-clj :as jdi-clj]
   [ritz.jpda.jdi-vm :as jdi-vm]
   [ritz.jpda.swell.impl :as swell-impl]
   [ritz.repl-utils.find :as find]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string])
  (:use
   [ritz.debugger.connection
    :only [connection-close break-context debug-context vm-context]]
   [ritz.debugger.exception-filters
    :only [exception-filters exception-filter-add!]]
   [ritz.debugger.inspect :only [reset-inspector value-as-string]]
   [ritz.logging :only [trace trace-str]]
   [ritz.repl-utils.classloader
    :only [configurable-classpath? eval-clojure has-classloader?]]
   [ritz.repl-utils.clojure :only [feature-cond]])
  (:import
   java.io.File
   (java.net Socket InetSocketAddress InetAddress)
   (com.sun.jdi.request
    ExceptionRequest BreakpointRequest)
   (com.sun.jdi.event
    BreakpointEvent ExceptionEvent StepEvent Event VMStartEvent VMDeathEvent
    VMDisconnectEvent)
   (com.sun.jdi
    Method VirtualMachine ObjectReference ThreadReference StackFrame
    BooleanValue ByteValue CharValue DoubleValue FloatValue IntegerValue
    LongValue ShortValue StringReference Value)))

(def exception-suspend-policy :suspend-all)
(def breakpoint-suspend-policy :suspend-all)

(def exception-policy
  (atom {:uncaught-only true
         :class-exclusion ["java.net.URLClassLoader*"
                           "java.lang.ClassLoader*"
                           "*ClassLoader.java"]
         :system-thread-names [jdi-vm/control-thread-name
                               "REPL" "Accept loop"
                               "Connection dispatch loop :repl"]}))

(defn launch-vm
  "Launch and configure the vm for the debugee."
  [{:keys [classpath main] :as options}]
  (trace "debug/launch-vm %s" options)
  (jdi/vm-event-daemon
   (jdi-clj/vm-rt
    (jdi-vm/launch-vm (or classpath (jdi-vm/current-classpath)) main options))))

(defn exit-vm
  [context]
  (jdi-vm/vm-exit context))

;;; # Threads
(defn threads
  "Return a sequence containing a thread reference for each remote thread."
  [context]
  (jdi/threads (:vm context)))

(defn- transform-thread-group
  [pfx [group groups threads]]
  [(->
    group
    (dissoc :id)
    (update-in [:name] (fn [s] (str pfx s))))
   (map #(transform-thread-group (str pfx "  ") %) groups)
   (map #(update-in % [:name] (fn [s] (str pfx "  " s))) threads)])

(defn thread-list
  "Provide a list of threads."
  [context]
  (flatten
   (map
    #(transform-thread-group "" %)
    (jdi/thread-groups (:vm context)))))

(defn nth-thread
  [debug-context index]
  (nth (:thread-list debug-context) index nil))

(defn stop-thread
  [vm-context thread-id]
  (when-let [^ThreadReference thread (first
                                      (filter
                                       #(= thread-id
                                           (.uniqueID ^ThreadReference %))
                                       (threads vm-context)))]
    (.stop
     thread
     (jdi-clj/control-eval-to-value
      vm-context `(new java.lang.Throwable "Stopped by swank")))))

(defn user-threads
  "Return the user threads."
  [vm-context]
  {:pre [vm-context (:vm vm-context)]}
  (jdi/threads-in-group (:vm vm-context) executor/ritz-executor-group-name))

;;; stacktrace
(defn- frame-data
  "Extract data from a stack frame"
  [^StackFrame frame]
  (jdi/location-data (jdi/location frame)))

(defn- exception-stacktrace [frames]
  (trace "exception-stacktrace")
  (trace "exception-stacktrace: %s frames" (count frames))
  (map frame-data frames))

(defn build-backtrace
  ([^ThreadReference thread]
     (doall (exception-stacktrace (.frames thread))))
  ([^ThreadReference thread start end]
     (trace "build-backtrace: %s %s" start end)
     (doall (exception-stacktrace
             (take (- end start) (drop start (.frames thread)))))))


;;; breakpoints
(defn breakpoints
  "Return a sequence of breakpoints."
  [vm-context]
  (map jdi/breakpoint-data (jdi/breakpoints (:vm vm-context))))

(defn line-breakpoint
  "Set a line breakpoint."
  [vm-context namespace filename line]
  {:pre [vm-context (:vm vm-context)]}
  (jdi/line-breakpoints
   ^VirtualMachine (:vm vm-context)
   breakpoint-suspend-policy namespace filename line))

(defn remove-breakpoint
  "Remove a line breakpoint."
  [connection ^Event event]
  (update-in (:debug connection)
             [:breakpoints]
             (fn [breakpoints]
               (when-let [request (.request event)]
                 (.disable request)
                 (.deleteEventRequest
                  (.eventRequestManager (.virtualMachine event)) request)
                 (remove #(= % request) breakpoints)))))

(defn breakpoints-for
  [connection file line]
  (let [vm (:vm (vm-context connection))]
    (doall (filter
            #(let [location (.location ^BreakpointRequest %)]
               (and (= file (.sourcePath location))
                    (= line (.lineNumber location))))
            (jdi/breakpoints vm)))))

(defn breakpoint-set-line
  "Set a line breakpoint."
  [connection namespace filename line]
  (map
   jdi/breakpoint-data
   (line-breakpoint (vm-context connection) namespace filename line)))

(defn breakpoint-kill
  [connection file line]
  (doseq [^BreakpointRequest breakpoint
          (breakpoints-for connection file line)]
    (.disable breakpoint)
    (.. breakpoint (virtualMachine) (eventRequestManager)
        (deleteEventRequest breakpoint))))

(defn breakpoint-enable
  [connection file line]
  (doseq [^BreakpointRequest breakpoint
          (breakpoints-for connection file line)]
    (.enable breakpoint)))

(defn breakpoint-disable
  [connection file line]
  (doseq [^BreakpointRequest breakpoint
          (breakpoints-for connection file line)]
    (.disable breakpoint)))

(defn breakpoint-move
  "Move a breakpoint in file from from-line to to-line."
  [connection namespace file from-line to-line]
  (for [^BreakpointRequest breakpoint
        (breakpoints-for connection file from-line)]
    (do
      (.. breakpoint virtualMachine eventRequestManager
          (deleteEventRequest breakpoint))
      [(breakpoint-set-line connection namespace file to-line)
       (jdi/breakpoint-data breakpoint)])))

(defn breakpoint-location
  [connection file line]
  (when-let [^BreakpointRequest breakpoint
             (first (breakpoints-for connection file line))]
    (let [location (.location breakpoint)]
      (trace
       "breakpoint-location %s %s %s"
       (jdi/location-source-name location)
       (jdi/location-source-path location)
       (jdi/location-line-number location))
      (when-let [path (find/find-source-path
                       (jdi/location-source-path location))]
        [path {:line (jdi/location-line-number location)}]))))

;;; debug methods

(defn event-break-info
  "Return a basic break-info for an event"
  [connection event]
  (trace "event-break-info")
  (let [thread (jdi/event-thread event)
        user-threads (conj (set (user-threads (vm-context connection))) thread)
        thread-id (.uniqueID thread)]
    {:thread-id thread-id
     :thread thread
     :event event
     :user-threads user-threads}))

(defn exception-message
  [connection thread-id exception-event]
  (or
   (break/break-exception-message connection thread-id exception-event)
   (let [msg (jdi-clj/exception-message
              (:vm-context connection) exception-event)]
     (break/break-exception-message! connection thread-id exception-event msg)
     msg)))

(def remote-condition-printer-sym-value (atom nil))
(def remote-condition-printer-fn (atom nil))

;; TODO investigate what happens if a data carrying exception contains a lazy
;; sequence that throws when realised.
(def data-carrying-exception
  #{"clojure.contrib.condition.Condition"
    "slingshot.Stone"
    "slingshot.ExceptionInfo"
    "clojure.lang.ExceptionInfo"})

(defn remote-condition-printer
  "Return the remote symbol for a var to use for the condition printer"
  [context thread]
  (or @remote-condition-printer-fn
      (if (compare-and-set!
           remote-condition-printer-sym-value
           nil
           (jdi-clj/eval
            context thread {:disable-exception-requests true}
            `(gensym "swank")))
        (let [s (name @remote-condition-printer-sym-value)
              c `(do
                   (require '~'clojure.pprint)
                   (defn ~(symbol s) [c#]
                     (try
                       (let [f# (fn ~'classify-exception-fn [e#]
                                  (case (.getName (class e#))
                                    "clojure.contrib.condition.Condition"
                                    :condition

                                    "slingshot.Stone" :stone
                                    "slingshot.ExceptionInfo" :exception-info
                                    "clojure.lang.ExceptionInfo" :exception-info

                                    "clojure.lang.PersistentHashMap"
                                    :stone-context

                                    "clojure.lang.PersistentArrayMap"
                                    :stone-context

                                    :throwable))
                             gc# (fn ~'get-cause-fn [e#]
                                   (case (f# e#)
                                     :stone (:cause (.context e#))
                                     :stone-context (:next e#)
                                     (.getCause e#)))
                             pc# (fn ~'print-cause-fn [e#]
                                   (case (f# e#)
                                     :condition [(:message e#)
                                                 (first (:stack-trace e#))]
                                     :throwable [(.getMessage e#)
                                                 (first (.getStackTrace e#))]
                                     :stone [(dissoc (.context e#) :stack :next)
                                             (first (:stack (.context e#)))]
                                     :exception-info [(dissoc (.getData e#)
                                                              :stack :next)
                                                      (first
                                                       (:stack (.getData e#)))]
                                     :stone-context [(dissoc e# :stack :next)
                                                     (first (:stack e#))]))
                             ca# (fn ~'cause-chain-fn [e#]
                                   (vec
                                    (map
                                     pc#
                                     (take-while identity (iterate gc# e#)))))]
                         (case (f# c#)
                           :condition
                           (with-out-str
                             (println (:message @(.state c#)))
                             (clojure.pprint/pprint
                              [(dissoc @(.state c#) :message)
                               (ca# c#)]))
                           :exception-info
                           (with-out-str
                             (println (.getMessage c#))
                             (clojure.pprint/pprint (.getData c#)))
                           :stone
                           (with-out-str
                             (println (.messagePrefix c#))
                             (clojure.pprint/pprint
                              (.object c#))
                             (clojure.pprint/pprint
                              (.context c#)))
                           :throwable
                           (with-out-str
                             (clojure.pprint/pprint
                              [(.getMessage c#)
                               (ca# c#)]))))
                       (catch Exception e#
                         (.printStackTrace e#))))
                   ;; force loading of some classes
                   (~(symbol s) (Exception. "")))]
          (trace "remote-condition-printer code %s" c)
          (trace "remote-condition-printer set sym %s" (pr-str s))
          (jdi-clj/eval context thread {:disable-exception-requests true} c)
          (trace "remote-condition-printer defined fn")
          (reset!
           remote-condition-printer-fn
           (jdi-clj/clojure-fn
            context thread {}
            (jdi-clj/eval-to-string
             context thread {}
             `(name (ns-name *ns*)))
            s 1))
          (trace
           "remote-condition-printer resolved fn %s"
           (pr-str @remote-condition-printer-fn))
          @remote-condition-printer-fn))
      @remote-condition-printer-fn))

(defn exception-info
  [connection ^ExceptionEvent event]
  (let [context (:vm-context connection)
        exception (.exception event)
        exception-type (.. exception referenceType name)
        thread (jdi/event-thread event)
        msg (exception-message connection (.uniqueID thread) event)
        data-carrying-exception (data-carrying-exception exception-type)]
    (trace "exception-info for %s %s" exception-type data-carrying-exception)
    {:exception-message msg
     :message (if data-carrying-exception
                (jdi/with-disabled-exception-requests [(:vm connection)]
                  ;; The remote-condition-printer will cause class not found
                  ;; exceptions (especially the first time it runs).
                  (let [[object method] (remote-condition-printer
                                         context thread)]
                    (str (jdi/invoke-method
                          thread
                          {:disable-exception-requests true}
                          object method [exception]))))
                (or msg "No message."))
     :type exception-type}))


;; This is a synthetic Event for an InvocationException delivered to the debug
;; thread calling invokeMethod.  These exceptions are (unfortunately) not
;; reported through the jdi debug event loop.
(deftype InvocationExceptionEvent
    [^ExceptionEvent exception thread]
  com.sun.jdi.event.ExceptionEvent
  (catchLocation [_] nil)
  (exception [_] exception)
  (location [_] (.location exception))
  (thread [_] thread)
  (virtualMachine [_] (.virtualMachine exception))
  (request [_] nil))

;;; # Restarts

;;; ## UI restarts
(defmulti display-break-level
  "Display any UI for the break level"
  (fn [connection level-info level]
    (:type connection)))

(defmulti dismiss-break-level
  "Dismiss any UI displaying the break level"
  (fn [connection level-info level]
    (:type connection)))

;;; ## JPDA restarts
(defn resume
  [^ThreadReference thread-ref suspend-policy]
  (case suspend-policy
    :suspend-all (.resume (.virtualMachine thread-ref))
    :suspend-event-thread (.resume thread-ref)
    nil))

(defn resume-break-level
  "Resume a break level."
  [connection {:keys [event user-threads] :as level-info}]
  (when-not (instance? InvocationExceptionEvent event)
    (trace "resuming threads %s" (vec user-threads))
    (jdi/resume-threads user-threads)))

(defn resume-thread-level-if-aborting
  "Check to see if there are any thread levels remaining for thread, and
resume the top level if there is one. This function should be called at the end
of any debug function that uses a user thread."
  [connection thread-id]
  (trace "resume-thread-level-if-aborting")
  (if (break/abort-level-not-reached? connection thread-id)
    (if-let [[level-info level] (break/break-level-info connection thread-id)]
      (do
        (trace "resume-thread-level-if-aborting resuming level %s" level)
        (break/break-drop-level! connection thread-id)
        (resume-break-level connection level-info))
      (trace "resume-thread-level-if-aborting no level info"))
    (when-let [[level-info level] (break/break-level-info connection thread-id)]
      (trace "resume-thread-level-if-aborting display break level %s" level)
      (display-break-level connection level-info level))))

;;; ## Restart functions
(defn continue-level
  "Continue the current level."
  [connection thread-id]
  (let [[level-info level] (break/break-level-info connection thread-id)]
    (trace "continue-level :thread-id %s :level %s" thread-id level)
    (break/break-drop-level! connection thread-id)
    (dismiss-break-level connection thread-id level)
    (resume-break-level connection level-info))
  nil)

(defn quit-level
  "Abort the current level"
  [connection thread-id]
  (let [[level-info level] (break/break-level-info connection thread-id)]
    (trace "quit-level: :thread-id %s :level %s" thread-id level)
    (break/break-drop-level! connection thread-id)
    (break/break-abort-to-level! connection thread-id level)
    (dismiss-break-level connection thread-id level)
    (resume-break-level connection level-info))
  nil)

(defn abort-all-levels
  [connection thread-id]
  (let [level-infos (break/break-level-infos connection thread-id)
        level-infos (reverse
                     (map #(assoc %1 :level %2) level-infos (iterate inc 0)))]
    (trace "abort-all-levels: :thread-id %s :levels %s"
      thread-id (count level-infos))
    (break/break-drop-level! connection thread-id)
    (break/break-abort-to-level! connection thread-id -1)
    (doseq [level-info level-infos]
      (dismiss-break-level connection thread-id (:level level-info)))
    ;; The other levels should be resumed by calls to
    ;; resume-thread-level-if-aborting, in the functions that
    ;; use a user thread.
    (resume-break-level connection (first level-infos))))

(defn step-request
  [thread size depth]
  (doto (jdi/step-request thread size depth)
    (.addCountFilter 1)
    (jdi/suspend-policy breakpoint-suspend-policy)
    (.enable)))

(defn ignore-exception-type
  "Add the specified exception to the connection's never-break-exceptions set."
  [connection exception-type]
  (trace "Adding %s to never-break-exceptions" exception-type)
  (exception-filter-add! connection {:type exception-type :enabled true}))

(defn ignore-exception-message
  "Add the specified exception to the connection's never-break-exceptions set."
  [connection exception-message]
  (trace "Adding %s to never-break-exceptions" exception-message)
  (exception-filter-add! connection {:message exception-message :enabled true}))

(defn ignore-exception-catch-location
  "Add the specified exception catch location to the connection's
   never-break-exceptions set."
  [connection catch-location]
  (trace "Adding %s to never-break-exceptions" catch-location)
  (exception-filter-add!
   connection {:catch-location catch-location :enabled true}))

(defn ignore-exception-location
  "Add the specified exception throw location to the connection's
   never-break-exceptions set."
  [connection catch-location]
  (trace "Adding %s to never-break-exceptions" catch-location)
  (exception-filter-add! connection {:location catch-location :enabled true}))


;;; # Restarts
(defn make-restart
  "Make a restart map.
   Contains
     :id keyword id
     :name short name
     :descritpton longer description
     :f restart function to invoke"
  [kw name description f]
  ;;[kw [name description f]]
  {:id kw :name name :description description :f f})

(defn- make-step-restart
  [thread thread-id id name description size depth]
  (make-restart
   id name description
   (fn [connection]
     (trace "restart %s" name)
     (step-request thread size depth)
     (continue-level connection thread-id))))

(defn stepping-restarts
  [thread]
  (let [thread-id (.uniqueID ^ThreadReference thread)]
    [(make-step-restart
      thread thread-id
      :step-into "STEP" "Step into the next line" :line :into)
     (make-step-restart
      thread thread-id
      :step-next "STEP-NEXT" "Step to the next line" :line :over)
     (make-step-restart
      thread thread-id
      :step-out "STEP-OUT" "Step out of current frame" :line :out)]))

(defn invoke-restart
  [connection thread-id level n]
  (when-let [[level-info level] (break/break-level-info connection thread-id)]
    ;; note that this ignores the passed level, since the level should
    ;; always be the last level, and it is not clear what should be done
    ;; otherwise
    (trace "invoke-restart %s of %s" n (count (:restarts level-info)))
    (trace "invoke-restart :level-info %s" level-info)
    (when-let [f (:f (try (nth (:restarts level-info) n)
                          (catch IndexOutOfBoundsException _)))]
      (reset-inspector connection)
      (f connection))
    thread-id))

(defn invoke-named-restart
  [connection thread-id kw]
  (trace "invoke-named-restart %s %s" (pr-str thread-id) kw)
  (trace
      "invoke-named-restart debug-context %s"
    (pr-str (break-context connection)))
  (if-let [[level-info level] (break/break-level-info connection thread-id)]
    (if-let [f (:f (some #(and (= kw (:id %)) %) (:restarts level-info)))]
      (do (reset-inspector connection)
          (f connection))
      (do (trace "invoke-named-restart %s not found" kw)
          (format "Restart %s not found" kw)))
    (trace "invoke-named-restart break-info for %s not found" thread-id)))

;;; # Debugger
(defprotocol Debugger
  (condition-info [event connection])
  (restarts [event condition connection level]))

(extend-type ExceptionEvent
  Debugger
  (condition-info
    [event connection]
    (-> (exception-info connection event)
        (update-in
         [:type] (fn [exception-type] (str "  [Thrown " exception-type "]")))))

  (restarts
    [^ExceptionEvent exception condition connection level]
    (trace "calculate-restarts exception")
    (let [^ThreadReference thread (.thread exception)
          thread-id (.uniqueID thread)
          context (:vm-context connection)]
      (if (.request exception)
        (filter
         identity
         (concat
          [(make-restart
            :continue "CONTINUE" "Pass exception to program"
            (fn [connection]
              (trace "restart Continuing")
              (continue-level connection thread-id)))
           (make-restart
            :abort "ABORT" "Abort request."
            (fn [connection]
              (trace "restart Abort request.")
              (quit-level connection thread-id)))
           (when (pos? level)
             (make-restart
              :quit "QUIT" "Quit to top level."
              (fn [connection]
                (trace "restart Quit to top level")
                (abort-all-levels connection thread-id))))
           (make-restart
            :ignore-type "IGNORE"
            "Do not enter debugger for this exception type"
            (fn [connection]
              (trace "restart Ignoring exceptions")
              (ignore-exception-type
               connection (.. exception exception referenceType name))
              (continue-level connection thread-id)))
           (when-not (string/blank? (:exception-message condition))
             (make-restart
              :ignore-message "IGNORE-MSG"
              "Do not enter debugger for this exception message"
              (fn [connection]
                (trace "restart Ignoring exceptions")
                (ignore-exception-message
                 connection (:exception-message condition))
                (continue-level connection thread-id))))
           (when-let [location (jdi/catch-location exception)]
             (let [location (jdi/location-type-name location)
                   location (re-find #"[^\$]+" location)]
               (make-restart
                :ignore-message "IGNORE-CATCH"
                (str
                 "Do not enter debugger for exceptions with catch location "
                 location ".*")
                (fn [connection]
                  (trace "restart Ignoring exceptions")
                  (ignore-exception-catch-location
                   connection (re-pattern (str location ".*")))
                  (continue-level connection thread-id)))))
           (when-let [location (jdi/location exception)]
             (let [location (jdi/location-type-name location)
                   location (re-find #"[^\$]+" location)]
               (make-restart
                :ignore-message "IGNORE-LOC"
                (str
                 "Do not enter debugger for exceptions with throw location "
                 location ".*")
                (fn [connection]
                  (trace "restart Ignoring exceptions")
                  (ignore-exception-location
                   connection (re-pattern (str location ".*")))
                  (continue-level connection thread-id)))))]
          (when-let [restarts (seq
                               (try
                                 (swell-impl/available-restarts context thread)
                                 (catch Exception _)))]
            (map
             #(make-restart
               :restart (str "RESTART " %)
               (str "Invoke restart " %)
               (fn [connection]
                 (trace "restart Ignoring exceptions")
                 (swell-impl/select-restart context thread %)
                 (continue-level connection thread-id)))
             restarts))))
        (filter
         identity
         [(when (pos? level)
            (make-restart
             :quit "QUIT" "Return to previous level."
             (fn [connection]
               (trace "restart Quiting to previous level")
               (quit-level connection thread-id))))])))))

(extend-type BreakpointEvent
  Debugger

  (condition-info
   [breakpoint _]
   {:message "BREAKPOINT"})

  (restarts
   [^BreakpointEvent breakpoint condition connection level]
   (trace "calculate-restarts breakpoint")
   (let [^ThreadReference thread (.thread breakpoint)
         thread-id (.uniqueID thread)]
     (concat
      [(make-restart
        :continue "CONTINUE" "Continue from breakpoint"
        (fn [connection]
          (trace "restart Continuing")
          (continue-level connection thread-id)))
       (make-restart
        :continue-clear "CONTINUE-CLEAR"
        "Continue and clear breakpoint"
        (fn [connection]
          (trace "restart Continue clear")
          (remove-breakpoint (vm-context connection) breakpoint)
          (continue-level connection thread-id)))]
      (stepping-restarts thread)))))

(extend-type StepEvent
  Debugger

  (condition-info
   [step _]
   {:message "STEPPING"})

  (restarts
   [^StepEvent step-event condition connection level]
   (trace "calculate-restarts step-event")
   (let [^ThreadReference thread (.thread step-event)
         thread-id (.uniqueID thread)]
     (concat
      [(make-restart
        :continue "CONTINUE" "Continue normal execution"
        (fn [connection]
          (trace "restart Continuing")
          (continue-level connection thread-id)))]
      (stepping-restarts thread)))))

(defn restart-info
  [connection event level]
  (let [condition-info (condition-info event connection)]
    {:condition condition-info
     :restarts (restarts event condition-info connection level)}))

(defn debugger-event-info
  "Calculate debugger information and invoke"
  [connection event]
  (trace "debugger-event-info")
  (let [{:keys [thread-id] :as level-info} (event-break-info connection event)
        [_ level] (break/break-level-info connection thread-id)]
    (merge level-info
           (restart-info connection event (or (and level (inc level)) 0)))))

(defn invoke-debugger
  "Calculate debugger information and invoke"
  [connection event]
  (trace "invoke-debugger")
  (let [{:keys [thread-id] :as level-info} (debugger-event-info
                                            connection event)
        debug-context (break/break-level-add! connection thread-id level-info)
        [_ level] (break/break-level-info connection thread-id)]
    (display-break-level connection level-info level)
    ;; The handler resumes threads, so make sure we suspend them again
    ;; first. The restart from the sldb buffer will resume these threads.
    (jdi/suspend-threads (:user-threads level-info))))

;;; Evaluation in frames
(defn frame-locals-with-string-values
  "Return frame locals for slime"
  [context ^ThreadReference thread n]
  (doall
   (for [{:keys [value] :as map-entry}
         (->>
          (jdi/unmangled-frame-locals (nth (.frames thread) n))
          (sort-by :unmangled-name))]
     (assoc map-entry
       :string-value
       (value-as-string (assoc context :current-thread thread) value)))))

(defn nth-frame-var
  "Return the var-index'th var in the frame-index'th frame"
  [context ^ThreadReference thread frame-index var-index]
  {:pre [(< frame-index (count (.frames thread)))]}
  (trace "debug/nth-frame-var %s %s" frame-index var-index)
  (->
   (seq (frame-locals-with-string-values context thread frame-index))
   (nth var-index)
   :value))

(defn local-bindings
  "Create a lexical environment with the specified values"
  [map-sym locals locals-map]
  (mapcat
   (fn [local]
     (let [local (:unmangled-name local)]
       `[~(symbol local) (~locals-map ~local ::not-found)]))
   locals))

(defn with-local-bindings-form
  "Create a form setting up local bindings around the given expr"
  [map-sym locals expr]
  (let [m (gensym "locals-map")]
    `(let [~m (var-get (ns-resolve '~'user '~map-sym))
           ~@(local-bindings map-sym locals m)]
       ~expr)))

(defn remote-map-sym
  "Return the remote symbol for a var to use in swank"
  [context thread]
  (trace "remote-map-sym")
  (symbol
               (jdi-clj/eval
                context thread
                {:disable-exception-requests true}
                `(gensym "swank"))))

(defn remote-empty-map
  "Return a remote empty map for use in resetting the swank remote var"
  [context thread]
  (trace "remote-empty-map")
  (jdi-clj/eval-to-value
              context thread
              {:disable-exception-requests true}
              `(hash-map)))

(def stm-types #{"clojure.lang.Atom"})

(defn stm-type? [^ObjectReference object-reference]
  (stm-types (.. object-reference referenceType name)))

(defn invoke-option-for [object-reference]
  jdi/invoke-single-threaded)

(defn assoc-local
  "Assoc a local variable into a remote var."
  [context thread map-var local]
  (trace "assoc-local %s %s %s" (:unmangled-name local) local map-var)
  (let [local-name (jdi-clj/remote-str context (:unmangled-name local))]
    (try
      (.disableCollection local-name)
      (let [local-value (when-let [value (:value local)]
                          (jdi-clj/remote-object value context thread))]
        (try
          (when local-value (.disableCollection local-value))
          (jdi-clj/remote-call
           context thread {:threading (invoke-option-for (:value local))}
           `assoc map-var local-name local-value)
          (finally
           (when local-value (.enableCollection local-value)))))
      (finally
       (.enableCollection local-name)))))

(defn set-remote-values
  "Build a map in map-var of name to value for all the locals"
  [context thread map-var locals]
  (trace "set-remote-values")
  (jdi-clj/swap-root
   context thread {}
   map-var
   (reduce
    (fn [v local] (assoc-local context thread v local))
    (jdi-clj/var-get context thread {:disable-exception-requests true} map-var)
    locals)))

(defn clear-remote-values
  [context thread map-var]
  {:pre [context thread map-var]}
  (trace "clear-remote-values")
  (jdi-clj/swap-root
   context thread {}
   map-var (remote-empty-map context thread)))

(defn eval-string-in-frame
  "Eval the string `expr` in the context of the specified `frame-number`."
  [connection context ^ThreadReference thread expr frame-number]
  (trace "eval-string-in-frame")
  (try
    (let [_ (assert (.isSuspended thread))
          locals (jdi/unmangled-frame-locals
                  (nth (.frames thread) frame-number))
          map-sym (remote-map-sym context thread)
          map-var (jdi-clj/eval-to-value
                   context thread {:disable-exception-requests true}
                   `(intern '~'user '~map-sym {}))]
      (try
        (jdi/possibly-disable-collection map-var)
        (set-remote-values context thread map-var locals)
        (let [v (jdi-clj/eval-to-value
                 context thread {}
                 (with-local-bindings-form
                   map-sym locals (read-string expr)))]
          (trace "eval-string-in-frame v %s" (pr-str v))
          (value-as-string (assoc context :current-thread thread) v))
        (finally
          (jdi/possibly-enable-collection map-var)
          (clear-remote-values context thread map-var)
          (trace "eval-string-in-frame done"))))
    (catch com.sun.jdi.InvocationException e
      (throw (RuntimeException.
              (jdi/exception-message context (.exception e) thread)
              e)))
    (finally
      (resume-thread-level-if-aborting connection (.uniqueID thread)))))

(defn pprint-eval-string-in-frame
  "Eval the string `expr` in the context of the specified `frame-number`
   and pretty print the result"
  [connection context ^ThreadReference thread expr frame-number]
  (try
    (let [_ (assert (.isSuspended thread))
          locals (jdi/unmangled-frame-locals
                  (nth (.frames thread) frame-number))
          map-sym (remote-map-sym context thread)
          map-var (jdi-clj/eval-to-value
                   context thread {:disable-exception-requests true}
                   `(intern '~'user '~map-sym {}))]
      (try
        (jdi/possibly-disable-collection map-var)
        (set-remote-values context thread map-var locals)
        (jdi-clj/eval-to-string
         context thread {}
         `(do
            (require 'clojure.pprint)
            (with-out-str
              ((resolve 'clojure.pprint/pprint)
               ~(with-local-bindings-form
                  map-sym locals (read-string expr))))))
        (finally
         (jdi/possibly-enable-collection map-var)
         (clear-remote-values context thread map-var)
         (trace "pprint-eval-string-in-frame done"))))
    (catch com.sun.jdi.InvocationException e
      (throw (RuntimeException.
              (jdi/exception-message context (.exception e) thread)
              e)))
    (finally
     (resume-thread-level-if-aborting connection (.uniqueID thread)))))

;;; Source location
(defn frame-source-location
  "Return a source location vector [buffer position] for the specified
   frame number."
  [^ThreadReference thread frame-number]
  (when-let [^StackFrame frame (nth (.frames thread) frame-number nil)]
    (let [location (.location frame)]
      (trace "frame-source-location %s" (bean location))
      [(find/find-source-path (jdi/location-source-path location))
       {:line (jdi/location-line-number location)}])))

;;; Inspection

;;; ## Primitive values
(defn lower-first [^String s]
  (str (string/lower-case (subs s 0 1)) (subs s 1)))

(defn primitive-value-as-string* [cls accessor]
  (let [obj (gensym "obj")]
    `(defmethod value-as-string ~(Class/forName (str "com.sun.jdi." (name cls)))
       [context# ~(vary-meta obj assoc :tag cls)]
       (trace "value-as-string: %s %s" '~cls ~obj)
       (pr-str (. ~obj ~accessor)))))

(defn primitive-value-as-string [cls]
  (let [obj (gensym "obj")]
    (primitive-value-as-string*
     cls (-> (string/split (name cls) #"\.") last lower-first symbol))))

(defmacro primitive-values-as-string [& cls]
  `(do ~@(map primitive-value-as-string cls)))

(defmacro primitive-value-as-string-with-method [cls method]
  (primitive-value-as-string* cls method))

(primitive-values-as-string
 BooleanValue ByteValue CharValue DoubleValue FloatValue LongValue ShortValue)

(primitive-value-as-string-with-method IntegerValue intValue)

;;; ## Other values
(defmethod value-as-string com.sun.jdi.StringReference
  [context ^StringReference obj]
  (trace "value-as-string: StringReference %s" obj)
  (pr-str (.value obj)))

(defmethod value-as-string com.sun.jdi.ObjectReference
  [context ^ObjectReference obj]
  {:pre [(:current-thread context)]}
  (trace "value-as-string: Value %s" obj)
  (try
    (jdi-clj/pr-str-arg context (:current-thread context) {} obj)
    (catch com.sun.jdi.InternalException e
      (trace "value-as-string: exeception %s" e)
      (format "#<%s>" (.. obj referenceType name)))))

;;; events
(defn add-exception-event-request
  [context]
  (trace "add-exception-event-request")
  (doto (jdi/exception-request (:vm context) nil true true)
    (jdi/suspend-policy exception-suspend-policy)))

;;; VM events

;; macros like `binding`, that use (try ... (finally ...)) cause exceptions
;; within their bodies to be considered caught.  We therefore need some
;; way for the user to be able to maintain a list of catch locations that
;; should not be considered as "caught".

(defn break-for?
  [connection exception-type location-name catch-location-name
   exception-message]
  (letfn [(equal-or-matches? [expr value]
            (trace "checking equal-or-matches? %s %s" expr value)
            (cond
             (string? expr) (= expr value)
             (string? value) (re-matches expr value)
             :else false))
          (matches? [{:keys [type location catch-location enabled message]
                      :as filter}]
            (and
             enabled
             (or (not type) (= type exception-type))
             (or (not location)
                 (equal-or-matches? location location-name))
             (or (not catch-location) (not catch-location-name)
                 (equal-or-matches? catch-location catch-location-name))
             (or (not message)
                 (equal-or-matches? message exception-message))))]
    (not (some matches? (exception-filters connection)))))

(defn break-for-exception?
  "Predicate to check whether we should invoke the debugger for the given
   exception event"
  [^ExceptionEvent exception-event connection]
  (let [catch-location (jdi/catch-location exception-event)
        location (jdi/location exception-event)
        location-name (jdi/location-type-name location)
        exception (.exception exception-event)
        exception-type (.. exception referenceType name)
        catch-location-name (when catch-location
                              (jdi/location-type-name catch-location))
        exception-msg (exception-message
                       connection
                       (.. (jdi/event-thread exception-event) uniqueID)
                       exception-event)]
    (trace
     "break-for-exception? %s %s %s"
     catch-location-name location-name exception-msg)

    (or (not catch-location)
        (break-for?
         connection
         exception-type location-name catch-location-name
         exception-msg))))


;; (defn connection-and-id-from-thread
;;   "Walk the stack frames to find the eval-for-emacs call and extract
;;    the id argument.  This finds the connection and id in the target
;;    vm"
;;   [context ^ThreadReference thread]
;;   (trace "connection-and-id-from-thread %s" thread)
;;   (some (fn [^StackFrame frame]
;;           (when-let [location (.location frame)]
;;             (when (and (= "ritz.swank$eval_for_emacs"
;;                           (jdi/location-type-name location))
;;                        (= "invoke" (jdi/location-method-name location)))
;;               ;; (trace "connection-and-id-from-thread found frame")
;;               (let [connection (first (.getArgumentValues frame))
;;                     id (last (.getArgumentValues frame))]
;;                 ;; (trace
;;                 ;;  "connection-and-id-from-thread id %s connection %s"
;;                 ;;  (jdi/object-reference id)
;;                 ;;  (jdi/object-reference connection))
;;                 {:connection connection
;;                  :id (jdi-clj/read-arg context thread id)}))))
;;         (.frames thread)))

(def ^{:private true} connection-for-event-fns (atom []))
(def ^{:private true} all-connections-fns (atom []))

(defn add-connection-for-event-fn! [f]
  (swap! connection-for-event-fns conj f))

(defn add-all-connections-fn! [f]
  (swap! all-connections-fns conj f))

(defn connection-for-event [event]
  (first (map #(% event) @connection-for-event-fns)))

(defn all-connections []
  (mapcat #(when % (%)) @all-connections-fns))

(defmethod jdi/handle-event ExceptionEvent
  [^ExceptionEvent event context]
  (let [exception (.exception event)
        thread (.thread event)
        silent? (jdi/silent-event? event)
        control-thread (:control-thread context)]
    (when (and
           control-thread
           (:RT context))
      (if (not silent?)
        ;; (trace "EXCEPTION %s" event)
        ;; assume a single connection for now
        (do
          (trace "EVENT %s" (.toString event))
          (trace "EXCEPTION %s" exception)
          ;; would like to print this, but can cause hangs
          ;;    (jdi-clj/exception-message context event)
          (if-let [connection (connection-for-event event)]
            (let [control-threads (set
                                   (filter
                                    #(instance? ThreadReference %)
                                    (vals (vm-context connection))))]
              (trace "thread %s" thread)
              (trace "control-threads %s" control-threads)
              (if (control-threads thread)
                (trace "Not activating sldb (control thread)")
                (if (break/aborting-level? connection (.uniqueID thread))
                  (trace "Not activating sldb (aborting)")
                  (when (break-for-exception? event connection)
                    (trace "Activating sldb")
                    (invoke-debugger connection event)))))
            (trace "Not activating sldb (no connection)")))
        (do
          (trace-str "@")
          ;; (trace
          ;;  "jdi/handle-event ExceptionEvent: Silent EXCEPTION %s %s"
          ;;  event
          ;;  (.. exception referenceType name)
          ;;  ;; (jdi-clj/exception-message context event)
          ;;  ;; (jdi/exception-event-string context event)
          ;;  )
          )))))

(defmethod jdi/handle-event BreakpointEvent
  [^BreakpointEvent event context]
  (trace "BREAKPOINT")
  (let [thread (.thread event)]
    (when (and (:control-thread context) (:RT context))
      (if-let [connection (connection-for-event event)]
        (do
          (trace "Activating sldb for breakpoint")
          (invoke-debugger connection event))
        (trace "Not activating sldb (no connection)")))))

(defmethod jdi/handle-event StepEvent
  [^StepEvent event context]
  (trace "STEP")
  (let [thread (.thread event)]
    (when (and (:control-thread context) (:RT context))
      (if-let [connection (connection-for-event event)]
        (do
          (trace "Activating sldb for stepping")
          ;; The step event is completed, so we discard it's request
          (jdi/discard-event-request (:vm context) (.. event request))
          (invoke-debugger connection event))
        (trace "Not activating sldb (no connection)")))))

(defmethod jdi/handle-event VMDeathEvent
  [event context]
  (doseq [connection (all-connections)]
    (trace "Closing connection")
    (connection-close connection))
  (System/exit 0))

(defmethod jdi/handle-event VMDisconnectEvent
  [event context]
  (doseq [connection (all-connections)]
    (trace "Closing connection")
    (connection-close connection))
  (System/exit 0))





;;; # Disassembler
(defn add-op-location
  [^Method method {:keys [code-index] :as op}]
  (if code-index
    (merge
     op
     (jdi/location-data (.locationOfCodeIndex method (long code-index))))
    op))

(defn format-arg
  [arg]
  (case (:type arg)
        :methodref (format
                    "%s/%s%s"
                    (string/replace (:class-name arg) "/" ".")
                    (:name arg) (:descriptor arg))
        :fieldref (format
                    "^%s %s.%s"
                     (:descriptor arg)
                     (string/replace (:class-name arg) "/" ".")
                     (:name arg))
        :interfacemethodref (format
                             "%s/%s%s"
                             (string/replace (:class-name arg) "/" ".")
                             (:name arg) (:descriptor arg))
        :nameandtype (format "%s%s" (:name arg) (:descriptor arg))
        :class (format "%s" (:name arg))
        (if-let [value (:value arg)]
          (format "%s" (pr-str value))
          (format "%s" (pr-str arg)))))

(defn format-ops
  [ops]
  (first
   (reduce
    (fn [[output location] op]
      (let [line (:line location)
            s (format
               "%5d  %s %s"
               (:code-index op) (:mnemonic op)
               (string/join " " (map format-arg (:args op))))]
        (if (= line (:line op))
          [(conj output s) location]
          [(->
            output
            (conj (format
                   "%s:%s"
                   (or (:source-path op) (:source op) (:function op))
                   (:line op)))
            (conj s))
           (select-keys op [:function :line])])))
    [[] {}]
    ops)))

(defn disassemble-method
  "Dissasemble a method, adding location info"
  [const-pool ^Method method]
  (let [ops (disassemble/disassemble const-pool (.bytecodes method))]
    (format-ops (map #(add-op-location method %) ops))))

(defn disassemble-frame
  [context ^ThreadReference thread frame-index]
  (let [^StackFrame frame (nth (.frames thread) frame-index)
        location (.location frame)]
    (if-let [method (.method location)]
      (let [const-pool (disassemble/constant-pool
                        (.. method declaringType constantPool))]
        (disassemble-method const-pool method))
      "Method not found")))

(defn disassemble-symbol
  "Dissasemble a symbol var"
  [context ^ThreadReference thread sym-ns sym-name]
  (trace "disassemble-symbol %s %s" sym-ns sym-name)
  (when-let [[^ObjectReference f methods]
             (jdi-clj/clojure-fn-deref context thread {} sym-ns sym-name)]
    (let [const-pool (disassemble/constant-pool
                      (.. f referenceType constantPool))]
      (apply
       concat
       (for [^Method method methods
             :let [ops (disassemble-method const-pool method)]
             :let [^String clinit (first (drop 3 ops))]
             :when (not (.contains clinit ":invokevirtual \""))]
         (concat
          [(str sym-ns "/" sym-name
                "(" (string/join " " (.argumentTypeNames method)) ")\n")]
          ops
          ["\n\n"]))))))
