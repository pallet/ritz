(ns ritz.jpda.jdi
  "JDI wrapper.
   The aim here is to work towards a clojure interface for JDI
   but is currently mainly a set of light wrapper functions."
  (:refer-clojure :exclude [methods])
  (:require
   [ritz.executor :as executor]
   [ritz.logging :as logging]
   [clojure.string :as string]
   [clojure.java.io :as io])
  (:import
   (com.sun.jdi
    VirtualMachine PathSearchingVirtualMachine
    Bootstrap VMDisconnectedException
    ObjectReference StringReference
    ThreadReference ThreadGroupReference
    ReferenceType Locatable Location StackFrame
    Field LocalVariable)
   (com.sun.jdi.event
    VMDisconnectEvent LocatableEvent ExceptionEvent StepEvent VMDeathEvent
    BreakpointEvent Event EventSet EventQueue)
   (com.sun.jdi.request
    ExceptionRequest EventRequest StepRequest EventRequestManager)))

;;; Connections
(def connector-names
     {:command-line "com.sun.jdi.CommandLineLaunch"
      :attach-shmem "com.sun.jdi.SharedMemoryAttach"
      :attach-socket "com.sun.jdi.SocketAttach"
      :listen-shmem "com.sun.jdi.SharedMemoryListen"
      :listen-socket "com.sun.jdi.SocketListen"})

(defn connectors
  "List known connectors."
  []
  (.. (Bootstrap/virtualMachineManager) allConnectors))

