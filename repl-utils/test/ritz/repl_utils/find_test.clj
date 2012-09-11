(ns ritz.repl-utils.find-test
  (:use
   ritz.repl-utils.find
   clojure.test))


(deftest find-source-path-test
  (is (= {:file "f" :zip "z.jar"}
         (find-source-path "z.jar:f"))))

(deftest java-source-path-test
  (is (= "some/ns/TheClass.java"
         (java-source-path "some.ns.TheClass" "TheClass.java"))))

(deftest clojure-source-path-test
  (is (= "some/ns/fred.clj"
         (clojure-source-path "some.ns.fred$sym1" "fred.clj"))))
