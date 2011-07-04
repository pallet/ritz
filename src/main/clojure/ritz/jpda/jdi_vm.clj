(ns ritz.jpda.jdi-vm
  "Virtual machine launch and event handling.

   Provides a context for a VM with a :control-thread"
  (:require
   [ritz.jpda.jdi :as jdi]
   [ritz.logging :as logging]
   [clojure.pprint :as pprint]
   [clojure.string :as string])
  (:import
   com.sun.jdi.event.ExceptionEvent
   com.sun.jdi.event.VMDeathEvent
   com.sun.jdi.request.ExceptionRequest
   com.sun.jdi.VirtualMachine))

;;; Control thread acquisition
(def control-thread-name "JDI-VM-Control-Thread")

(defn- start-control-thread-body
  "Form to start a thread for the debugger to work with.  This should contain
   only clojure.core symbols."
  []
  `(do
     (let [thread# (Thread.
                    (fn []
                      (try
                        (throw (Exception.
                                (str '~(symbol control-thread-name))))
                        (catch Exception _#
                          (throw
                           (Exception. (str 'CONTROL-THREAD-CONTINUED)))))))]
       (.setName thread# (str '~(symbol control-thread-name)))
       (.setDaemon thread# false)
       (.start thread#))))

(defn- request-exception-for-control-thread
  [^VirtualMachine vm]
  (logging/trace "request-exception-for-control-thread")
  (doto (jdi/exception-request vm nil false true)
    (jdi/suspend-policy :suspend-all)
    (.enable)))

(defn- handle-acquire-event
  [context connected control-thread event]
  (try
    (cond
     (instance? ExceptionEvent event)
     (let [thread (.thread ^ExceptionEvent event)]
       (if (= control-thread-name (.name thread))
         (do
           (logging/trace
            "jdi-vm/acquire-control-thread: found-thread")
           ;; so it remains suspended when the vm is resumed
           (reset! control-thread thread)
           (.suspend thread)
           (.. event (virtualMachine) (suspend)))
         (logging/trace
          "jdi-vm/acquire-control-thread: unexpected exception %s"
          (jdi/exception-event-string context event))))

     (instance? VMDeathEvent event)
     (do
       (reset! connected false)
       (logging/trace
        "jdi-vm/acquire-control-thread: unexpected VM shutdown"))

     :else (logging/trace "Ignoring event %s" event))

    (catch com.sun.jdi.VMDisconnectedException e
      (reset! connected false))
    (catch Throwable e
      (logging/trace
       "jdi/acquire-control-thread: Unexpected exeception %s"
       e))))

(defn- acquire-control-thread
  "Acquire the control thread."
  [context]
  (let [control-thread (atom nil)
        ^VirtualMachine vm (:vm context)
        connected (:connected context)
        queue (.eventQueue vm)]
    (loop []
      (when (and @connected (not @control-thread))
        (try
          (let [event-set (.remove queue)]
            (try
              (doseq [event event-set]
                (handle-acquire-event context connected control-thread event))
              (finally (when @connected (.resume event-set)))))
          (catch com.sun.jdi.InternalException e
            (logging/trace
             "jdi/acquire-control-thread: Unexpected exeception %s" e)))
        (recur)))
    (logging/trace "Control thread %s" @control-thread)
    (assoc context :control-thread @control-thread)))

;;; VM Control
(defn vm-resume
  [context]
  (.resume (:vm context)))

(defn wrap-launch-cmd
  [cmd]
  ;; `(doto (Thread. (fn []
  ;;                   ~(start-control-thread-body)
  ;;                   ~cmd))
  ;;    (.setDaemon false)
  ;;    (.start))
  `(do
     ~(start-control-thread-body)
     ~cmd))

(def ^{:private true}
  var-signature "(Ljava/lang/String;Ljava/lang/String;)Lclojure/lang/Var;")

(defn vm-rt
  "Lookup clojure runtime."
  [context]
  (logging/trace "vm-rt")
  (if-not (:RT context)
    (if-let [rt (first (jdi/classes (:vm context) "clojure.lang.RT"))]
      (let [vm (:vm context)
            compiler (first (jdi/classes vm "clojure.lang.Compiler"))
            var (first (jdi/classes vm "clojure.lang.Var"))
            throwable (first (jdi/classes vm "java.lang.Throwable"))
            deref (first (jdi/classes vm "clojure.lang.IDeref"))
            context (clojure.core/assoc
                     context
                     :RT rt
                     :Compiler compiler
                     :Var var
                     :Throwable throwable
                     :Deref deref)]
        (logging/trace "vm-rt: classes found")
        (clojure.core/assoc
         context
         :read-string (first (jdi/methods rt "readString"))
         :var (first (jdi/methods rt "var" var-signature))
         :eval (first (jdi/methods compiler "eval"))
         :get (first (jdi/methods var "get"))
         :deref (first (jdi/methods deref "deref"))
         :assoc (first (jdi/methods rt "assoc"))
         :swap-root (first (jdi/methods var "swapRoot"))
         :exception-message (first (jdi/methods throwable "getMessage"))))
      (do
        (logging/trace "vm-rt: RT not found")
        (throw (Exception. "No clojure runtime found in vm"))))
    context))

(defn launch-vm
  "Launch a vm and provide a control thread. Returns a context map.
   The vm is in a suspended state when returned."
  [classpath cmd & {:as options}]
  (logging/trace
   "launch-vm %s\n%s" classpath (with-out-str (pprint/pprint cmd)))
  (let [vm (jdi/launch classpath (wrap-launch-cmd cmd))
        connected (atom true)
        context {:vm vm :connected connected}
        context (merge (jdi/vm-stream-daemons vm options) context)
        exception-request (request-exception-for-control-thread vm)]
    (vm-resume context)
    (let [context (acquire-control-thread context)]
      (jdi/discard-event-request vm exception-request)
      (if @(:connected context)
        (let [context (vm-rt context)
              context (merge
                       (jdi/vm-event-daemon vm connected context)
                       context)]
         context)
        context))))

;;; Classpath Helpers
(defn- format-classpath-url [url]
  (if (= "file" (.getProtocol url))
    (.getPath url)
    url))

(defn current-classpath []
  (string/join
   ":"
   (map format-classpath-url (.getURLs (.getClassLoader clojure.lang.RT)))))
