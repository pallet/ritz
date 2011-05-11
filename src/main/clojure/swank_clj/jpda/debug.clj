(ns swank-clj.jpda.debug
  "Debug functions using jpda/jdi, used to implement debugger commands via jpda.
   The aim is to move all return messaging back up into swank-clj.commands.*
   and to accept only vm-context aguments, rather than a connection"
  (:require
   [swank-clj.connection :as connection]
   [swank-clj.executor :as executor]
   [swank-clj.inspect :as inspect]
   [swank-clj.jpda.jdi :as jdi]
   [swank-clj.jpda.jdi-clj :as jdi-clj]
   [swank-clj.jpda.jdi-vm :as jdi-vm]
   [swank-clj.logging :as logging]
   [swank-clj.repl-utils.find :as find]
   [swank-clj.repl-utils.helpers :as helpers]
   [swank-clj.rpc-socket-connection :as rpc-socket-connection]
   [swank-clj.swank.core :as core]
   [swank-clj.swank.messages :as messages] ;; TODO - remove this
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string])
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
   (com.sun.jdi
    BooleanValue ByteValue CharValue DoubleValue FloatValue IntegerValue
    LongValue ShortValue StringReference)))

(def control-thread-name "swank-clj-debug-thread")

(def exception-suspend-policy :suspend-all)
(def breakpoint-suspend-policy :suspend-all)
(def exception-policy
  (atom {:uncaught-only true
         :class-exclusion ["java.net.URLClassLoader*"
                           "java.lang.ClassLoader*"
                           "*ClassLoader.java"]
         :system-thread-names [control-thread-name
                               "REPL" "Accept loop"
                               "Connection dispatch loop :repl"]}))

(def first-eval-seen (atom false))
;;;

(def *sldb-initial-frames* 10)

(defonce connections (atom {}))

(defn add-connection [connection proxied-connection]
  (swap! connections assoc connection proxied-connection))

(defn remove-connection [connection]
  (swap! connections dissoc connection))


(defn log-exception [e]
  (logging/trace
   "Caught exception %s %s"
   (pr-str e)
   (helpers/stack-trace-string e)))


;; (defn request-events
;;   "Add event requests that should be set before resuming the vm."
;;   [context]
;;   (let [req (doto (jdi/exception-request (:vm context) nil true true)
;;               (jdi/suspend-policy :suspend-event-thread)
;;               (.enable))]
;;     (swap! context assoc :exception-request req)))

;;; debugee function for starting a thread that may be used from the debugger
(defn- vm-swank-main
  [options]
  `(try
     (require '~'swank-clj.socket-server)
     ((resolve '~'swank-clj.socket-server/start) ~options)
     (catch Exception e#
       (println e#)
       (.printStackTrace e#))))

;;; functions for acquiring the control thread in the proxy
(defn launch-vm-with-swank
  "Launch and configure the vm for the debugee."
  [{:keys [port announce log-level classpath] :as options}]
  (jdi-vm/launch-vm
   (or classpath (jdi-vm/current-classpath))
   (vm-swank-main {:port port
                   :announce announce
                   :server-ns `(quote swank-clj.repl)
                   :log-level (keyword log-level)})))

(defn launch-vm
  "Launch and configure the vm for the debugee."
  [{:keys [classpath main] :as options}]
  (jdi-vm/launch-vm (or classpath (jdi-vm/current-classpath)) main))

;; (defn launch-vm-without-swank
;;   "Launch and configure the vm for the debugee."
;;   [classpath {:as options}]
;;   (logging/trace "launch-vm-without-swank %s" classpath)
;;   (jdi-vm/launch-vm classpath ))

;; (defn stop-vm
;;   [context]
;;   (when context
;;     (.exit (:vm context) 0)
;;     (reset! (:continue-handling context) nil)
;;     nil))

(defn connect-to-repl-on-vm [port]
  (logging/trace "debugger/connect-to-repl-on-vm port %s" port)
  (Socket. "localhost" port))

(defn create-connection [options]
  (logging/trace "debugger/create-connection: connecting to proxied connection")
  (->
   (connect-to-repl-on-vm (:port options))
   (rpc-socket-connection/create options)
   (connection/create options)))


