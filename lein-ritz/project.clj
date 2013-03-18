(defproject lein-ritz "0.7.1-SNAPSHOT"
  :description "A Leiningen plugin for launching a ritz swank server for SLIME."
  :dependencies [[org.clojure/tools.cli "0.2.2"
                  :exclusions [org.clojure/clojure]]
                 [org.clojure/tools.nrepl "0.2.1"
                  :exclusions [org.clojure/clojure]]]
  :eval-in-leiningen true)
