(ns lein-ritz.plugin-helpers
  "Helpers for use in the lein plugins"
  (:require
   [clojure.java.io :refer [file]]))

;;; Dependencies used in vms and classloaders
(def lein-profile
  {:dependencies '[[leiningen "2.0.0"
                    ;; these are bits of lein we are not interested in
                    :exclusions [org.clojure/clojure
                                 reply
                                 clj-http
                                 org.apache.maven.indexer/indexer-core
                                 org.clojure/data.xml]]]})

(def classlojure-profile {:dependencies '[[classlojure "0.6.6"]]})
(def clojure-profile {:dependencies '[[org.clojure/clojure "1.4.0"]]})

;;; Locate JDK extra jars
(defn jpda-jars
  []
  (let [libdir (file (System/getProperty "java.home") ".." "lib")]
    (for [j ["tools.jar" "sa-jdi.jar"]
          :when (.exists (file libdir j))]
      (.getCanonicalPath (file libdir j)))))

(defn add-jpda-jars
  "JPDA is in the JDK's tools.jar and sa-jdi.jar. Add them to the classpath."
  [f project]
  (concat (f project) (jpda-jars)))
