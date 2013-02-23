(ns ritz.repl-utils.namespaces
  "Namespace functions"
  (:use
   [clojure.set :only [difference union]])
  (:require
   ritz.repl-utils.core.defonce))

;;; Functions to flag and clear marked vars. Used to remove dead vars on
;;; load-file operations.
(defn mark-vars-with-meta
  [ns]
  (when (find-ns ns)
    (doseq [[_ v] (ns-interns ns)
            :let [m (meta v)]
            :when (not (:defonce m))]
      (alter-meta! v assoc ::marked true))))

(defn clear-marked-vars
  [ns]
  (doseq [[_ v] (ns-interns ns)
          :let [m (meta v)]
          :when (::marked m)]
    (ns-unmap (ns-name (:ns m)) (:name m))))

(defmacro with-var-clearing
  "Marks all vars in a namespace, executes the body, then removes all vars that
still have metadata."
  {:indent 1}
  [ns & body]
  `(let [ns# ~ns]
     (mark-vars-with-meta ns#)
     ~@body
     (clear-marked-vars ns#)))

;;; Namespace Dependencies
(defn package-dependencies
  "Returns the packages on which the namespace depends."
  [ns]
  (->>
   (ns-imports ns)
   vals
   (map
    ;; can throw if mixed pre/post 1.2.1 types are on the classpath due to
    ;; changes in defrecord and deftype mangling
    #(try (symbol (.getName (.getPackage ^Class %))) (catch Exception _)))
   distinct
   (filter identity)))

(defn refered-dependencies
  "Calculate the namespaces that are refered for a given namespace."
  [ns]
  (distinct (map (comp ns-name :ns meta val) (ns-refers ns))))

(defn aliased-dependencies
  "Calculate the namespaces that are aliased for a given namespace."
  [ns]
  (->> (ns-aliases ns) vals (map ns-name) distinct))

(defn namespace-dependencies
  "Calculate the namespaces that the given namespace depends on."
  [ns]
  (distinct
   (concat
    (refered-dependencies ns)
    (aliased-dependencies ns)
    (filter (set (map ns-name (all-ns))) (package-dependencies ns)))))

(defn direct-dependencies
  "Calculate namespace dependencies based on namespace information.
This reduces a map, from namespace symbol, to a set of symbols for the direct
namespace dependencies of that namespace."
  []
  (reduce
   (fn [dependencies ns]
     (assoc dependencies (ns-name ns) (set (namespace-dependencies ns))))
   {}
   (all-ns)))

(defn dependency-comparator
  "A comparator for dependencies in a dependency map"
  [dependency-map]
  (fn comparator [[ns-a deps-a] [ns-b deps-b]]
    (cond
      (when deps-a (deps-a ns-b)) 1
      (when deps-b (deps-b ns-a)) -1
      :else 0)))

(defn sorted-dependencies
  "Sort a dependency map so that all namespaces appear after the namespaces that
they depend on."
  [dependency-map]
  (sort-by identity (dependency-comparator dependency-map) dependency-map))

(defn transitive-dependencies
  "Given a direct dependencies, returns transitive dependencies"
  [direct-dependencies]
  (letfn [(dependencies [deps namespaces]
            (reduce
             (fn [all-deps ns]
               (union all-deps (deps ns)))
             #{}
             namespaces))]
    (reduce
     (fn [deps [ns direct-deps]]
       (assoc deps ns (union direct-deps (dependencies deps direct-deps))))
     {}
     (sorted-dependencies direct-dependencies))))

(defn dependent-on
  "Return the namespaces that are dependent on the given namespace symbol."
  [ns]
  (->>
   (transitive-dependencies (direct-dependencies))
   (filter #((val %) ns))
   (map key)))

(defn dependencies
  "Return the namespaces the given namespace has as dependencies."
  [ns]
  (->
   (transitive-dependencies (direct-dependencies))
   (get ns)))

;;; # Namespace modifications

(defn unuse
  "Remove all symbols from `from-ns` that have been refered from `ns`"
  ([ns from-ns]
     (doseq [[sym v] (ns-refers (ns-name from-ns))
             :let [m (meta v)]
             :when (and m (:ns m) (= ns (ns-name (:ns m))))]
       (ns-unmap from-ns sym)))
  ([ns] (unuse ns *ns*)))

(defn ns-remove
  "Remove the specified namespace, ensuring removal from core too"
  [ns]
  (remove-ns ns)
  (dosync
   (commute @#'clojure.core/*loaded-libs* disj ns)))

;;; # All namespace tracking and reset

(defn namespace-state
  "Returns namespace symbols for all loaded namespaces"
  []
  (map ns-name (all-ns)))

(defn namespaces-since
  "Return the namespaces since the given namespace state"
  [state]
  (difference (set (namespace-state)) (set state)))

(defn namespaces-reset
  "Reset the set of loaded namespaces to the given state."
  [state]
  (doseq [ns (namespaces-since state)]
    (ns-remove ns)))
