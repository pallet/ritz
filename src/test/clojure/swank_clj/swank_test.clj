(ns swank-clj.swank-test
  (:use clojure.test)
  (:require
   [swank-clj.swank :as swank]
   [swank-clj.swank.commands :as commands]
   [swank-clj.logging :as logging]
   [swank-clj.rpc-socket-connection :as rpc-s-c]
   [swank-clj.test-utils :as test-utils]))

(commands/defslimefn echo [_ arg] arg)

(deftest eval-for-emacs-test
  (test-utils/eval-for-emacs-test
   `(~'swank/echo :a)
   "000014(:return (:ok :a) 1)"))

(deftest dispatch-event-test
  (test-utils/dispatch-event-test
   '(swank/echo :a)
   "000014(:return (:ok :a) 2)"
   {:request-id 2}))
