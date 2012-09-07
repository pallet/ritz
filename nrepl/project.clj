(defproject ritz/ritz-nrepl "0.4.1"
  :description "nREPL server using ritz"
  :url "https://github.com/pallet/ritz"
  :scm {:url "git@github.com:pallet/ritz.git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/tools.nrepl "0.2.0-beta9"
                  :exclusions [org.clojure/clojure]]
                 [ritz/ritz-debugger "0.4.1"]]
  :profiles
  {:dev {:repl-options
         {:nrepl-middleware
          ['ritz.nrepl.middleware.simple-complete/wrap-simple-complete]}}})
