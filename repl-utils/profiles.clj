{:dev {:dependencies [[com.cemerick/pomegranate "0.0.13"
                       :exclusions [org.slf4j/slf4j-api org.clojure/clojure]]
                      [classlojure "0.6.6"
                       :exclusions [org.slf4j/slf4j-api org.clojure/clojure]]]}
 :codox {:codox {:writer codox-md.writer/write-docs
                 :output-dir "../doc/0.4/repl-utils/api"}
         :dependencies [[codox-md "0.1.0"]]
         :pedantic :warn}
 :marginalia {:pedantic :warn
              :dir "../doc/0.4/repl-utils/source"}
 :release
 {:set-version
  {:updates [{:path "README.md"
              :no-snapshot true
              :search-regex #"ritz/ritz-repl-utils \"\d+\.\d+\.\d+\""}]}}}
