(ns ritz.commands.contrib.ritz-test
  (:use
   [ritz.logging :as logging]
   [ritz.commands.contrib.ritz :as sc]
   [ritz.jpda.jdi-vm :as jdi-vm]
   clojure.test)
  (:require
   [ritz.test-utils :as test-utils]))

(def file *file*)

(deftest breakpoint-test
  (let [context (jdi-vm/launch-vm
                 (jdi-vm/current-classpath)
                 `(do
                    (require '~'ritz.commands.contrib.ritz-test)
                    (println (str '~'hi)))
                 :out *out*)]
    (Thread/sleep 1000)
    (->>
     {:vm-context (atom context)}
     (test-utils/eval-for-emacs-test
      `(~'swank/list-breakpoints)
      "00002e(:return (:ok ((:id :file :line :enabled))) 1)")
     ;; (test-utils/eval-for-emacs-test
     ;;  `(~'swank/line-breakpoint
     ;;    "ritz.commands.contrib.ritz-test"
     ;;    ~file
     ;;    12)
     ;;  "000025(:return (:ok \"Set 1 breakpoints\") 1)")
     (test-utils/eval-for-emacs-test
      `(~'swank/list-breakpoints)
      "00002e(:return (:ok ((:id :file :line :enabled))) 1)")
     (test-utils/eval-for-emacs-test
      `(~'swank/breakpoint-disable 0)
      "000015(:return (:ok nil) 1)")
     (test-utils/eval-for-emacs-test
      `(~'swank/list-breakpoints)
      "00002e(:return (:ok ((:id :file :line :enabled))) 1)")
     (test-utils/eval-for-emacs-test
      `(~'swank/breakpoint-enable 0)
      "000015(:return (:ok nil) 1)")
     (test-utils/eval-for-emacs-test
      `(~'swank/list-breakpoints)
      "00002e(:return (:ok ((:id :file :line :enabled))) 1)")
     (test-utils/eval-for-emacs-test
      `(~'swank/breakpoint-kill 0)
      "000015(:return (:ok nil) 1)")
     (test-utils/eval-for-emacs-test
      `(~'swank/list-breakpoints)
      "00002e(:return (:ok ((:id :file :line :enabled))) 1)"))))
