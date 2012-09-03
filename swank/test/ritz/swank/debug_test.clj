(ns ritz.swank.debug-test
  (:require
   [ritz.logging :as logging]
   [ritz.swank.debug :as debug]
   [clojure.string :as string])
  (:use clojure.test))

(deftest vm-swank-main-test
  (is (re-matches
       #"\(try \(clojure.core/require \(quote ritz.swank.socket-server\)\) \(\(clojure.core/resolve \(quote ritz.swank.socket-server/start\)\) \{:a 1\}\) \(catch java.lang.Exception e__\d+__auto__ \(clojure.core/spit \(clojure.core/str \(clojure.core/name \(quote ritz.swank.debug/ritz-startup-error\)\)\) e__\d+__auto__\) \(clojure.core/println e__\d+__auto__\) \(.printStackTrace e__\d+__auto__\)\)\)"
       (pr-str (#'debug/vm-swank-main {:a 1})))))
