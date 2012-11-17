{:dev {:dependencies [[com.cemerick/pomegranate "0.0.13"
                       :exclusions [org.slf4j/slf4j-api org.clojure/clojure]]
                      [classlojure "0.6.6"
                       :exclusions [org.slf4j/slf4j-api org.clojure/clojure]]]
       :repl-options
       {:nrepl-middleware
        [ritz.nrepl.middleware.codeq/wrap-codeq-def]}}}
 :codox {:codox {:writer codox-md.writer/write-docs
                 :output-dir "../doc/0.5/nrepl-codeq/api"}
         :dependencies [[codox-md "0.1.0"]]
         :pedantic :warn}
 :marginalia {:pedantic :warn
              :dir "../doc/0.5/nrepl-codeq/source"}
 :release
 {:set-version
  {:updates [{:path "README.md"
              :no-snapshot true
              :search-regex #"ritz/ritz-nrepl-codeq \"\d+\.\d+\.\d+\""}]}}}
