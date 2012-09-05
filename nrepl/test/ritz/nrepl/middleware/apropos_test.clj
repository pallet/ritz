(ns ritz.nrepl.middleware.apropos-test
  (:use
   [clojure.java.io :only [file]]
   [clojure.string :only [split-lines]]
   [clojure.test :only [deftest is testing]]
   [ritz.nrepl.middleware.apropos :only [apropos-reply]]
   [ritz.nrepl.middleware.test-transport :only [test-transport messages]]))

(deftest apropos-test
  (testing "no match"
    (let [t (test-transport)]
      (apropos-reply
       {:transport t :symbol "this-doesnt-exit"
        :ns "ritz.nrepl.middleware.doc"})
      (is (= [{:value nil} {:status #{:done}}] (messages t)))))
  (testing "match"
    (let [t (test-transport)]
      (apropos-reply
       {:transport t :symbol "ns-resolve" :ns "clojure.core"})
      (is (= [{:value `(("symbol-name" "clojure.core/ns-resolve"
                        "type" :function
                        "arglists" "([ns sym])"
                        "doc"
                        ~(-> (-> ns-resolve meta :doc) split-lines first)))}
              {:status #{:done}}]
             (messages t))))))
