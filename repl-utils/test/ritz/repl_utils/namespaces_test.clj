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

(deftest namespaces-reset-test
  (let [state (namespace-state)]
    (is (not ((set state) 'ritz.repl-utils.test-namespace)))
    (require 'ritz.repl-utils.test-namespace)
    (is (= #{'ritz.repl-utils.test-namespace}
           (namespaces-since state)))
    (namespaces-reset state)
    (is (not ((set (namespace-state)) 'ritz.repl-utils.test-namespace)))))
