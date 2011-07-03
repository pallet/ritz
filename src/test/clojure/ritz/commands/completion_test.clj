(ns swank-clj.commands.completion-test
  (:use clojure.test)
  (:require
   [swank-clj.commands.completion :as completion]
   [swank-clj.logging :as logging]
   [swank-clj.test-utils :as test-utils]))


(deftest fuzzy-completions-test
  ;; (logging/set-level :trace)
  (test-utils/eval-for-emacs-test
   `(~'swank/simple-completions "shutdown-a" "clojure.core")
   "000039(:return (:ok ((\"shutdown-agents\") \"shutdown-agents\")) 1)"))
