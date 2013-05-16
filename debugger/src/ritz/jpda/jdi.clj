(ns ritz.jpda.jdi
  "JDI wrapper.
   The aim here is to work towards a clojure interface for JDI
   but is currently mainly a set of light wrapper functions."
  (:refer-clojure :exclude [methods])
  (:require
   [ritz.debugger.executor :as executor]
   [ritz.logging :as logging]
   [clojure.string :as string]
   [clojure.java.io :as io])
  (:use
   [clojure.stacktrace :only [print-cause-trace]])
  (:import
   (java.io
    File)
   (com.sun.jdi
    VirtualMachine PathSearchingVirtualMachine
    Bootstrap VMDisconnectedException
    ObjectReference StringReference
    ThreadReference ThreadGroupReference
    ReferenceType Locatable Location StackFrame
    Field LocalVariable Method ClassType Value Mirror
    ClassLoaderReference)
   (com.sun.jdi.connect
    Connector)
   (com.sun.jdi.event
    VMDisconnectEvent LocatableEvent ExceptionEvent StepEvent VMDeathEvent
    BreakpointEvent Event EventSet EventQueue)
   (com.sun.jdi.request
    BreakpointRequest ExceptionRequest EventRequest StepRequest
    EventRequestManager)))

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

(defn ^Connector connector
  "Lookup connector based on a keyword in (keys connector-names)"
  [connector-kw]
  (let [^String name (connector-names connector-kw)]
    (some #(and (= (.name ^Connector %) name) %) (connectors))))

(defn connector-args
  "Returns connector arguments based on name value pairs in arg-map."
  [^Connector connector arg-map]
  (let [args (.defaultArguments connector)]
    (doseq [[^String arg-name value] arg-map
            :let [arg (.get args arg-name)]]
      (when-not arg
        (throw
         (IllegalStateException.
          (str "Could not find JPDA connector argument for " arg-name))))
      (.setValue arg value))
    args))

(defn launch
  "Launch a vm.
   `classpath` is a string to pass as the classpath, and `expr` is an
   sexp that will be passed to clojure.main.

   Returns an instance of VirtualMachine."
  ([classpath expr options]
     (let [^Connector launch-connector (connector :command-line)
           arguments (.defaultArguments launch-connector)
           quote-args (.get arguments "quote")
           main-args (.get arguments "main")
           option-args (.get arguments "options")
           init-file (File/createTempFile "ritz-init" ".clj")
           args (str " -cp '" classpath "' clojure.main -i " (.getCanonicalPath init-file))]
       (.deleteOnExit init-file)
       (spit init-file expr)
       (logging/trace "jdi/launch %s" args)
       (logging/trace "jdi/launch options %s" (string/join " " options))
       (.setValue quote-args "'")
       (.setValue main-args args)
       (.setValue option-args (string/join " " options))
       (.launch launch-connector arguments)))
  ([classpath expr]
     (launch classpath expr "")))

(defn interrupt-if-alive
  [^Thread thread]
  (when (.isAlive thread)
    (.interrupt thread)))

(defn shutdown
  "Shut down virtual machine."
  [context]
  (.exit ^VirtualMachine (:vm context) 0)
  (interrupt-if-alive (:vm-out-thread context))
  (interrupt-if-alive (:vm-in-thread context))
  (interrupt-if-alive (:vm-err-thread context)))

;;; Streams
(defn vm-stream-daemons
  "Start threads to copy standard input, output and error streams"
  [^VirtualMachine vm {:keys [in out err]}]
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
  [^Event event]
  (if-let [^String event-str (try (.toString event)
                                  (catch java.lang.InternalError _))]
    (or (.startsWith event-str "ExceptionEvent@java.net.URLClassLoader")
        (.startsWith event-str "ExceptionEvent@java.lang.Class")
        (.startsWith event-str "ExceptionEvent@clojure.lang.RT")
        (.startsWith event-str "ExceptionEvent@com.sun.xml.internal")
        (.startsWith event-str "ExceptionEvent@sun.reflect.generics.parser")
        (.startsWith event-str "ExceptionEvent@sun.net.www")
        (.startsWith
         event-str "ExceptionEvent@com.sun.org.apache.xerces.internal")
        (.startsWith
         event-str "ExceptionEvent@com.google.inject.spi.InjectionPoint"))
    false))

(defn handle-event-set
  "NB, this resumes the event-set, so you will need to suspend within
   the handlers if required."
  [^EventQueue queue connected context f]
  (let [^EventSet event-set (.remove queue)]
    (try
      (doseq [^Event event event-set]
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
            (logging/trace
             "jdi/handle-event-set:  root exeception %s"
             (with-out-str
               (print-cause-trace e)
               ;; (.printStackTrace (or
               ;;                    ; (clojure.stacktrace/root-cause e)
               ;;                    e))
               ))
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
  [context]
  (logging/trace "vm-event-daemon")
  (assoc context
    :vm-ev (executor/daemon-thread
            "vm-events"
            (run-events (:vm context) (:connected context) context handle-event)
            (logging/trace "vm-events: exit"))))

;;; low level wrappers
(defn ^VirtualMachine virtual-machine
  [^Mirror m]
  (.virtualMachine m))

(defn classes
  "Return the class references for the class name from the vm."
  [^VirtualMachine vm ^String class-name]
  (.classesByName vm class-name))

(defn methods
  "Return a class's methods with name from the vm."
  ([^ReferenceType class ^String method-name]
     (.methodsByName class method-name))
  ([^ReferenceType class ^String method-name ^String signature]
     (.methodsByName class method-name signature)))

(defn ^Value mirror-of
  "Mirror a primitive value or string into the given vm."
  [^VirtualMachine vm value]
  (.mirrorOf vm value))

(defn ^StringReference mirror-of-string
  "Mirror a string into the given vm."
  [^VirtualMachine vm ^String value]
  (.mirrorOf vm value))

(defn string-value
  [^StringReference value]
  ;; create a copy of the string to prevent pinning of string in client vm
  (str (.value value)))

(defn object-reference-type-name
  [^ObjectReference obj-ref]
  (format "ObjectReference %s" (.. obj-ref referenceType name)))

(defn field-values
  "Returns a sequence of Field name and Value pairs."
  [^ObjectReference obj-ref]
  (map
   (juxt #(.name ^Field %) #(.getValue obj-ref ^Field %))
   (.. obj-ref referenceType allFields)))

(defn ^ObjectReference disable-collection [^ObjectReference ref]
  (doto ref
    (.disableCollection)))

(defn possibly-disable-collection [ref]
  (when (instance? ObjectReference ref)
    (disable-collection ref)))

(defn ^ObjectReference enable-collection [^ObjectReference ref]
  (doto ref
    (.enableCollection)))

(defn possibly-enable-collection [ref]
  (when (instance? ObjectReference ref)
    (enable-collection ref)))

(defmacro with-remote-value
  "Provide a scope in which collection of a remote value is disabled"
  [bindings & body]
  (if (seq bindings)
    (let [[sym value] (seq (take 2 bindings))]
      `(let [~sym ~(if (sequential? value)
                     (with-meta `(disable-collection ~value) (meta value))
                     value)]
         (try
           (with-remote-value [~@(drop 2 bindings)]
             ~@body)
           (finally
             ~(with-meta `(enable-collection ~sym) (meta (last body)))))))
    `(do ~@body)))

(defn save-exception-request-states
  [^VirtualMachine vm]
  (reduce
   (fn [m ^ExceptionRequest r] (assoc m r (.isEnabled r)))
   {}
   (.. vm eventRequestManager exceptionRequests)))

(defn disable-exception-request-states
  [^VirtualMachine vm]
  (doseq [^ExceptionRequest r (.. vm eventRequestManager exceptionRequests)]
    (.disable r)))

(defn enable-exception-request-states
  [^VirtualMachine vm]
  (doseq [^ExceptionRequest r (.. vm eventRequestManager exceptionRequests)]
    (.enable r)))

(defn restore-exception-request-states
  [^VirtualMachine vm m]
  (doseq [^ExceptionRequest r (.. vm eventRequestManager exceptionRequests)]
    (.setEnabled r (m r))))

(defmacro with-disabled-exception-requests [[vm] & body]
  `(let [vm# ~vm
         m# (save-exception-request-states vm#)]
     (try
       (disable-exception-request-states vm#)
       ~@body
       (finally
         ;; (restore-exception-request-states vm# m#)
        (enable-exception-request-states vm#)))))

(def invoke-multi-threaded 0)
(def invoke-single-threaded ObjectReference/INVOKE_SINGLE_THREADED)
(def invoke-nonvirtual ObjectReference/INVOKE_NONVIRTUAL)

(defn arg-list [& args]
  (or args []))

(defn ^Value invoke-method
  "Methods can only be invoked on threads suspended for exceptions.
   `args` is a sequence of remote object references."
  [^ThreadReference thread
   {:keys [threading disable-exception-requests]
    :or {threading invoke-single-threaded disable-exception-requests false}
    :as options}
   class-or-object ^Method method args]
  {:pre [thread class-or-object method]}
  ;; (logging/trace
  ;;  "jdi/invoke-method %s %s\nargs %s\noptions %s"
  ;;  class-or-object method (pr-str args) options)
  (logging/trace
   "jdi/invoke-method %s arg count %s options %s" method (count args) options)
  (try
    (letfn [(invoke []
              (let [args (java.util.ArrayList. (or args []))]
                (cond
                  (instance? com.sun.jdi.ClassType class-or-object)
                  (.invokeMethod
                   ^ClassType class-or-object thread
                   method args (int threading))
                  (instance? com.sun.jdi.ObjectReference class-or-object)
                  (.invokeMethod
                   ^ObjectReference class-or-object thread
                   method args (int threading)))))]
      (if disable-exception-requests
        (with-disabled-exception-requests [(.virtualMachine thread)]
          (invoke))
        (invoke)))
    (catch com.sun.jdi.InvocationException e
      (logging/trace
       "Exception in remote method invocation %s" (.exception e))
      (throw e))))

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
   (filter #(not (.isDirectory ^java.util.jar.JarEntry %)))
   (map #(.getName ^java.util.jar.JarEntry %))))

(defn filepaths
  "Returns a sequence of JarFile objects for the jar files on classpath."
  [classpath]
  (filter
   identity
   (mapcat
    (fn [^java.io.File file]
      (if (jar-file? file)
        (try
          (->
           file
           (java.util.jar.JarFile.)
           filepaths-from-jar)
          (catch Exception _))
        [(str file)])) ; (map #(.getPath %) (file-seq file))
    (map #(java.io.File. ^String %) classpath))))

(defn matching-classpath-files
  "Return a sequence of class paths that the specified filepath matches."
  [classpath ^String filepath]
  (logging/trace "matching-classpath-files %s" filepath)
  ;;(logging/trace "matching-classpath-files %s" (vec (filepaths classpath)))
  (->> (filepaths classpath)
       (filter #(or (.endsWith filepath %) (.startsWith filepath %)))
       (map #(if (and (.startsWith filepath %) (.isDirectory (io/file %)))
               (subs filepath (inc (count %)))
               %))))

(defn namespace-for-path
  "Takes a path and builds a namespace string from it"
  [path]
  (logging/trace "namespace-for-path %s" path)
  (when path
    (->
     path
     (string/replace #".class$" "")
     (string/replace #".clj$" "")
     (string/replace "/" "."))))

(defn file-namespace
  "Get the top level namespace for the given file path"
  [classpath ^String filepath]
  (logging/trace "file-namespace %s" filepath)
  (->>
   (string/replace (-> filepath (.split "\\.jar:") last) ".java" ".class")
   (matching-classpath-files classpath)
   (map namespace-for-path)))

(defn classname-re
  "Return a regular expression pattern for matching all classes in the
   given namespace name"
  [ns]
  (re-pattern (str (string/replace ns "-" "_") "\\$")))

(defn namespace-classes
  "Return all classes for the given namespace"
  [^VirtualMachine vm namespace]
  (logging/trace "namespace-classes %s" namespace)
  (when-not (string/blank? namespace)
    (let [re (classname-re namespace)]
      (logging/trace "Looking for re %s" re)
      (filter
       (fn [^ReferenceType class-ref]
         (re-find re (.name class-ref)))
       (.allClasses vm)))))

(defn file-classes
  "Return all classes for the given path"
  [^VirtualMachine vm filename]
  (let [file-ns (file-namespace (classpath vm) filename)]
    (logging/trace
       "Looking for %s in %s using %s"
       filename
       (vec (map #(.name ^ReferenceType %) (take 50 (.allClasses vm))))
       (vec file-ns))
    (namespace-classes vm (first file-ns))))


;;; Threads
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
  [^ThreadReference thread]
  {:id (.uniqueID thread)
   :name (.name thread)
   :status (thread-states (.status thread))
   :suspend-count (.suspendCount thread)
   :suspended? (.isSuspended thread)
   :at-breakpoint? (.isAtBreakpoint thread)})

(defn threads
  [^VirtualMachine vm]
  (when vm (.allThreads vm)))

(defn thread-groups
  "Build a thread group tree"
  [^VirtualMachine vm]
  (letfn [(thread-group-f [^ThreadGroupReference group]
            [{:name (.name group) :id (.uniqueID group)}
             (map thread-group-f (.threadGroups group))
             (map thread-data (.threads group))])]
    (map thread-group-f (.topLevelThreadGroups vm))))

(defn threads-in-group
  "Returns all threads under a named group"
  [^VirtualMachine vm ^String group-name]
  (letfn [(thread-group-f [^ThreadGroupReference group]
            (concat
             (mapcat thread-group-f (.threadGroups group))
             (.threads group)))
          (thread-filter-f [^ThreadGroupReference group]
            (if (= (.name group) group-name)
              (thread-group-f group)
              (mapcat thread-filter-f (.threadGroups group))))]
    (mapcat thread-filter-f (.topLevelThreadGroups vm))))

(defn suspend-thread
  "Suspend a thread reference"
  [^ThreadReference thread]
  (.suspend thread))

(defn suspend-threads
  "Suspend a thread reference"
  [threads]
  (doseq [^ThreadReference thread threads]
    (.suspend thread)))

(defn resume-thread
  "Resume a thread reference"
  [^ThreadReference thread]
  (.resume thread))

(defn resume-threads
  "Suspend a thread reference"
  [threads]
  (doseq [^ThreadReference thread threads]
    (.resume thread)))

(defn ^ClassLoaderReference thread-classloader
  "Return the classloader used by the current thread. This works by finding the
classloader for the current frame's declaring type."
  [^ThreadReference thread]
  (.. ^StackFrame (first (.frames thread)) location declaringType classLoader))

;;; Event Requests
(def
  ^{:doc "Keyword to event request suspend policy value. Allows specification
          of suspend policy by keyword."}
  event-request-policies
  {:suspend-all EventRequest/SUSPEND_ALL
   :suspend-event-thread EventRequest/SUSPEND_EVENT_THREAD
   :suspend-none EventRequest/SUSPEND_NONE})

(defn discard-event-request
  [^VirtualMachine vm ^EventRequest event-request]
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

(defn ^ThreadReference event-thread
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

(defn method-line-locations
  "Return all locations at the given line for the given method.
   If the line doesn't exist for the given class, returns nil."
  [^Method method line]
  ;; (logging/trace
  ;;  "Looking for line %s in %s" line (.name method))
  (try
    (.locationsOfLine method line)
    (catch com.sun.jdi.AbsentInformationException _
      (logging/trace "not found")
      nil)))

(defn class-line-locations
  "Return all locations at the given line for the given class.
   Returns a vector of the class-def and a sequence of locations
   found for the line."
  [^ReferenceType class-def line]
  (logging/trace
   "Looking for line %s in %s" line (.name class-def))
  (try
    [class-def (doall (distinct
                       (concat
                        (.locationsOfLine class-def line)
                        (mapcat #(method-line-locations % line)
                                (.methods class-def)))))]
    (catch com.sun.jdi.ClassNotPreparedException _)
    (catch com.sun.jdi.AbsentInformationException _
      (logging/trace "not found")
      nil)))

(defn- init-location? [location]
  (= "<init>" (location-method-name location)))

(defn- clinit-location? [location]
  (= "<clinit>" (location-method-name location)))

(defn latest-defs
  "Return only the class-defs with the highest $evalnnnn."
  [class-locations]
  (logging/trace "latest defs in %s" (vec class-locations))
  (logging/trace
   "latest defs in %s"
   (vec
    (map
     #(vector (when (first %)
                (.name (first %))) (second %) (count (.instances (first %) 10)))
     class-locations)))
  (let [class-locations (filterv (comp seq second) class-locations)
        re #".*\$eval(\d+)(\$loading.*)?"
        evaln (fn [[^ReferenceType class-ref locations]]
                (when-let [n (second (re-find re (.name class-ref)))]
                  (Integer/parseInt n)))
        ns (->> class-locations (mapv evaln) (filterv identity))
        max-n (apply max -1 ns)         ; -1 in case ns is empty
        filter-f (fn [[^ReferenceType class-ref locations]]
                   (let [[_ n loading?] (re-find re (.name class-ref))]
                     (if n
                       (and (= max-n (Integer/parseInt n)) (not loading?))
                       ;; filter on class refs that have an instance
                       (first (.instances class-ref 1)))))
        locations (filterv filter-f class-locations)
        ;; remove class initialisers as breakpoints, as they are implementation
        ;; details in clojure, as long as there is some other location to break
        ;; on.
        remove-inits (fn [ls] (remove
                               #(or (init-location? %) (clinit-location? %))
                               ls))
        locations (mapv (fn [[c ls]]
                          [c (case (count ls)
                               1 ls
                               2 (or (seq (remove-inits ls)) [(first ls)])
                               (remove-inits ls))])
                        locations)]
    (logging/trace
     "latest defs %s %s %s"
     (vec (map #(.name (first %)) class-locations))
     max-n
     locations)
    locations))

;;; breakpoints

(defn breakpoint
  "Create a breakpoint"
  [^VirtualMachine vm suspend-policy ^Location location]
  (logging/trace "Setting breakpoint %s %s %s"
                 (location-type-name location)
                 (location-method-name location)
                 (location-line-number location))
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
   (map #(class-line-locations % line))
   (remove nil?)
   (latest-defs)
   (mapcat second)
   (map #(breakpoint vm suspend-policy %))))

(defn clojure-frame?
  "Predicate to test if a frame is a clojure frame. Uses the declaring type's
default stratum to decide."
  [^StackFrame frame fields]
  (let [^Location location (.location frame)
        ^String stratum (.. location declaringType defaultStratum)]
    (= "Clojure" stratum)))

(def clojure-implementation-regex
  #"(^__cached_[a-z_]+\d+$|^const__\d*$|^__meta$|^__var__callsite__\d*$|^__site__\d*__$|^__thunk__\d*__$)")

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
     :value value
     :synthetic (.isSynthetic field)
     :static (.isStatic field)
     :final (.isFinal field)
     :type (.typeName field)}))

(defn local-maps
  "Returns a sequence of maps representing unmangled clojure locals."
  [locals unmangle?]
  (for [[^LocalVariable local value] locals
        :let [local-name (.name local)]]
    {:local local
     :name local-name
     :unmangled-name (if unmangle? (unmunge-clojure local-name) local-name)
     :value value
     :type (.typeName local)
     :argument (.isArgument local)}))

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

(defn breakpoint-data
  "Returns breakpoint data"
  [^BreakpointRequest breakpoint]
  (let [location (.location breakpoint)]
    {:file (str (.sourcePath location))
     :line (.lineNumber location)
     :enabled (.isEnabled breakpoint)}))

(defn breakpoints
  "List breakpoints"
  [^VirtualMachine vm]
  (.. vm (eventRequestManager) (breakpointRequests)))

(defn exception-requests
  "List exception requests"
  [^VirtualMachine vm]
  (.. vm (eventRequestManager) (exceptionRequests)))

(defn location-data
  "Take a location, and extract function source and line."
  [^Location location]
  (let [declaring-type (location-type-name location)
        method (location-method-name location)
        line (location-line-number location)
        ^String source-name (or (location-source-name location) "UNKNOWN")
        ^String source-path (or (location-source-path location) "UNKNOWN")
        ^String stratum (.. location declaringType defaultStratum)]
    (if (= stratum "Clojure")
      {:function (and declaring-type (unmunge-clojure declaring-type))
       :source source-name
       :source-path source-path
       :line line
       :stratum stratum}
      {:function (format "%s.%s" declaring-type method)
       :source source-name
       :source-path source-path
       :line line
       :stratum stratum})))

(defn exception-message
  "Provide a string with the details of the exception"
  [context ^ObjectReference exception ^ThreadReference thread]
  (with-disabled-exception-requests [(.virtualMachine thread)]
    (when-let [msg (invoke-method
                    thread
                    {:disable-exception-requests true}
                    exception
                    (:exception-message context) [])]
      (string-value msg))))

(defn exception-event-message
  "Provide a string with the details of the exception"
  [context ^ExceptionEvent event]
  (exception-message context (.exception event) (event-thread event)))

(defn exception-event-string
  "Provide a string with the details of the exception"
  [context ^ExceptionEvent event]
  (format
   "%s\n%s\n%s\n%s"
   (.. event exception referenceType name)
   (exception-event-message context event)
   (.. event exception toString)
   (string/join
    \newline
    (map
     (fn [i ^StackFrame frame]
       (let [l (location-data (.location frame))]
         (str i " " (:function l) " " (:source l) " " (:line l))))
     (iterate inc 0)
     (.. (event-thread event) (frames))))))

(defn collected?
  [^ObjectReference obj]
  (.isCollected obj))
