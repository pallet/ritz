(ns ritz.swank.utils-test
  (:use clojure.test)
  (:require
   [ritz.swank.utils :as utils]))

(deftest maybe-ns-test
  (is (= (the-ns 'user) (utils/maybe-ns 'user))))
