(ns swank-clj.repl-utils.doc
  "Documentation utils"
  (:refer-clojure :exclude [print-doc]))

(defn- print-doc*
  "Replacement for clojure.core/print-doc"
  [m]
  (println "-------------------------")
  (println (str (when-let [ns (:ns m)] (str (ns-name ns) "/")) (:name m)))
  (cond
   (:forms m) (doseq [f (:forms m)]
                (print "  ")
                (prn f))
   (:arglists m) (prn (:arglists m)))
  (if (:special-form m)
    (do
      (println "Special Form")
      (println " " (:doc m))
      (if (contains? m :url)
        (when (:url m)
          (println (str "\n  Please see http://clojure.org/" (:url m))))
        (println (str "\n  Please see http://clojure.org/special_forms#"
                      (:name m)))))
    (do
      (when (:macro m)
        (println "Macro"))
      (println " " (:doc m)))))

(def print-doc
  (let [print-doc (resolve 'clojure.core/print-doc)]
    (if (or (nil? print-doc) (-> print-doc meta :private))
      (comp print-doc* meta)
      print-doc)))

(defn doc-string
  "Return a string with a var's formatted documentation"
  [var]
  (with-out-str (print-doc var)))

(defn describe
  "Describe a var"
  [var]
  (let [m (meta var)]
    {:symbol-name (str (ns-name (:ns m)) "/" (:name m))
     :type (cond
            (:macro m) :macro
            (:arglists m) :function
            :else :variable)
     :arglists (str (:arglists m))
     :doc (:doc m)}))


(defn- make-apropos-matcher [pattern case-sensitive?]
  (let [pattern (java.util.regex.Pattern/quote pattern)
        pat (re-pattern (if case-sensitive?
                          pattern
                          (format "(?i:%s)" pattern)))]
    (fn [var] (re-find pat (pr-str var)))))

(defn- apropos-symbols [string ns public-only? case-sensitive?]
  (let [ns (if ns [ns] (all-ns))
        matcher (make-apropos-matcher string case-sensitive?)
        lister (if public-only? ns-publics ns-interns)]
    (filter matcher
            (apply concat (map (comp (partial map second) lister) ns)))))

(defn- present-symbol-before
  "Comparator such that x belongs before y in a printed summary of symbols.
Sorted alphabetically by namespace name and then symbol name, except
that symbols accessible in the current namespace go first."
  [ns x y]
  (let [accessible?
        (fn [var] (= (ns-resolve ns (:name (meta var)))
                     var))
        ax (accessible? x) ay (accessible? y)]
    (cond
     (and ax ay) (compare (:name (meta x)) (:name (meta y)))
     ax -1
     ay 1
     :else (let [nx (str (:ns (meta x))) ny (str (:ns (meta y)))]
             (if (= nx ny)
               (compare (:name (meta x)) (:name (meta y)))
               (compare nx ny))))))

(defn apropos-list
  "Find a list of matching symbols for name, restricted to ns if non-nil,
   prefering symbols accessible from prefer-ns."
  [ns name public-only? case-sensitive? prefer-ns]
  (sort
   #(present-symbol-before prefer-ns %1 %2)
   (apropos-symbols name ns public-only? case-sensitive?)))

(defn arglist
  "Given a keyword or symbol and a possibly nil namespace, return an
   arglist"
  [kw-or-symbol namespace]
  (cond
   (keyword? kw-or-symbol) "([map])"
   (symbol? kw-or-symbol) (let [var (ns-resolve
                                     (or namespace *ns*) kw-or-symbol)]
                            (when-let [args (and var (:arglists (meta var)))]
                              (pr-str args)))
   :else nil))
