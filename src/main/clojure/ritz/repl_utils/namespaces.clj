(ns ritz.repl-utils.namespaces
  "Namespace functions")

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
