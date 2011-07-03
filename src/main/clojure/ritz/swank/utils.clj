(ns ritz.swank.utils
  "Utils for swank implementation")

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
