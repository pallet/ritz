(ns swank-clj.jpda.debug-test
  (:require
   [swank-clj.logging :as logging]
   [swank-clj.jpda.debug :as debug]
   [swank-clj.jpda.disassemble :as disassemble]
   [swank-clj.inspect :as inspect]
   [swank-clj.jpda.jdi :as jdi]
   [swank-clj.jpda.jdi-clj :as jdi-clj]
   [swank-clj.jpda.jdi-vm :as jdi-vm]
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

;; (use-fixtures :once
;;               (fn [f]
;;                 (let [context (jdi-vm/launch-vm (jdi-vm/current-classpath) nil)
;;                       context (jdi-clj/vm-rt context)
;;                       thread (:control-thread context)]
;;                   (is thread)
;;                   (f)
;;                   (jdi/shutdown (:vm context)))))


(deftest vm-swank-main-test
  (is (re-matches
       #"\(try \(clojure.core/require \(quote swank-clj.socket-server\)\) \(\(clojure.core/resolve \(quote swank-clj.socket-server/start\)\) \{:a 1\}\) \(catch java.lang.Exception e__\d+__auto__ \(clojure.core/println e__\d+__auto__\) \(.printStackTrace e__\d+__auto__\)\)\)"
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
      (finally
       (jdi/shutdown (:vm context))))))
