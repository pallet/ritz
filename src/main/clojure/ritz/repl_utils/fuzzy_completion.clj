;;; fuzzy symbol completion, Clojure implementation.

;; Original CL implementation authors (from swank-fuzzy.lisp) below,
;; Authors: Brian Downing <bdowning@lavos.net>
;;          Tobias C. Rittweiler <tcr@freebits.de>
;;          and others

;; This progam is based on the swank-fuzzy.lisp.
;; Thanks the CL implementation authors for that useful software.

(ns ritz.repl-utils.fuzzy-completion
  (:require
   [ritz.repl-utils.clojure :as clj]
   [ritz.repl-utils.helpers :as helpers]))

(def ^{:dynamic true} *fuzzy-recursion-soft-limit* 30)
(defn- compute-most-completions [short full]
  (let [collect-chunk (fn [[pcur [[pa va] ys]] [pb vb]]
                        (let [xs (if (= (dec pb) pcur)
                                   [[pa (str va vb)]]
                                   [[pb vb] [pa va]])]
                          [pb (if ys (conj xs ys) xs)]))
        step (fn step [short full pos chunk seed limit?]
               (cond
                 (and (empty? full) (not (empty? short)))
                 nil
                 (or (empty? short) limit?)
                 (if chunk
                   (conj seed
                         (second (reduce collect-chunk
                                         [(ffirst chunk) [(first chunk)]]
                                         (rest chunk))))
                   seed)
                 (= (first short) (first full))
                 (let [seed2
                       (step short (rest full) (inc pos) chunk seed
                             (< *fuzzy-recursion-soft-limit* (count seed)))]
                   (recur (rest short) (rest full) (inc pos)
                          (conj chunk [pos (str (first short))])
                          (if (and seed2 (not (empty? seed2)))
                            seed2
                            seed)
                          false))
                 :else
                 (recur short (rest full) (inc pos) chunk seed false)))]
    (map reverse (step short full 0 [] () false))))

(def ^{:dynamic true} *fuzzy-completion-symbol-prefixes* "*+-%&?<")
(def ^{:dynamic true} *fuzzy-completion-word-separators* "-/.")
(def ^{:dynamic true} *fuzzy-completion-symbol-suffixes* "*+->?!")
(defn- score-completion [completion short full]
  (let [find1
        (fn [c s]
          (re-find (re-pattern (java.util.regex.Pattern/quote (str c))) s))
        at-beginning? zero?
        after-prefix?
        (fn [pos]
          (and (= pos 1)
               (find1 (nth full 0) *fuzzy-completion-symbol-prefixes*)))
        word-separator?
        (fn [pos]
          (find1 (nth full pos) *fuzzy-completion-word-separators*))
        after-word-separator?
        (fn [pos]
          (find1 (nth full (dec pos)) *fuzzy-completion-word-separators*))
        at-end?
        (fn [pos]
          (= pos (dec (count full))))
        before-suffix?
        (fn [pos]
          (and (= pos (- (count full) 2))
               (find1 (nth full (dec (count full)))
                      *fuzzy-completion-symbol-suffixes*)))]
    (letfn [(score-or-percentage-of-previous
             [base-score pos chunk-pos]
             (if (zero? chunk-pos)
               base-score
               (max base-score
                    (+ (* (score-char (dec pos) (dec chunk-pos)) 0.85)
                       (Math/pow 1.2 chunk-pos)))))
            (score-char
             [pos chunk-pos]
             (score-or-percentage-of-previous
              (cond (at-beginning? pos)         10
                    (after-prefix? pos)         10
                    (word-separator? pos)       1
                    (after-word-separator? pos) 8
                    (at-end? pos)               6
                    (before-suffix? pos)        6
                    :else                       1)
              pos chunk-pos))
            (score-chunk
             [chunk]
             (let [chunk-len (count (second chunk))]
               (apply +
                      (map score-char
                           (take chunk-len (iterate inc (first chunk)))
                           (reverse (take chunk-len
                                          (iterate dec (dec chunk-len))))))))]
      (let [chunk-scores (map score-chunk completion)
            length-score (/ 10.0 (inc (- (count full) (count short))))]
        [(+ (apply + chunk-scores) length-score)
         (list (map list chunk-scores completion) length-score)]))))

(defn- compute-highest-scoring-completion [short full]
  (let [scored-results
        (map (fn [result]
               [(first (score-completion result short full))
                result])
             (compute-most-completions short full))
        winner (first (sort (fn [[av _] [bv _]] (> av bv))
                            scored-results))]
    [(second winner) (first winner)]))

