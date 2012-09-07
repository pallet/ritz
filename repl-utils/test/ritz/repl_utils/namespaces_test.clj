(ns ritz.repl-utils.namespaces-test
  (:use
   ritz.repl-utils.namespaces
   clojure.test))

(deftest dependent-on-test
  (is (= #{'ritz.repl-utils.namespaces-test}
         (disj                        ; ensure test works whether in ritz or not
          (set (dependent-on 'ritz.repl-utils.namespaces))
          'ritz.swank.commands.basic
          'ritz.swank.commands.contrib.swank-arglists))))

(deftest dependencies-test
  (is (= #{'clojure.set 'clojure.core 'clojure.java.io}
         (set (dependencies 'ritz.repl-utils.namespaces)))))
