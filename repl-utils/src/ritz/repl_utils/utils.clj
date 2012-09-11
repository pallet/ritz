(ns ritz.repl-utils.utils
  "Utils")

(defn maybe-ns [package]
  "Try to turn package into a namespace"
  (cond
   (symbol? package) (or (find-ns package) (maybe-ns 'user))
   (string? package) (maybe-ns (symbol package))
   (keyword? package) (maybe-ns (name package))
   (instance? clojure.lang.Namespace package) package
   :else (maybe-ns 'user)))

(defn ^Integer position
  "Finds the first position of an item that matches a given predicate
   within col. Returns nil if not found. Optionally provide a start
   offset to search from."
  ([pred coll] (position pred coll 0))
  ([pred coll start]
     (loop [coll (drop start coll), i start]
       (when (seq coll)
         (if (pred (first coll))
           i
           (recur (rest coll) (inc i)))))))


;; based on flatland's useful.ns/alias-var
(defn alias-var
  "Create a var with the supplied name in the current namespace, having the same
metadata and root-binding as the supplied var."
  [name ^clojure.lang.Var var]
  (let [v (when (.hasRoot var) [@var])]
    (apply intern *ns* (with-meta name (merge (meta var) (meta name))) v)))

(defn alias-var-once
  "Create a var with the supplied name in the current namespace, having the same
metadata and root-binding as the supplied var."
  [name ^clojure.lang.Var var]
  (when-not (:ritz/redefed (meta var))
    (alias-var (vary-meta name assoc :defonce true) var)))
