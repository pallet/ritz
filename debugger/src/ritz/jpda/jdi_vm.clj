(ns ritz.jpda.jdi-vm
  "Virtual machine launch and event handling.

   Provides a context for a VM with a :control-thread"
  (:require
   [ritz.jpda.jdi :as jdi]
   [ritz.logging :as logging]
   [ritz.repl-utils.class-browse :as class-browse]
   [clojure.pprint :as pprint]
   [clojure.string :as string])
  (:import
   [com.sun.jdi ThreadReference VirtualMachine]
   [com.sun.jdi.event
    Event EventSet ExceptionEvent VMDeathEvent VMDisconnectEvent]
   com.sun.jdi.request.ExceptionRequest))

;;; VM resume
(defn vm-resume
  [context]
  (.resume ^VirtualMachine (:vm context)))

(defn vm-exit
  [context]
  (.exit ^VirtualMachine (:vm context) 0))

;;; Control thread acquisition
(def control-thread-name "JDI-VM-Control-Thread")

(defn start-control-thread-body
  "Form to start a thread for the debugger to work with.  This should contain
   only clojure.core symbols. Strings can also cause quoting issues."
  [thread-name]
  `(let [thread# (Thread.
                  (fn []
                    ;; Not sure why timing is an issue here, as events should
                    ;; just be queued.
                    (Thread/sleep 1000)
                    (throw (Exception. ~thread-name))
                    ;; (try
                    ;;   (throw (Exception.
                    ;;           (str '~(symbol thread-name))))
                    ;;   (catch Exception _#
                    ;;     (throw
                    ;;      (Exception.
                    ;;       (str '~(symbol thread-name) '~'-CONTINUED)))))
                    ))]
     (.setName thread# (str '~(symbol thread-name)))
     (.setDaemon thread# false)
     (.start thread#)
     nil))

(defn- request-exception-for-acquire-thread
  [^VirtualMachine vm]
  (logging/trace "request-exception-for-acquire-thread")
  (doto (jdi/exception-request vm nil false true)
    (jdi/suspend-policy :suspend-all)
    (.enable)))

(defn- handle-acquire-event
  "Filter the event for an exception from the specified thread name."
  [context ^Event event acquire-thread-name]
  (try
    (logging/trace "jdi-vm/handle-acquire-event: event %s" event)
    (cond
      (instance? ExceptionEvent event)
      (let [thread (.thread ^ExceptionEvent event)]
        (if (= acquire-thread-name (.name thread))
          (do
            (logging/trace
             "jdi-vm/handle-acquire-event: found-thread")
            ;; so it remains suspended when the vm is resumed
            (.suspend thread)
            (.. event (virtualMachine) (suspend))
            [true thread])
          (do
            (logging/trace
             "jdi-vm/handle-acquire-event: unexpected exception %s %s"
             (.exception event)
             (vec (jdi/field-values (.exception event))))
            [true nil])))

      (instance? VMDisconnectEvent event)
      (do
        (logging/trace
         "jdi-vm/handle-acquire-event: unexpected VM disconnect")
        [false nil])

      (instance? VMDeathEvent event)
      (do
        (logging/trace
         "jdi-vm/handle-acquire-event: unexpected VM shutdown")
        [false nil])

      :else (do
              (logging/trace "Ignoring event %s" event)
              [true nil]))

    (catch com.sun.jdi.VMDisconnectedException e
      [false nil])
    (catch Throwable e
      (logging/trace
       "jdi/handle-acquire-event: Unexpected exeception %s"
       e)
      [true nil])))

(defn- handle-acquire-event-set
  [context ^EventSet event-set connected acquire-thread-name]
  (try
    (first
     (for [event event-set
           :let [[connected? thread] (handle-acquire-event
                                      context event
                                      acquire-thread-name)
                 _ (reset! connected connected?)]
           :while connected?
           :when thread]
       thread))
    (finally (when @connected (.resume event-set)))))

(defn- acquire-thread-via-exception
  "Acquire the thread named by `acquire-thread-name`."
  [context acquire-thread-name]
  (let [^VirtualMachine vm (:vm context)
        connected (:connected context)
        queue (.eventQueue vm)
        thread (loop []
                 (when @connected
                   (if-let [thread (try
                                     (handle-acquire-event-set
                                      context
                                      (.remove queue)
                                      connected
                                      acquire-thread-name)
                                     (catch com.sun.jdi.InternalException e
                                       (logging/trace
                                        "jdi/acquire-thread: Exception %s" e)))]
                     thread
                     (recur))))]
    (logging/trace "Acquired thread %s" thread)
    thread))

(defn acquire-thread
  [context acquire-thread-name acquire-f]
  (let [^VirtualMachine vm (:vm context)
        exception-request (request-exception-for-acquire-thread vm)]
    (logging/trace "Added exception event request")
    (vm-resume context)
    (logging/trace "Resumed vm")
    (when acquire-f
      (logging/trace "Starting acquisition function")
      (acquire-f context acquire-thread-name))
    (logging/trace "Acquiring thread...")
    (let [connected (:connected context)
          thread (acquire-thread-via-exception context acquire-thread-name)]
      (jdi/discard-event-request vm exception-request)
      (logging/trace "Discarded event request")
      thread)))

;;; VM Control
(defn wrap-launch-cmd
  "Returns a form that starts a thread for use as the control thread, and then
executes the provided `cmd`."
  [cmd]
  `(do
     ~(start-control-thread-body control-thread-name)
     ~cmd))

(defn launch-vm
  "Launch a vm and provide a control thread. Returns a context map.
   The vm is in a suspended state when returned. Threads are started
   to copy the vm's in out and err streams."
  [classpath cmd {:as options}]
  (logging/trace
   "launch-vm %s\n%s" classpath (with-out-str (pprint/pprint cmd)))
  (logging/trace "launch-vm options %s" options)
  (let [vm (jdi/launch classpath (wrap-launch-cmd cmd) (:jvm-opts options))
        connected (atom true)
        context {:vm vm :connected connected}
        context (merge (jdi/vm-stream-daemons vm options) context)
        context (if-let [thread (acquire-thread
                                 context control-thread-name nil)]
                  (assoc context :control-thread thread)
                  context)]
    context))

;;; Classpath Helpers
(defn- format-classpath-url [^java.net.URL url]
  (if (= "file" (.getProtocol url))
    (.getPath url)
    url))

(defn current-classpath []
  (string/join
   ":"
   (map
    format-classpath-url
    (class-browse/classpath-urls))))
