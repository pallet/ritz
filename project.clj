(defproject ritz "0.4.2"
  :description "Another swank server for clojure in SLIME"
  :url "https://github.com/pallet/ritz"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:url "git@github.com:pallet/ritz.git"}
  :sub ["repl-utils" "debugger" "nrepl" "swank" "lein-ritz"]
  :plugins [[lein-sub "0.2.2"]]
  :aliases {"clean" ["sub" "clean"]
            "install" ["sub" "install"]
            "deploy" ["sub" "deploy"]
            "test" ["sub" "with-profile" "default,jdk1.7" "test"]
            "doc" ["sub" "with-profile" "codox,jdk1.7" "doc"]
            "set-version" ["sub" "with-profile" "release" "set-version"]})
