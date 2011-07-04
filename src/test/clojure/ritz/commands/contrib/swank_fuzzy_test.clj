(ns ritz.commands.contrib.swank-fuzzy-test
  (:use
   [ritz.commands.contrib.swank-fuzzy :as sf]
   clojure.test)
  (:require
   [ritz.test-utils :as test-utils]))

(deftest call-with-timeout-test
  (are [to? ret to proc] (= [ret to?]
                              (let [[x y _] (#'sf/call-with-timeout to proc)]
                                [x y]))
       false "r" 10 (fn [_] "r")
       true  nil 1 (fn [_] (Thread/sleep 10) nil)))

(deftest fuzzy-completions-test
  ;; (logging/set-level :trace)
  (test-utils/eval-for-emacs-test
   `(~'swank/fuzzy-completions "shutdown-a" "clojure.core"
                            :limit 10 :time-limit-in-msec 1000)
   #"00005\d\(:return \(:ok \(\(\(\"shutdown-agents\" \"[\d]+.\d\d\" \(\(0 \"shutdown-a\"\)\) \"-f------\"\)\) nil\)\) 1\)"))
