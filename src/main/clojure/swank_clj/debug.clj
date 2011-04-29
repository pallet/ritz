(ns swank-clj.debug
  "Debug functions, used to implement debugger commands via jpda."
  (:require
   [swank-clj.logging :as logging]
   [swank-clj.swank.core :as core]
   [swank-clj.jpda :as jpda]
   [swank-clj.connection :as connection]
   [swank-clj.executor :as executor]
   [swank-clj.inspect :as inspect]
   [swank-clj.rpc-socket-connection :as rpc-socket-connection]
   [clojure.java.io :as io]
   [clojure.string :as string])
  (:import
   java.io.File
   java.net.Socket
   java.net.InetSocketAddress
   java.net.InetAddress
   com.sun.jdi.event.BreakpointEvent
   com.sun.jdi.event.ExceptionEvent
   com.sun.jdi.request.ExceptionRequest
   com.sun.jdi.event.VMStartEvent
   com.sun.jdi.event.VMDeathEvent
   com.sun.jdi.VirtualMachine
   com.sun.jdi.ObjectReference))

(defonce vm nil)

(def exception-suspend-policy :suspend-all)
(def control-thread-name "swank-clj-debug-thread-implementation")

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

(defn thread-list
  [connection]
  (let [threads (map
                 (fn [thread]
                   (list
                    (.uniqueID thread)
                    (.name thread)
                    (jpda/thread-states (.status thread))
                    (.suspendCount thread)))
                 (threads))]
    (swap! connection assoc :threads threads)
    threads))

(defn nth-thread
  [connection index]
  (nth (:threads @connection) index nil))

