(ns swank-clj.jpda
  "JPDA/JDI wrapper.
   The aim here is to work towards a clojure interface for JDI,
   but is currently mainly a set of light wrapper functions."
  (:refer-clojure :exclude [methods])
  (:require
   [swank-clj.logging :as logging]
   [clojure.string :as string])
  (:import
   (com.sun.jdi
    VirtualMachine PathSearchingVirtualMachine
    Bootstrap VMDisconnectedException
    ObjectReference StringReference
    ThreadReference ThreadGroupReference
    ReferenceType)
   (com.sun.jdi.event
    VMDisconnectEvent LocatableEvent ExceptionEvent StepEvent)
   (com.sun.jdi.request
    ExceptionRequest EventRequest StepRequest EventRequestManager)))

(def connector-names
     {:command-line "com.sun.jdi.CommandLineLaunch"
      :attach-shmem "com.sun.jdi.SharedMemoryAttach"
      :attach-socket "com.sun.jdi.SocketAttach"
      :listen-shmem "com.sun.jdi.SharedMemoryListen"
      :listen-socket "com.sun.jdi.SocketListen"})

(def sys-ns ["java.*" "javax.*" "sun.*" "com.sun.*"])

(defn connectors []
  (.. (Bootstrap/virtualMachineManager) allConnectors))

(defn connector [which]
  (let [name (connector-names which)]
    (some #(and (= (.name %) name) %)
          (.. (Bootstrap/virtualMachineManager) allConnectors))))

(defn launch
  "Launch a debugee.  Returns the VirtualMachine."
  [cp expr]
  (let [launch-connector (connector :command-line)
        arguments (.defaultArguments launch-connector)
        main-args (.get arguments "main")]
    (.setValue main-args (str "-cp " cp " clojure.main -e \"" expr "\""))
    (.launch launch-connector arguments)))

(defn shutdown
  "Shut down virtual machine."
  [vm] (.exit vm 0))

(defn current-classpath []
  (. System getProperty "java.class.path"))
;; (defmulti cleanup-events (fn [e a] (class e)))

;; (defmethod cleanup-events VMDisconnectEvent [e connected]
;;   (reset! connected false))

;; (defmethod cleanup-events :default [e connected]
;;   nil)


(defn event-thread
  "The event's thread reference"
  [^LocatableEvent e]
  (.thread e))

(defn location
  "A location"
  [e]
  (.location e))

(defmulti handle-event (fn [event _] (class event)))
(defmethod handle-event :default [event connected]
  ;(println event)
  )

(defn handle-event-set [vm queue connected f]
  (try
   (let [event-set (.remove queue)]
     (doseq [event event-set]
       (try
         (f event connected)
         (catch VMDisconnectedException e
           (reset! connected false))
         (catch Throwable e
           (logging/trace
            "VM-EVENT, exeception %s" ; \n%s
            e
            ;; (with-out-str (.printStackTrace e *out*))
            ))))
     (.resume event-set))))

(defn run-events
  ([vm connected] (run-events connected handle-event))
  ([vm connected f]
     (let [queue (.eventQueue vm)]
       (loop []
         (if-not @connected
           nil
           (do
             (try
               (handle-event-set vm queue connected f)
               (catch com.sun.jdi.InternalException e
                 (logging/trace "VM-EVENTS, exeception %s" e)))
             (recur)))))))

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
  [vm value]
  (.mirrorOf vm value))

(def invoke-multi-threaded 0)
(def invoke-single-threaded ObjectReference/INVOKE_SINGLE_THREADED)
(def invoke-nonvirtual ObjectReference/INVOKE_NONVIRTUAL)

(defn invoke-method
  [class-or-object method thread options args]
  (.invokeMethod class-or-object thread method args options))

(defn string-value
  [^StringReference value]
  (.value value))

(defn object-reference
  [obj-ref]
  (format "ObjectReference %s" (.. obj-ref referenceType name)))

(defn ^ExceptionRequest exception-request
  "Create an exception request"
  [^EventRequestManager manager ^ReferenceType ref-type
   notify-caught notify-uncaught]
  (.createExceptionRequest
   manager ref-type (boolean notify-caught) (boolean notify-uncaught)))

(def event-request-policies
  {:suspend-all EventRequest/SUSPEND_ALL
   :suspend-event-thread EventRequest/SUSPEND_EVENT_THREAD
   :suspend-none EventRequest/SUSPEND_NONE})

(defn suspend-policy
  "Set the suspend policy for an exeception request.
   policy is one of :suspend-all, :suspend-event-thread, or :suspend-none"
  [^EventRequest request policy]
  (.setSuspendPolicy request (policy event-request-policies)))

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

(defn catch-location
  [^ExceptionEvent event]
  (.catchLocation event))

(defn location-type-name
  [location]
  (.. location declaringType name))

(defn location-method-name
  [location]
  (.. location method name))

(defn location-source-name
  [location]
  (try
    (.. location sourceName)
    (catch Exception _ "UNKNOWN")))

(defn location-source-path
  [location]
  (try
    (.. location sourcePath)
    (catch Exception _ "UNKNOWN")))

(defn location-line-number
  [location]
  (try
    (.lineNumber location)
    (catch Exception _ -1)))

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

;;; breakpoints

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
  [frame fields]
  (let [names (map #(.name %) fields)]
    (or (.endsWith (location-source-path (.location frame)) ".clj")
        (some #{"__meta"} names))))

(def clojure-implementation-regex
  #"(^const__\d*$|^__meta$|^__var__callsite__\d*$|^__site__\d*__$|^__thunk__\d*__$)")

(defn filter-implementation-fields [fields]
  (seq (remove #(re-find clojure-implementation-regex (.name %)) fields)))

(defn clojure-fields
  "Closure locals are fields on the frame's this object."
  [frame]
  (try
    (when-let [this (.thisObject frame)]
      (let [fields (.. this referenceType fields)]
        (when (clojure-frame? frame fields)
          (logging/trace "Field names %s" (vec (map #(.name %) fields)))
          (filter-implementation-fields fields))))
    (catch com.sun.jdi.AbsentInformationException e
      (logging/trace "fields unavailable")
      nil)))

(defn clojure-locals
  [frame]
  (when-let [fields (clojure-fields frame)]
    (.getValues (.thisObject frame) fields)))

(defn frame-locals
  [frame]
  (try
    (when-let [locals (.visibleVariables frame)]
      (.getValues frame locals))
    (catch com.sun.jdi.AbsentInformationException e
      (logging/trace "locals unavailable")
      nil)))

(defn unmunge-clojure
  "unmunge a clojure name"
  [munged-name]
  {:pre [(string? munged-name)]}
  (reduce
   #(string/replace %1 (val %2) (str (key %2)))
   (string/replace munged-name "$" "/")
   clojure.lang.Compiler/CHAR_MAP))

(defn threads
  [vm]
  (.allThreads vm))

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
