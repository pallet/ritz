(defproject ritz "0.2.1-SNAPSHOT"
  :description "Another swank server for clojure in SLIME"
  :source-path "src/main/clojure"
  :resources-path "src/main/resources"
  :test-path "src/test/clojure"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [useful "0.4.0"]
                 [org.clojure/data.codec "0.1.0"]]
  :dev-dependencies [[lein-multi "1.0.0"]
                     [org.cloudhoist/pallet "0.7.0-SNAPSHOT"]
                     [org.cloudhoist/stevedore "0.7.0"]
                     [org.cloudhoist/git "0.7.0-SNAPSHOT"]
                     [org.cloudhoist/java "0.7.0-SNAPSHOT"]
                     [org.cloudhoist/pallet-lein "0.4.2-SNAPSHOT"]
                     [org.slf4j/slf4j-api "1.6.1"]
                     [ch.qos.logback/logback-core "0.9.29"]
                     [ch.qos.logback/logback-classic "0.9.29"]
                     [vmfest "0.2.3"]]
  :multi-deps {"1.2.0" [[org.clojure/clojure "1.2.0"]
                        [clojure-source "1.2.0"]]
               "1.2.1" [[org.clojure/clojure "1.2.1"]
                        [org.clojure/clojure "1.2.1" :classifier "sources"]]
               "1.3" [[org.clojure/clojure "1.3.0"]
                      [org.clojure/clojure "1.3.0" :classifier "sources"]]}
  :local-repo-classpath true
  :repositories
  {"sonatype-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"
   "sonatype" "https://oss.sonatype.org/content/repositories/releases/"}
  :tasks [cake.tasks.ritz])
