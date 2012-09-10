(ns ritz.repl-utils.timeout-test
  (:use
   [ritz.repl-utils.timeout :only [call-with-timeout]]
   clojure.test))

(deftest call-with-timeout-test
  (are [to? ret to proc] (= [ret to?]
                              (let [[x y _] (#'call-with-timeout to proc)]
                                [x y]))
       false "r" 10 (fn [_] "r")
       true  nil 1 (fn [_] (Thread/sleep 10) nil)))
