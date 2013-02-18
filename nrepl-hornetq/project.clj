(defproject ritz/ritz-nrepl-hornetq "0.7.1-SNAPSHOT"
  :description "nREPL transport for HornetQ"
  :url "http://github.com/ritz"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.nrepl "0.2.1"]
                 [cheshire "3.0.0"]
                 [hornetq-clj/client "0.2.0"]
                 [ritz/ritz-repl-utils "0.7.1-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[hornetq-clj/server "0.2.0-SNAPSHOT"]
                                  [clojure-complete "0.2.2"]
                                  [org.slf4j/jul-to-slf4j "1.6.4"]
                                  [ch.qos.logback/logback-classic "1.0.0"]]}})
