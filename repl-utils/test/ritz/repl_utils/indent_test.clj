(ns ritz.repl-utils.indent-test
  (:use clojure.test)
  (:require
   [ritz.repl-utils.indent :as indent]))

(deftest update-indentation-delta-test
  (is (nil? (seq (#'indent/update-indentation-delta
                  (the-ns 'ritz.repl-utils.indent-test)
                  (all-ns)
                  {}
                  false))))
  (is (every?
       identity
       ((juxt seq #(every? (fn [[x [s i]]]
                             (string?  x)
                             (or (integer? i)
                                 (= 'defun i))) %))
        (#'indent/update-indentation-delta
         (the-ns 'ritz.repl-utils.indent-test)
         (all-ns)
         {}
         true)))))
