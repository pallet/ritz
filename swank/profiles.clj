{:codox {:codox {:writer codox-md.writer/write-docs
                 :output-dir "../doc/0.4/swank/api"}
         :dependencies [[codox-md "0.1.0"]]
         :pedantic :warn}
 :marginalia {:pedantic :warn
              :dir "../doc/0.4/swank/source"}
 :release
 {:set-version
  {:updates [{:path "README.md"
              :no-snapshot true
              :search-regex #"lein-ritz \"\d+\.\d+\.\d+\""}
             {:path "elisp/slime-ritz.el"
              :no-snapshot true
              :search-regex #";; Version: \d+\.\d+\.\d+"}
             {:path "src/ritz/swank/project.clj"
              :search-regex
              #"ritz/ritz-swank \"\d+\.\d+\.\d+(-SNAPSHOT)?\""}]}}}
