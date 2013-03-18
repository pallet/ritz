{:dev {:dependencies [[org.clojure/clojure "1.4.0"]]}
 :release
 {:set-version
  {:updates [{:path "README.md" :no-snapshot true}
             {:path "src/leiningen/ritz.clj"
              :search-regex
              #"\"ritz.version\" \"\d+\.\d+\.\d+(-SNAPSHOT)?\""}
             {:path "src/leiningen/ritz.clj"
              :search-regex
              #"ritz-swank \"\d+\.\d+\.\d+(-SNAPSHOT)?\""}
             {:path "src/leiningen/ritz_nrepl.clj"
              :search-regex
              #"ritz-nrepl \"\d+\.\d+\.\d+(-SNAPSHOT)?\""}
             {:path "src/leiningen/ritz_nrepl.clj"
              :search-regex
              #"ritz-repl-utils \"\d+\.\d+\.\d+(-SNAPSHOT)?\""}
             {:path "src/leiningen/ritz_hornetq.clj"
              :search-regex
              #"ritz-nrepl-hornetq \"\d+\.\d+\.\d+(-SNAPSHOT)?\""}]}}}
