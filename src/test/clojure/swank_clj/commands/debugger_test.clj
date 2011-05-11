(ns swank-clj.commands.debugger-test
  (:use clojure.test)
  (:require
   [swank-clj.commands.debugger :as debugger]
   [swank-clj.jpda.jdi :as jdi]
   [swank-clj.jpda.jdi-vm :as jdi-vm]
   [swank-clj.logging :as logging]
   [swank-clj.swank.utils :as utils]
   [swank-clj.test-utils :as test-utils]))

(deftest threads-test
  (let [context (jdi-vm/launch-vm
                 (jdi-vm/current-classpath)
                 `(do
                    (doto (Thread.
                           (fn [] (loop [] (Thread/sleep 1000) (recur))))
                      (.setName (str '~'debugger-test-thread))
                      (.start)))
                 :out *out*)]
    (.resume (:vm context))
    (Thread/sleep 1000)
    (let [connection (test-utils/eval-for-emacs-test
                      `(~'swank/list-threads)
                      #"000[0-9a-f]{3,3}\(:return \(:ok \(\(:id :name :state :at-breakpoint\? :suspended\? :suspends\).+\)\) 1\)"
                      {:vm-context (atom context)})
          threads (-> connection :vm-context deref :threads)
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
            threads (-> connection :vm-context deref :threads)]
        (is (seq threads))
        (is (nil? (first
                   (filter
                    #(= "debugger-test-thread" (second %))
                    threads))))))
    (jdi/shutdown (:vm context))))
