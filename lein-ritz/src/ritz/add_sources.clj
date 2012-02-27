(ns ritz.add-sources
  (:require
   [clojure.java.io :as io])
  (:use
   [robert.hooke :only [add-hook]]))

(defn- source-jar
  "Return the path to the source jar if it exists."
  [path]
  (if-let [[p basename] (re-matches #"(.*).jar" path)]
    (let [file (io/file (str basename "-sources.jar"))]
      (when (.canRead file)
        (.getPath file)))))

(defn- classpath-with-source-jars
  "Try adding source code jars to the classpath"
  [classpath-elements]
  (concat
   classpath-elements
   (->>
    classpath-elements
    (map source-jar)
    (filter identity))))

(defn add-source-artifacts
  [f project]
  (classpath-with-source-jars (f project)))

(add-hook #'leiningen.core.classpath/get-classpath add-source-artifacts)
