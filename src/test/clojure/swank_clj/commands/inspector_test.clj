(ns swank-clj.commands.inspector-test
  (:use clojure.test)
  (:require
   [swank-clj.commands.inspector :as inspector]
   [swank-clj.logging :as logging]
   [swank-clj.test-utils :as test-utils]))

(deftest init-inspector-test
  (test-utils/eval-for-emacs-test
   `(~'swank/init-inspector "1")
   #"0002[0-9a-z]{2,2}\(:return \(:ok \(:title \"1\" :id 0 :content \(\(\"Type: \" \(:value \"java.lang.Integer\" 0\) \"\n\" \"Value: \" \(:value \"1\" 1\) \"\n\" \"---\" \"\n\" \"Fields: \" \"\n\" \"  MIN_VALUE: \" \(:value \"-2147483648\" 2\) \"\n\" \"  MAX_VALUE: \" \(:value \"2147483647\" 3\) \"\n\" \"  TYPE: \" \(:value \"int\" 4\) \"\n\" \"  digits: \" \(:value \"#<char\[\] \[C@[0-9a-f]+>\" 5\) \"\n\" \"  DigitTens: \" \(:value \"#<char\[\] \[C@[0-9a-f]+>\" 6\) \"\n\" \"  DigitOnes: \" \(:value \"#<char\[\] \[C@[0-9a-f]+>\" 7\) \"\n\" \"  sizeTable: \" \(:value \"#<int\[\] \[I@[0-9a-f]+>\" 8\) \"\n\" \"  integerCacheHighPropValue: \" \(:value \"nil\" 9\) \"\n\" \"  value: \" \(:value \"1\" 10\) \"\n\" \"  SIZE: \" \(:value \"32\" 11\) \"\n\" \"  serialVersionUID: \" \(:value \"[0-9]+\" 12\) \"\n\"\) 43 0 500\)\)\) 1\)"))

(deftest inspect-nth-part-test)
(deftest inspector-range-test)
(deftest inspector-call-nth-action-test)
(deftest inspector-pop-test)
(deftest inspector-next-test)
(deftest inspector-reinspect-test)

(deftest quit-inspector-test
  (test-utils/eval-for-emacs-test
   `(~'swank/quit-inspector)
   "000015(:return (:ok nil) 1)"))

(deftest describe-inspectee-test)