(defn stop-thread
  [thread-id]
  (when-let [thread (some #(= thread-id (.uniqueID %)) (threads))]
    ;; to do - realy stop the thread
    (.interrupt thread)))

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
   (core/stack-trace-string e)))

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
  [vm {:keys [announce port log-level] :as options}]
  (if vm
    vm
    (do
      (reset! continue-handling true)
      (let [cmd (format vm-main (pr-str {:port port
                                         :announce announce
                                         :server-ns 'swank-clj.repl
                                         :log-level (keyword log-level)}))
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
  (->
   (connect-to-repl-on-vm (:port options))
   (rpc-socket-connection/create options)
   (connection/create options)))

(def *current-thread-reference*)

(defn execute-if-inspect-frame-var
  [handler]
  (fn [connection form buffer-package id f]
    (if (and f (= "inspect-frame-var" (name (first form))))
      (binding [*current-thread-reference*
                (:thread (connection/sldb-level-info connection))]
        (core/execute-slime-fn* connection f (rest form) buffer-package))
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
      (binding [*current-thread-reference*
                ;; this may be wrong level's thread
                (:thread (connection/sldb-level-info connection))]
        (core/execute-slime-fn* connection f (rest form) buffer-package))
      (handler connection form buffer-package id f))))

(defn execute-unless-inspect
  [handler]
  (fn [connection form buffer-package id f]
    (if (and f (not (re-find #"inspect" (name (first form)))))
      (core/execute-slime-fn* connection f (rest form) buffer-package)
      (handler connection form buffer-package id f))))

(declare clear-abort-for-current-level)

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
      (when (= 'swank/listener-eval (first form))
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
  ([vm thread form]
     (remote-eval* vm thread form jpda/invoke-multi-threaded))
  ([vm thread form options]
     {:pre [vm thread form options]}
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
  ([vm thread form]
     (remote-eval-to-string* vm thread form jpda/invoke-multi-threaded))
  ([vm thread form options]
     (logging/trace "debug/remote-eval-to-string* %s" form)
     (if-let [rv (remote-eval* vm thread form options)]
       (jpda/string-value rv))))

(defn eval-to-string
  [form]
  `(pr-str (eval ~form)))

(defmacro remote-eval
  ([vm thread form]
     `(remote-eval-to-string* ~vm ~thread '~(eval-to-string form)))
  ([vm thread form options]
     `(remote-eval-to-string* ~vm ~thread '~(eval-to-string form) ~options)))

(defmacro remote-value
  [vm thread form options]
  `(remote-eval* ~vm ~thread '~form ~options))

(defmacro control-eval
  ([form]
     `(read-string (remote-eval vm (:control-thread vm) '~form)))
  ([form options]
     `(read-string (remote-eval vm (:control-thread vm) '~form ~options))))

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

(def vm-port (atom nil))

(defn wait-for-control-thread
  []
  (logging/trace "wait-for-control-thread")
  (when-not (:control-thread vm) ;; theoretical race
    (.await @wait-for-control-thread-latch))
  (logging/trace "wait-for-control-thread: acquired")
  (ensure-runtime)
  (logging/trace "wait-for-control-thread: runtime set")
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

;;; debug methods
(defn debugger-condition-for-emacs [exception thread]
  (logging/trace "debugger-condition-for-emacs")
  (list (or (exception-message exception thread) "No message.")
        (str "  [Thrown " (.. exception referenceType name) "]")
        nil))

(defn format-restarts-for-emacs [restarts]
  (doall (map #(list (first (second %)) (second (second %))) restarts)))

(defn format-frame
  [frame]
  (let [location (.location frame)
        declaring-type (jpda/location-type-name location)
        method (jpda/location-method-name location)
        line (jpda/location-line-number location)
        source-name (jpda/location-source-name location)]
    (if (and (= method "invoke") (.endsWith source-name ".clj"))
      (format
       "%s (%s:%s)"
       (jpda/unmunge-clojure declaring-type)
       source-name
       line)
      (format
       "%s.%s (%s:%s)"
       declaring-type
       method
       source-name
       line))))

(defn exception-stacktrace [thread]
  (map #(list %1 %2 '(:restartable nil))
       (iterate inc 0)
       (map format-frame (.frames thread))))

(defn build-backtrace [level-info start end]
  (doall
   (take (- end start)
         (drop start (exception-stacktrace (:thread level-info))))))

(defn make-restart [kw name description f]
  [kw [name description f]])

(defn resume
  [thread-ref]
  (case exception-suspend-policy
    :suspend-all (.resume (.virtualMachine thread-ref))
    :suspend-event-thread (.resume thread-ref)
    nil))

(defn- abort-level
  "Abort the current level"
  [connection]
  (swap!
   connection
   (fn [current]
     (->
      current
      (assoc :abort-to-level (dec (count (:sldb-levels current))))
      (update-in
       [:sldb-levels]
       (fn [levels]
         (when-let [level (last levels)]
           (resume (:thread level)))
         (subvec levels 0 (dec (count levels)))))))))

(defn abort-all-levels
  [connection]
  (swap! connection
         (fn [connection]
           (->
            connection
            (update-in
             [:sldb-levels]
             (fn [levels]
               (doseq [level (reverse (:sldb-levels connection))]
                 (resume (:thread level)))
               []))
            (assoc :abort-to-level 0)))))

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

(defn calculate-restarts [connection thrown thread]
  (logging/trace "calculate-restarts")
  (let [restarts (filter
                  identity
                  [(make-restart
                    :continue "CONTINUE" "Pass exception to program"
                    (fn [_]
                      (logging/trace "restart Continuing")
                      (.resume thread)))
                   (make-restart
                    :abort "ABORT" "Return to SLIME's top level."
                    (fn [connection]
                      (logging/trace "restart Aborting to top level")
                      (abort-all-levels connection)))
                   (when (> (count (:sldb-levels @connection)) 1)
                     (make-restart
                      :quit "QUIT" "Return to previous level."
                      (fn [connection]
                        (logging/trace "restart Quiting to previous level")
                        (abort-level connection)
                        (.resume thread))))])]
    (apply array-map (apply concat restarts))))

(defn build-debugger-info-for-emacs
  [exception condition level-info start end conts]
  (list condition
        (format-restarts-for-emacs (:restarts level-info))
        (build-backtrace level-info start end)
        conts))

(defn invoke-sldb
  [connection exception thread]
  (logging/trace "invoke-sldb")
  (let [thread-id (.uniqueID thread)
        restarts (calculate-restarts connection exception thread)
        level-info {:restarts restarts :thread thread}
        level (connection/next-sldb-level connection level-info)]
    (logging/trace "invoke-sldb: call emacs")
    (connection/send-to-emacs
     connection
     (list* :debug thread-id level
            (build-debugger-info-for-emacs
             exception
             (debugger-condition-for-emacs exception (:thread level-info))
             level-info 0 *sldb-initial-frames*
             (list* (connection/pending connection)))))
    (connection/send-to-emacs
     connection
     `(:debug-activate ~thread-id ~level nil))
    (.suspend thread)))

(defn invoke-breakpoint
  [connection exception thread]
  (logging/trace "invoke-breakpoint")
  (let [thread-id (.uniqueID thread)
        restarts (calculate-restarts connection exception thread)
        level-info {:restarts restarts :thread thread}
        level (connection/next-sldb-level connection level-info)]
    (logging/trace "invoke-breakpoint: call emacs")
    (connection/send-to-emacs
     connection
     (list* :debug thread-id level
            (build-debugger-info-for-emacs
             exception (list "BREAKPOINT" "" nil)
             level-info 0 *sldb-initial-frames*
             (list* (connection/pending connection)))))
    (connection/send-to-emacs
     connection
     `(:debug-activate ~thread-id ~level nil))
    (.suspend thread)))

(defn invoke-restart
  [connection level-info n]
  (when-let [f (last (nth (vals (:restarts level-info)) n))]
    (inspect/reset-inspector (:inspector @connection))
    (f connection))
  (.uniqueID (:thread level-info)))

(def stm-types #{"clojure.lang.Atom"})

(defn stm-type? [object-reference]
  (stm-types (.. object-reference referenceType name)))

(defn lazy-seq? [object-reference]
  (= "clojure.lang.LazySeq" (.. object-reference referenceType name)))

(defn invoke-option-for [object-reference]
  (logging/trace
   "invoke-option-for %s" (.. object-reference referenceType name))
  (if (stm-type? object-reference)
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
     vm *current-thread-reference* start jpda/invoke-single-threaded)
    (remote-eval*
     vm *current-thread-reference* end jpda/invoke-single-threaded))))

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
  "Return frame locals for slime"
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

;;; Source location

(defn- clean-windows-path [#^String path]
  ;; Decode file URI encoding and remove an opening slash from
  ;; /c:/program%20files/... in jar file URLs and file resources.
  (or (and (.startsWith (System/getProperty "os.name") "Windows")
           (second (re-matches #"^/([a-zA-Z]:/.*)$" path)))
      path))

(defn- slime-zip-resource [#^java.net.URL resource]
  (let [jar-connection #^java.net.JarURLConnection (.openConnection resource)
        jar-file (.getPath (.toURI (.getJarFileURL jar-connection)))]
    (list :zip (clean-windows-path jar-file) (.getEntryName jar-connection))))

(defn- slime-file-resource [#^java.net.URL resource]
  (list :file (clean-windows-path (.getFile resource))))

(defn- slime-find-resource [#^String file]
  (if-let [resource (.getResource (clojure.lang.RT/baseLoader) file)]
    (if (= (.getProtocol resource) "jar")
      (slime-zip-resource resource)
      (slime-file-resource resource))))

(defn- slime-find-file [#^String file]
  (if (.isAbsolute (File. file))
    (list :file file)
    (slime-find-resource file)))

(defn source-location-for-frame [level-info n]
  (when-let [frame (nth (.frames (:thread level-info)) n)]
    (let [location (.location frame)]
      `(:location
        ~(slime-find-file (jpda/location-source-path location))
        (:line ~(jpda/location-line-number location))
        nil))))

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
       (core/stack-trace-string e)))))

(defn caught?
  "Predicate for testing if the given exception is caught outside of swank-clj"
  [exception-event]
  (when-let [location (jpda/catch-location exception-event)]
    (let [location-name (jpda/location-type-name location)]
      (logging/trace
       "caught? %s %s"
       (not (.startsWith location-name "swank_clj.swank"))
       location-name)
      (not (.startsWith location-name "swank_clj.swank")))))

(defn in-swank-clj?
  "Predicate for testing if the given thread is inside of swank-clj"
  [thread]
  (when-let [frame (first (.frames thread))]
    (when-let [location (.location frame)]
      (let [location-name (jpda/location-type-name location)]
        (logging/trace
         "in-swank-clj? %s %s"
         (.startsWith location-name "swank_clj.swank")
         location-name)
        (or (.startsWith location-name "swank_clj.swank")
            (.startsWith location-name "swank_clj.commands.contrib"))))))

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
      ;; (logging/trace "EXCEPTION %s" event)
      ;; assume a single connection for now
      (if (or (caught? event) (in-swank-clj? thread))
        (do
          (logging/trace "Not activating sldb (caught)")
          ;; (logging/trace (string/join (exception-stacktrace thread)))
          )
        (let [{:keys [id connection]} (connection-and-id-from-thread thread)]
          (logging/trace "EXCEPTION %s" (exception-message exception thread))
          ;; (logging/trace "exception-event: %s %s" id connection)
          (let [connection (ffirst @connections)]
            ;; ensure we have started - id and connection need to go
            (if (and id connection)
              (if (aborting-level? connection)
                (logging/trace "Not activating sldb (aborting)")
                (do
                  (logging/trace "Activating sldb")
                  (invoke-sldb connection exception thread)))
              (do (logging/trace "Not activating sldb (no id, connection)")
                  ;; (logging/trace (string/join (exception-stacktrace thread)))
                  ))))))
    (when-not (:control-thread vm)
      (maybe-acquire-control-thread exception thread))
    (when (:control-thread vm)
      (logging/trace
       "Current thread: %s" (.name thread))
      (logging/trace
       "Threads:\n%s"
       (string/join "\n" (map format-thread (jpda/threads (:vm vm))))))))

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
              (invoke-breakpoint connection event thread))
            (logging/trace "Not activating sldb (no id, connection)")))))))

(defmethod jpda/handle-event VMDeathEvent
  [event connected]
  (doseq [[connection proxied-connection] @connections]
    (connection/close proxied-connection)
    (connection/close connection))
  (System/exit 0))

;;; breakpoints
(defn line-breakpoint
  "Set a line breakpoint."
  [connection namespace filename line]
  (let [breakpoints (jpda/line-breakpoints (:vm vm) namespace filename line)]
    (swap!
     connection
     update-in
     [:breakpoints]
     #(conj % breakpoints))
    (format "Set %d breakpoints" (count breakpoints))))
