(ns swank-clj.proxy-test
  (:use clojure.test)
  (:require
   [swank-clj.proxy :as proxy]
   [swank-clj.swank.commands :as commands]
   [swank-clj.logging :as logging]
   [swank-clj.rpc-socket-connection :as rpc-s-c]
   [swank-clj.test-utils :as test-utils]))
