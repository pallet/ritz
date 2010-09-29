(ns swank-clj.clj-contrib.pprint)

(def
 ^{:private true}
 pprint-enabled?
 (try
  ;; 1.0, 1.1
  (do
    (.loadClass (clojure.lang.RT/baseLoader) "clojure.contrib.pprint.PrettyWriter")
    (require '[clojure.contrib.pprint :as pp])
    (defmacro pretty-pr-code*
      ([code]
         (if pprint-enabled?
           `(binding [pp/*print-suppress-namespaces* true]
              (pp/with-pprint-dispatch pp/*code-dispatch* (pp/write ~code :pretty true :stream nil)))
           `(pr-str ~code)))
      {:private true})
    true)
  (catch Exception e
    (try
     ;; 1.2
     (do
       (.getResource (clojure.lang.RT/baseLoader) "clojure/pprint")
       (require '[clojure.pprint :as pp])
       (defmacro pretty-pr-code*
         ([code]
            (if pprint-enabled?
              `(binding [pp/*print-suppress-namespaces* true]
                 (pp/with-pprint-dispatch pp/code-dispatch (pp/write ~code :pretty true :stream nil)))
              `(pr-str ~code)))
         {:private true})
       true)
     (catch Exception e
       (println e))))))

(defn pretty-pr-code [code]
  (pretty-pr-code* code))
