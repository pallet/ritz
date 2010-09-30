(ns swank-clj.jpda
  "JPDA/JDI wrapper"
  (:refer-clojure :exclude [methods])
  (:require
   [swank-clj.logging :as logging])
  (:import
   (com.sun.jdi
    VirtualMachine Bootstrap VMDisconnectedException
    ObjectReference StringReference)
   (com.sun.jdi.event VMDisconnectEvent LocatableEvent)))

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

(defmulti handle-event (fn [event _] (class event)))
(defmethod handle-event :default [event connected]
  ;(println event)
  )

(defn handle-event-set [vm queue connected f]
  (try
   (let [event-set (.remove queue)]
     (doseq [event event-set]
       (f event connected))
     (.resume event-set))
   (catch InterruptedException e)
   (catch VMDisconnectedException e
     (reset! connected false))))

(defn run-events
  ([vm connected] (run-events connected handle-event))
  ([vm connected f]
     (let [queue (.eventQueue vm)]
       (loop []
         (if-not @connected
           nil
           (do
             (handle-event-set vm queue connected f)
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

(defn invoke-method
  [class-or-object method thread args]
  (.invokeMethod
   class-or-object thread method args
   ObjectReference/INVOKE_SINGLE_THREADED))

(defn string-value
  [^StringReference value]
  (.value value))

(defn object-reference
  [obj-ref]
  (format "ObjectReference %s" (.. obj-ref referenceType name)))

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


;; from cdt
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
  (when-let [this (.thisObject frame)]
    (let [fields (.. this referenceType fields)]
      (when (clojure-frame? frame fields)
        (filter-implementation-fields fields)))))

(defn clojure-locals
  [frame]
  (when-let [fields (clojure-fields frame)]
    (.getValues (.thisObject frame) fields)))

(defn frame-locals
  [frame]
  (when-let [locals (.visibleVariables frame)]
    (.getValues frame locals)))
