(defproject ritz "0.1.7-SNAPSHOT"
  :description "Another swank server for clojure in SLIME"
  :source-path "src/main/clojure"
  :resources-path "src/main/resources"
  :test-path "src/test/clojure"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [useful "0.4.0"]]
  :dev-dependencies [[org.clojure/clojure "1.2.1" :classifier "sources"]
                     [lein-multi "1.0.0"]]
  :multi-deps {"1.2.0" [[org.clojure/clojure "1.2.0"]
                        [clojure-source "1.2.0"]]
               "1.3" [[org.clojure/clojure "1.3.0-master-SNAPSHOT"]
                      [org.clojure/clojure "1.3.0-master-SNAPSHOT"
                       :classifier "sources"]]
               "1.3.0-alpha8" [[org.clojure/clojure "1.3.0-alpha8"]
                      [org.clojure/clojure "1.3.0-alpha8"
                       :classifier "sources"]]}
  :repositories
  {"sonatype-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"
   "sonatype" "https://oss.sonatype.org/content/repositories/releases/"}
  :tasks [cake.tasks.ritz])
