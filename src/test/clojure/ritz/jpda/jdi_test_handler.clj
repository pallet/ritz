(ns ritz.jpda.jdi-test-handler
  "Provides a JDI event handler for testing"
  (:require
   [ritz.logging :as logging]
   [ritz.jpda.jdi :as jdi]
   [clojure.string :as string])
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

(defmethod jdi/handle-event ExceptionEvent
  [^Event event context]
  (logging/trace "test handler EVENT %s" event)
  (try
    (let [thread (jdi/event-thread event)]
      (logging/trace "test handler has thread")
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
            (logging/trace "test handler already invoked one-shot"))
          (do
            (logging/trace "test handler invoking")
            (f event context)))
        (logging/trace "No debug handler for %s" event)))
    (catch java.lang.Exception e
      (logging/trace "test handler %s" e))))
