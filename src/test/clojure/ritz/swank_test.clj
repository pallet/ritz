(ns ritz.swank-test
  (:use clojure.test)
  (:require
   [ritz.swank :as swank]
   [ritz.swank.commands :as commands]
   [ritz.logging :as logging]
   [ritz.rpc-socket-connection :as rpc-s-c]
   [ritz.test-utils :as test-utils]))

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
