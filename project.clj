(defproject ritz "0.4.0-SNAPSHOT"
  :description "Another swank server for clojure in SLIME"
  :url "https://github.com/pallet/ritz"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:url "git@github.com:pallet/ritz.git"}
  :sub ["repl-utils" "debugger" "nrepl" "swank" "lein-ritz"]
  :plugins [[lein-sub "0.2.1"]])
