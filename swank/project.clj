(defproject ritz/ritz-swank "0.4.0-SNAPSHOT"
  :description "Swank server using ritz"
  :url "https://github.com/pallet/ritz"
  :scm {:url "git@github.com:pallet/ritz.git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/data.codec "0.1.0"
                  :exclusions [org.clojure/clojure]]
                 [org.clojure/tools.macro "0.1.1"
                  :exclusions [org.clojure/clojure]]
                 [ritz/ritz-debugger "0.4.0-SNAPSHOT"]])
