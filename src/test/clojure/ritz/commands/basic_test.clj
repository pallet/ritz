(ns ritz.commands.basic-test
  (:use clojure.test)
  (:require
   [ritz.commands.basic :as basic]
   [ritz.logging :as logging]
   [ritz.test-utils :as test-utils]))

(deftest connection-info-test
  (test-utils/eval-for-emacs-test
   `(~'swank/connection-info)
   #"0000[abc][0-9a-f]\(:return \(:ok \(:pid \"\d+\" :style :spawn :lisp-implementation \(:type \"Clojure\" :name \"clojure\" :version \"1.[23].[0-1]([a-zA-Z-]+)?\"\) :package \(:name \"user\" :prompt \"user\"\) :version \"20101113\"\)\) 1\)"))

(deftest pprint-eval-test
  (test-utils/eval-for-emacs-test
   `(~'swank/pprint-eval "[1 2]")
   "000019(:return (:ok \"[1 2]\") 1)"))

(deftest briefly-describe-symbol-for-emacs-test
  (is
   (=
    [:designator "clojure.core/when"
     :macro (str "([test & body]) Evaluates test. If logical true,"
                 " evaluates body in an implicit do.")]
    (#'basic/briefly-describe-symbol-for-emacs #'clojure.core/when))))

(deftest apropos-list-for-emacs-test
  ;; (logging/set-level :trace)
  (test-utils/eval-for-emacs-test
   `(~'swank/apropos-list-for-emacs "shutdown-a")
   (str "00008e(:return (:ok ((:designator \"clojure.core/shutdown-agents\""
        " :function \"([]) Initiates a shutdown of the thread pools that"
        " back the agent\"))) 1)")
   {:ns 'clojure.core}))

(deftest describe-definition-for-emacs-test
  ;; (logging/set-level :trace)
  (test-utils/eval-for-emacs-test
   `(~'swank/describe-definition-for-emacs "clojure.core/when" :macro)
   (str "00009b(:return (:ok \"-------------------------\nclojure.core/when\n"
        "([test & body])\nMacro\n  Evaluates test. If logical true, evaluates"
        " body in an implicit do.\n\") 1)")
   {:ns 'clojure.core}))

(deftest list-all-package-names-test
  ;; (logging/set-level :trace)
  (test-utils/eval-for-emacs-test
   `(~'swank/list-all-package-names)
   #".*clojure.core.*"))

(deftest eval-and-grab-output-test
  ;; (logging/set-level :trace)
  (test-utils/eval-for-emacs-test
   `(~'swank/eval-and-grab-output "(println 1)2")
   "00001c(:return (:ok (\"1\n\" \"2\")) 1)"))

(deftest find-definitions-for-emacs-test
  ;; (logging/set-level :trace)
  (test-utils/eval-for-emacs-test
   `(~'swank/find-definitions-for-emacs "clojure.test/run-tests")
   #"000[0-9a-f]{3,3}\(:return \(:ok \(\(\"\(defn run-tests\)\" \(:location \(:zip \"[^\"]+\" \"clojure/test.clj\"\) \(:line \d+\) nil\)\)\)\) 1\)"))
