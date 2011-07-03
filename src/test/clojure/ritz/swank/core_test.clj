(ns ritz.swank.core-test
  (:use clojure.test)
  (:require
   [ritz.swank.core :as core]))

;; (deftest send-repl-results-to-emacs-test
;;   (is (= "000027(:write-string \"\\\"abc\\\"\n\" :repl-result)"
;;          (with-out-str
;;            (binding [core/*current-connection* (atom {:writer *out*})]
;;              (is (= "abc" (core/send-repl-results-to-emacs "abc"))))))))