(defn remote-swank-port
  "Obtain the swank port on the remote vm"
  [context]
  (loop []
    (logging/trace "debug/remote-swank-port: loop")
    (if-let [port (jdi-clj/control-eval
                   context
                   `(deref swank-clj.socket-server/acceptor-port))]
      port
      (do
        (logging/trace "debug/remote-swank-port: no port yet ...")
        (Thread/sleep 1000)
        (recur)))))

;;; interactive form tracking
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
    (logging/trace
     "inspect-if-inspector-active %s %s %s"
     f
     (re-find #"inspect" (name (first form)))
     (inspect/content (connection/inspector connection)))
    (if (and f
             (re-find #"inspect" (name (first form)))
             (inspect/content (connection/inspector connection)))
      (core/execute-slime-fn* connection f (rest form) buffer-package)
      (handler connection form buffer-package id f))))

(defn execute-peek
  [handler]
  (fn [connection form buffer-package id f]
    (swank-peek connection form buffer-package id f)
    (handler connection form buffer-package id f)))

(defn execute-unless-inspect
  [handler]
  (fn [connection form buffer-package id f]
    (if (and f (not (re-find #"inspect" (name (first form)))))
      (core/execute-slime-fn* connection f (rest form) buffer-package)
      (handler connection form buffer-package id f))))

(declare clear-abort-for-current-level format-thread threads)

(defn forward-command
  [handler]
  (fn [connection form buffer-package id f]
    (let [proxied-connection (:proxy-to @connection)]
      (logging/trace
       "debugger/forward-command: forwarding %s to proxied connection"
       (first form))
      (logging/trace
       "VM threads:\n%s"
       (string/join
        "\n"
        (map format-thread (threads (connection/vm-context connection)))))
      (clear-abort-for-current-level connection)
      (executor/execute-request
       (partial
        connection/send-to-emacs
        proxied-connection (list :emacs-rex form buffer-package true id)))
      :swank-clj.swank/pending)))

(defn forward-rpc
  [connection rpc]
  (let [proxied-connection (:proxy-to @connection)]
    (logging/trace
     "debugger/forward-command: forwarding %s to proxied connection" rpc)
    (executor/execute-request
     (partial connection/send-to-emacs proxied-connection rpc))))

(defn forward-reply
  [connection]
  (logging/trace
   "debugger/forward-command: waiting reply from proxied connection")
  (let [proxied-connection (:proxy-to @connection)]
    (let [reply (connection/read-from-connection proxied-connection)]
      (executor/execute-request
       (partial connection/send-to-emacs connection reply))
      (let [id (last reply)]
        (logging/trace "removing pending-id %s" id)
        (connection/remove-pending-id connection id)))))


;;; threads
(defn format-thread
  [thread-reference]
  (format
   "%s %s (suspend count %s)"
   (.name thread-reference)
   (jdi/thread-states (.status thread-reference))
   (.suspendCount thread-reference)))

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
  "Provide a list of threads. The list is cached in the context
   to allow it to be retrieved by index."
  [context]
  (let [threads (flatten
                 (map
                  #(transform-thread-group "" %)
                  (jdi/thread-groups (:vm context))))]
    (assoc context :threads threads)))

(defn nth-thread
  [context index]
  (nth (:threads context) index nil))

(defn stop-thread
  [context thread-id]
  (when-let [thread (some
                     #(and (= thread-id (.uniqueID %)) %)
                     (threads context))]
    (.stop
     thread
     (jdi-clj/control-eval-to-value
      context `(new java.lang.Throwable "Stopped by swank")))))

(defn level-info-thread-id
  [level-info]
  (.uniqueID (:thread level-info)))





;;; stacktrace
(defn- frame-data
  "Extract data from a stack frame"
  [frame]
  (jdi/location-data (jdi/location frame)))

(defn- exception-stacktrace [frames]
  (logging/trace "exception-stacktrace")
  (map frame-data frames))

(defn- build-backtrace
  ([thread]
     (doall (exception-stacktrace (.frames thread))))
  ([thread start end]
     (doall (exception-stacktrace
             (take (- end start) (drop start (.frames thread)))))))

(defn backtrace
  "Create a backtrace for the specified frames.
   TODO: seperate out return message generation."
  [connection start end]
  (when-let [[level-info level] (connection/current-sldb-level-info connection)]
    (build-backtrace (:thread level-info) start end)))


;;; Source location
(defn frame-source-location
  "Return a source location vector [buffer position] for the specified
   frame number."
  [connection frame-number]
  (let [[level-info level] (connection/current-sldb-level-info connection)]
    (when-let [frame (nth (.frames (:thread level-info)) frame-number nil)]
      (let [location (.location frame)]
        [(find/find-source-path (jdi/location-source-path location))
         {:line (jdi/location-line-number location)}]))))

;;; breakpoints
;;; todo - use the event manager's event list
(defn breakpoint-list
  "Update the context with a list of breakpoints. The list is cached in the
   context to allow it to be retrieveb by index."
  [context]
  (let [breakpoints (map
                     #(->
                       (jdi/breakpoint-data %1)
                       (assoc :id %2))
                     (jdi/breakpoints (:vm context))
                     (iterate inc 0))]
    (assoc-in context [:breakpoints] breakpoints)))

