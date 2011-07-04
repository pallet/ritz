(ns ritz.repl-utils.arglist-test
  (:require
   [ritz.repl-utils.arglist :as arglist])
  (:use
   clojure.test))

(deftest arglist-test
  (is (= '([map]) (arglist/arglist :kw (the-ns 'clojure.core))))
  (is (= '([name] [ns name])
         (arglist/arglist 'symbol (the-ns 'clojure.core)))))

(deftest paths-test
  (is (= [[1]] (#'arglist/paths 1)))
  (is (= [['a]] (#'arglist/paths 'a)))
  (is (= [[{:a 1}]] (#'arglist/paths {:a 1})))
  (is (= '(((a 1) a) ((a 1) 1)) (#'arglist/paths '(a 1))))
  (is (= '(((b (a 1)) b) ((b (a 1)) (a 1) a)  ((b (a 1)) (a 1) 1))
         (#'arglist/paths '(b (a 1))))))

(deftest branch-for-terminal-test
  (is (= '(::m (list "q" ::m) (list (list "q" ::m) 1 2))
         (#'arglist/branch-for-terminal '(list (list "q" ::m) 1 2) ::m))))

(deftest indexed-sexps-test
  (is
   (=
    '[[(list "q" ::m) 2] [(list (list "q" ::m) 1 2) 1]]
    (arglist/indexed-sexps '(list (list "q" ::m) 1 2) ::m))))

(deftest arglist-at-terminal-test
  (is (= ['([& items]) 1]
         (arglist/arglist-at-terminal
          '("list" ("list" "q" ::m) 1 2) ::m (the-ns 'clojure.core))))
  (testing "apply"
    (is (= ['([f args* argseq]) 0]
           (arglist/arglist-at-terminal
            '("apply" ::m "list" "q" ) ::m (the-ns 'clojure.core))))
    (is (= ['([f args* argseq]) 0]
           (arglist/arglist-at-terminal
            '("apply" ::m ) ::m (the-ns 'clojure.core))))
    (is (= ['([f args* argseq]) 1]
           (arglist/arglist-at-terminal
            '("apply" "" ::m ) ::m (the-ns 'clojure.core))))
    (is (= ['([& items]) 0]
           (arglist/arglist-at-terminal
            '("apply" "list" ::m "q" ) ::m (the-ns 'clojure.core))))
    (is (nil? (arglist/arglist-at-terminal
               '("clojure.core.mvdedad" ::m) ::m (the-ns 'clojure.core))))
    (is (nil? (arglist/arglist-at-terminal
               '("deploy/" ::m) ::m (the-ns 'clojure.core))))))
