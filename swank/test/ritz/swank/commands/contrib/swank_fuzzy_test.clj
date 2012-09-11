(ns ritz.swank.commands.contrib.swank-fuzzy-test
  (:use
   [ritz.swank.commands.contrib.swank-fuzzy :as sf]
   clojure.test)
  (:require
   [ritz.swank.test-utils :as test-utils]))

(deftest fuzzy-completions-test
  (test-utils/eval-for-emacs-test
   `(~'swank/fuzzy-completions
     "shutdown-a" "clojure.core"
     :limit 10 :time-limit-in-msec 1000)
   #"00005\d\(:return \(:ok \(\(\(\"shutdown-agents\" \"[\d]+.\d\d\" \(\(0 \"shutdown-a\"\)\) \"-f------\"\)\) nil\)\) 1\)"))
