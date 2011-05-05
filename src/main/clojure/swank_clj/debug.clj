(ns swank-clj.debug
  "Debug functions, used to implement debugger commands via jpda.
   The aim is to move all return messaging back up into swank-clj.commands.*"
  (:require
   [swank-clj.connection :as connection]
   [swank-clj.executor :as executor]
   [swank-clj.inspect :as inspect]
   [swank-clj.jpda :as jpda]
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
    LongValue ShortValue)))

(defonce vm nil)
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


(defn- format-classpath-url [url]
  (if (= "file" (.getProtocol url))
    (.getPath url)
    url))

(defn current-classpath []
  (string/join
   ":"
   (map format-classpath-url (.getURLs (.getClassLoader clojure.lang.RT)))))

(defn log-exception [e]
  (logging/trace
   "Caught exception %s %s"
   (pr-str e)
   (helpers/stack-trace-string e)))

(defmacro with-caught-jdpa-exceptions
  [& body]
  `(try
     ~@body
     (catch com.sun.jdi.InternalException e#
       (log-exception e#))))


(def throwable (delay (first (jpda/classes (:vm vm) "java.lang.Throwable"))))
(def get-message (delay (first (jpda/methods @throwable "getMessage"))))
(defn exception-message [exception thread]
  (with-caught-jdpa-exceptions
    (if-let [message (jpda/invoke-method
                      exception @get-message thread jpda/invoke-single-threaded
                      [])]
      (jpda/string-value message))))

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

;;; debugee function for starting a thread that may be used from the debugger

(defn start-control-thread
  "Start a thread that can be used by the proxy to execute arbitrary code."
  []
  (logging/trace "start-control-thread")
  (try
    (let [thread (Thread/currentThread)]
      (.setName thread control-thread-name)
      (throw (Exception. control-thread-name)))
    (catch Exception _
      (logging/trace "CONTROL THREAD CONTINUED!"))))

(def vm-main
  "(require 'swank-clj.socket-server)(swank-clj.socket-server/start '%s)")

(defn start-control-thread-body
  "Form to start a thread for the debugger to work with.  This should contain
only clojure.core symbols."
  []
  `(let [thread# (Thread.
                  (fn []
                    (try
                      (throw (Exception. (str '~(symbol control-thread-name))))
                      (catch Exception _#
                        (throw
                         (Exception. (str 'CONTROL-THREAD-CONTINUED)))))))]
     (.setName thread# (str '~(symbol control-thread-name)))
     (.start thread#)))

(defn launch-vm
  "Launch and configure the vm for the debugee."
  [vm classpath cmd options]
  (if vm
    vm
    (do
      (reset! continue-handling true)
      (logging/trace
       "launch-vm %s\n%s" classpath (pprint/pprint cmd))
      (let [vm (jpda/launch classpath cmd)]
        (let [options (-> options
                          (assoc :vm vm)
                          (start-vm-daemons)
                          (request-events))]
          (.resume vm)
          options)))))

(defn launch-vm-with-swank
  "Launch and configure the vm for the debugee."
  [{:keys [port announce log-level] :as options}]
  #(launch-vm
    %
    (current-classpath)
    (format vm-main (pr-str {:port port
                             :announce announce
                             :server-ns 'swank-clj.repl
                             :log-level (keyword log-level)}))
    options))

(defn launch-vm-without-swank
  "Launch and configure the vm for the debugee."
  [classpath {:as options}]
  (logging/trace "launch-vm-without-swank %s" classpath)
  #(launch-vm % classpath (pr-str (start-control-thread-body)) options))

(defn ensure-vm
  "Ensure the debug vm has been started"
  [launch-f]
  (logging/trace "ensure-vm")
  (alter-var-root #'vm launch-f))

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
  (->
   (connect-to-repl-on-vm (:port options))
   (rpc-socket-connection/create options)
   (connection/create options)))

(def *current-thread-reference*)

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
      (let [[level-info _] (connection/current-sldb-level-info connection)]
        (binding [*current-thread-reference* (:thread level-info)]
          (core/execute-slime-fn* connection f (rest form) buffer-package)))
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
      (let [[level-info _] (connection/current-sldb-level-info connection)]
        (binding [*current-thread-reference* (:thread level-info)]
          (core/execute-slime-fn* connection f (rest form) buffer-package)))
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
       (string/join "\n" (map format-thread (threads))))
      (when (= 'swank/source-form (first form))
        (clear-abort-for-current-level connection))
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
          :get (first (jpda/methods (:Var vm) "get"))
          :assoc (first (jpda/methods (:RT vm) "assoc"))
          :swap-root (first (jpda/methods (:Var vm) "swapRoot"))))
      (throw (Exception. "No clojure runtime found in vm")))
    vm))

(defn ensure-runtime
  []
  (alter-var-root #'vm vm-rt))

;;; Remote evaluation
(defn arg-list [& args]
  (or args []))

(defn remote-str
  "Create a remote string"
  [s]
  (jpda/mirror-of (:vm vm) s))

(defn remote-eval*
  ([thread form]
     (remote-eval* vm thread form jpda/invoke-multi-threaded))
  ([thread form options]
     {:pre [thread options]}
     (logging/trace "debug/remote-eval* %s" form)
     (->>
      (str form)
      (jpda/mirror-of (:vm vm))
      arg-list
      (jpda/invoke-method
       (:RT vm) (:read-string vm) thread options)
      arg-list
      (jpda/invoke-method
       (:Compiler vm) (:eval vm) thread options))))

(defn remote-eval-to-string*
  ([thread form]
     (remote-eval-to-string* thread form jpda/invoke-multi-threaded))
  ([thread form options]
     (logging/trace "debug/remote-eval-to-string* %s" form)
     (if-let [rv (remote-eval* thread form options)]
       (jpda/string-value rv))))

(defn eval-to-string
  [form]
  `(pr-str (eval ~form)))

(defmacro remote-eval
  ([thread form]
     `(remote-eval-to-string* ~thread '~(eval-to-string form)))
  ([thread form options]
     `(remote-eval-to-string* ~thread '~(eval-to-string form) ~options)))

(defmacro remote-value
  [thread form options]
  `(remote-eval* ~thread ~form ~options))

(defmacro control-eval
  ([form]
     `(read-string (remote-eval (:control-thread vm) '~form)))
  ([form options]
     `(read-string (remote-eval (:control-thread vm) '~form ~options))))

(defprotocol RemoteObject
  "Protocol for obtaining a remote object reference"
  (remote-object [value thread]))


(let [st jpda/invoke-single-threaded]
  (extend-protocol RemoteObject
    ObjectReference (remote-object [o _] o)
    BooleanValue (remote-object
                  [o thread] (remote-value thread (list 'boolean (.value o)) st))
    ByteValue (remote-object
                  [o thread] (remote-value thread (list 'byte (.value o)) st))
    CharValue (remote-object
                  [o thread] (remote-value thread (list 'char (.value o)) st))
    DoubleValue (remote-object
                 [o thread] (remote-value thread (list 'double (.value o)) st))
    FloatValue (remote-object
                  [o thread] (remote-value thread (list 'float (.value o)) st))
    IntegerValue (remote-object
                  [o thread] (remote-value thread (list 'int (.value o)) st))
    LongValue (remote-object
               [o thread] (remote-value thread (list 'long (.value o)) st))
    ShortValue (remote-object
                [o thread] (remote-value thread (list 'short (.value o)) st))))

(def jni-object "Ljava/lang/Object;")
(defn invoke-signature
  "Clojure invoke signature for the specified number of arguments"
  [n]
  (str "(" (string/join (repeat n jni-object)) ")" jni-object))

(defn clojure-fn
  "Resolve a clojure function in the remote vm. Returns an ObjectReference and
   a Method for n arguments."
  [ns name n thread options]
  (let [object (jpda/invoke-method
                (:RT vm) (:var vm) thread
                options
                [(jpda/mirror-of (:vm vm) ns)
                 (jpda/mirror-of (:vm vm) name)])]
    [object (first (jpda/methods
                    (.referenceType object) "invoke" (invoke-signature n)))]))

(defn invoke-clojure-fn
  "Invoke a function on the control connection with the given remote arguments."
  [ns name thread options & args]
  (logging/trace "invoke-clojure-fn %s %s %s" ns name args)
  (let [[object method] (clojure-fn ns name (count args) thread options)]
    (jpda/invoke-method object method thread options args)))

(defn remote-call
  "Call a function using thread with the given remote arguments."
  [thread options sym & args]
  (logging/trace "remote-call %s %s" (pr-str sym) args)
  (let [[object method] (clojure-fn
                         (namespace sym) (name sym) (count args)
                         thread options)]
    (logging/trace "clojure fn is  %s %s" object method)
    (jpda/invoke-method object  method thread options args)))

(defn remote-var-get
  [thread options value]
  (jpda/invoke-method value (:get vm) thread options []))

(defn remote-assoc
  [thread options & values]
  (jpda/invoke-method (:RT vm) (:assoc vm) thread options values))

(defn remote-swap-root
  [thread options var value]
  (jpda/invoke-method var (:swap-root vm) thread options [value]))

(defn pr-str-arg
  "Read the value of the given arg"
  ([thread arg]
     (pr-str-arg thread arg jpda/invoke-multi-threaded))
  ([thread arg options]
     (-> (invoke-clojure-fn "clojure.core" "pr-str" thread options arg)
         jpda/string-value)))

(defn read-arg
  "Read the value of the given arg"
  [thread arg]
  (-> (pr-str-arg thread arg)
      read-string))

(defn get-keyword
  "Get ObjectReference for the result of looking up keyword in a remote map
   object."
  [thread m kw]
  (let [kw (invoke-clojure-fn
            "clojure.core" "keyword" thread jpda/invoke-multi-threaded
            (jpda/mirror-of (:vm vm) (name kw)))
        method (first
                (jpda/methods
                 (.referenceType m) "invoke" (invoke-signature 1)))]
    (logging/trace "map %s" (jpda/object-reference m))
    (logging/trace "signature %s" (invoke-signature 1))
    (logging/trace "keyword %s" (jpda/object-reference kw))
    (logging/trace "method %s" (pr-str method))
    (jpda/invoke-method
     m method (:control-thread vm) jpda/invoke-multi-threaded [kw])))


;;; threads
(defn format-thread
  [thread-reference]
  (format
   "%s %s (suspend count %s)"
   (.name thread-reference)
   (jpda/thread-states (.status thread-reference))
   (.suspendCount thread-reference)))

(defn threads
  []
  (jpda/threads (:vm vm)))

(defn transform-thread-group
  [pfx [group groups threads]]
  [(->
    group
    (dissoc :id)
    (update-in [:name] (fn [s] (str pfx s))))
   (map #(transform-thread-group (str pfx "  ") %) groups)
   (map #(update-in % [:name] (fn [s] (str pfx "  " s))) threads)])

(defn thread-list
  "Provide a list of threads. The list is cached in the connection
   to allow it to be retrieveb by index."
  [connection]
  (let [threads (flatten
                 (map
                  #(transform-thread-group "" %)
                  (jpda/thread-groups (:vm vm))))]
    (swap! connection assoc :threads threads)
    threads))

(defn nth-thread
  [connection index]
  (nth (:threads @connection) index nil))

(defn stop-thread
  [thread-id]
  (when-let [thread (some
                     #(and (= thread-id (.uniqueID %)) %)
                     (threads))]
    ;; to do - realy stop the thread
    (.stop thread (remote-eval*
                   (:control-thread vm)
                   `(new java.lang.Throwable "Stopped by swank")
                   jpda/invoke-single-threaded))))

(defn level-info-thread-id
  [level-info]
  (.uniqueID (:thread level-info)))


(def require-control-thread
  (delay (executor/execute start-control-thread)))

;;; functions for acquiring the control thread in the proxy
(def wait-for-control-thread-latch
  (delay (java.util.concurrent.CountDownLatch. 1)))

(defn control-thread-acquired!
  []
  (logging/trace "control-thread-acquired!")
  (.countDown @wait-for-control-thread-latch))

(def vm-port (atom nil))

(defn wait-for-control-thread
  []
  (logging/trace "wait-for-control-thread")
  (when-not (:control-thread vm) ;; theoretical race
    (.await @wait-for-control-thread-latch))
  (logging/trace "wait-for-control-thread: acquired")
  (ensure-runtime)
  (logging/trace "wait-for-control-thread: runtime set"))

(defn remote-swank-port
  []
  (loop []
    (if-let [port (swap!
                   vm-port
                   #(or %
                        (control-eval
                         (deref swank-clj.socket-server/acceptor-port)
                         jpda/invoke-single-threaded)))]
      port
      (do
        (Thread/sleep 200)
        (recur)))))


;;; stacktrace
(defn- location-data
  [location]
  (let [declaring-type (jpda/location-type-name location)
        method (jpda/location-method-name location)
        line (jpda/location-line-number location)
        source-name (or
                     (jpda/location-source-name location)
                     "UNKNOWN")]
    (if (and (= method "invoke") (.endsWith source-name ".clj"))
      {:function (jpda/unmunge-clojure declaring-type)
       :source source-name
       :line line}
      {:function (format "%s.%s" declaring-type method)
       :source source-name
       :line line})))

(defn- frame-data
  "Extract data from a stack frame"
  [frame]
  (location-data (.location frame)))

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
        [(find/find-source-path (jpda/location-source-path location))
         {:line (jpda/location-line-number location)}]))))

;;; breakpoints
;;; todo - use the event manager's event list
(defn breakpoint-list
  "Provide a list of breakpoints. The list is cached in the connection
   to allow it to be retrieveb by index."
  [connection]
  (let [breakpoints (map
                     #(->
                       (jpda/breakpoint-data %1)
                       (assoc :id %2))
                     (jpda/breakpoints (:vm vm))
                     (iterate inc 0))]
    (swap! connection assoc :breakpoints breakpoints)
    breakpoints))

(defn line-breakpoint
  "Set a line breakpoint."
  [connection namespace filename line]
  (let [breakpoints (jpda/line-breakpoints
                     (:vm vm) breakpoint-suspend-policy
                     namespace filename line)]
    (swap!
     connection
     update-in
     [:breakpoints]
     #(concat % breakpoints))
    (format "Set %d breakpoints" (count breakpoints))))

(defn remove-breakpoint
  "Set a line breakpoint."
  [connection event]
  (swap!
   connection
   (fn [connection]
     (update-in
      connection
      [:breakpoints]
      (fn [breakpoints]
        (when-let [request (.request event)]
          (.disable request)
          (.deleteEventRequest
           (.eventRequestManager (.virtualMachine event)) request)
          (remove #(= % request) breakpoints)))))))

(defn breakpoints-for-id
  [connection id]
  (when-let [breakpoints (:breakpoints @connection)]
    (when-let [{:keys [file line]} (nth breakpoints id nil)]
      (seq (map
            #(let [location (.location %)]
               (and (= file (.sourcePath location))
                    (= line (.lineNumber location))
                    %))
            (jpda/breakpoints (:vm vm)))))))

(defn breakpoint-kill
  [connection breakpoint-id]
  (doseq [breakpoint (breakpoints-for-id connection breakpoint-id)]
    (.disable breakpoint)
    (.. breakpoint (virtualMachine) (eventRequestManager)
        (deleteEventRequest breakpoint))))

(defn breakpoint-enable
  [connection breakpoint-id]
  (doseq [breakpoint (breakpoints-for-id connection breakpoint-id)]
    (.enable breakpoint)))

(defn breakpoint-disable
  [connection breakpoint-id]
  (doseq [breakpoint (breakpoints-for-id connection breakpoint-id)]
    (.disable breakpoint)))

(defn breakpoint-location
  [connection breakpoint-id]
  (when-let [breakpoint (first (breakpoints-for-id connection breakpoint-id))]
    (let [location (.location breakpoint)]
      (logging/trace
       "debug/breakpoint-location %s %s %s"
       (jpda/location-source-name location)
       (jpda/location-source-path location)
       (jpda/location-line-number location))
      (when-let [path (find/find-source-path
                       (jpda/location-source-path location))]
        [path {:line (jpda/location-line-number location)}]))))

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
    (jpda/resume-event-threads event))
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
  (doto (jpda/step-request thread size depth)
    (.addCountFilter 1)
    (jpda/suspend-policy breakpoint-suspend-policy)
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
  (condition-info [event])
  (restarts [event connection]))

(extend-type ExceptionEvent
  Debugger

  (condition-info
   [event]
   (let [exception (.exception event)]
     {:message (or
                (exception-message exception (jpda/event-thread event))
                "No message.")
      :type (str "  [Thrown " (.. exception referenceType name) "]")}))

  (restarts
   [exception connection]
   (logging/trace "calculate-restarts exception")
   (let [thread (jpda/event-thread exception)]
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
   [breakpoint]
   {:message "BREAKPOINT"})

  (restarts
   [breakpoint connection]
   (logging/trace "calculate-restarts breakpoint")
   (let [thread (jpda/event-thread breakpoint)]
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
   [step]
   {:message "STEPPING"})

  (restarts
   [step-event connection]
   (logging/trace "calculate-restarts step-event")
   (let [thread (jpda/event-thread step-event)]
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
  (let [thread (jpda/event-thread event)
        thread-id (.uniqueID thread)
        restarts (restarts event connection)
        level-info {:restarts restarts :thread thread :event event}
        level (connection/next-sldb-level connection level-info)]
    (logging/trace "invoke-debugger: send-to-emacs")
    (connection/send-to-emacs
     connection
     (messages/debug
      thread-id level
      (condition-info event)
      restarts
      (if (instance? InvocationExceptionEvent event)
        [{:function "Unavailble" :source "UNKNOWN" :line "UNKNOWN"}]
        (build-backtrace thread 0 *sldb-initial-frames*))
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
  (jpda/suspend-event-threads event))

(defn debugger-info-for-emacs
  "Calculate debugger information and invoke"
  [connection start end]
  (logging/trace "debugger-info")
  (let [[level-info level] (connection/current-sldb-level-info connection)
        thread (:thread level-info)
        event (:event level-info)]
    (logging/trace "invoke-debugger: send-to-emacs")
    (messages/debug-info
     (condition-info event)
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
    jpda/invoke-multi-threaded
    jpda/invoke-single-threaded))

(defmethod inspect/value-as-string com.sun.jdi.PrimitiveValue
  [obj] (pr-str (.value obj)))

(defmethod inspect/value-as-string com.sun.jdi.Value
  [obj]
  (try
    (jpda/string-value
     (invoke-clojure-fn
      "swank-clj.inspect" "value-as-string"
      *current-thread-reference*
      jpda/invoke-multi-threaded
      obj))
    (catch com.sun.jdi.InternalException e
      (logging/trace "inspect/value-as-string: exeception %s" e)
      (format "#<%s>" (.. obj referenceType name)))))

;; (defmethod inspect/emacs-inspect com.sun.jdi.PrimitiveValue
;;   [obj]
;;   (inspect/emacs-inspect (.value obj)))

;; (defmethod inspect/emacs-inspect com.sun.jdi.Value
;;   [obj]
;;   (try
;;     (->
;;      (invoke-clojure-fn
;;       "swank-clj.inspect" "emacs-inspect"
;;       *current-thread-reference*
;;       jpda/invoke-multi-threaded
;;       obj))
;;     (catch com.sun.jdi.InternalException e
;;       (logging/trace "inspect/emacs-inspect: exeception %s" e)
;;       `("unavailable : " ~(str e)))))

(defmethod inspect/object-content-range com.sun.jdi.PrimitiveValue
  [object start end]
  (inspect/object-content-range (.value object) start end))

(defmethod inspect/object-content-range com.sun.jdi.Value
  [object start end]
  (logging/trace
   "inspect/object-content-range com.sun.jdi.Value %s %s" start end)
  (read-arg
   *current-thread-reference*
   (invoke-clojure-fn
    "swank-clj.inspect" "object-content-range"
    *current-thread-reference* jpda/invoke-multi-threaded
    object
    (remote-eval*
     *current-thread-reference* start jpda/invoke-single-threaded)
    (remote-eval*
     *current-thread-reference* end jpda/invoke-single-threaded))))

(defmethod inspect/object-nth-part com.sun.jdi.Value
  [object n max-index]
  (read-arg
   *current-thread-reference*
   (invoke-clojure-fn
    "swank-clj.inspect" "object-nth-part"
    *current-thread-reference* jpda/invoke-multi-threaded
    object (jpda/mirror-of (:vm vm) n) (jpda/mirror-of (:vm vm) max-index))))

(defmethod inspect/object-call-nth-action :default com.sun.jdi.Value
  [object n max-index args]
  (read-arg
   *current-thread-reference*
   (invoke-clojure-fn
    "swank-clj.inspect" "object-call-nth-action"
    *current-thread-reference*
    jpda/invoke-multi-threaded
    object
    (jpda/mirror-of (:vm vm) n)
    (jpda/mirror-of (:vm vm) max-index)
    (remote-eval (:vm vm) *current-thread-reference* args))))

(defn frame-locals
  "Return frame locals for slime, a sequence of [LocalVariable Value]
   sorted by name."
  [level-info n]
  (let [frame (nth (.frames (:thread level-info)) n)]
    (sort-by
     #(.name (key %))
     (merge {} (jpda/frame-locals frame) (jpda/clojure-locals frame)))))

(defn frame-locals-with-string-values
  "Return frame locals for slime"
  [level-info n]
  (binding [*current-thread-reference* (:thread level-info)]
    (doall
     (for [map-entry (seq (frame-locals level-info n))]
       {:name (.name (key map-entry))
        :value (val map-entry)
        :string-value (inspect/value-as-string (val map-entry))}))))

(defn nth-frame-var
  "Return the var-index'th var in the frame-index'th frame"
  [level-info frame-index var-index]
  {:pre [(< frame-index (count (.frames (:thread level-info))))]}
  (logging/trace "debug/nth-frame-var %s %s" frame-index var-index)
  (->
   (seq (frame-locals-with-string-values level-info frame-index))
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
  [thread]
  (or @remote-map-sym-value
      (reset! remote-map-sym-value
              (symbol (remote-eval thread `(gensym "swank"))))))

(def ^{:private true
       :doc "An empty map"}
  remote-empty-map-value (atom nil))

(defn remote-empty-map
  "Return a remote empty map for use in resetting the swank remote var"
  [thread]
  (or @remote-empty-map-value
      (reset! remote-empty-map-value
              (remote-value thread `(hash-map) jpda/invoke-single-threaded))))

(defn assoc-local
  "Assoc a local variable into a remote var"
  [thread map-var local]
  (remote-call
   thread (invoke-option-for (val local))
   `assoc map-var
   (remote-str (.name (key local)))
   (when-let [value (val local)]
     (remote-object (val local) thread))))

(defn set-remote-values
  "Build a map in map-var of name to value for all the locals"
  [thread map-var locals]
  (remote-swap-root
   thread jpda/invoke-single-threaded
   map-var
   (reduce
    (fn [v local] (assoc-local thread v local))
    (remote-var-get thread jpda/invoke-single-threaded  map-var)
    locals)))

(defn clear-remote-values
  [thread map-var]
  (remote-swap-root
   thread jpda/invoke-single-threaded
   map-var (remote-empty-map thread)))

(defn eval-string-in-frame
  "Eval the string `expr` in the context of the specified `frame-number`."
  [connection expr frame-number]
  (let [[level-info level] (connection/current-sldb-level-info connection)
        thread (:thread level-info)]
    (try
      (let [_ (assert (.isSuspended thread))
            locals (frame-locals level-info frame-number)
            _ (logging/trace "eval-string-in-frame: map-sym")
            map-sym (remote-map-sym thread)
            _ (logging/trace "eval-string-in-frame: map-var for %s" map-sym)
            map-var (remote-value
                     thread `(intern '~'user '~map-sym {})
                     jpda/invoke-single-threaded)
            _ (logging/trace "eval-string-in-frame: form")
            form (with-local-bindings-form map-sym locals (read-string expr))]
        (logging/trace "eval-string-in-frame: form %s" form)
        (try
          (logging/trace "eval-string-in-frame: set-remote-values")
          (set-remote-values (:thread level-info) map-var locals)
          ;; create a bindings form
          (logging/trace "eval-string-in-frame: remote-eval")
          (remote-eval-to-string*
           thread `(pr-str ~form) jpda/invoke-single-threaded)
          (finally
           (logging/trace "eval-string-in-frame: clear-remote-values")
           (clear-remote-values (:thread level-info) map-var))))
      (catch com.sun.jdi.InvocationException e
        (invoke-debugger*
         connection (InvocationExceptionEvent. (.exception e) thread))))))

;;; events
(defn add-exception-event-request
  [vm]
  (logging/trace "add-exception-event-request")
  (if (:exception-request vm)
    vm
    (do (logging/trace "add-exception-event-request: adding request")
        (assoc vm
          :exception-request (doto (jpda/exception-request
                                    (:event-request-manager vm)
                                    nil true true)
                               (jpda/suspend-policy exception-suspend-policy)
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
    (let [status (jpda/thread-states (.status thread))]
      (when (not= status :running)
        (logging/trace
         "maybe-acquire-control-thread: thread status %s" status)))
    (when-let [message (exception-message exception thread)]
      (when (= message control-thread-name)
        (.suspend thread) ;; make sure it stays suspended
        (.disable (:exception-request vm))
        (alter-var-root
         #'vm assoc
         :control-thread thread
         :exception-request nil)
        (control-thread-acquired!)))
    (catch Throwable e
      (logging/trace
       "control thread acquistion error %s"
       (pr-str e)
       ;; (helpers/stack-trace-string e)
       ))))

(defn caught?
  "Predicate for testing if the given exception is caught outside of swank-clj"
  [exception-event]
  (when-let [catch-location (jpda/catch-location exception-event)]
    (let [catch-location-name (jpda/location-type-name catch-location)
          location (jpda/location exception-event)
          location-name (jpda/location-type-name location)]
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
      (let [location-name (jpda/location-type-name location)]
        (logging/trace "ignore-location? %s" location-name)
        (or (.startsWith location-name "swank_clj.swank")
            (.startsWith location-name "swank_clj.commands.contrib")
            (.startsWith location-name "clojure.lang.Compiler"))))))

(defn stacktrace-contains?
  "Predicate to check for specific deifining type name in the stack trace."
  [thread defining-type]
  (some
   #(= defining-type (jpda/location-type-name (.location %)))
   (.frames thread)))

(defn break-for-exception?
  "Predicate to check whether we should invoke the debugger fo the given
   exception event"
  [exception-event]
  (let [catch-location (jpda/catch-location exception-event)
        location (jpda/location exception-event)
        location-name (jpda/location-type-name location)]
    (or
     (not catch-location)
     (let [catch-location-name (jpda/location-type-name catch-location)]
       (logging/trace
        "break-for-exception? %s %s" catch-location-name location-name)
       (or
        (.startsWith catch-location-name "swank_clj.swank")
        (and
         (.startsWith catch-location-name "clojure.lang.Compiler")
         (stacktrace-contains?
          (jpda/event-thread exception-event)
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
              ;; (logging/trace "connection-and-id-from-thread found frame")
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
                ;; (logging/trace
                ;;  "connection-and-id-from-thread id %s connection %s"
                ;;  (jpda/object-reference id)
                ;;  (jpda/object-reference connection))
                {:connection connection
                 :id (read-arg thread id)}))))
        (.frames thread)))

(defmethod jpda/handle-event ExceptionEvent
  [event connected]
  (let [exception (.exception event)
        thread (jpda/event-thread event)]
    (if-not (:control-thread vm)
      (maybe-acquire-control-thread exception thread)
      (when (and (:control-thread vm) (:RT vm))
        ;; (logging/trace "EXCEPTION %s" event)
        ;; assume a single connection for now
        (logging/trace "EXCEPTION %s" (exception-message exception thread))
        (if (break-for-exception? event)
          (let [{:keys [id connection]} (connection-and-id-from-thread thread)]
            ;; (logging/trace "exception-event: %s %s" id connection)
            (let [connection (ffirst @connections)]
              ;; ensure we have started - id and connection need to go
              (if (and id connection)
                (if (aborting-level? connection)
                  (logging/trace "Not activating sldb (aborting)")
                  (do
                    (logging/trace "Activating sldb")
                    (invoke-debugger connection event)))
                (logging/trace "Not activating sldb (no id, connection)"))))
          (do
            (logging/trace "Not activating sldb (break-for-exception?)")))))))

(defmethod jpda/handle-event BreakpointEvent
  [event connected]
  (let [thread (jpda/event-thread event)]
    (when (and (:control-thread vm) (:RT vm))
      (let [{:keys [id connection]} (connection-and-id-from-thread thread)]
        (logging/trace "BREAKPOINT")
        (let [connection (ffirst @connections)]
          ;; ensure we have started - id and connection need to go
          (if (and id connection)
            (do
              (logging/trace "Activating sldb for breakpoint")
              (invoke-debugger connection event))
            (logging/trace "Not activating sldb (no id, connection)")))))))

(defmethod jpda/handle-event StepEvent
  [event connected]
  (let [thread (jpda/event-thread event)]
    (when (and (:control-thread vm) (:RT vm))
      (let [{:keys [id connection]} (connection-and-id-from-thread thread)]
        (logging/trace "STEP")
        (let [connection (ffirst @connections)]
          ;; ensure we have started - id and connection need to go
          (if (and id connection)
            (do
              (let [request (.. event request)]
                (.disable request)
                (.. event (virtualMachine) (eventRequestManager)
                    (deleteEventRequest request)))
              (logging/trace "Activating sldb for stepping")
              (invoke-debugger connection event))
            (logging/trace "Not activating sldb (no id, connection)")))))))

(defmethod jpda/handle-event VMDeathEvent
  [event connected]
  (doseq [[connection proxied-connection] @connections]
    (connection/close proxied-connection)
    (connection/close connection))
  (System/exit 0))
