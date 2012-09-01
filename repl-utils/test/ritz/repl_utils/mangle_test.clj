(ns ritz.repl-utils.mangle-test
  (:use
   ritz.repl-utils.mangle
   clojure.test))

(deftest clojure->java-test
  (is (= "a_b_c_BANG_d" (clojure->java "a-b-c!d"))))

(deftest java->clojure-test
  (is (= "a-b-c!d" (java->clojure "a_b_c_BANG_d"))))

(deftest namespace-name->path-test
  (is (= "a/b_c/d" (namespace-name->path "a.b-c.d"))))

(deftest path->namespace-name-test
  (is (= "a.b-c.d" (path->namespace-name "a/b_c/d"))))

(deftest clojure-class-name->namespace-name-test
  (is (= "a.b-c" (clojure-class-name->namespace-name "a.b_c$d"))))
