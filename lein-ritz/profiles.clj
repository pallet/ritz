{:release
 {:set-version
  {:updates [{:path "README.md" :no-snapshot true}
             {:path "src/leiningen/ritz.clj"
              :search-regex
              #"\"ritz.version\" \"\d+\.\d+\.\d+(-SNAPSHOT)?\""}]}}}
