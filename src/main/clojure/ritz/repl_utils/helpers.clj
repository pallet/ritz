(ns ritz.repl-utils.helpers
  "Some helpers")

(defn exception-causes
  "Create a sequence of throwable causes."
  [^Throwable t]
  (lazy-seq
    (cons t (when-let [cause (.getCause t)]
              (exception-causes cause)))))

(defn stack-trace-string
  [throwable]
  (with-out-str
    (with-open [out-writer (java.io.PrintWriter. *out*)]
      (.printStackTrace throwable out-writer))))

(defn symbol-name-for-var
  "Return the fully qualified name of the symbol the var was defined with"
  [v]
  (str (ns-name (:ns (meta v))) "/" (:name (meta v))))

(defn symbol-name-parts
  [symbol]
  [(namespace symbol) (name symbol)])
