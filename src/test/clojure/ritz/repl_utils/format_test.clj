(ns ritz.repl-utils.format-test
  (:use
   clojure.test)
  (:require
   [ritz.repl-utils.format :as format]))

(deftest pprint-code-test
  (is (= "(a b c)") (format/pprint-code '(a b c))))
