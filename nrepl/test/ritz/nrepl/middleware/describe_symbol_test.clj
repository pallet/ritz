(ns ritz.nrepl.middleware.describe-symbol-test
  (:use
   [clojure.java.io :only [file]]
   [clojure.string :only [split-lines]]
   [clojure.test :only [deftest is testing]]
   [ritz.nrepl.middleware.describe-symbol :only [describe-symbol-reply]]
   [ritz.nrepl.middleware.test-transport :only [test-transport messages]]))

(deftest describe-symbol-test
  (testing "no match"
    (let [t (test-transport)]
      (describe-symbol-reply
       {:transport t :symbol "this-doesnt-exit"
        :ns "ritz.nrepl.middleware.doc"})
      (is (= [{:status #{:not-found}}] (messages t)))))
  (testing "match"
    (let [t (test-transport)]
      (describe-symbol-reply
       {:transport t :symbol "ns-resolve" :ns "clojure.core"})
      (is (= [{:value `("symbol-name" "clojure.core/ns-resolve"
                        "type" :function
                        "arglists" "([ns sym])"
                        "doc" ~(-> ns-resolve meta :doc))}
              {:status #{:done}}]
             (messages t))))))
