(ns swank-clj.swank.utils-test
  (:use clojure.test)
  (:require
   [swank-clj.swank.utils :as utils]))

(deftest maybe-ns-test
  (is (= (the-ns 'user) (utils/maybe-ns 'user))))