(defn connector
  "Lookup connector based on a keyword in (keys connector-names)"
  [connector-kw]
  (let [name (connector-names connector-kw)]
    (some #(and (= (.name %) name) %) (connectors))))

(defn launch
  "Launch a vm.
   `classpath` is a string to pass as the classpath, and `expr` is an
   sexp that will be passed to clojure.main.

   Returns an instance of VirtualMachine."
  [classpath expr]
  (let [launch-connector (connector :command-line)
        arguments (.defaultArguments launch-connector)
        main-args (.get arguments "main")]
    (logging/trace "jdi/launch %s" expr)
    (.setValue main-args (str "-cp " classpath " clojure.main -e \"" expr "\""))
    (.launch launch-connector arguments)))

(defn interrupt-if-alive
  [^Thread thread]
  (when (.isAlive thread)
    (.interrupt thread)))

(defn shutdown
  "Shut down virtual machine."
  [context]
  (.exit (:vm context) 0)
  (interrupt-if-alive (:vm-out-thread context))
  (interrupt-if-alive (:vm-in-thread context))
  (interrupt-if-alive (:vm-err-thread context)))

Thread
;;; Streams
(defn vm-stream-daemons
  "Start threads to copy standard input, output and error streams"
  [vm {:keys [in out err]}]
  (let [process (.process vm)]
    (logging/trace "vm-stream-daemons")
    {:vm-in-thread (executor/daemon-thread
                    "vm-in"
                    (io/copy (or in *in*) (.getOutputStream process))
                    (logging/trace "vm-in: exit"))
     :vm-out-thread (executor/daemon-thread
                     "vm-out"
                     (io/copy (.getInputStream process) (or out *out*))
                     (logging/trace "vm-out: exit"))
     :vm-err-thread (executor/daemon-thread
                     "vm-err"
                     (io/copy (.getErrorStream process) (or err *err*))
                     (logging/trace "vm-err: exit"))}))

;;; Event handling
;;; `run-events` provides an event loop for jdi events.
(defmulti handle-event
  "Client code should implement this for event tyoes that it is interested
   in receiving.
   By default, events are ignored."
  (fn [event _] (class event)))

(defmethod handle-event :default [event context]
  (logging/trace "Unhandled event: %s" event))

(defn silent-event?
  [event]
  (let [event-str (.toString event)]
    (or (.startsWith event-str "ExceptionEvent@java.net.URLClassLoader")
        (.startsWith event-str "ExceptionEvent@clojure.lang.RT"))))

(defn handle-event-set
  "NB, this resumes the event-set, so you will need to suspend within
   the handlers if required."
  [^EventQueue queue connected context f]
  (let [^EventSet event-set (.remove queue)]
    (try
      (doseq [event event-set]
        (try
          (if (silent-event? event)
            (logging/trace-str "!")
            (logging/trace "jdi/handle-event-set: %s" event))
          (f event context)
          (when (instance? VMDeathEvent event)
            (logging/trace "jdi/handle-event-set: vm death seen")
            (reset! connected false))
          (catch VMDisconnectedException e
            (logging/trace
             "jdi/handle-event-set: vm disconnected exception seen")
            (reset! connected false))
          (catch Throwable e
            (logging/trace "jdi/handle-event-set: Unexpected exeception %s" e)
            (.printStackTrace e))))
      (finally (when @connected (.resume event-set))))))

(defn run-events
  "Run the event loop for the specified vm. `connected` should be an atom
   that can be set to false to cleanly shut down the event loop.
   context is passed opaquely to the event handlers."
  ([vm connected context] (run-events vm connected context handle-event))
  ([^VirtualMachine vm connected context f]
     (loop []
       (when @connected
         (try
           ;; (logging/trace "jdi/run-events: handle event set")
           (handle-event-set (.eventQueue vm) connected context f)
           (catch VMDisconnectedException e
             (reset! connected false))
           (catch com.sun.jdi.InternalException e
             (logging/trace "jdi/run-events: Unexpected exeception %s" e)))
         (recur)))))

(defn vm-event-daemon
  "Runs a thread to dispatch the vm events
   `connected` is an atom to to allow clean loop shutdown"
  [vm connected context]
  (logging/trace "vm-event-daemons")
  {:vm-ev (executor/daemon-thread
           "vm-events"
           (run-events vm connected context handle-event)
           (logging/trace "vm-events: exit"))})

;;; low level wrappers
(defn classes
  "Return the class references for the class name from the vm."
  [vm class-name]
  (.classesByName vm class-name))

(defn methods
  "Return a class's methods with name from the vm."
  ([class method-name]
     (.methodsByName class method-name))
  ([class method-name signature]
     (.methodsByName class method-name signature)))

(defn mirror-of
  "Mirror a primitive value or string into the given vm."
  [^VirtualMachine vm value]
  (.mirrorOf vm value))

(defn string-value
  [^StringReference value]
  (.value value))

(defn object-reference-type-name
  [^ObjectReference obj-ref]
  (format "ObjectReference %s" (.. obj-ref referenceType name)))

(def invoke-multi-threaded 0)
(def invoke-single-threaded ObjectReference/INVOKE_SINGLE_THREADED)
(def invoke-nonvirtual ObjectReference/INVOKE_NONVIRTUAL)

(defn arg-list [& args]
  (or args []))

(defn invoke-method
  "Methods can only be invoked on threads suspended for exceptions.
   `args` is a sequence of remote object references."
  [thread options class-or-object method args]
  ;; (logging/trace
  ;;  "jdi/invoke-method %s %s\nargs %s\noptions %s"
  ;;  class-or-object method (pr-str args) options)
  (logging/trace "jdi/invoke-method %s" method)
  (.invokeMethod class-or-object thread method (or args []) options))

;;; classpath
(defn classpath
  [^PathSearchingVirtualMachine vm]
  (.classPath vm))

(defn base-directory
  [^PathSearchingVirtualMachine vm]
  (.baseDirectory vm))

(defn jar-file?
  "Returns true if file is a normal file with a .jar or .JAR extension."
  [^java.io.File file]
  (and (.isFile file)
       (or (.endsWith (.getName file) ".jar")
           (.endsWith (.getName file) ".JAR"))))

(defn filepaths-from-jar
  "Returns a sequence of Strings naming the non-directory entries in jar-file."
  [^java.util.jar.JarFile jar-file]
  (->>
   (.entries jar-file)
   enumeration-seq
   (filter #(not (.isDirectory %)))
   (map #(.getName %))))

(defn filepaths
  "Returns a sequence of JarFile objects for the jar files on classpath."
  [classpath]
  (filter
   identity
   (map
    (fn [file]
      (if (jar-file? file)
        (try
          (->
           file
           (java.util.jar.JarFile.)
           filepaths-from-jar)
          (catch Exception _))
        (str file)))
    (map #(java.io.File. %) classpath))))

(defn matching-classpath-files
  "Return a sequence of class paths that the specified filepath matches."
  [classpath filepath]
  (logging/trace "matching-classpath-files %s" filepath)
  ;;(logging/trace "matching-classpath-files %s" (vec (filepaths classpath)))
  (some #(= filepath %) (filepaths classpath)))

(defn namespace-for-path
  "Takes a path and builds a namespace string from it"
  [path]
  (logging/trace "namespace-for-path %s" path)
  (when path
    (string/replace path java.io.File/separator ".")))

(defn file-namespace
  "Get the top level namespace for the given file path"
  [classpath filepath]
  (->>
   (-> filepath (.split "\\.jar:") last (.split "\\.") first)
   (matching-classpath-files classpath)
   (namespace-for-path)))

(defn classname-re
  "Return a regular expression pattern for matching all classes in the
   given namespace name"
  [ns]
  (re-pattern (str (string/replace ns "-" "_") "\\$")))

(defn namespace-classes
  "Return all classes for the given namespace"
  [vm namespace]
  (logging/trace "namespace-classes %s" namespace)
  (when-not (string/blank? namespace)
    (let [re (classname-re namespace)]
      (logging/trace "Looking for re %s" re)
      (filter
       (fn [class-ref]
         (re-find re (.name class-ref)))
       (.allClasses vm)))))

(defn file-classes
  "Return all classes for the given path"
  [vm filename]
  (let [file-ns (file-namespace (classpath vm) filename)]
    (logging/trace
       "Looking for %s in %s using %s"
       filename (vec (map #(.name %) (take 10 (.allClasses vm))))
       (vec file-ns))
    (namespace-classes (first file-ns))))


;;; Event Requests
(def
  ^{:doc "Keyword to event request suspend policy value. Allows specification
          of suspend policy by keyword."}
  event-request-policies
  {:suspend-all EventRequest/SUSPEND_ALL
   :suspend-event-thread EventRequest/SUSPEND_EVENT_THREAD
   :suspend-none EventRequest/SUSPEND_NONE})

(defn discard-event-request
  [vm event-request]
  (.disable event-request)
  (.deleteEventRequest (.eventRequestManager vm) event-request))

(defn ^ExceptionRequest exception-request
  "Create an exception request"
  [^VirtualMachine vm ^ReferenceType ref-type
   notify-caught notify-uncaught]
  (.createExceptionRequest
   (.eventRequestManager vm)
   ref-type (boolean notify-caught) (boolean notify-uncaught)))

(defn suspend-policy
  "Set the suspend policy for an exeception request.
   policy is one of :suspend-all, :suspend-event-thread, or :suspend-none"
  [^EventRequest request policy]
  (.setSuspendPolicy request (policy event-request-policies)))

(defn event-suspend-policy
  "Returns the suspend policy for an event"
  [^Event event]
  (let [policy (.. event (request) (suspendPolicy))]
    (some #(and (= policy (val %)) (key %)) event-request-policies)))

(defn suspend-event-threads
  [^Event event]
  (condp = (.. event (request) (suspendPolicy))
      EventRequest/SUSPEND_ALL (.suspend (.virtualMachine event))
      EventRequest/SUSPEND_EVENT_THREAD (.suspend (.thread event))
      EventRequest/SUSPEND_NONE nil))

(defn resume-event-threads
  [^Event event]
  (condp = (.. event (request) (suspendPolicy))
      EventRequest/SUSPEND_ALL (.resume (.virtualMachine event))
      EventRequest/SUSPEND_EVENT_THREAD (.resume (.thread event))
      EventRequest/SUSPEND_NONE nil))

(def step-sizes
  {:min StepRequest/STEP_MIN
   :line StepRequest/STEP_LINE})

(def step-depths
  {:into StepRequest/STEP_INTO
   :over StepRequest/STEP_OVER
   :out StepRequest/STEP_OUT})

(defn ^StepRequest step-request
  "Create an step request
   `size` is one of :min or :line
   `depth` is one of :into, :over or :out"
  [^ThreadReference thread size depth]
  (..
   thread
   (virtualMachine)
   (eventRequestManager)
   (createStepRequest
    thread
    (size step-sizes StepRequest/STEP_LINE)
    (depth step-depths StepRequest/STEP_OVER))))

(defn event-thread
  "Return the event's thread - note that there is no common interface for this.
   A BreakpointEvent just has a thread method."
  [^LocatableEvent event]
  (.thread event))

;;; locations
(defn catch-location
  [^ExceptionEvent event]
  (.catchLocation event))

(defn location
  [^Locatable l]
  (.location l))

(defn location-type-name
  [^Location location]
  (.. location declaringType name))

(defn location-method-name
  [^Location location]
  (.. location method name))

(defn location-source-name
  [^Location location]
  (try
    (.. location sourceName)
    (catch Exception _)))

(defn location-source-path
  [^Location location]
  (try
    (.. location sourcePath)
    (catch Exception _)))

(defn location-line-number
  [^Location location]
  (try
    (.lineNumber location)
    (catch Exception _ -1)))

(defn class-line-locations
  "Return all locations at the given line for the given class.
   If the line doesn't exist for the given class, returns nil."
  [^ReferenceType class line]
  (logging/trace
   "Looking for line %s in %s" line (.name class))
  (try
    (.locationsOfLine class line)
    (catch com.sun.jdi.AbsentInformationException _
      (logging/trace "not found")
      nil)))

;;; breakpoints

(defn breakpoint
  "Create a breakpoint"
  [^VirtualMachine vm suspend-policy ^Location location]
  (doto (.createBreakpointRequest (.eventRequestManager vm) location)
    (.setSuspendPolicy (suspend-policy event-request-policies))
    (.enable)))

(defn line-breakpoints
  "Create breakpoints at the given location"
  [vm suspend-policy namespace filename line]
  (logging/trace "line-breakpoints %s %s %s" namespace filename line)
  (->>
   (or (and namespace (namespace-classes vm namespace))
       (file-classes vm filename))
   (mapcat #(class-line-locations % line))
   (map #(breakpoint vm suspend-policy %))))

;;; from cdt
(defn clojure-frame?
  "Predicate to test if a frame is a clojure frame. Checks the for the extension
   of the frame location's source name, or for the presence of well know clojure
   field prefixes."
  [^StackFrame frame fields]
  (let [source-path (location-source-path (.location frame))]
    (or (and source-path (.endsWith source-path ".clj"))
        (and
         (some #{"__meta"} (map #(.name ^Field %) fields))
         ;;(or (nil? source-path) (not (.endsWith source-path ".java")))
         ))))

(def clojure-implementation-regex
  #"(^const__\d*$|^__meta$|^__var__callsite__\d*$|^__site__\d*__$|^__thunk__\d*__$)")

(defn visible-clojure-fields
  "Return the subset of fields that should be visible."
  [fields]
  (remove
   #(re-find clojure-implementation-regex (.name ^Field %))
   fields))

(defn ^String unmunge-clojure
  "unmunge a clojure name"
  [^String munged-name]
  {:pre [(string? munged-name)]}
  (reduce
   #(string/replace %1 (val %2) (str (key %2)))
   (string/replace munged-name "$" "/")
   clojure.lang.Compiler/CHAR_MAP))

(defn frame-fields
  "Fields for the frame's this object."
  [^StackFrame frame]
  (try
    (when-let [this (.thisObject frame)]
      (.. this referenceType fields))
    (catch com.sun.jdi.AbsentInformationException e
      (logging/trace "fields unavailable"))))

(defn frame-field-values
  "Fields for the frame's this object."
  [^StackFrame frame fields]
  (when-let [this (.thisObject frame)]
    (.. this (getValues fields))))

(defn frame-locals
  "Returns a map from LocalVariable to Value"
  [^StackFrame frame]
  (try
    (when-let [locals (.visibleVariables frame)]
      (.getValues frame locals))
    (catch com.sun.jdi.AbsentInformationException e
      (logging/trace "locals unavailable")
      nil)))

(defn field-maps
  "Returns a sequence of maps representing unmangled clojure fields."
  [fields]
  (for [[^Field field value] fields
        :let [field-name (.name field)]]
    {:field field
     :name field-name
     :unmangled-name (unmunge-clojure field-name)
     :value value}))

(defn local-maps
  "Returns a sequence of maps representing unmangled clojure locals."
  [locals unmangle?]
  (for [[^LocalVariable local value] locals
        :let [local-name (.name local)]]
    {:local local
     :name local-name
     :unmangled-name (if unmangle? (unmunge-clojure local-name) local-name)
     :value value}))

(defn unmangled-frame-locals
  "Return a sequence of maps, representing the fields and locals in a frame.
   Each map has :name, :unmangled-name and :value keys, and either a :field
   or a :local key."
  [frame]
  (let [fields (frame-fields frame)
        locals (frame-locals frame)]
    (if (clojure-frame? frame fields)
      (concat
       (field-maps (frame-field-values frame (visible-clojure-fields fields)))
       (local-maps locals true))
      (local-maps locals false))))

(defn threads
  [vm]
  (when vm (.allThreads vm)))

(def thread-states
  {ThreadReference/THREAD_STATUS_MONITOR :monitor
   ThreadReference/THREAD_STATUS_NOT_STARTED :not-started
   ThreadReference/THREAD_STATUS_RUNNING :running
   ThreadReference/THREAD_STATUS_SLEEPING :sleeping
   ThreadReference/THREAD_STATUS_UNKNOWN :unknown
   ThreadReference/THREAD_STATUS_WAIT :wait
   ThreadReference/THREAD_STATUS_ZOMBIE :zombie})

(defn thread-data
  "Returns thread data"
  [thread]
  {:id (.uniqueID thread)
   :name (.name thread)
   :status (thread-states (.status thread))
   :suspend-count (.suspendCount thread)
   :suspended? (.isSuspended thread)
   :at-breakpoint? (.isAtBreakpoint thread)})


(defn thread-groups
  "Build a thread group tree"
  [vm]
  (let [f (fn thread-group-f [group]
            [{:name (.name group) :id (.uniqueID group)}
             (map thread-group-f (.threadGroups group))
             (map thread-data (.threads group))])]
    (map f (.topLevelThreadGroups vm))))

(defn breakpoint-data
  "Returns breakpoint data"
  [^BreakpointEvent breakpoint]
  (let [location (.location breakpoint)]
    {:file (str (.sourcePath location))
     :line (.lineNumber location)
     :enabled (.isEnabled breakpoint)}))

(defn breakpoints
  "List breakpoints"
  [^VirtualMachine vm]
  (.. vm (eventRequestManager) (breakpointRequests)))

(defn location-data
  "Take a location, and extract function source and line."
  [^Location location]
  (let [declaring-type (location-type-name location)
        method (location-method-name location)
        line (location-line-number location)
        source-name (or (location-source-name location) "UNKNOWN")]
    (if (and (= method "invoke") source-name (.endsWith source-name ".clj"))
      {:function (and declaring-type (unmunge-clojure declaring-type))
       :source source-name
       :line line}
      {:function (format "%s.%s" declaring-type method)
       :source source-name
       :line line})))

(defn exception-message
  "Provide a string with the details of the exception"
  [context ^ExceptionEvent event]
  (when-let [msg (invoke-method
                  (event-thread event)
                  invoke-single-threaded
                  (.exception event)
                  (:exception-message context) [])]
    (string-value msg)))

(defn exception-event-string
  "Provide a string with the details of the exception"
  [context ^ExceptionEvent event]
  (format
   "%s\n%s\n%s\n%s"
   (.. event exception referenceType name)
   (exception-message context event)
   (.. event exception toString)
   (string/join
    \newline
    (map
     (fn [i ^StackFrame frame]
       (let [l (location-data (.location frame))]
         (str i " " (:function l) " " (:source l) " " (:line l))))
     (iterate inc 0)
     (.. (event-thread event) (frames))))))
