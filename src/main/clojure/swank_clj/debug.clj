(ns swank-clj.debug
  "Debug functions"
  (:require
   [swank-clj.logging :as logging]
   [swank-clj.swank.core :as core]
   [swank-clj.jpda :as jpda]
   [swank-clj.connection :as connection]
   [swank-clj.executor :as executor]
   [clojure.java.io :as io]
   [clojure.string :as string])
  (:import
   java.net.Socket
   java.net.InetSocketAddress
   java.net.InetAddress
   com.sun.jdi.event.ExceptionEvent
   com.sun.jdi.request.ExceptionRequest
   com.sun.jdi.event.VMStartEvent
   com.sun.jdi.VirtualMachine))

(defonce vm nil)

(def control-thread-name "swank-clj-debug-thread-implementation")


;;;

(def *sldb-initial-frames* 10)

(defonce connections (atom {}))

(defn add-connection [connection proxied-connection]
  (swap! connections assoc connection proxied-connection))

(defn remove-connection [connection]
  (swap! connections dissoc connection))



(def throwable (delay (first (jpda/classes (:vm vm) "java.lang.Throwable"))))
(def get-message (delay (first (jpda/methods @throwable "getMessage"))))
(defn exception-message [exception thread]
  (if-let [message (jpda/invoke-method exception @get-message thread [])]
    (jpda/string-value message)))

(def continue-handling (atom true))

(defn request-events [context]
(let [manager (.eventRequestManager (:vm context))]
  (->
   context
   (assoc
       :event-request-manager manager
       :exception-request (doto (.createExceptionRequest manager nil true true)
                            (.setSuspendPolicy
                             ExceptionRequest/SUSPEND_EVENT_THREAD)
                            (.enable))))
  ;; (doto (.createClassPrepareRequest manager)
  ;;   (.enable))
  ))

(def vm-main
  "(require 'swank-clj.socket-server)(swank-clj.socket-server/start '%s)")

(defn start-vm-daemons
  [vm]
  (let [process (.process (:vm vm))]
    (logging/trace "start-vm-daemons")
    (assoc vm
      :vm-in (executor/daemon-thread
              "vm-in"
              (io/copy *in* (.getOutputStream process))
              (logging/trace "vm-in: exit"))
      :vm-out (executor/daemon-thread
               "vm-out"
               (io/copy (.getInputStream process) logging/logging-out)
               (logging/trace "vm-out: exit"))
      :vm-err (executor/daemon-thread
               "vm-err"
               (io/copy (.getErrorStream process) logging/logging-out)
               (logging/trace "vm-err: exit"))
      :vm-ev (executor/daemon-thread
              "vm-events"
              (jpda/run-events
                (:vm vm) continue-handling jpda/handle-event)
              (logging/trace "vm-events: exit")))))

