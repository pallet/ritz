(ns swank-clj.rpc-server-test
  (:use
   clojure.test)
  (:require
   [swank-clj.rpc-server :as server]))

(deftest next-id-test
  (binding [server/current-id (atom 0)
            server/tasks (atom {})]
    (is (= 1 (#'server/next-id)))
    (reset! (var-get #'server/tasks) {2 'a})
    (is (= 3 (#'server/next-id)))))
