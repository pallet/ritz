(ns ritz.repl-utils.compile-test
  (:use
   clojure.test
   [ritz.repl-utils.compile :only [with-compiler-options]]
   [ritz.repl-utils.clojure
    :only [feature-cond clojure-1-4-or-greater compiler-options]]))

(defn get-compiler-options
  "Retrieve compiler options"
  []
  (feature-cond
   compiler-options *compiler-options*
   :else {:disable-locals-clearing false}))

(deftest with-compiler-options-test
  (with-compiler-options {:debug true}
    (is
     (= {:disable-locals-clearing clojure-1-4-or-greater}
        (get-compiler-options)))))
