(defproject ritz/ritz-nrepl-middleware "0.7.1-SNAPSHOT"
  :description "nREPL middleware"
  :url "https://github.com/pallet/ritz"
  :scm {:url "git@github.com:pallet/ritz.git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[ritz/ritz-repl-utils "0.7.1-SNAPSHOT"]
                 [org.clojure/tools.nrepl "0.2.1"
                  :exclusions [org.clojure/clojure]]])
