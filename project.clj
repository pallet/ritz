(defproject ritz "0.1.8-SNAPSHOT"
  :description "Another swank server for clojure in SLIME"
  :source-path "src/main/clojure"
  :resources-path "src/main/resources"
  :test-path "src/test/clojure"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [useful "0.4.0"]]
  :dev-dependencies [[org.clojure/clojure "1.2.1" :classifier "sources"]
                     [lein-multi "1.0.0"]
                     [org.cloudhoist/pallet "0.6.3-SNAPSHOT"]
                     [org.cloudhoist/stevedore "0.6.1-SNAPSHOT"]
                     [org.cloudhoist/automated-admin-user "0.6.0-SNAPSHOT"]
                     [org.cloudhoist/git "0.5.0"]
                     [org.cloudhoist/java "0.5.1"]
                     [org.cloudhoist/pallet-lein "0.4.2-SNAPSHOT"]
                     [org.slf4j/slf4j-api "1.6.1"]
                     [ch.qos.logback/logback-core "0.9.28"]
                     [ch.qos.logback/logback-classic "0.9.28"]
                     [vmfest "0.2.3"]]
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
