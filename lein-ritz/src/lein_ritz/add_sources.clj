(ns lein-ritz.add-sources
  (:require
   [leiningen.core.main :refer [debug]]
   [clojure.java.io :as io]))

(defn- source-jar
  "Return the path to the source jar if it exists."
  [path]
  (if-let [[p basename] (re-matches #"(.*).jar" path)]
    (let [file (io/file (str basename "-sources.jar"))]
      (debug "jar" path " source jar" (.getPath file))
      (when (.canRead file)
        (debug "adding source jar")
        (.getPath file)))))

(defn- classpath-with-source-jars
  "Try adding source code jars to the classpath"
  [classpath-elements]
  (concat
   classpath-elements
   (->>
    classpath-elements
    (map #(.getCanonicalPath (io/file %)))
    (map source-jar)
    (filter identity))))

(defn add-source-artifacts
  [f project]
  (classpath-with-source-jars (f project)))
