{:codox {:codox {:writer codox-md.writer/write-docs
                 :output-dir "../doc/0.4/debugger/api"}
         :dependencies [[codox-md "0.1.0"]]
         :pedantic :warn}
 :marginalia {:pedantic :warn
              :dir "../doc/0.4/debugger/source"}
 :release
 {:set-version
  {:updates [{:path "README.md"
              :no-snapshot true
              :search-regex #"ritz/ritz-debugger \"\d+\.\d+\.\d+\""}]}}}
