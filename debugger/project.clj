(defproject ritz/ritz-debugger "0.7.1-SNAPSHOT"
  :description "Ritz debugger"
  :url "https://github.com/pallet/ritz"
  :scm {:url "git@github.com:pallet/ritz.git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[ritz/ritz-repl-utils "0.7.1-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[classlojure "0.6.6"]
                                  [bultitude "0.1.7"]]
                   :plugins [[lein-jdk-tools "0.1.0"]]}})
