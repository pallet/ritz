(ns ritz.repl-utils.find-test
  (:use
   ritz.repl-utils.find
   clojure.test))

(deftest java-source-path-test
  (is (= "some/ns/TheClass.java"
         (java-source-path "some.ns.TheClass" "TheClass.java"))))

(deftest clojure-source-path-test
  (is (= "some/ns/fred.clj"
         (clojure-source-path "some.ns.fred$sym1" "fred.clj"))))
