(ns ritz.repl-utils.clojure-test
  (:use
   clojure.test
   ritz.repl-utils.clojure))

(deftest protocols-test
  (is (feature-cond
       protocols clojure-1-2-or-greater
       (not protocols) (not (clojure-1-2-or-greater)))))
