(ns ritz.jpda.jdi-vm-test
  (:require
   [ritz.logging :as logging]
   [ritz.jpda.jdi :as jdi]
   [ritz.jpda.jdi-vm :as jdi-vm]
   [clojure.string :as string])
  (:use clojure.test)
  (:import com.sun.jdi.event.VMDeathEvent))

;;(logging/set-level :trace)

(deftest launch-vm-test
  (is (= "hi\n"
         (with-out-str
           (let [context (jdi-vm/launch-vm
                          (jdi-vm/current-classpath)
                          `(println (str '~'hi))
                          :out *out*)]
             (.resume (:vm context))
             (is (:control-thread context))
             (is (.isSuspended (:control-thread context)))
             (Thread/sleep 1000)
             (jdi/shutdown context))))))
