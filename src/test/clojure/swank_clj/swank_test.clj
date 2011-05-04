(ns swank-clj.swank-test
  (:use clojure.test)
  (:require
   [swank-clj.swank :as swank]
   [swank-clj.commands :as commands]
   [swank-clj.logging :as logging]
   [swank-clj.rpc-socket-connection :as rpc-s-c]
   [swank-clj.test-utils :as test-utils]))

(deftest eval-for-emacs-test
  ;; (logging/set-level :trace)
  (binding [commands/slime-fn-map {'swank/echo (fn echo [_ arg] arg)}]
    (test-utils/eval-for-emacs-test
      (swank/echo :a)
      "000014(:return (:ok :a) 1)")))

(deftest dispatch-event-test
  ;; (logging/set-level :trace)
  (binding [commands/slime-fn-map {'swank/echo (fn echo [_ arg] arg)}]
    (test-utils/dispatch-event-test
      '(swank/echo :a)
      "000014(:return (:ok :a) 2)"
      {:request-id 2})))
