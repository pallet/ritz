(ns ritz.nrepl.middleware.tracking-eval-test
  (:require
   clojure.tools.nrepl.middleware.session)
  (:use
   [clojure.java.io :only [file]]
   [clojure.test :only [deftest is testing]]
   [ritz.nrepl.middleware.tracking-eval
    :only [eval-reply configure-executor source-forms-reply]]
   [ritz.nrepl.middleware.test-transport :only [test-transport messages]]))

(deftest tracking-eval-test
  (testing "eval"
    (let [t (test-transport)
          session (#'clojure.tools.nrepl.middleware.session/create-session t)]
      (eval-reply {:transport t
                   :code "(let [x 1] x)"
                   :ns "ritz.nrepl.middleware.tracking-eval-test"
                   :session session}
                  (configure-executor))
      (Thread/sleep 1000)
      (is (= [{:value 1
               :ns "ritz.nrepl.middleware.tracking-eval-test"
               :session (:id (meta session))}
              {:status #{:done} :session (:id (meta session))}]
             (messages t))))))

(deftest source-form-test
  (testing "source-form"
    (let [t (test-transport)]
      (source-forms-reply {:transport t})
      (is (= [{:value '()} {:status #{:done}}]
             (messages t))))))