(defn line-breakpoint
  "Set a line breakpoint."
  [context namespace filename line]
  (let [breakpoints (jdi/line-breakpoints
                     (:vm context) breakpoint-suspend-policy
                     namespace filename line)]
    (println (format "line-breakpoint %s %s %s" namespace filename line))
    (update-in context [:breakpoints] concat breakpoints)))

(defn remove-breakpoint
  "Set a line breakpoint."
  [context event]
  (update-in context [:breakpoints]
             (fn [breakpoints]
               (when-let [request (.request event)]
                 (.disable request)
                 (.deleteEventRequest
                  (.eventRequestManager (.virtualMachine event)) request)
                 (remove #(= % request) breakpoints)))))

(defn breakpoints-for-id
  [context id]
  (let [vm (:vm context)]
    (when-let [breakpoints (:breakpoints context)]
      (when-let [{:keys [file line]} (nth breakpoints id nil)]
        (doall (filter
                #(let [location (.location %)]
                   (and (= file (.sourcePath location))
                        (= line (.lineNumber location))))
                (jdi/breakpoints vm)))))))

(defn breakpoint-kill
  [context breakpoint-id]
  (doseq [breakpoint (breakpoints-for-id context breakpoint-id)]
    (.disable breakpoint)
    (.. breakpoint (virtualMachine) (eventRequestManager)
        (deleteEventRequest breakpoint))))

(defn breakpoint-enable
  [context breakpoint-id]
  (doseq [breakpoint (breakpoints-for-id context breakpoint-id)]
    (.enable breakpoint)))

(defn breakpoint-disable
  [context breakpoint-id]
  (doseq [breakpoint (breakpoints-for-id context breakpoint-id)]
    (.disable breakpoint)))

(defn breakpoint-location
  [context breakpoint-id]
  (when-let [breakpoint (first (breakpoints-for-id context breakpoint-id))]
    (let [location (.location breakpoint)]
      (logging/trace
       "debug/breakpoint-location %s %s %s"
       (jdi/location-source-name location)
       (jdi/location-source-path location)
       (jdi/location-line-number location))
      (when-let [path (find/find-source-path
                       (jdi/location-source-path location))]
        [path {:line (jdi/location-line-number location)}]))))

;;; debug methods

;; This is a synthetic Event for an InvocationException delivered to the debug
;; thread calling invokeMethod.  These exceptions are (unfortunately) not
;; reported through the jdi debug event loop.
(deftype InvocationExceptionEvent
    [exception thread]
  com.sun.jdi.event.ExceptionEvent
  (catchLocation [_] nil)
  (exception [_] exception)
  (location [_] (.location exception))
  (thread [_] thread)
  (virtualMachine [_] (.virtualMachine exception))
  (request [_] nil))

;; Restarts
(defn resume
  [thread-ref suspend-policy]
  (case suspend-policy
    :suspend-all (.resume (.virtualMachine thread-ref))
    :suspend-event-thread (.resume thread-ref)
    nil))


(defn- resume-sldb-levels
  "Resume sldb levels specified in the connection.
   This is side effecting, so can not be used within swap!"
  [connection]
  (doseq [level-info (:resume-sldb-levels connection)
          :let [event (:event level-info)]
          :when (not (instance? InvocationExceptionEvent event))]
    (logging/trace "resuming threads for sldb-level")
    (jdi/resume-event-threads event))
  connection)

(defn- return-or-activate-sldb-levels
  "Return the nested debug levels"
  [connection connection-map]
  (if-let [[level-info level] (connection/current-sldb-level-info connection)]
    (connection/send-to-emacs
     connection
     (messages/debug-activate (.uniqueID (:thread level-info)) level))
    (let [resume-levels (:resume-sldb-levels connection-map)]
      (connection/send-to-emacs
       connection
       (messages/debug-return
        (.uniqueID (:thread (first resume-levels))) (count resume-levels)))))
  connection)

(defn- continue-level
  "Continue the current level"
  [connection]
  (logging/trace "continue-level")
  (return-or-activate-sldb-levels
   connection
   (resume-sldb-levels
    (swap!
     connection
     (fn [current]
       (->
        current
        (assoc-in [:resume-sldb-levels] [(last (:sldb-levels current))])
        (update-in
         [:sldb-levels]
         (fn [levels] (subvec levels 0 (dec (count levels))))))))))
  nil)

(defn- quit-level
  "Abort the current level"
  [connection]
  (logging/trace "quit-level")
  (return-or-activate-sldb-levels
   connection
   (resume-sldb-levels
    (swap!
     connection
     (fn [current]
       (->
        current
        (assoc :abort-to-level (dec (count (:sldb-levels current))))
        (assoc-in [:resume-sldb-levels] [(last (:sldb-levels current))])
        (update-in
         [:sldb-levels]
         (fn [levels] (subvec levels 0 (dec (count levels))))))))))
  nil)

(defn abort-all-levels
  [connection]
  (logging/trace "abort-all-levels")
  (return-or-activate-sldb-levels
   connection
   (resume-sldb-levels
    (swap!
     connection
     (fn [connection]
       (->
        connection
        (assoc-in [:resume-sldb-levels] (reverse (:sldb-levels connection)))
        (assoc-in [:sldb-levels] [])
        (assoc :abort-to-level 0))))))
  nil)

(defn aborting-level?
  "Aborting predicate."
  [connection]
  (connection/aborting-level? connection))

(defn clear-abort-for-current-level
  "Clear any abort for the current level"
  [connection]
  (swap!
   connection
   (fn [c]
     (logging/trace
      "clear-abort-for-current-level %s %s"
      (count (:sldb-levels c)) (:abort-to-level c))
     (if (and (:abort-to-level c)
              (= (count (:sldb-levels c)) (:abort-to-level c)))
       (dissoc c :abort-to-level)
       c))))

(defn step-request
  [thread size depth]
  (doto (jdi/step-request thread size depth)
    (.addCountFilter 1)
    (jdi/suspend-policy breakpoint-suspend-policy)
    (.enable)))

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
  [thread id name description size depth]
  (make-restart
   id name description
   (fn [connection]
     (logging/trace "restart %s" name)
     (step-request thread size depth)
     (continue-level connection))))

(defn stepping-restarts
  [thread]
  [(make-step-restart
    thread :step-into "STEP" "Step into the next line" :line :into)
   (make-step-restart
    thread :step-next "STEP-NEXT" "Step to the next line" :line :over)
   (make-step-restart
    thread :step-out "STEP-OUT" "Step out of current frame" :line :out)])

(defprotocol Debugger
  (condition-info [event context])
  (restarts [event connection]))

(extend-type ExceptionEvent
  Debugger

  (condition-info
   [event context]
   (let [exception (.exception event)]
     {:message (or (jdi-clj/exception-message context event) "No message.")
      :type (str "  [Thrown " (.. exception referenceType name) "]")}))

  (restarts
   [exception connection]
   (logging/trace "calculate-restarts exception")
   (let [thread (jdi/event-thread exception)]
     (if (.request exception)
       (filter
        identity
        [(make-restart
          :continue "CONTINUE" "Pass exception to program"
          (fn [connection]
            (logging/trace "restart Continuing")
            (continue-level connection)))
         (make-restart
          :abort "ABORT" "Return to SLIME's top level."
          (fn [connection]
            (logging/trace "restart Aborting to top level")
            (abort-all-levels connection)))
         (when (pos? (connection/sldb-level connection))
           (make-restart
            :quit "QUIT" "Return to previous level."
            (fn [connection]
              (logging/trace "restart Quiting to previous level")
              (quit-level connection))))])
       (filter
        identity
        [(when (pos? (connection/sldb-level connection))
           (make-restart
            :quit "QUIT" "Return to previous level."
            (fn [connection]
              (logging/trace "restart Quiting to previous level")
              (quit-level connection))))])))))

(extend-type BreakpointEvent
  Debugger

  (condition-info
   [breakpoint _]
   {:message "BREAKPOINT"})

  (restarts
   [breakpoint connection]
   (logging/trace "calculate-restarts breakpoint")
   (let [thread (jdi/event-thread breakpoint)]
     (concat
      [(make-restart
        :continue "CONTINUE" "Continue from breakpoint"
        (fn [connection]
          (logging/trace "restart Continuing")
          (continue-level connection)))
       (make-restart
        :continue-clear "CONTINUE-CLEAR"
        "Continue and clear breakpoint"
        (fn [connection]
          (logging/trace "restart Continue clear")
          (remove-breakpoint connection breakpoint)
          (continue-level connection)))]
      (stepping-restarts thread)))))

(extend-type StepEvent
  Debugger

  (condition-info
   [step _]
   {:message "STEPPING"})

  (restarts
   [step-event connection]
   (logging/trace "calculate-restarts step-event")
   (let [thread (jdi/event-thread step-event)]
     (concat
      [(make-restart
        :continue "CONTINUE" "Continue normal execution"
        (fn [connection]
          (logging/trace "restart Continuing")
          (continue-level connection)))]
      (stepping-restarts thread)))))

(defn invoke-debugger*
  "Calculate debugger information and invoke"
  [connection event]
  (logging/trace "invoke-debugger*")
  (let [thread (jdi/event-thread event)
        thread-id (.uniqueID thread)
        restarts (restarts event connection)
        level-info {:restarts restarts :thread thread :event event}
        level (connection/next-sldb-level connection level-info)
        _ (logging/trace "building condition")
        condition (condition-info event @(:vm-context @connection))
        _ (logging/trace "building backtrace")
        backtrace (if (instance? InvocationExceptionEvent event)
                    [{:function "Unavailble" :source "UNKNOWN" :line "UNKNOWN"}]
                    (build-backtrace thread 0 *sldb-initial-frames*))]
    (logging/trace "invoke-debugger: send-to-emacs")
    (connection/send-to-emacs
     connection
     (messages/debug
      thread-id level condition restarts backtrace
      (connection/pending connection)))
    (connection/send-to-emacs
     connection (messages/debug-activate thread-id level))))

(defn invoke-debugger
  "Calculate debugger information and invoke"
  [connection event]
  (logging/trace "invoke-debugger")
  ;; Invoke debugger from a new thread, so we don't block the
  ;; event loop
  (executor/execute #(invoke-debugger* connection event))
  ;; the handler resumes threads, so make sure we suspend them
  ;; again first
  (jdi/suspend-event-threads event))

(defn debugger-info-for-emacs
  "Calculate debugger information and invoke"
  [connection start end]
  (logging/trace "debugger-info")
  (let [[level-info level] (connection/current-sldb-level-info connection)
        thread (:thread level-info)
        event (:event level-info)]
    (logging/trace "invoke-debugger: send-to-emacs")
    (messages/debug-info
     (condition-info event @(:vm-context @connection))
     (:restarts level-info)
     (if (instance? InvocationExceptionEvent event)
       [{:function "Unavailble" :source "UNKNOWN" :line "UNKNOWN"}]
       (build-backtrace thread start end))
     (connection/pending connection))))

(defn invoke-restart
  [connection level n]
  (let [level-info (connection/sldb-level-info connection level)]
    (logging/trace "invoke-restart %s of %s" n (count (:restarts level-info)))
    (when-let [f (:f (nth (:restarts level-info) n))]
      (inspect/reset-inspector (:inspector @connection))
      (f connection))
    (level-info-thread-id level-info)))

(defn invoke-named-restart
  [connection kw]
  (logging/trace "invoke-named-restart %s" kw)
  (when-let [[level-info level] (connection/current-sldb-level-info connection)]
    (if-let [f (:f (some #(and (= kw (:id %)) %) (:restarts level-info)))]
      (do (inspect/reset-inspector (:inspector @connection))
          (f connection))
      (do (logging/trace "invoke-named-restart %s not found" kw)
          (format "Restart %s not found" kw)))))

(def stm-types #{"clojure.lang.Atom"})

(defn stm-type? [object-reference]
  (stm-types (.. object-reference referenceType name)))

(defn lazy-seq? [object-reference]
  (= "clojure.lang.LazySeq" (.. object-reference referenceType name)))

(defn invoke-option-for [object-reference]
  (logging/trace "invoke-option-for %s" object-reference)
  (if (and (instance? ObjectReference object-reference)
           (stm-type? object-reference))
    jdi/invoke-multi-threaded
    jdi/invoke-single-threaded))

(defmethod inspect/value-as-string com.sun.jdi.PrimitiveValue
  [context obj] (pr-str (.value obj)))

(defmethod inspect/value-as-string com.sun.jdi.StringReference
  [context obj] (pr-str (.value obj)))

(defmethod inspect/value-as-string com.sun.jdi.Value
  [context obj]
  {:pre [(:current-thread context)]}
  (try
    (jdi-clj/pr-str-arg
     context (:current-thread context) jdi/invoke-single-threaded obj)
    (catch com.sun.jdi.InternalException e
      (logging/trace "inspect/value-as-string: exeception %s" e)
      (format "#<%s>" (.. obj referenceType name)))))

(defmethod inspect/object-content-range com.sun.jdi.PrimitiveValue
  [context object start end]
  (inspect/object-content-range context (.value object) start end))

(defmethod inspect/object-content-range com.sun.jdi.Value
  [context object start end]
  (logging/trace
   "inspect/object-content-range com.sun.jdi.Value %s %s" start end)
  (jdi-clj/read-arg
   context
   (:current-thread context)
   (jdi-clj/invoke-clojure-fn
    context (:current-thread context)
    jdi/invoke-multi-threaded
    "swank-clj.inspect" "object-content-range"
    nil object
    (jdi-clj/eval-to-value
     context (:current-thread context) jdi/invoke-single-threaded start)
    (jdi-clj/eval-to-value
     context (:current-thread context) jdi/invoke-single-threaded end))))

(defmethod inspect/object-nth-part com.sun.jdi.Value
  [context object n max-index]
  (jdi-clj/read-arg
   context
   (:current-thread context)
   (jdi-clj/invoke-clojure-fn
    "swank-clj.inspect" "object-nth-part"
    (:current-thread context) jdi/invoke-single-threaded
    nil object
    (jdi/mirror-of (:vm context) n)
    (jdi/mirror-of (:vm context) max-index))))

(defmethod inspect/object-call-nth-action :default com.sun.jdi.Value
  [context object n max-index args]
  (jdi-clj/read-arg
   context
   (:current-thread context)
   (jdi-clj/invoke-clojure-fn
    context
    (:current-thread context)
    jdi/invoke-multi-threaded
    "swank-clj.inspect" "object-call-nth-action"
    object
    (jdi/mirror-of (:vm context) n)
    (jdi/mirror-of (:vm context) max-index)
    (jdi-clj/eval
     (:vm context) (:current-thread context)
     jdi/invoke-single-threaded
     args))))

(defn frame-locals
  "Return frame locals for slime, a sequence of [LocalVariable Value]
   sorted by name."
  [thread n]
  (let [frame (nth (.frames thread) n)]
    (sort-by
     #(.name (key %))
     (merge {} (jdi/frame-locals frame) (jdi/clojure-locals frame)))))

(defn frame-locals-with-string-values
  "Return frame locals for slime"
  [context thread n]
  (doall
   (for [map-entry (seq (frame-locals thread n))]
     {:name (.name (key map-entry))
      :value (val map-entry)
      :string-value (inspect/value-as-string
                     (assoc context :current-thread thread)
                     (val map-entry))})))

(defn nth-frame-var
  "Return the var-index'th var in the frame-index'th frame"
  [context thread frame-index var-index]
  {:pre [(< frame-index (count (.frames thread)))]}
  (logging/trace "debug/nth-frame-var %s %s" frame-index var-index)
  (->
   (seq (frame-locals-with-string-values context thread frame-index))
   (nth var-index)
   :value))

(defn local-bindings
  "Create a lexical environment with the specified values"
  [map-sym locals]
  (mapcat
   (fn [local]
     (let [local (.name (key local))]
       `[~(symbol local) ((var-get (ns-resolve '~'user '~map-sym)) ~local)]))
   locals))

(defn with-local-bindings-form
  "Create a form setting up local bindings around the given expr"
  [map-sym locals expr]
  `(let [~@(local-bindings map-sym locals)] ~expr))

(def ^{:private true
       :doc "A symbol generated on the debuggee, naming a var for our use"}
  remote-map-sym-value (atom nil))

(defn remote-map-sym
  "Return the remote symbol for a var to use in swank"
  [context thread]
  (or @remote-map-sym-value
      (reset! remote-map-sym-value
              (symbol (jdi-clj/eval
                       context thread jdi/invoke-single-threaded
                       `(gensym "swank"))))))

(def ^{:private true
       :doc "An empty map"}
  remote-empty-map-value (atom nil))

(defn remote-empty-map
  "Return a remote empty map for use in resetting the swank remote var"
  [context thread]
  (or @remote-empty-map-value
      (reset! remote-empty-map-value
              (jdi-clj/eval-to-value
               context thread jdi/invoke-single-threaded
               `(hash-map)))))

(defn assoc-local
  "Assoc a local variable into a remote var"
  [context thread map-var local]
  (jdi-clj/remote-call
   context thread (invoke-option-for (val local))
   `assoc map-var
   (jdi-clj/remote-str context (.name (key local)))
   (when-let [value (val local)]
     (jdi-clj/remote-object value context thread))))

(defn set-remote-values
  "Build a map in map-var of name to value for all the locals"
  [context thread map-var locals]
  (jdi-clj/swap-root
   context thread jdi/invoke-single-threaded
   map-var
   (reduce
    (fn [v local] (assoc-local context thread v local))
    (jdi-clj/var-get context thread jdi/invoke-single-threaded map-var)
    locals)))

(defn clear-remote-values
  [context thread map-var]
  (jdi-clj/swap-root
   context thread jdi/invoke-single-threaded
   map-var (remote-empty-map context thread)))

(defn eval-string-in-frame
  "Eval the string `expr` in the context of the specified `frame-number`."
  [context thread expr frame-number]
  (try
    (let [_ (assert (.isSuspended thread))
          locals (frame-locals thread frame-number)
          _ (logging/trace "eval-string-in-frame: map-sym")
          map-sym (remote-map-sym context thread)
          _ (logging/trace "eval-string-in-frame: map-var for %s" map-sym)
          map-var (jdi-clj/eval-to-value
                   context thread jdi/invoke-single-threaded
                   `(intern '~'user '~map-sym {}))
          _ (logging/trace "eval-string-in-frame: form")
          form (with-local-bindings-form map-sym locals (read-string expr))]
      (logging/trace "eval-string-in-frame: form %s" form)
      (try
        (logging/trace "eval-string-in-frame: set-remote-values")
        (set-remote-values context thread map-var locals)
        ;; create a bindings form
        (logging/trace "eval-string-in-frame: eval")
        (jdi-clj/eval context thread jdi/invoke-single-threaded form)
        (finally
         (logging/trace "eval-string-in-frame: clear-remote-values")
         (clear-remote-values context thread map-var))))
    (catch com.sun.jdi.InvocationException e
      (invoke-debugger*
       context (InvocationExceptionEvent. (.exception e) thread)))))

;;; events
(defn add-exception-event-request
  [context]
  (logging/trace "add-exception-event-request")
  (doto (jdi/exception-request (:vm context) nil true true)
    (jdi/suspend-policy exception-suspend-policy)
    (.enable)))

;;; VM events
(defn caught?
  "Predicate for testing if the given exception is caught outside of swank-clj"
  [exception-event]
  (when-let [catch-location (jdi/catch-location exception-event)]
    (let [catch-location-name (jdi/location-type-name catch-location)
          location (jdi/location exception-event)
          location-name (jdi/location-type-name location)]
      (logging/trace "caught? %s %s" catch-location-name location-name)
      (or (not (.startsWith catch-location-name "swank_clj.swank"))
          (and
           (not (re-matches #"[^$]+\$eval.*." location-name))
           (.startsWith catch-location-name "clojure.lang.Compiler"))))))

(defn ignore-location?
  "Predicate for testing if the given thread is inside of swank-clj"
  [thread]
  (when-let [frame (first (.frames thread))]
    (when-let [location (.location frame)]
      (let [location-name (jdi/location-type-name location)]
        (logging/trace "ignore-location? %s" location-name)
        (or (.startsWith location-name "swank_clj.swank")
            (.startsWith location-name "swank_clj.commands.contrib")
            (.startsWith location-name "clojure.lang.Compiler"))))))

(defn stacktrace-contains?
  "Predicate to check for specific deifining type name in the stack trace."
  [thread defining-type]
  (some
   #(= defining-type (jdi/location-type-name (.location %)))
   (.frames thread)))

(defn break-for-exception?
  "Predicate to check whether we should invoke the debugger fo the given
   exception event"
  [exception-event]
  (let [catch-location (jdi/catch-location exception-event)
        location (jdi/location exception-event)
        location-name (jdi/location-type-name location)]
    (or
     (not catch-location)
     (let [catch-location-name (jdi/location-type-name catch-location)]
       (logging/trace
        "break-for-exception? %s %s" catch-location-name location-name)
       (or
        (.startsWith catch-location-name "swank_clj.swank")
        (and
         (.startsWith catch-location-name "clojure.lang.Compiler")
         (stacktrace-contains?
          (jdi/event-thread exception-event)
          "swank_clj.commands.basic$eval_region")))
       ;; (or
       ;; ;; (and
       ;; ;;  (.startsWith location-name "clojure.lang.Compiler")
       ;; ;;  (re-matches #"[^$]+\$eval.*." catch-location-name))
       ;; ;; (and
       ;; ;;  (.startsWith catch-location-name "clojure.lang.Compiler")
       ;; ;;  (re-matches #"[^$]+\$eval.*." location-name))
       ;; (.startsWith catch-location-name "swank_clj.swank"))
       ;; (or
       ;;  (and
       ;;   (.startsWith location-name "clojure.lang.Compiler")
       ;;   (re-matches #"[^$]+\$eval.*." catch-location-name))
       ;;  (and
       ;;   (.startsWith catch-location-name "clojure.lang.Compiler")
       ;;   (re-matches #"[^$]+\$eval.*." location-name))
       ;;  (not (.startsWith catch-location-name "swank_clj.swank")))
       ))))


(defn connection-and-id-from-thread
  "Walk the stack frames to find the eval-for-emacs call and extract
   the id argument.  This finds the connection and id in the target
   vm"
  [context thread]
  (logging/trace "connection-and-id-from-thread %s" thread)
  (some (fn [frame]
          (when-let [location (.location frame)]
            (when (and (= "swank_clj.swank$eval_for_emacs"
                          (jdi/location-type-name location))
                       (= "invoke" (jdi/location-method-name location)))
              ;; (logging/trace "connection-and-id-from-thread found frame")
              (let [connection (first (.getArgumentValues frame))
                    id (last (.getArgumentValues frame))]
                ;; (logging/trace
                ;;  "connection-and-id-from-thread id %s connection %s"
                ;;  (jdi/object-reference id)
                ;;  (jdi/object-reference connection))
                {:connection connection
                 :id (jdi-clj/read-arg context thread id)}))))
        (.frames thread)))

(defmethod jdi/handle-event ExceptionEvent
  [event context]
  (let [exception (.exception event)
        thread (jdi/event-thread event)
        silent? (.startsWith
                 (.toString event)
                 "ExceptionEvent@java.net.URLClassLoader")]
    (if (and
         (:control-thread context)
         (:RT context)
         (not silent?))
      ;; (logging/trace "EXCEPTION %s" event)
      ;; assume a single connection for now
      (do
        (logging/trace "EXCEPTION %s" exception)
        ;; would like to print this, but can cause hangs
        ;;    (jdi-clj/exception-message context event)
        (if (break-for-exception? event)
          (if-let [connection (ffirst @connections)]
            (if (aborting-level? connection)
              (logging/trace "Not activating sldb (aborting)")
              (do
                (logging/trace "Activating sldb")
                (invoke-debugger connection event)))
            (logging/trace "Not activating sldb (no connection)"))
          ;; (logging/trace "Not activating sldb (break-for-exception?)")
          ))
      (when-not silent?
        (logging/trace
         "jdi/handle-event ExceptionEvent: Can't handle EXCEPTION %s %s"
         event
         (jdi-clj/exception-message context event)
         ;;(jdi/exception-event-string context event)
         )))))

(defmethod jdi/handle-event BreakpointEvent
  [event context]
  (logging/trace "BREAKPOINT")
  (let [thread (jdi/event-thread event)]
    (when (and (:control-thread context) (:RT context))
      (if-let [connection (ffirst @connections)]
        (do
          (logging/trace "Activating sldb for breakpoint")
          (invoke-debugger connection event))
        (logging/trace "Not activating sldb (no connection)")))))

(defmethod jdi/handle-event StepEvent
  [event context]
  (logging/trace "STEP")
  (let [thread (jdi/event-thread event)]
    (when (and (:control-thread context) (:RT context))
      (if-let [connection (ffirst @connections)]
        (do
          (logging/trace "Activating sldb for stepping")
          (invoke-debugger connection event)
          (jdi/discard-event-request (:vm context) (.. event request)))
        (logging/trace "Not activating sldb (no connection)")))))

(defmethod jdi/handle-event VMDeathEvent
  [event context-atom]
  (doseq [[connection proxied-connection] @connections]
    (connection/close proxied-connection)
    (connection/close connection))
  (System/exit 0))
