(ns swank-clj.repl-utils.format-test
  (:use
   clojure.test)
  (:require
   [swank-clj.repl-utils.format :as format]))

(deftest pprint-code-test
  (is (= "(a b c)") (format/pprint-code '(a b c))))
