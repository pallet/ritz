(ns swank-clj.commands)

(defonce slime-fn-map {})

(defmacro ^{:indent 'defun} defslimefn
  [fname & body]
  `(alter-var-root #'slime-fn-map
                   assoc
                   (symbol "swank" ~(name fname))
                   (defn ~fname ~@body)))

(defn slime-fn [sym]
  (slime-fn-map (symbol "swank" (name sym))))
