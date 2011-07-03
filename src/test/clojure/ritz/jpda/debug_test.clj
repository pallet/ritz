(ns ritz.jpda.debug-test
  (:require
   [ritz.logging :as logging]
   [ritz.jpda.debug :as debug]
   [ritz.jpda.disassemble :as disassemble]
   [ritz.inspect :as inspect]
   [ritz.jpda.jdi :as jdi]
   [ritz.jpda.jdi-clj :as jdi-clj]
   [ritz.jpda.jdi-test-handler :as jdi-test-handler] ;; after jpda.debug
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

;; (logging/set-level :trace)


(deftest vm-swank-main-test
  (is (re-matches
       #"\(try \(clojure.core/require \(quote ritz.socket-server\)\) \(\(clojure.core/resolve \(quote ritz.socket-server/start\)\) \{:a 1\}\) \(catch java.lang.Exception e__\d+__auto__ \(clojure.core/println e__\d+__auto__\) \(.printStackTrace e__\d+__auto__\)\)\)"
       (pr-str (#'debug/vm-swank-main {:a 1})))))

(deftest inspect-test
  (let [context (debug/launch-vm
                 {:main `(do (ns ~'fred)
                             (def ~'x 1)
                             (def ~'y (atom 2))
                             (defn ~'f [x#]
                               (+ x# 2)
                               (deref ~'y)
                               (case x#
                                     1 true
                                     0 false)))})
        context (assoc context :current-thread (:control-thread context))
        vm (:vm context)]
    (.resume (:vm context))

    (try
      (is (= "1" (inspect/value-as-string context (jdi/mirror-of vm 1))))
      (is (= "true" (inspect/value-as-string context (jdi/mirror-of vm true))))
      (is (= "\"2\"" (inspect/value-as-string
                      (assoc context :current-thread (:control-thread context))
                      (jdi-clj/remote-str context "2"))))
      (let [v (jdi-clj/control-eval-to-value context `[1 "a" '~'b])]
        (is (= "[1 \"a\" b]" (inspect/value-as-string context v))))
      (let [[f method] (jdi-clj/clojure-fn-deref
                        context (:control-thread context)
                        jdi/invoke-single-threaded
                        "fred" "f" 1)]
        (let [ops (debug/disassemble-method
                   (disassemble/constant-pool
                    (.. f (referenceType) (constantPool)))
                   method)]
          ;; (clojure.pprint/pprint ops)
          (is (seq ops))))
      (is (debug/disassemble-symbol
           context (:control-thread context) "fred" "f"))
      (finally
       (jdi/shutdown context)))))


(defn eval-in-frame-test [context thread]
  (testing "atomic eval"
    (is (re-matches
         #"#<Atom@[0-9a-f]+: \{:a 1\}>"
         (debug/eval-string-in-frame nil context thread "a" 0)))
    (is (= "{:m 2}"
           (debug/eval-string-in-frame nil context thread "m" 0)))
    (is (= "1" (debug/eval-string-in-frame nil context thread "i" 0)))
    (is (= "1.0" (debug/eval-string-in-frame nil context thread "d" 0)))
    (is (= "nil" (debug/eval-string-in-frame nil context thread "n" 0)))
    (is (= "#'clojure.core/slurp"
           (debug/eval-string-in-frame nil context thread "v" 0)))
    (is (= "\"a string\""
           (debug/eval-string-in-frame nil context thread "s" 0)))
    (is (= "1"
           (debug/eval-string-in-frame nil context thread "w-dash" 0))))
  (testing "form eval"
    (is (= "{:m 3}"
           (debug/eval-string-in-frame
            nil context thread
            "(zipmap (keys m) (map inc (vals m)))" 0)))
    (is (= "2"
           (debug/eval-string-in-frame nil context thread "(inc i)" 0)))
    (is (= "2.0"
           (debug/eval-string-in-frame nil context thread "(inc d)" 0)))
    (is (= "2"
           (debug/eval-string-in-frame nil context thread "(inc w-dash)" 0)))))

(defn pprint-eval-in-frame-test [context thread]
  (testing "atomic eval"
    (is (re-matches
         #"#<Atom@[0-9a-f]+: \{:a 1\}>\n"
         (debug/pprint-eval-string-in-frame nil context thread "a" 0)))
    (is (= "{:m 2}\n"
           (debug/pprint-eval-string-in-frame nil context thread "m" 0)))
    (is (= "1\n"
           (debug/pprint-eval-string-in-frame nil context thread "i" 0)))
    (is (= "1.0\n"
           (debug/pprint-eval-string-in-frame nil context thread "d" 0)))
    (is (= "nil\n"
           (debug/pprint-eval-string-in-frame nil context thread "n" 0)))
    (is (re-matches
         #"#<Var@[0-9a-f]+: #<core\$slurp clojure.core\$slurp@[0-9a-f]+>>\n"
         (debug/pprint-eval-string-in-frame nil context thread "v" 0)))
    (is (= "\"a string\"\n"
           (debug/pprint-eval-string-in-frame nil context thread "s" 0))))
  (testing "form eval"
    (is (= "{:m 3}\n"
           (debug/pprint-eval-string-in-frame
            nil context thread
            "(zipmap (keys m) (map inc (vals m)))" 0)))
    (is (= "2\n"
           (debug/pprint-eval-string-in-frame nil context thread "(inc i)" 0)))
    (is (= "2.0\n"
           (debug/pprint-eval-string-in-frame
            nil context thread "(inc d)" 0)))))

(let [test-finished (promise)]
  (defn handler-for-frame-test [^ExceptionEvent event context]
    (try
      (logging/trace "handler-for-frame-test")
      (let [thread (.thread event)]
        (eval-in-frame-test context thread)
        (pprint-eval-in-frame-test context thread))
      (finally
       (jdi/resume-event-threads event)
       (deliver test-finished nil))))

  (deftest frame-test
    (let [context (debug/launch-vm {:main `(deref (promise))})
          context (assoc context :current-thread (:control-thread context))
          vm (:vm context)]
      (debug/add-exception-event-request context)
      (.resume (:vm context))

      (try
        (jdi-test-handler/add-one-shot-event-handler
         handler-for-frame-test "frame-test")
        (jdi-clj/remote-thread
         context (:control-thread context) jdi/invoke-single-threaded
         `(let [~'a (atom {:a 1})
                ~'m {:m 2}
                ~'i 1
                ~'d 1.0
                ~'n nil
                ~'v #'clojure.core/slurp ; arbitrary var
                ~'s "a string"
                ~'w-dash 1]
            (throw (Exception. "go do handler-for-frame-test"))
            ;; prevent local clearing before the exception
            [~'a ~'m ~'i ~'d ~'n ~'v ~'s])
         {:name "frame-test"})
        @test-finished
        (finally
         (jdi/shutdown context))))))
