(ns ritz.jpda.jdi-test-handler
  "Provides a JDI event handler for testing"
  (:require
   [ritz.logging :as logging]
   [ritz.jpda.jdi :as jdi]
   [clojure.string :as string]
   ritz.jpda.debug)                  ; ensure we clobber the debug event handler
  (:import
   com.sun.jdi.event.BreakpointEvent
   com.sun.jdi.event.ExceptionEvent
   com.sun.jdi.event.StepEvent
   com.sun.jdi.request.ExceptionRequest
   com.sun.jdi.event.VMStartEvent
   com.sun.jdi.event.VMDeathEvent
   com.sun.jdi.event.Event
   com.sun.jdi.VirtualMachine
   com.sun.jdi.ObjectReference
   (com.sun.jdi
    Value BooleanValue ByteValue CharValue DoubleValue FloatValue IntegerValue
    LongValue ShortValue))
  (:use clojure.test))

(def handlers (atom {}))

(defn add-one-shot-event-handler
  "Add an event handler for the specified thread name. The passed function
   should accept an event and a context parameter, and is called only one
   time, on a new thread."
  [f thread-name]
  (swap! handlers assoc thread-name [f {:atom (atom false)
                                        :bindings
                                        [*out* *err* *in* *test-out*
                                         *report-counters*
                                         *testing-contexts*
                                         *stack-trace-depth*]}]))

(defn add-event-handler
  "Add an event handler for the specified thread name. The passed function
   should accept an event and a context parameter."
  [f thread-name]
  (swap! handlers assoc thread-name [f {:bindings
                                        [*out* *err* *in* *test-out*
                                         *report-counters*
                                         *testing-contexts*
                                         *stack-trace-depth*]}]))
(def one-shot-error (atom nil))

(defn reset-one-shot-error
  [] (reset! one-shot-error nil))

(defn one-shot-error?
  [] @one-shot-error)

(defn reset-handlers!
  []
  (reset! handlers {})
  (reset-one-shot-error))

(defmethod jdi/handle-event ExceptionEvent
  [^Event event context]
  (try
    (let [thread (jdi/event-thread event)]
      (let [event-str (str event)]
        (when (not (or (.contains event-str "java.net.URLClassLoader")
                       (.contains event-str "java.lang.ClassLoader")
                       (.contains event-str "clojure.lang.AFn")
                       (.contains event-str "clojure.lang.Util")
                       (.contains (str (.exception event))
                                  "java.lang.ClassNotFoundException")))
          (logging/trace "test handler EVENT %s" event)
          (logging/trace
           "test handler handling exception %s" (.exception event))
          (if-let [[f options] (@handlers (.name thread))]
            (if-let [f-atom (:atom options)]
              (if (compare-and-set! f-atom false true)
                (do
                  (logging/trace "invoking one-shot")
                  (jdi/suspend-event-threads event)
                  (let [[out err in test-out report-counters testing-contexts
                         stack-trace-depth] (:bindings options)]
                    (doto
                        (Thread.
                         (fn []
                           (binding [*out* out *err* err *in* in
                                     *test-out* test-out
                                     *report-counters* report-counters
                                     *testing-contexts* testing-contexts
                                     *stack-trace-depth* stack-trace-depth]
                             (f event context))))
                      (.start))))
                (do
                  (logging/trace "test handler already invoked one-shot")
                  (binding [*out* *err*]
                    (println "Test handler already invoked. Received"
                             (str event) (str (.exception event))))
                  (reset! one-shot-error true)))
              (do
                (logging/trace "test handler invoking")
                (f event context)))
            (logging/trace
             "No debug handler for %s on thread %s" event (.name thread))))))
    (catch java.lang.Exception e
      (logging/trace "test handler %s" e))))
