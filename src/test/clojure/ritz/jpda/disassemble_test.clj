(ns ritz.jpda.disassemble-test
  "Disassemble tests"
  (:use
   clojure.test)
  (:require
   [clojure.pprint :as pprint]
   [ritz.jpda.disassemble :as disassemble]
   [ritz.jpda.jdi :as jdi]
   [ritz.jpda.jdi-clj :as jdi-clj]
   [ritz.jpda.jdi-vm :as jdi-vm]))

(deftest ubyte-value-test
  (is (= 255 (disassemble/ubyte-value (byte -1))))
  (is (= 128 (disassemble/ubyte-value (byte -128)))))

(deftest byte-for-uvalue-test
  (is (= (byte 1) (disassemble/byte-for-uvalue 0x01)))
  (is (= (byte -1) (disassemble/byte-for-uvalue 0xff)))
  (is (= (byte -128) (disassemble/byte-for-uvalue 0x80))))

(deftest int-to-bytes-test
  (is (= [(byte 1) (byte 2) (byte 3) (byte -1)]
           (vec (disassemble/int-to-bytes 0x010203ff)))))

(deftest long-to-bytes-test
  (is (= [(byte 1) (byte 2) (byte 3) (byte -1)
          (byte -1) (byte 3) (byte 2) (byte 1)]
           (vec (disassemble/long-to-bytes 0x010203ffff030201)))))

(deftest utf8-pool-entry-test
  (is (= [{:value "A" :type :utf8} 4 1]
         (disassemble/utf8-pool-entry [(byte 0) (byte 1) (byte 65)]))))

(deftest int-pool-entry-test
  (is (= [{:value 123 :type :int} 5 1]
         (disassemble/int-pool-entry [(byte 0) (byte 0) (byte 0) (byte 123)]))))

(deftest float-pool-entry-test
  (is (= [{:value (Float. 1.234) :type :float} 5 1]
           (disassemble/float-pool-entry
            (disassemble/int-to-bytes (Float/floatToIntBits (Float. 1.234)))))))

(deftest long-pool-entry-test
  (is (= [{:value 0x0000007f0000007e :type :long} 9 2]
         (disassemble/long-pool-entry
          [(byte 0) (byte 0) (byte 0) (byte 0x7f)
           (byte 0) (byte 0) (byte 0) (byte 0x7e)]))))

(deftest double-pool-entry-test
  (is (= [{:value (Double. 1.234) :type :double} 9 2]
           (disassemble/double-pool-entry
            (disassemble/long-to-bytes
             (Double/doubleToLongBits (Double. 1.234)))))))

(deftest class-pool-entry-test
  (is (= [{:name-index 1 :type :class} 3 1]
           (disassemble/class-pool-entry [(byte 0) (byte 1)]))))

(deftest fieldref-pool-entry-test
  (is (= [{:class-index 1 :name-and-type-index 2 :type :fieldref} 5 1]
           (disassemble/fieldref-pool-entry
            [(byte 0) (byte 1) (byte 0) (byte 2)]))))

(deftest methodref-pool-entry-test
  (is (= [{:class-index 1 :name-and-type-index 2 :type :methodref} 5 1]
           (disassemble/methodref-pool-entry
            [(byte 0) (byte 1) (byte 0) (byte 2)]))))

(deftest interfacemethodref-pool-entry-test
  (is (= [{:class-index 1 :name-and-type-index 2 :type :interfacemethodref} 5 1]
           (disassemble/interfacemethodref-pool-entry
            [(byte 0) (byte 1) (byte 0) (byte 2)]))))

(deftest string-pool-entry-test
  (is (= [{:index 1 :type :string} 3 1]
           (disassemble/string-pool-entry [(byte 0) (byte 1)]))))

(deftest nameandtype-pool-entry-test
  (is (= [{:name-index 1 :descriptor-index 2 :type :nameandtype} 5 1]
           (disassemble/nameandtype-pool-entry
            [(byte 0) (byte 1) (byte 0) (byte 2)]))))

(deftest pad-to-4byte-test
  (is (= 0 (disassemble/pad-to-4byte 0)))
  (is (= 3 (disassemble/pad-to-4byte 1)))
  (is (= 2 (disassemble/pad-to-4byte 2)))
  (is (= 1 (disassemble/pad-to-4byte 3)))
  (is (= 0 (disassemble/pad-to-4byte 4))))

(deftest disassemble-test
  (let [context (jdi-vm/launch-vm
                 (jdi-vm/current-classpath)
                 `(do (ns ~'fred)
                      (def ~'y (atom 2))
                      (defn ~'x [x#]
                        (+ x# 2)
                        (deref ~'y)
                        (case x#
                              1 true
                              0 false)
                        (case x#
                              1 true
                              44 false))
                      (loop []
                        (try
                          (Thread/sleep 5000)
                          (catch Exception _#))
                        (recur))))
        context (assoc context :current-thread (:control-thread context))
        vm (:vm context)]
    (try
      (.resume (:vm context))
      (Thread/sleep 1000)
      (let [[object method] (jdi-clj/clojure-fn-deref
                             context (:control-thread context)
                             jdi/invoke-single-threaded
                             "fred" "x" 1)
            const-pool (disassemble/constant-pool
                        (.. object (referenceType) (constantPool)))
            bytecodes (.bytecodes method)
            ops (disassemble/disassemble const-pool bytecodes)]
        ;; (pprint/pprint const-pool)
        ;; (pprint/pprint (count const-pool))
        ;; (pprint/pprint object)
        ;; (pprint/pprint method)
        ;; (pprint/pprint ops)
        (is (= :aload_1 (:mnemonic (first ops)))))
      (finally
       (jdi/shutdown context)))))
