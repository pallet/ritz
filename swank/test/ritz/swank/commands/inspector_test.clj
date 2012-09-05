(ns ritz.swank.commands.inspector-test
  (:use
   [ritz.debugger.inspect :only [reset-inspector]]
   clojure.test)
  (:require
   [ritz.swank.inspect :as inspect]
   [ritz.jpda.jdi-vm :as jdi-vm]
   [ritz.logging :as logging]
   [ritz.swank.commands.inspector :as inspector]
   [ritz.swank.test-utils :as test-utils]))

;; (logging/set-level :trace)

;; (deftest init-inspector-test
;;   (test-utils/eval-for-emacs-test
;;    `(~'swank/init-inspector "1")
;;    #"(?s)0002[0-9a-f]{2,2}\(:return \(:ok \(:title \"1\".+\)\)\) 1\)"))

(deftest inspect-nth-part-test
  (let [connection {:inspector (atom {})}]
    (reset-inspector connection)
    (inspect/inspect-object (:inspector connection) {:a 1 :b 2})
    (test-utils/eval-for-emacs-test
     `(~'swank/inspect-nth-part 0)
     #"(?s)00[0-9a-f]{4,4}\(:return \(:ok \(:title \"clojure.lang.PersistentArrayMap\".*"
      connection)))

(deftest inspector-range-test)
(deftest inspector-call-nth-action-test)
(deftest inspector-pop-test)
(deftest inspector-next-test)
(deftest inspector-reinspect-test)

;; (deftest quit-inspector-test
;;   (test-utils/eval-for-emacs-test
;;    `(~'swank/quit-inspector)
;;    "000015(:return (:ok nil) 1)"))

(deftest describe-inspectee-test)
