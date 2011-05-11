(ns swank-clj.inspect-test
  (:require
   [swank-clj.inspect :as inspect]
   [clojure.string :as string]
   clojure.walk)
  (:use clojure.test))

(deftype X [a])

(deftest value-as-string-test
  (is (= "\"a\"" (inspect/value-as-string nil "a")))
  (is (= "1" (inspect/value-as-string nil 1)))
  (is (= "[1 \"a\" b]" (inspect/value-as-string nil [1 "a" 'b])))
  (is (= "#<clojure.lang.LazySeq (0 1 2)>"
         (inspect/value-as-string nil (take 3 (iterate inc 0)))))
  (is (= "#<clojure.lang.LazySeq (0 1 2 3 4 5 6 7 8 9 ...)>"
         (inspect/value-as-string nil (take 11 (iterate inc 0)))))
  (is (= "{:a 1 :b 2}"
         (inspect/value-as-string nil {:a 1 :b 2})))
  (is (= "(:a 1 3)"
         (inspect/value-as-string nil '(:a 1 3))))
  (is (re-matches #"#<X swank[-_]clj.inspect[-_]test.X@.*>"
         (inspect/value-as-string nil (X. 1)))))

(deftest display-values-test
  (is (re-matches
       #"\[\"1\" 0 \(\(\"Type: \" \(:value \"java.lang.Integer\" 0\) \"\\n\" \"Value: \" \(:value \"1\" 1\) \"\\n\" \"---\" \"\\n\" \"Fields: \" \"\\n\" \"  MIN_VALUE: \" \(:value \"-2147483648\" 2\) \"\\n\" \"  MAX_VALUE: \" \(:value \"2147483647\" 3\) \"\\n\" \"  TYPE: \" \(:value \"int\" 4\) \"\\n\" \"  digits: \" \(:value \"#<char\[\] \[C@[0-9a-f]+>\" 5\) \"\\n\" \"  DigitTens: \" \(:value \"#<char\[\] \[C@[0-9a-f]+>\" 6\) \"\\n\" \"  DigitOnes: \" \(:value \"#<char\[\] \[C@[0-9a-f]+>\" 7\) \"\\n\" \"  sizeTable: \" \(:value \"#<int\[\] \[I@[0-9a-f]+>\" 8\) \"\\n\" \"  integerCacheHighPropValue: \" \(:value \"nil\" 9\) \"\\n\" \"  value: \" \(:value \"1\" 10\) \"\\n\" \"  SIZE: \" \(:value \"32\" 11\) \"\\n\" \"  serialVersionUID: \" \(:value \"[0-9]+\" 12\) \"\\n\"\) 43 0 500\)\]"
       (str (inspect/display-values nil (atom {:inspectee 1}))))))
