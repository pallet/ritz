(ns ritz.repl-utils.arglist
  "Arglists for symbols and sexps"
  (:require
   [ritz.swank.utils :as utils]
   [ritz.logging :as logging]
   [clojure.string :as string]))

(defn arglist
  "Given a keyword or symbol and a possibly nil namespace, return an
   arglist"
  [kw-or-symbol namespace]
  (cond
   (keyword? kw-or-symbol) '([map])
   (symbol? kw-or-symbol) (when-let [var (try
                                           (ns-resolve
                                            (or namespace *ns*) kw-or-symbol)
                                           (catch ClassNotFoundException _))]
                            (and var (:arglists (meta var))))
   :else nil))

(defn paths
  "Find all paths to leaves in the given tree"
  [tree]
  (if (seq? tree)
    (mapcat
     (fn [node]
       (map
        (fn [path] (conj path tree))
        (paths node)))
     tree)
    (list (list tree))))

;; (defn branch-for-terminal
;;   "Returns the branch path for a given unique terminal in an sexp."
;;   [raw terminal]
;;   (let [s
;;         (->>
;;          (reverse (tree-seq seq? seq raw))
;;          (drop-while #(not= :ritz/cursor-marker %))
;;          (drop-while #(not (seq? %))))]
;;     (take-while #(and (seq? %) (> (count %) 1)) s)))

(defn branch-for-terminal
  "Returns the (reversed) branch path for a given unique terminal in an sexp."
  [sexp terminal]
  (->>
   (paths sexp)
   (map reverse)
   (filter #(= terminal (first %)))
   first))

(defn indexed-sexps
  "Returns an sequence of [expr index] with the index of the argument of expr
   containing the specified terminal."
  [sexp terminal]
  (rest
   (reductions
    (fn [[sub-expr pos] expr]
      [expr (utils/position #(= sub-expr %) expr)])
    [terminal 0]
    (rest (branch-for-terminal sexp terminal)))))

(defn handle-apply [sym sexp index]
  (if (and (> index 1) (= sym "apply")
           (string? (first sexp)) (not (string/blank? (first sexp))))
    [(first sexp) (dec index)]
    [sym index]))

(defn arglist-at-terminal
  "Returns an arglist and an index for the position of the expression containing
   the given terminal."
  [sexp terminal ns]
  (logging/trace "arglist-at-terminal %s" terminal)
  (some
   (fn [[[sym & sexp] index]]
     (let [[sym index] (handle-apply sym sexp index)]
       (when (and (string? sym) (not (string/blank? sym)))
         (when-let [sym (try (read-string sym) (catch Exception _))]
           (when-let [arglist (arglist sym ns)]
             [arglist (dec index)])))))
   (indexed-sexps sexp terminal)))
