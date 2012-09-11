(require 'ritz.repl-utils.clojure)

(ritz.repl-utils.clojure/feature-cond
 ritz.repl-utils.clojure/clojure-1-3-or-greater
 (do
   (ns ritz.repl-utils.core.defprotocol-test
     (:use
      clojure.test
      ritz.repl-utils.core.defprotocol))

   (def protocol-args 1)
   (require 'ritz.repl-utils.core.test-protocol :reload)
   (deftype T [] P (f [_] :a))
   (def t (T.))

   (deftest defprotocol-test
     (is (= :a (f t)))
     (testing "redef with same args"
       (require 'ritz.repl-utils.core.test-protocol :reload)
       ;; we need compile after redef of P, so use eval
       (is (= :a (eval `(f t)))))
     (testing "redef with different args"
       (alter-var-root #'protocol-args (constantly 2))
       (require 'ritz.repl-utils.core.test-protocol :reload)
       ;; we need compile after redef of P, so use eval
       (is (thrown? Exception (eval `(f t)))))))
 (not ritz.repl-utils.clojure/clojure-1-3-or-greater)
 (do
   (ns ritz.repl-utils.core.defprotocol-test)
   (def protocol-args 1)
   (require 'ritz.repl-utils.core.test-protocol)))
