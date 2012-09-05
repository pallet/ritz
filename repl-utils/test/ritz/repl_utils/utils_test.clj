(ns ritz.repl-utils.utils-test
  (:use clojure.test)
  (:require
   [ritz.repl-utils.utils :as utils]))

(deftest maybe-ns-test
  (is (= (the-ns 'user) (utils/maybe-ns 'user))))
