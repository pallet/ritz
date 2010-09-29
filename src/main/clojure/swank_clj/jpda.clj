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
  (.. location declaringType name))
