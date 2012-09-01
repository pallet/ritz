(ns ritz.swank.proxy-test
  (:use clojure.test)
  (:require
   [ritz.logging :as logging]
   [ritz.swank.commands :as commands]
   [ritz.swank.proxy :as proxy]
   [ritz.swank.rpc-socket-connection :as rpc-s-c]
   [ritz.swank.test-utils :as test-utils]))
