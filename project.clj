(defproject swank-clj "0.1.0-SNAPSHOT"
  :description "Another swank for clojure"
  :source-path "src/main/clojure"
  :resources-path "src/main/resources"
  :test-path "src/test/clojure"
  :aot [swank-clj.main]
  :dependencies [[org.clojure/clojure "1.2.0"]]
  :dev-dependencies [[swank-clojure "1.2.1"]
                     [lein-swank-clj "1.0.0-SNAPSHOT"]
                     [clojure-source "1.2.0"]])
