(defproject ritz "0.3.0-SNAPSHOT"
  :description "Another swank server for clojure in SLIME"
  :source-path "src/main/clojure"
  :resources-path "src/main/resources"
  :test-path "src/test/clojure"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [useful "0.4.0"]
                 [org.clojure/data.codec "0.1.0"]]
  :dev-dependencies [[org.cloudhoist/pallet "0.6.7"]
                     [org.cloudhoist/automated-admin-user "0.6.0"]
                     [org.cloudhoist/git "0.5.0"]
                     [org.cloudhoist/java "0.5.1"]
                     [org.cloudhoist/pallet-lein "0.4.1"]
                     [org.slf4j/slf4j-api "1.6.1"]
                     [ch.qos.logback/logback-core "1.0.0"]
                     [ch.qos.logback/logback-classic "1.0.0"]
                     [vmfest "0.2.3"]
                     [org.clojure/clojure-contrib "1.2.0"]]
  :multi-deps {"1.2.0" [[org.clojure/clojure "1.2.0"]
                        [clojure-source "1.2.0"]]
               "1.2.1" [[org.clojure/clojure "1.2.1"]
                        [org.clojure/clojure "1.2.1" :classifier "sources"]]
               "1.3" [[org.clojure/clojure "1.3.0"]
                      [org.clojure/clojure "1.3.0" :classifier "sources"]]}
  :local-repo-classpath true
  :repositories
  {"sonatype-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"
   "sonatype" "https://oss.sonatype.org/content/repositories/releases/"})
