(ns ritz.nrepl.middleware.doc-test
  (:use
   [clojure.java.io :only [file]]
   [clojure.test :only [deftest is testing]]
   [ritz.nrepl.middleware.doc :only [doc-reply]]
   [ritz.nrepl.middleware.test-transport :only [test-transport messages]]))

(deftest doc-test
  (testing "no match"
    (let [t (test-transport)]
      (doc-reply {:transport t :symbol "this-doesnt-exit"
                  :ns "ritz.nrepl.middleware.doc"})
      (is (= [{:status #{:not-found}}]
             (messages t)))))
  (testing "match"
    (let [t (test-transport)]
      (doc-reply {:transport t :symbol "str" :ns "ritz.nrepl.middleware.doc"})
      (is (= [{:value (-> str meta :doc)}
              {:status #{:done}}]
             (messages t))))))
