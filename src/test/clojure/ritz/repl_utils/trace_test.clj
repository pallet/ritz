(ns ritz.repl-utils.trace-test
  (:use
   clojure.test
   ritz.repl-utils.trace))

(defn f [x] x)

(deftest trace-test
  (let [op (str "ritz.repl-utils.trace-test/f (1)\n"
                "ritz.repl-utils.trace-test/f => 1\n")]
    (is (= "" (with-out-str (f 1))))
    (is (= op (do (trace! #'f) (with-out-str (#'f 1)))))
    (is (= "" (do (untrace! #'f) (with-out-str (#'f 1)))))
    (is (= op (do (toggle-trace! #'f) (with-out-str (#'f 1)))))
    (is (= "" (do (toggle-trace! #'f) (with-out-str (#'f 1)))))
    (is (= "" (do (trace! #'f) (untrace-all!) (with-out-str (#'f 1)))))))
