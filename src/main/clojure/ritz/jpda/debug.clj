(ns ritz.jpda.debug
  "Debug functions using jpda/jdi, used to implement debugger commands via jpda.
   The aim is to move all return messaging back up into ritz.commands.*
   and to accept only vm-context aguments, rather than a connection"
  (:require
   [ritz.connection :as connection]
   [ritz.executor :as executor]
   [ritz.hooks :as hooks]
   [ritz.inspect :as inspect]
   [ritz.jpda.disassemble :as disassemble]
   [ritz.jpda.jdi :as jdi]
   [ritz.jpda.jdi-clj :as jdi-clj]
   [ritz.jpda.jdi-vm :as jdi-vm]
   [ritz.logging :as logging]
   [ritz.repl-utils.find :as find]
   [ritz.repl-utils.helpers :as helpers]
   [ritz.rpc-socket-connection :as rpc-socket-connection]
   [ritz.swank.core :as core]
   [ritz.swank.messages :as messages] ;; TODO - remove this
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
   com.sun.jdi.ThreadReference
   com.sun.jdi.StackFrame
   (com.sun.jdi
    BooleanValue ByteValue CharValue DoubleValue FloatValue IntegerValue
    LongValue ShortValue StringReference)))

(def control-thread-name "ritz-debug-thread")

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

