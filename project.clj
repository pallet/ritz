(defproject swank-clj "0.1.6"
  :description "Another swank for clojure"
  :source-path "src/main/clojure"
  :resources-path "src/main/resources"
  :test-path "src/test/clojure"
  ;; :aot [swank-clj.main]
  :dependencies [[org.clojure/clojure "1.2.0"]]
  :dev-dependencies [[swank-clojure "1.2.1"]
                     [lein-swank-clj "1.0.0-SNAPSHOT"]
                     [clojure-source "1.2.0"]]
  :repositories
  {"sonatype-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"
   "sonatype" "https://oss.sonatype.org/content/repositories/releases/"}
  :tasks [cake.tasks.swank-clj])