(defrecord FuzzyMatching
    [var ns symbol ns-name score ns-chunks var-chunks])

(defn fuzzy-extract-matching-info [matching string]
  (let [[user-ns-name _] (helpers/symbol-name-parts (symbol string))]
    (cond
      (:var matching)
      [(str (:symbol matching))
       (cond (nil? user-ns-name) nil
             :else (:ns-name matching))]
      :else
      [""
       (str (:symbol matching))])))

(defn- fuzzy-find-matching-vars
  [string ns var-filter external-only?]
  (let [compute (partial compute-highest-scoring-completion string)
        ns-maps (cond
                  external-only? ns-publics
                  (= ns *ns*)    ns-map
                  :else          ns-interns)]
    (map (fn [[match-result score var sym]]
           (if (var? var)
             (FuzzyMatching.
              var nil (or (:name (meta var)) (symbol (pr-str var)))
              nil score nil match-result)
             (FuzzyMatching. nil nil sym nil score nil match-result)))
         (filter (fn [[match-result & _]]
                   (or (= string "")
                       (not-empty match-result)))
                 (map (fn [[k v]]
                        (if (= string "")
                          (conj [nil 0.0] v k)
                          (conj (compute (.toLowerCase (str k))) v k)))
                      (filter var-filter (seq (ns-maps ns))))))))

(defn- fuzzy-find-matching-nss
  [string]
  (let [compute (partial compute-highest-scoring-completion string)]
    (->>
     (concat
      (map (fn [ns] [(symbol (str ns)) ns]) (all-ns))
      (ns-aliases *ns*))
     (map (fn [[ns-sym ns]] (conj (compute (str ns-sym)) ns ns-sym)))
     (filter (fn [[match-result & _]] (not-empty match-result)))
     (map (fn [[match-result score ns ns-sym]]
            (FuzzyMatching.
             nil ns ns-sym (str ns-sym) score match-result nil))))))

(defn fuzzy-generate-matchings
  [string default-ns timed-out?]
  (let [take* (partial take-while (fn [_] (not (timed-out?))))
        [parsed-ns-name parsed-symbol-name] (helpers/symbol-name-parts
                                             (symbol string))
        find-vars
        (fn find-vars
          ([designator ns]
             (find-vars designator ns identity))
          ([designator ns var-filter]
             (find-vars designator ns var-filter nil))
          ([designator ns var-filter external-only?]
             (take* (fuzzy-find-matching-vars
                     designator ns var-filter external-only?))))
        find-nss (comp take* fuzzy-find-matching-nss)
        make-duplicate-var-filter
        (fn [fuzzy-ns-matchings]
          (let [nss (set (map :ns-name fuzzy-ns-matchings))]
            (comp not nss str :ns meta second)))
        matching-greater
        (fn [a b]
          (cond
            (> (:score a) (:score b)) -1
            (< (:score a) (:score b)) 1
            :else (compare (:symbol a) (:symbol b))))
        fix-up
        (fn [matchings parent-package-matching]
          (map (fn [m]
                 (assoc m
                   :ns-name (:ns-name parent-package-matching)
                   :ns-chunks (:ns-chunks parent-package-matching)
                   :score (if (= parsed-ns-name "")
                            (/ (:score parent-package-matching) 100)
                            (+ (:score parent-package-matching)
                               (:score m)))))
               matchings))]
    (sort matching-greater
          (cond
            (nil? parsed-ns-name)
            (concat
             (find-vars parsed-symbol-name (the-ns default-ns))
             (find-nss parsed-symbol-name))
            ;; (apply concat
            ;;        (let [ns *ns*]
            ;;          (pcalls #(binding [*ns* ns]
            ;;                     (find-vars parsed-symbol-name
            ;;                                (maybe-ns default-ns)))
            ;;                  #(binding [*ns* ns]
            ;;                     (find-nss parsed-symbol-name)))))

            (= "" parsed-ns-name)
            (find-vars parsed-symbol-name (the-ns default-ns))

            :else
            (let [found-nss (find-nss parsed-ns-name)
                  find-vars1 (fn [ns-matching]
                               (fix-up
                                (find-vars
                                 parsed-symbol-name
                                 (:ns ns-matching)
                                 (make-duplicate-var-filter
                                  (filter
                                   #(= ns-matching (:ns-name %))
                                   found-nss))
                                 true)
                                ns-matching))]
              (concat
               (apply concat
                      (map find-vars1 (sort matching-greater found-nss)))
               found-nss))))))
