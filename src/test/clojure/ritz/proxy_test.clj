(ns ritz.proxy-test
  (:use clojure.test)
  (:require
   [ritz.proxy :as proxy]
   [ritz.swank.commands :as commands]
   [ritz.logging :as logging]
   [ritz.rpc-socket-connection :as rpc-s-c]
   [ritz.test-utils :as test-utils]))