(defn launch-vm
  "Launch and configure the vm for the debugee."
  [vm {:keys [announce port] :as options}]
  (if vm
    vm
    (do
      (reset! continue-handling true)
      (let [cmd (format vm-main (pr-str {:port port
                                         :announce announce
                                         :server-ns 'swank-clj.repl}))
            vm (jpda/launch (jpda/current-classpath) cmd)]
        ;; (.setDebugTraceMode vm VirtualMachine/TRACE_NONE)
        (let [options (-> options
                          (assoc :vm vm)
                          (start-vm-daemons)
                          (request-events))]
          (.resume vm)
          options)))))

(defn ensure-vm
  "Ensure the debug vm has been started"
  [{:as options}]
  (logging/trace "ensure-vm")
  (alter-var-root #'vm launch-vm options))

(defn stop-vm
  []
  (when vm
    (.exit (:vm vm) 0)
    (reset! continue-handling nil)
    (alter-var-root #'vm (constantly nil))))

(defn connect-to-repl-on-vm [port]
  (logging/trace "debugger/connect-to-repl-on-vm port %s" port)
  (Socket. "localhost" port))

(defn create-connection [options]
  (logging/trace "debugger/create-connection: connecting to proxied connection")
  (connection/create (connect-to-repl-on-vm (:port options)) options))

(defn forward-command
  [connection form buffer-package id]
  (if-let [proxied-connection (:proxy-to @connection)]
    (do
      (logging/trace
       "debugger/forward-command: forwarding %s to proxied connection"
       (first form))
      (executor/execute-request
       (partial
        connection/send-to-emacs
        proxied-connection (list :emacs-rex form buffer-package true id))))
    (do
      (logging/trace
       "swank/eval-for-emacs: no forwarding fn %s" (first form))
      (executor/execute-request
       (partial connection/send-to-emacs connection `(:return (:abort) ~id)))
      (connection/remove-pending-id connection id))))

(defn forward-reply
  [connection]
  (logging/trace
   "debugger/forward-command: waiting reply from proxied connection")
  (let [proxied-connection (:proxy-to @connection)
        reply (connection/read-from-connection proxied-connection)]
    (executor/execute-request
     (partial connection/send-to-emacs connection reply))
    (let [id (second reply)]
      (connection/remove-pending-id connection id))))

(defn debugger-condition-for-emacs [exception thread]
  (list (or (exception-message exception thread) "No message.")
        (str "  [Thrown " (.. exception referenceType name) "]")
        nil))

(defn format-restarts-for-emacs [restarts]
  (doall (map #(list (first (second %)) (second (second %))) restarts)))

(defn format-frame
  [frame]
  (let [location (.location frame)
        method (.method location)
        line (.lineNumber location)
        source-name (.sourceName location)
        source-path (.sourcePath location)
        declaring-type (.declaringType location)]
    (format
     "%s %s %s:%s"
     (.name declaring-type)
     (.name method)
     source-path
     line)))

(defn exception-stacktrace [thread]
  (map #(list %1 %2 '(:restartable nil))
       (iterate inc 0)
       (map format-frame (.frames thread))))

(defn build-backtrace [thread start end]
  (doall (take (- end start) (drop start (exception-stacktrace thread)))))


;; (defonce *debug-quit-exception* (Exception. "Debug quit"))
;; (defonce *debug-continue-exception* (Exception. "Debug continue"))
;; (defonce *debug-abort-exception* (Exception. "Debug abort"))

(defn make-restart [kw name description f]
  [kw [name description f]])

(defn calculate-restarts [thrown thread]
  (logging/trace "calculate-restarts")
  (let [restarts [(make-restart :quit "CONTINUE" "Pass exception to program"
                               (fn [] (.resume thread)))]]
    (apply array-map (apply concat restarts))))

(defn build-debugger-info-for-emacs [exception thread restarts start end conts]
  (list (debugger-condition-for-emacs exception thread)
        (format-restarts-for-emacs restarts)
        (build-backtrace thread start end)
        conts))

(defn invoke-sldb
  [connection exception thread]
  (logging/trace "invoke-sldb")
  (let [thread-id (.uniqueID thread)
        restarts (calculate-restarts exception thread)
        level (connection/next-sldb-level connection restarts thread-id)]
    (logging/trace "invoke-sldb: call emacs")
    (connection/send-to-emacs
     connection
     (list* :debug thread-id level
            (build-debugger-info-for-emacs
             exception thread
             restarts
             0 *sldb-initial-frames*
             (list* (connection/pending connection)))))
    (connection/send-to-emacs
     connection
     `(:debug-activate ~thread-id ~level nil))
    (.suspend thread)))

(def var-signature "(Ljava/lang/String;Ljava/lang/String;)Lclojure/lang/Var;")

(defn vm-rt
  "Lookup clojure runtime."
  [vm]
  (if-not (:RT vm)
    (if-let [rt (first (jpda/classes (:vm vm) "clojure.lang.RT"))]
      (let [vm (assoc vm
                 :RT rt
                 :Compiler (first
                            (jpda/classes (:vm vm) "clojure.lang.Compiler"))
                 :Var (first (jpda/classes (:vm vm) "clojure.lang.Var")))]
        (assoc vm
          :read-string (first (jpda/methods (:RT vm) "readString"))
          :var (first (jpda/methods (:RT vm) "var" var-signature))
          :eval (first (jpda/methods (:Compiler vm) "eval"))
          :get (first (jpda/methods (:Var vm) "get"))))
      (throw (Exception. "No clojure runtime found in vm")))
    vm))

(defn ensure-runtime
  []
  (alter-var-root #'vm vm-rt))



;;; Remote evaluation
(defn arg-list [& args]
  (or args []))

(defn remote-eval*
  [vm thread form]
  (if-let [rv (->>
               (str form)
               (jpda/mirror-of (:vm vm))
               arg-list
               (jpda/invoke-method (:RT vm) (:read-string vm) thread)
               arg-list
               (jpda/invoke-method (:Compiler vm) (:eval vm) thread))]
    (jpda/string-value rv)))

(defn eval-to-string
  [form]
  `(pr-str (eval ~form)))

(defmacro remote-eval
  [vm thread form]
  `(remote-eval* ~vm ~thread '~(eval-to-string form)))

(defmacro remote-
  [vm thread form]
  `(remote-eval* ~vm ~thread '~(eval-to-string form)))

(defmacro control-eval
  [form]
  `(read-string (remote-eval vm (:control-thread vm) '~form)))

(def jni-object "Ljava/lang/Object;")
(defn invoke-signature
  "Clojure invoke signature for the specified number of arguments"
  [n]
  (str "(" (string/join (repeat n jni-object)) ")" jni-object))

(defn clojure-fn
  "Resolve a clojure function in the remote vm. Returns an ObjectReference and
   a Method for n arguments."
  [ns name n thread]
  (let [object (jpda/invoke-method
                (:RT vm) (:var vm) thread
                [(jpda/mirror-of (:vm vm) ns)
                 (jpda/mirror-of (:vm vm) name)])]
    [object (first (jpda/methods
                    (.referenceType object) "invoke" (invoke-signature n)))]))

(defn invoke-clojure-fn
  "Invoke a function on the control connection with the given remote arguments."
  [ns name thread & args]
  (logging/trace "invoke-clojure-fn %s %s %s" ns name args)
  (let [[object method] (clojure-fn ns name (count args) thread)]
    (jpda/invoke-method object method thread args)))

(defn read-arg
  "Read the value of the given arg"
  [thread arg]
  (->
   (invoke-clojure-fn "clojure.core" "pr-str" thread arg)
   jpda/string-value
   read-string))

(defn get-keyword
  "Get ObjectReference for the result of looking up keyword in a remote map
   object."
  [thread m kw]
  (let [kw (invoke-clojure-fn
            "clojure.core" "keyword" thread (jpda/mirror-of (:vm vm) (name kw)))
        method (first
                (jpda/methods
                 (.referenceType m) "invoke" (invoke-signature 1)))]
    (logging/trace "map %s" (jpda/object-reference m))
    (logging/trace "signature %s" (invoke-signature 1))
    (logging/trace "keyword %s" (jpda/object-reference kw))
    (logging/trace "method %s" (pr-str method))
    (jpda/invoke-method m method (:control-thread vm) [kw])))

;; objectref(Integer)

;; (defn vm-var
;;   [thread namespace name]
;;   (if-let [var-method (or (:var vm)
;;                           (:vm (alter-var-root #'vm vm-rt-eval-method)))]
;;     (.invoke (:rt vm) tread (:var vm) (map remote-value [namespace name]) 0)))

;; (defmethod jpda/handle-event VMStartEvent
;;   [event connected]
;;   (println event)
;;   ;; (let [thread (.thread event)]
;;   ;;   (logging/trace "jpda/handle-event execpetion")
;;   ;;   (executor/execute
;;   ;;    #(alter-var-root #'vm assoc :control-thread thread)))
;;   )

;;; debugee functions for starting a thread that may be used from the proxy
(defn start-control-thread
  "Start a thread that can be used by the proxy execute arbitrary code."
  []
  (logging/trace "start-control-thread")
  (try
    (let [thread (Thread/currentThread)]
      (.setName thread control-thread-name)
      (throw (Exception. control-thread-name)))
    (catch Exception _
      (logging/trace "CONTROL THREAD CONTINUED!"))))

(def require-control-thread
  (delay (executor/execute start-control-thread)))

;;; functions for acquiring the control thread in the proxy
(def wait-for-control-thread-latch
  (delay (java.util.concurrent.CountDownLatch. 1)))

(defn control-thread-acquired!
  []
  (logging/trace "control-thread-acquired!")
  (.countDown @wait-for-control-thread-latch))

(defn wait-for-control-thread
  []
  (logging/trace "wait-for-control-thread")
  (when-not (:control-thread vm) ;; theoretical race
    (.await @wait-for-control-thread-latch))
  (logging/trace "wait-for-control-thread: acquired")
  (ensure-runtime)
  (logging/trace "wait-for-control-thread: runtime set")
  (loop []
    (if-let [port (control-eval (deref swank-clj.socket-server/acceptor-port))]
      port
      (do
        (Thread/sleep 200)
        (recur)))))

(defn add-exception-event-request
  [vm]
  (logging/trace "add-exception-event-request")
  (if (:exception-request vm)
    vm
    (do (logging/trace "add-exception-event-request: adding request")
        (assoc vm
          :exception-request (doto (.createExceptionRequest
                                    (:event-request-manager vm)
                                    nil true true)
                               (.setSuspendPolicy
                                ExceptionRequest/SUSPEND_ALL)
                               (.enable))))))

(defn ensure-exception-event-request
  []
  (alter-var-root #'vm add-exception-event-request))

;;; VM events
(defn maybe-acquire-control-thread
  "Inspect exception to see if we can use it to acquire a control thread.
   If found, swap the exception event request for uncaught exceptions."
  [exception thread]
  (try
    (when-let [message (exception-message exception thread)]
      (when (= message control-thread-name)
        (.suspend thread)
        (.disable (:exception-request vm))
        (alter-var-root
         #'vm assoc
         :control-thread thread
         :exception-request nil)
        (control-thread-acquired!)))
    (catch Throwable e
      (logging/trace
       "control thread acquistion error %s %s"
       (pr-str e)
       (with-out-str (.printStackTrace e))))))

(defn connection-and-id-from-thread
  "Walk the stack frames to find the eval-for-emacs call and extract
   the id argument."
  [thread]
  (logging/trace "connection-and-id-from-thread %s" thread)
  (some (fn [frame]
          (when-let [location (.location frame)]
            ;; (logging/trace
            ;;  "connection-and-id-from-thread %s %s"
            ;;  (jpda/location-type-name location)
            ;;  (jpda/location-method-name location))
            (when (and (= "swank_clj.swank$eval_for_emacs"
                          (jpda/location-type-name location))
                       (= "invoke"
                          (jpda/location-method-name location)))
              (logging/trace "connection-and-id-from-thread found frame")
              (let [connection (first (.getArgumentValues frame))
                    id (last (.getArgumentValues frame))
                    ;; socket (-> (invoke-clojure-fn
                    ;;             "clojure.core" "deref" connection)
                    ;;            (get-keyword thread :socket))
                    ;; port (jpda/invoke-method
                    ;;       socket
                    ;;       (first
                    ;;        (jpda/methods
                    ;;         (.referenceType socket) "getPort"))
                    ;;       thread [])
                    ]
                (logging/trace
                 "connection-and-id-from-thread id %s connection %s"
                 (jpda/object-reference id)
                 (jpda/object-reference connection))
                {:connection connection
                 :id (read-arg thread id)}))))
        (.frames thread)))

(defmethod jpda/handle-event ExceptionEvent
  [event connected]
  (let [exception (.exception event)
        thread (jpda/event-thread event)]
    (when (and (:control-thread vm) (:RT vm))
      (logging/trace "EXCEPTION %s" (exception-message exception thread))
      ;; assume a single connection for now
      (let [{:keys [id connection]} (connection-and-id-from-thread thread)]
        (logging/trace "exception-event: %s %s" id connection)
        (let [connection (ffirst @connections)]
          (if (and id connection) ;; ensure we have started - id needs to go
            (do
              (logging/trace "Activating sldb")
              (invoke-sldb connection exception thread))
            (logging/trace "Not activating sldb")))))
    (when-not (:control-thread vm)
      (maybe-acquire-control-thread exception thread))))

;; (defmethod jpda/handle-event VMStartEvent
;;   [event connected]
;;   (println event)
;;     (logging/trace "jpda/handle-event vmstart")
;;     )

    ;; (when (= (.getMessage exception) control-thread-name)
    ;;   (logging/trace "jpda/handle-event execpetion control-thread-name")
    ;;   #(alter-var-root
    ;;     #'vm assoc :control-thread thread
    ;;     ;; :port (remote-eval
    ;;     ;;        (:vm vm) thread
    ;;     ;;        (deref swank-clj.socket-server/local-port))
    ;;     )
    ;;   (control-thread-acquired!)
    ;;   (.resume thread))

  ;; (let [remote-port
  ;;       (remote-eval
  ;;        vm
  ;;        (.thread event)
  ;;        (connection/local-port
  ;;         (core/*current-connection)))]
  ;;   (logging/trace "Exception on remote connection with port %s" remote-port))

  ;; (let [exception (.exception event)
  ;;       thread (.thread event)
  ;;       {:keys [connection id]} (connection-and-id-from-thread thread)]

  ;;   ;; ;;
  ;;   ;; (connection/send-to-emacs
  ;;   ;;  core/*current-connection*
  ;;   ;;  (list* :debug true 0
  ;;   ;;         (build-debugger-info-for-emacs exception 0 *sldb-initial-frames*)))
  ;;   ;; (connection/send-to-emacs
  ;;   ;;  core/*current-connection*
  ;;   ;;  `(:debug-activate true 1 nil))
  ;;   )
