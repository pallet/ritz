(ns ritz.nrepl.middleware
  "Functions used across various middleware")

(defmulti transform-value "Transform a value for output" type)

(defmethod transform-value :default [v] v)

(defmethod transform-value clojure.lang.PersistentVector
  [v]
  (list* v))

(defn args-for-map
  "Return a value list based on a map. The keys are converted to strings."
  [m]
  (list* (mapcat #(vector (name (key %)) (transform-value (val %))) m)))

(defn read-when
  "Read from the string passed if it is not nil"
  [s]
  (when s (read-string s)))
