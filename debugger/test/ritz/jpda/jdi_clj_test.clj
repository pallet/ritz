(ns ritz.jpda.jdi-clj-test
  (:require
   [ritz.logging :as logging]
   [ritz.jpda.jdi :as jdi]
   [ritz.jpda.jdi-clj :as jdi-clj]
   [ritz.jpda.jdi-vm :as jdi-vm]
   [clojure.string :as string])
  (:import
   com.sun.jdi.event.BreakpointEvent
   com.sun.jdi.event.ExceptionEvent
   com.sun.jdi.event.StepEvent
   com.sun.jdi.request.ExceptionRequest
   com.sun.jdi.event.VMStartEvent
   com.sun.jdi.event.VMDeathEvent
   com.sun.jdi.VirtualMachine
   com.sun.jdi.ObjectReference
   (com.sun.jdi
    Value BooleanValue ByteValue CharValue DoubleValue FloatValue IntegerValue
    LongValue ShortValue))
  (:use clojure.test))

;(logging/set-level :trace)

(deftest eval-test
  (let [context (jdi-vm/launch-vm
                 (jdi-vm/current-classpath)
                 `(do (ns ~'fred)
                      (def ~'x 1)
                      (def ~'y (atom 2))))
        thread (:control-thread context)]
    (is thread)
    (.resume (:vm context))
    (testing "eval"
      (is (= 1
             (jdi-clj/eval
              context thread jdi/invoke-multi-threaded `(+ 0 1))))
      (is (= "ab"
             (jdi-clj/eval
              context thread jdi/invoke-multi-threaded `(str "a" "b"))))
      (is (= 'ab
             (jdi-clj/eval
              context thread jdi/invoke-multi-threaded `(symbol "ab"))))
      (is (= [1 "ab" 'ab]
               (jdi-clj/eval
                context thread jdi/invoke-multi-threaded `[1 "ab" '~'ab]))))
    (testing "control-eval"
      (is (= [1 2 3] (jdi-clj/control-eval context `[1 2 3])))
      (is (= "ab" (jdi-clj/control-eval context `(str '~'ab))))
      (is (= 1 (jdi-clj/control-eval context `fred/x)))
      (is (= 2 (jdi-clj/control-eval context `@fred/y))))
    (testing "control-eval-to-value"
      (is (= 1 (jdi-clj/read-arg context thread
                (jdi-clj/control-eval-to-value context `1)))))

    (is (= (clojure-version) (jdi-clj/clojure-version context thread)))

    (is (= "\"a\""
           (jdi/string-value
            (jdi-clj/invoke-clojure-fn
             context thread jdi/invoke-single-threaded "clojure.core" "pr-str"
             (jdi-clj/remote-str context "a")))))
    (jdi/shutdown context)))
