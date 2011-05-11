(ns swank-clj.commands.inspector-test
  (:use clojure.test)
  (:require
   [swank-clj.commands.inspector :as inspector]
   [swank-clj.logging :as logging]
   [swank-clj.test-utils :as test-utils]))

(deftest init-inspector-test
  (test-utils/eval-for-emacs-test
   `(~'swank/init-inspector "1")
   #"(?s)0002[0-9a-f]{2,2}\(:return \(:ok \(:title \"1\".+\)\)\) 1\)"))

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
