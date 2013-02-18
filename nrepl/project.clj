(defproject ritz/ritz-nrepl "0.7.0"
  :description "nREPL server using ritz"
  :url "https://github.com/pallet/ritz"
  :scm {:url "git@github.com:pallet/ritz.git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.nrepl "0.2.1"
                  :exclusions [org.clojure/clojure]]
                 [ritz/ritz-debugger "0.7.0"]
                 [ritz/ritz-nrepl-middleware "0.7.0"]])
