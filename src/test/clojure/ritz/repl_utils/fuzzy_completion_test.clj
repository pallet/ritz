(ns ritz.repl-utils.fuzzy-completion-test
  (:use
   [ritz.repl-utils.fuzzy-completion :as sf]
   clojure.test))

(try
  (import ritz.repl_utils.fuzzy_completion.FuzzyMatching)
  (catch ClassNotFoundException _
    (import ritz.repl-utils.fuzzy-completion.FuzzyMatching)))

(deftest compute-most-completions-test
  (is (= '(([0 "m"] [9 "v"] [15 "b"]))
         (#'sf/compute-most-completions "mvb" "multiple-value-bind")))
  (is (= '(([0 "zz"]) ([0 "z"] [2 "z"]) ([1 "zz"]))
         (#'sf/compute-most-completions "zz" "zzz")))
  (is (= 103
         (binding [*fuzzy-recursion-soft-limit* 2]
           (count
            (#'sf/compute-most-completions
             "ZZZZZZ" "ZZZZZZZZZZZZZZZZZZZZZZZ"))))))

(deftest score-completion-test
  (are [x p s] (= x (#'sf/score-completion [[p s]] s "*multiple-value+"))
       '[10.625 (((10 [0 "*"])) 0.625)] 0  "*" ;; at-beginning
       '[10.625 (((10 [1 "m"])) 0.625)] 1  "m" ;; after-prefix
       '[1.625 (((1 [9 "-"])) 0.625)]   9  "-" ;; word-sep
       '[8.625 (((8 [10 "v"])) 0.625)]  10 "v" ;; after-word-sep
       '[6.625 (((6 [15 "+"])) 0.625)]  15 "+" ;; at-end
       '[6.625 (((6 [14 "e"])) 0.625)]  14 "e" ;; before-suffix
       '[1.625 (((1 [2 "u"])) 0.625)]   2  "u" ;; other
       )
  (is (= (+ 10                              ;; m's score
            (+ (* 10 0.85) (Math/pow 1.2 1))) ;; u's score
         (let [[_ x]
               (#'sf/score-completion [[1 "mu"]] "mu" "*multiple-value+")]
           ((comp first ffirst) x)))
      "`m''s score + `u''s score (percentage of previous which is 'm''s)"))

(deftest compute-highest-scoring-completion-test
  (is (= '[([0 "zz"]) 24.7]
         (#'sf/compute-highest-scoring-completion "zz" "zzz"))))

(deftest fuzzy-extract-matching-info-test
  (are [symbol package input] (= [symbol package]
                                   (#'sf/fuzzy-extract-matching-info
                                    (FuzzyMatching.
                                     true nil
                                     "symbol" "ns-name"
                                     nil nil nil)
                                    input))
       "symbol" "ns-name" "p/*"
       "symbol" nil "*")
  (is (= ["" "ns-name"]
           (#'sf/fuzzy-extract-matching-info
            (FuzzyMatching.
                    nil nil
                    "ns-name" ""
                    nil nil nil)
            ""))))

(defmacro try! #^{:private true}
  [& body]
  `(do
     ~@(map (fn [x] `(try ~x (catch Throwable ~'_ nil)))
            body)))

(def testing-testing0 't)
(def #^{:private true} testing-testing1 't)

(deftest fuzzy-find-matching-vars-test
  (let [ns (the-ns 'ritz.repl-utils.fuzzy-completion-test)]
    (try
      (are
       [x
        external-only?] (= x (vec
                              (sort
                               (map (comp str :symbol)
                                    (#'sf/fuzzy-find-matching-vars
                                     "testing"
                                     ns
                                     (fn [[k v]]
                                       (and (= ((comp :ns meta) v) ns)
                                            (re-find #"^testing-" (str k))))
                                     external-only?)))))
       ["testing-testing0" "testing-testing1"] nil
       ["testing-testing0"] true))))

(deftest fuzzy-find-matching-nss-test
  (try
    (create-ns 'testing-testing0)
    (create-ns 'testing-testing1)
    (is (= '["testing-testing0" "testing-testing1"]
           (vec
            (sort
             (map (comp str :symbol)
                  (#'sf/fuzzy-find-matching-nss "testing-"))))))
    (finally
     (try!
      (remove-ns 'testing-testing0)
      (remove-ns 'testing-testing1)))))

(deftest fuzzy-generate-matchings-test
  (let [ns (the-ns 'user)]
    (try
      (is
       (= ["ritz.repl-utils.fuzzy-completion-test/testing-testing0"]
          (->>
           (#'sf/fuzzy-generate-matchings
            "ritz.repl-utils.fuzzy-completion-test/testing"
            ns
            (fn [] false))
           (map #(str (:ns-name %) "/" (name (:symbol %))))
           (filter #(re-find #"/test" %))))))))
