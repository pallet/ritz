(defproject ritz "0.7.1-SNAPSHOT"
  :description "Another swank server for clojure in SLIME"
  :url "https://github.com/pallet/ritz"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:url "git@github.com:pallet/ritz.git"}
  :sub ["repl-utils" "debugger" "nrepl-middleware" "nrepl-core" "nrepl"
        "nrepl-hornetq" "swank" "lein-ritz" "nrepl-codeq" "nrepl-project"]
  :plugins [[lein-sub "0.2.3"]]
  :aliases {"clean" ["sub" "clean"]
            "install" ["sub" "install"]
            "deploy" ["sub" "deploy"]
            "test" ["sub" "test"]
            "doc" ["sub" "with-profile" "codox,jdk1.7" "doc"]
            "set-sub-version" ["sub" "with-profile" "release" "set-version"]})
