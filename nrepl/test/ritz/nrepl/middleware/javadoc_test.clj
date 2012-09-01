(ns ritz.nrepl.middleware.javadoc-test
  (:use
   [clojure.java.io :only [file]]
   [clojure.test :only [deftest is testing]]
   [ritz.nrepl.middleware.javadoc :only [javadoc-reply]]
   [ritz.nrepl.middleware.test-transport :only [test-transport messages]]))

(deftest javadoc-test
  (dosync (commute @#'clojure.java.javadoc/*local-javadocs* (constantly nil)))
  (testing "no match"
    (let [t (test-transport)]
      (javadoc-reply {:transport t :symbol "String"})
      (is (= [{:value "http://www.google.com/search?q=%2Bjavadoc+String"}
              {:status #{:done}}]
             (messages t)))))
  (testing "found"
    (let [t (test-transport)]
      (javadoc-reply {:transport t :symbol "java.lang.String"})
      (is (= [{:value
               "http://java.sun.com/javase/6/docs/api/java/lang/String.html"}
              {:status #{:done}}]
             (messages t)))))
  (testing "resolve"
    (let [t (test-transport)]
      (javadoc-reply {:transport t
                      :symbol "String" :ns "ritz.nrepl.middleware.javadoc"})
      (is (= [{:value
               "http://java.sun.com/javase/6/docs/api/java/lang/String.html"}
              {:status #{:done}}]
             (messages t)))))
  (testing "local-paths"
    (let [t (test-transport)
          doc (file (System/getProperty "user.dir")
                    "javadoc/java/lang/String.html")]
      (javadoc-reply {:transport t :local-paths "javadoc" :symbol "String"})
      (is (= [{:value (str "file:" doc)}
              {:status #{:done}}]
             (messages t))))))