(def ^{:dynamic true} *sldb-initial-frames* 10)

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
     (require '~'ritz.socket-server)
     ((resolve '~'ritz.socket-server/start) ~options)
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
                   :server-ns `(quote ritz.repl)
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
                   ;; NB very important that this doesn't throw
                   ;; as that can cause hangs in the startup
                   `(when (find-ns '~'ritz.socket-server)
                      (when-let [v# (resolve
                                     '~'ritz.socket-server/acceptor-port)]
                        (when-let [a# (var-get v#)]
                          (deref a#)))))]
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
     (inspect/inspecting? (connection/inspector connection)))
    (if (and f
             (re-find #"inspect" (name (first form)))
             (inspect/inspecting? (connection/inspector connection)))
      (core/execute-slime-fn* connection f (rest form) buffer-package)
      (handler connection form buffer-package id f))))

(defn execute-peek
  [handler]
  (fn [connection form buffer-package id f]
    (logging/trace "execute-peek %s" f)
    (swank-peek connection form buffer-package id f)
    (handler connection form buffer-package id f)))

(defn execute-unless-inspect
  "If the function has resolved (in the debug proxy) then execute it,
otherwise pass it on."
  [handler]
  (fn [connection form buffer-package id f]
    (logging/trace "execute-unless-inspect %s %s" f form)
    (if (and f
         (not (re-find #"inspect" (name (first form))))
         (not (re-find #"nth-value" (name (first form)))) ;; hack
         (:ritz.swank.commands/swank-fn (meta f)))
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
      :ritz.swank/pending)))

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
    (let [reply (connection/read-from-connection proxied-connection)
          id (last reply)]
      (when (or (not (number? id)) (not (zero? id))) ; filter (= id 0)
        (executor/execute-request
         (partial connection/send-to-emacs connection reply))
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
  [^StackFrame frame]
  (jdi/location-data (jdi/location frame)))

(defn- exception-stacktrace [frames]
  (logging/trace "exception-stacktrace")
  (logging/trace "exception-stacktrace: %s frames" (count frames))
  (map frame-data frames))

(defn- build-backtrace
  ([^ThreadReference thread]
     (doall (exception-stacktrace (.frames thread))))
  ([^ThreadReference thread start end]
     (logging/trace "build-backtrace: %s %s" start end)
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
  [thread frame-number]
  (when-let [frame (nth (.frames thread) frame-number nil)]
    (let [location (.location frame)]
      [(find/find-source-path (jdi/location-source-path location))
       {:line (jdi/location-line-number location)}])))

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

;;; exception-filters
(defn exception-filter-list
  "Return a sequence of exception filters, ensuring that expressions are strings
   and not regexes."
  [connection]
  (map
   (fn [filter]
     (->
      filter
      (update-in [:location] str)
      (update-in [:catch-location] str)
      (update-in [:message] str)))
   (:exception-filters connection)))

(defn exception-filter-kill
  "Remove an exception-filter."
  [connection id]
  (swap! connection update-in [:exception-filters]
         #(vec (concat
                (take (max id 0) %)
                (drop (inc id) %)))))

(defn update-filter-exception
  [connection id f]
  (swap! connection update-in [:exception-filters]
         #(vec (concat
               (take id %)
               [(f (nth % id))]
               (drop (inc id) %)))))

(defn exception-filter-enable
  [connection id]
  (update-filter-exception connection id #(assoc % :enabled true)))

(defn exception-filter-disable
  [connection id]
  (update-filter-exception connection id #(assoc % :enabled false)))


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
         (fn [levels] (subvec levels 0 (max 0 (dec (count levels)))))))))))
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

(defn ignore-exception-type
  "Add the specified exception to the connection's never-break-exceptions set."
  [connection exception-type]
  (logging/trace "Adding %s to never-break-exceptions" exception-type)
  (swap! connection update-in [:exception-filters] conj
         {:type exception-type :enabled true}))

(defn ignore-exception-message
  "Add the specified exception to the connection's never-break-exceptions set."
  [connection exception-message]
  (logging/trace "Adding %s to never-break-exceptions" exception-message)
  (swap! connection update-in [:exception-filters] conj
         {:message exception-message :enabled true}))

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

(def remote-condition-printer-sym-value (atom nil))
(def remote-condition-printer-fn (atom nil))
(defn remote-condition-printer
  "Return the remote symbol for a var to use for the condition printer"
  [context thread]
  (or @remote-condition-printer-fn
      (if (compare-and-set!
           remote-condition-printer-sym-value
           nil
           (jdi-clj/eval
            context thread jdi/invoke-single-threaded `(gensym "swank")))
        (let [s (name @remote-condition-printer-sym-value)]
          (logging/trace
           "set sym %s" (pr-str s))
          (jdi-clj/eval
           context thread jdi/invoke-single-threaded
           `(do
              (require 'clojure.pprint)
              (defn ~(symbol s) [c#]
                (let [f# (fn ~'classify-exception-fn [e#]
                           (case (.getName (class e#))
                             "clojure.contrib.condition.Condition" :condition
                             "slingshot.Stone" :stone
                             "clojure.lang.PersistentHashMap" :stone-context
                             "clojure.lang.PersistentArrayMap" :stone-context
                             :throwable))
                      gc# (fn ~'get-cause-fn [e#]
                            (case (f# e#)
                              :stone (:obj (.context e#))
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
                    :stone
                    (do
                      (with-out-str
                        (println (.messagePrefix c#))
                        (clojure.pprint/pprint
                         (.object c#))
                        (clojure.pprint/pprint
                         (.context c#))))
                    :throwable
                    (with-out-str
                      (clojure.pprint/pprint
                       [(.getMessage c#)
                        (ca# c#)])))))))
          (logging/trace "defined condition-printer-fn")
          (reset!
           remote-condition-printer-fn
           (jdi-clj/clojure-fn
            context thread jdi/invoke-single-threaded
            (jdi-clj/eval-to-string
             context thread jdi/invoke-single-threaded
             `(name (ns-name *ns*))) s 1))
          (logging/trace
           "resolved condition-printer-fn %s"
           (pr-str @remote-condition-printer-fn))
          @remote-condition-printer-fn))
      @remote-condition-printer-fn))

(defprotocol Debugger
  (condition-info [event context])
  (restarts [event connection]))

(extend-type ExceptionEvent
  Debugger
  (condition-info
    [event context]
    (let [exception (.exception event)
          exception-type (.. exception referenceType name)
          thread (jdi/event-thread event)]
      {:message (if (#{"clojure.contrib.condition.Condition"
                       "slingshot.Stone"} exception-type)
                  (let [[object method] (remote-condition-printer
                                         context thread)]
                    (str "\n" (jdi/invoke-method
                               thread
                               jdi/invoke-multi-threaded
                               object method [exception])))
                  (or (jdi-clj/exception-message context event) "No message."))
       :type (str "  [Thrown " exception-type "]")}))

  (restarts
    [^ExceptionEvent exception connection]
    (logging/trace "calculate-restarts exception")
    (let [thread (.thread exception)]
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
               (quit-level connection))))
          (make-restart
           :ignore-type "IGNORE" "Do not enter debugger for this exception type"
           (fn [connection]
             (logging/trace "restart Ignoring exceptions")
             (ignore-exception-type
              connection (.. exception exception referenceType name))
             (continue-level connection)))
          (make-restart
           :ignore-message "IGNORE-MSG"
           "Do not enter debugger for this exception message"
           (fn [connection]
             (logging/trace "restart Ignoring exceptions")
             (ignore-exception-message
              connection
              (jdi-clj/exception-message @(:vm-context @connection) exception))
             (continue-level connection)))])
        ;; Never break on this exception at this catch location
        ;; Never break on this exception at this throw location
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
   [^BreakpointEvent breakpoint connection]
   (logging/trace "calculate-restarts breakpoint")
   (let [thread (.thread breakpoint)]
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
          (remove-breakpoint (connection/vm-context connection) breakpoint)
          (continue-level connection)))]
      (stepping-restarts thread)))))

(extend-type StepEvent
  Debugger

  (condition-info
   [step _]
   {:message "STEPPING"})

  (restarts
   [^StepEvent step-event connection]
   (logging/trace "calculate-restarts step-event")
   (let [thread (.thread step-event)]
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
        _ (logging/trace "adding sldb level")
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
  ;; The handler resumes threads, so make sure we suspend them
  ;; again first. The restart from the sldb buffer will resume these
  ;; threads.
  (jdi/suspend-event-threads event)
  ;; Invoke debugger from a new thread, so we don't block the
  ;; event loop
  (executor/execute #(invoke-debugger* connection event)))

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
  (when-let [level-info (connection/sldb-level-info connection level)]
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
    "ritz.inspect" "object-content-range"
    nil object
    (jdi-clj/eval-to-value
     context (:current-thread context) jdi/invoke-single-threaded start)
    (jdi-clj/eval-to-value
     context (:current-thread context) jdi/invoke-single-threaded end))))

(defmethod inspect/object-nth-part com.sun.jdi.Value
  [context object n max-index]
  (jdi-clj/invoke-clojure-fn
   context (:current-thread context) jdi/invoke-single-threaded
   "ritz.inspect" "object-nth-part"
   nil object
   (jdi-clj/eval-to-value
    context (:current-thread context) jdi/invoke-single-threaded n)
   (jdi-clj/eval-to-value
    context (:current-thread context) jdi/invoke-single-threaded max-index)))

(defmethod inspect/object-call-nth-action :default com.sun.jdi.Value
  [context object n max-index args]
  (jdi-clj/read-arg
   context
   (:current-thread context)
   (jdi-clj/invoke-clojure-fn
    context
    (:current-thread context)
    jdi/invoke-multi-threaded
    "ritz.inspect" "object-call-nth-action"
    object
    (jdi-clj/eval-to-value
     context (:current-thread context) jdi/invoke-single-threaded n)
    (jdi-clj/eval-to-value
     context (:current-thread context) jdi/invoke-single-threaded max-index)
    (jdi-clj/eval-to-value
     context (:current-thread context)
     jdi/invoke-single-threaded
     args))))

(defn frame-locals-with-string-values
  "Return frame locals for slime"
  [context thread n]
  (doall
   (for [{:keys [value] :as map-entry} (jdi/unmangled-frame-locals
                                        (nth (.frames thread) n))]
     (assoc map-entry
       :string-value (inspect/value-as-string
                      (assoc context :current-thread thread) value)))))

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
     (let [local (:unmangled-name local)]
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
   context thread (invoke-option-for (:value local))
   `assoc map-var
   (jdi-clj/remote-str context (:unmangled-name local))
   (when-let [value (:value local)]
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
  [connection context thread expr frame-number]
  (try
    (let [_ (assert (.isSuspended thread))
          locals (jdi/unmangled-frame-locals
                  (nth (.frames thread) frame-number))
          map-sym (remote-map-sym context thread)
          map-var (jdi-clj/eval-to-value
                   context thread jdi/invoke-single-threaded
                   `(intern '~'user '~map-sym {}))
          form (with-local-bindings-form map-sym locals (read-string expr))]
      (locking remote-map-sym
        (try
          (set-remote-values context thread map-var locals)
          (let [v (jdi-clj/eval-to-value
                   context thread jdi/invoke-single-threaded form)]
            (inspect/value-as-string (assoc context :current-thread thread) v))
          (finally
           (clear-remote-values context thread map-var)))))
    (catch com.sun.jdi.InvocationException e
      (if connection
        (invoke-debugger*
         connection (InvocationExceptionEvent. (.exception e) thread))
        (do
          (println (.exception e))
          (println e)
          (.printStackTrace e))))))

(defn pprint-eval-string-in-frame
  "Eval the string `expr` in the context of the specified `frame-number`
   and pretty print the result"
  [connection context thread expr frame-number]
  (try
    (let [_ (assert (.isSuspended thread))
          locals (jdi/unmangled-frame-locals
                  (nth (.frames thread) frame-number))
          map-sym (remote-map-sym context thread)
          map-var (jdi-clj/eval-to-value
                   context thread jdi/invoke-single-threaded
                   `(intern '~'user '~map-sym {}))
          form `(with-out-str
                  (require 'clojure.pprint)
                  ((resolve 'clojure.pprint/pprint)
                   ~(with-local-bindings-form
                      map-sym locals (read-string expr))))]
      (locking remote-map-sym
        (try
          (set-remote-values context thread map-var locals)
          (jdi-clj/eval-to-string
           context thread jdi/invoke-single-threaded form)
          (finally
           (clear-remote-values context thread map-var)))))
    (catch com.sun.jdi.InvocationException e
      (if connection
        (invoke-debugger*
         connection (InvocationExceptionEvent. (.exception e) thread))
        (do
          (println (.exception e))
          (println e)
          (.printStackTrace e))))))

;;; events
(defn add-exception-event-request
  [context]
  (logging/trace "add-exception-event-request")
  (doto (jdi/exception-request (:vm context) nil true true)
    (jdi/suspend-policy exception-suspend-policy)
    (.enable)))

;;; VM events

;; macros like `binding`, that use (try ... (finally ...)) cause exceptions
;; within their bodies to be considered caught.  We therefore need some
;; way for the user to be able to maintain a list of catch locations that
;; should not be considered as "caught".

(defn break-for?
  [connection exception-type location-name catch-location-name
   exception-message]
  (letfn [(equal-or-matches? [expr value]
            (logging/trace "checking equal-or-matches? %s %s" expr value)
            (cond
             (string? expr) (= expr value)
             :else (re-matches expr value)))
          (matches? [{:keys [type location catch-location enabled message]
                      :as filter}]
            (and
             enabled
             (or (not type) (= type exception-type))
             (or (not location)
                 (equal-or-matches? location location-name))
             (or (not catch-location)
                 (equal-or-matches? catch-location catch-location-name))
             (or (not message)
                 (equal-or-matches? message exception-message))))]
    (not (some matches? (:exception-filters @connection)))))

(defn break-for-exception?
  "Predicate to check whether we should invoke the debugger for the given
   exception event"
  [exception-event connection]
  (let [catch-location (jdi/catch-location exception-event)
        location (jdi/location exception-event)
        location-name (jdi/location-type-name location)
        exception (.exception exception-event)
        exception-type (.. exception referenceType name)
        catch-location-name (jdi/location-type-name catch-location)]
    (logging/trace
        "break-for-exception? %s %s" catch-location-name location-name)
     (or (not catch-location)
         (break-for?
          connection
          exception-type location-name catch-location-name
          (jdi-clj/exception-message
           @(:vm-context @connection) exception-event)))))

(defn connection-and-id-from-thread
  "Walk the stack frames to find the eval-for-emacs call and extract
   the id argument.  This finds the connection and id in the target
   vm"
  [context thread]
  (logging/trace "connection-and-id-from-thread %s" thread)
  (some (fn [frame]
          (when-let [location (.location frame)]
            (when (and (= "ritz.swank$eval_for_emacs"
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
  [^ExceptionEvent event context]
  (let [exception (.exception event)
        thread (.thread event)
        silent? (jdi/silent-event? event)]
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
        (if-let [connection (ffirst @connections)]
          (if (aborting-level? connection)
            (logging/trace "Not activating sldb (aborting)")
            (when (break-for-exception? event connection)
              (logging/trace "Activating sldb")
              (invoke-debugger connection event)))
          ;; (logging/trace "Not activating sldb (no connection)")
          ))
      (if silent?
        (logging/trace-str "@")
        (logging/trace
         "jdi/handle-event ExceptionEvent: Can't handle EXCEPTION %s %s"
         event
         (jdi-clj/exception-message context event)
         ;;(jdi/exception-event-string context event)
         )))))

(defmethod jdi/handle-event BreakpointEvent
  [^BreakpointEvent event context]
  (logging/trace "BREAKPOINT")
  (let [thread (.thread event)]
    (when (and (:control-thread context) (:RT context))
      (if-let [connection (ffirst @connections)]
        (do
          (logging/trace "Activating sldb for breakpoint")
          (invoke-debugger connection event))
        (logging/trace "Not activating sldb (no connection)")))))

(defmethod jdi/handle-event StepEvent
  [^StepEvent event context]
  (logging/trace "STEP")
  (let [thread (.thread event)]
    (when (and (:control-thread context) (:RT context))
      (if-let [connection (ffirst @connections)]
        (do
          (logging/trace "Activating sldb for stepping")
          ;; The step event is completed, so we discard it's request
          (jdi/discard-event-request (:vm context) (.. event request))
          (invoke-debugger connection event))
        (logging/trace "Not activating sldb (no connection)")))))

(defmethod jdi/handle-event VMDeathEvent
  [event context-atom]
  (doseq [[connection proxied-connection] @connections]
    (connection/close proxied-connection)
    (connection/close connection))
  (System/exit 0))

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

(defn add-op-location [method {:keys [code-index] :as op}]
  (if code-index
    (merge op (jdi/location-data (.locationOfCodeIndex method code-index)))
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
            (conj (format "%s:%s" (:function op) (:line op)))
            (conj s))
           (select-keys op [:function :line])])))
    [[] {}]
    ops)))

(defn disassemble-method
  "Dissasemble a method, adding location info"
  [const-pool method]
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
  (logging/trace "disassemble-symbol %s %s" sym-ns sym-name)
  (when-let [[f methods] (jdi-clj/clojure-fn-deref
                          context thread
                          jdi/invoke-single-threaded
                          sym-ns sym-name)]
    (let [const-pool (disassemble/constant-pool
                      (.. f referenceType constantPool))]
      (apply
       concat
       (for [method methods
             :let [ops (disassemble-method const-pool method)]
             :let [clinit (first (drop 3 ops))]
             :when (not (.contains clinit ":invokevirtual \""))]
         (concat
          [(str sym-ns "/" sym-name
                "(" (string/join " " (.argumentTypeNames method)) ")\n")]
          ops
          ["\n\n"]))))))
