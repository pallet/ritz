(ns ritz.swank.inspect-test
  (:require
   [ritz.swank.inspect :as inspect]
   [clojure.string :as string]
   clojure.walk)
  (:use clojure.test))

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
