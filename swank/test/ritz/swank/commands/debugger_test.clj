(ns ritz.swank.commands.debugger-test
  (:use clojure.test)
  (:require
   [ritz.jpda.jdi :as jdi]
   [ritz.jpda.jdi-clj :as jdi-clj]
   [ritz.jpda.jdi-vm :as jdi-vm]
   [ritz.logging :as logging]
   [ritz.repl-utils.utils :as utils]
   [ritz.swank.commands.debugger :as debugger]
   [ritz.swank.test-utils :as test-utils]))

(deftest threads-test
  (let [context (jdi-vm/launch-vm
                 (jdi-vm/current-classpath)
                 `(do
                    (doto (Thread.
                           (fn [] (loop [] (Thread/sleep 1000) (recur))))
                      (.setName (str '~'debugger-test-thread))
                      (.start)))
                 {:out *out*})
        context (jdi-clj/vm-rt context)]
    (.resume (:vm context))
    (Thread/sleep 1000)
    (let [connection (test-utils/eval-for-emacs-test
                      `(~'swank/list-threads)
                      #"000[0-9a-f]{3,3}\(:return \(:ok \(\(:id :name :state :at-breakpoint\? :suspended\? :suspends\).+\)\) 1\)"
                      {:vm-context context})
          threads (-> connection :debug deref :thread-list)
          thread (first
                  (filter
                   #(re-find #"debugger-test-thread" (:name %))
                   threads))]
      (is (seq threads))
      (is thread)
      (let [connection (test-utils/eval-for-emacs-test
                        `(~'swank/kill-nth-thread
                          ~(utils/position #{thread} threads))
                        #"000[0-9a-f]{3,3}\(:return \(:ok .+"
                        (dissoc connection :writer))
            threads (-> connection :debug deref :thread-list)]
        (is (seq threads))
        (is (nil? (first
                   (filter
                    #(= "debugger-test-thread" (second %))
                    threads))))))
    (jdi/shutdown context)))
