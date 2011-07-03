(ns ritz.swank.indent-test
  (:use clojure.test)
  (:require
   [ritz.swank.indent :as indent]))

(deftest update-indentation-delta-test
  (is (nil? (#'indent/update-indentation-delta
             (the-ns 'ritz.swank.indent-test)
             (ref {})
             false)))
  (is (every?
       identity
       ((juxt seq #(every? (fn [x]
                             (string? (first x))
                             (= '. (second x))
                             (or (integer? (nth x 2))
                                 (= 'defun (nth x 2)))) %))
        (#'indent/update-indentation-delta
         (the-ns 'ritz.swank.indent-test)
         (ref {})
         true)))))
