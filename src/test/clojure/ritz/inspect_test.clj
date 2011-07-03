(ns ritz.inspect-test
  (:require
   [ritz.inspect :as inspect]
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
  (is (re-matches #"#<X ritz.inspect[-_]test.X@.*>"
         (inspect/value-as-string nil (X. 1)))))

(deftest display-values-test
  (is (re-matches
       #"\[\"1\" 0 \(\(\"Type: \" \(:value \"java.lang.(Integer|Long)\" 0\) \"\\n\" \"Value: \" \(:value \"1\" 1\) \"\\n\" \"---\" \"\\n\" \"Fields: .* 0 500\)\]"
       (str (inspect/display-values nil (atom {:inspectee 1}))))))

(deftest emacs-inspect-test
  (is (= ["Class" ": " [:value clojure.lang.PersistentArrayMap] [:newline]
          "Count" ": " [:value 2] [:newline] "Contents: " [:newline]
          "  " [:value :a] " = " [:value 1] [:newline]
          "  " [:value :b] " = " [:value 2] [:newline]]
         (inspect/emacs-inspect  {:a 1 :b 2}))))
