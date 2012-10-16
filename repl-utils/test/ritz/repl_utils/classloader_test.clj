(ns ritz.repl-utils.classloader-test
  (:use
   clojure.test
   ritz.repl-utils.classloader
   [cemerick.pomegranate.aether :only [dependency-files resolve-dependencies]]))

(def repositories {"clojars" "https://clojars.org/repo/"
                   "central" "http://repo1.maven.org/maven2/"})
(deftest classloader-test
  (let [classpath (dependency-files
                   (resolve-dependencies
                    :repositories repositories
                    :coordinates '[[org.clojure/clojure "1.4.0"]]
                    :retrieve true))
        {:keys [cl]} (classloader classpath nil nil nil)]
    (is (= 1 (eval-clojure-in cl 1)))
    (is (= "1.4.0" (eval-clojure-in cl `(clojure-version))))))

(deftest eval-clojure-test
  (testing "1.4"
    (let [classpath (dependency-files
                     (resolve-dependencies
                      :repositories repositories
                      :coordinates '[[org.clojure/clojure "1.4.0"]]
                      :retrieve true))
          {:keys [cl]} (set-classpath! classpath)]
      (is (= 1 (eval-clojure 1)))
      (is (= "1.4.0" (eval-clojure `(clojure-version))))))
  (testing "replace 1.4 with 1.3"
    (let [classpath (dependency-files
                     (resolve-dependencies
                      :repositories repositories
                      :coordinates '[[org.clojure/clojure "1.3.0"]]
                      :retrieve true))
          {:keys [cl]} (set-classpath! classpath)]
      (is (= 1 (eval-clojure 1)))
      (is (= "1.3.0" (eval-clojure `(clojure-version)))))))
