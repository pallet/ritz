(ns swank-clj.hooks)

(defmacro defhook [name & hooks]
  `(defonce ~name (atom (list ~@hooks))))

;;;; Hooks
(defn add-hook
  [place function]
  (swap! place conj function))

(defn run-hook
  [functions & arguments]
  (doseq [f @functions]
    (apply f arguments)))
