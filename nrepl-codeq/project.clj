(defproject ritz/ritz-nrepl-codeq "0.5.1-SNAPSHOT"
  :description "nREPL middleware for datom codeq"
  :url "https://github.com/pallet/ritz"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.nrepl "0.2.0-beta9"]
                 [com.datomic/datomic-free "0.8.3551"]
                 [datomic/codeq "0.1.0-SNAPSHOT"]
                 [ritz/ritz-nrepl-codeq "0.5.1-SNAPSHOT"]])
