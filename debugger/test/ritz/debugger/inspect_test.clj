(ns ritz.debugger.inspect-test
  (:require
   [ritz.debugger.inspect :as inspect]
   ritz.jpda.debug)
  (:use clojure.test))

(deftype X [a])

(deftest value-as-string-test
  (is (= "\"a\"" (inspect/value-as-string nil "a")))
  (is (= "1" (inspect/value-as-string nil 1)))
  (is (= "[1 \"a\" b]" (inspect/value-as-string nil [1 "a" 'b])))
  (is (= "{:a 1, :b 2}"
         (inspect/value-as-string nil {:a 1 :b 2})))
  (is (= "(:a 1 3)"
         (inspect/value-as-string nil '(:a 1 3))))
  (is (re-matches #"#<X ritz.debugger.inspect[-_]test.X@.*>"
         (inspect/value-as-string nil (X. 1)))))
