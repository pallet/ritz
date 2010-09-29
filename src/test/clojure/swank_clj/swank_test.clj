(ns swank-clj.swank-test
  (:use clojure.test)
  (:require
   [swank-clj.swank :as swank]
   [swank.commands :as commands]))

(deftest maybe-ns-test
  (is (= (the-ns 'user) (swank/maybe-ns 'user))))

(deftest eval-for-emacs-test
  (binding [commands/slime-fn-map {'swank/echo (fn echo [arg] arg)}]
    (is (= '(:return 1 (:ok :a) 1)
           (swank/eval-for-emacs '(echo :a) 'user 1)))))

(deftest dispatch-event-test
  (binding [commands/slime-fn-map {'swank/echo (fn echo [arg] arg)}]
    (let [connection {:reader nil :writer nil}]
      (is (= '(:return 1 (:ok :a) 2)
             (swank/dispatch-event
              '(:emacs-rex (swank:echo :a) "user" true 2)
              connection))))))
