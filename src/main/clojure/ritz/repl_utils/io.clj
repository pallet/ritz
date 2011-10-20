(ns ritz.repl-utils.io
  "io for reading clojure files")

(defn guess-namespace [^java.io.File file]
  (->>
   (reverse (.split (.getParent file) "/"))
   (reductions #(str %1 "." %2))
   (map symbol)
   (filter find-ns)
   first))

(defn- line-at-position [^java.io.File file position]
  (try
    (with-open [f (java.io.LineNumberReader. (java.io.FileReader. file))]
      (.skip f position)
      (.getLineNumber f))
    (catch Exception e 1)))

(defn read-position-line [file position]
  (if (number? position)
    (if (.isFile file)
      (line-at-position file  position)
      0)
    (when (list? position)
      (or
       (second (first (filter #(= :line (first %)) position)))
       (when-let [p (second (first (filter #(= :position (first %)) position)))]
         (line-at-position file p))))))
