(ns lein-ritz.plugin
  "Define middleware for lein2"
  (:require
   [lein-ritz.add-sources :refer [add-source-artifacts]]
   [lein-ritz.plugin-helpers :refer [add-jpda-jars]]
   [robert.hooke :refer [add-hook]]))

(defn hooks []
  (add-hook #'leiningen.core.classpath/get-classpath add-jpda-jars)
  (add-hook #'leiningen.core.classpath/get-classpath add-source-artifacts))
