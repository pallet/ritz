(ns ritz.repl-utils.class-browse-test
  (:use
   ritz.repl-utils.class-browse
   clojure.test))

(deftest available-classes-test
  (testing "jars"
    (is (some #(= "clojure.lang.Compiler" %) top-level-classes))
    (is (not (some #(= "clojure.core$defn" %) top-level-classes)))
    (is (some #(= "clojure.core$defn" %) nested-classes))
    (is (not (some #(= "clojure.lang.Compiler" %) nested-classes))))
  ;; (testing "directories"
  ;;   (is (some #(= "gen a class to test here" %) nested-classes)))
  )
