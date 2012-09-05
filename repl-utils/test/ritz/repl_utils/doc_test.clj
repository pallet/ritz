(ns ritz.repl-utils.doc-test
  (:use
   clojure.test)
  (:require
   [ritz.repl-utils.doc :as doc])
  (:import
   java.io.File))

(deftest describe-test
  (is (= {:symbol-name "clojure.core/shutdown-agents"
          :type :function
          :arglists "([])"
          :doc (:doc (meta #'shutdown-agents))}
          (doc/describe #'shutdown-agents)))
  (is (= {:symbol-name "clojure.core/when"
          :type :macro
          :arglists "([test & body])"
          :doc (:doc (meta #'when))}
         (doc/describe #'when))))

(deftest apropos-list-test
  (is (= [#'clojure.core/shutdown-agents]
         (doc/apropos-list
          (the-ns 'clojure.core) "shutdown" true true (the-ns 'clojure.core))))
  (testing "nil package"
    (is (= [#'clojure.core/shutdown-agents]
             (doc/apropos-list
              nil "shutdown-a" nil nil (the-ns 'ritz.repl-utils.doc)))))
  (testing "case insensitive"
    (is (= [#'clojure.core/shutdown-agents]
             (doc/apropos-list
              (the-ns 'clojure.core)
              "SHUTDOWN" true false (the-ns 'clojure.core)))))
  (testing "case sensitive"
    (is (= []
             (doc/apropos-list
              (the-ns 'clojure.core)
              "SHUTDOWN" true true (the-ns 'clojure.core)))))
  (testing "non public"
    (is (= []
             (doc/apropos-list
              (the-ns 'clojure.core)
              "is-annotation?" true true (the-ns 'clojure.core))))
    (is (= [#'clojure.core/is-annotation?]
             (doc/apropos-list
              (the-ns 'clojure.core)
              "is-annotation?" false true (the-ns 'clojure.core))))))

(deftest javadoc-url-test
  (is (re-matches
       #"http://java.sun.com/javase/[678]/docs/api/java/io/File.html"
       (doc/javadoc-url "java.io.File" "")))
  (is (re-matches
       #"http://java.sun.com/javase/[678]/docs/api/java/io/File.html"
       (doc/javadoc-url "java.io.File." "")))
  (is (re-matches
       #"http://java.sun.com/javase/[678]/docs/api/java/io/File.html"
       (doc/javadoc-url "File" "ritz.repl-utils.doc-test")))
    (is (=
         "http://www.google.com/search?q=%2Bjavadoc+NoSuchClassName"
         (doc/javadoc-url "NoSuchClassName" "ritz.repl-utils.doc-test"))))
