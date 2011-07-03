(ns ritz.repl-utils.completion-test
  (:use
   ritz.repl-utils.completion
   clojure.test))

(deftest simple-completion-test
  (is (= [["shutdown-agents"] "shutdown-agents"]
           (simple-completion "shutdown" (the-ns 'clojure.core))))
  (is (= [["ns-aliases" "ns-imports" "ns-interns" "ns-map" "ns-name"
           "ns-publics" "ns-refers" "ns-resolve" "ns-unalias" "ns-unmap"]
          "ns-"]
           (simple-completion "ns-" (the-ns 'clojure.core)))))
