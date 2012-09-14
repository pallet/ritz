(ns ritz.swank.jdi-connection
  "Swank connction using JDI"
  (:require
   [ritz.repl-utils.helpers :as helpers]
   [ritz.repl-utils.utils :as utils]
   [clojure.java.io :as java-io])
  (:use
   ritz.debugger.connection
   [ritz.debugger.exception-filters
    :only [exception-filters-set!
           read-exception-filters default-exception-filters]]
   [ritz.logging :only [trace]]
   [ritz.swank.connection
    :only [make-output-redirection make-repl-input-stream]])
  (:import
   java.util.concurrent.LinkedBlockingQueue))

(defn make-connection
  "Returns a connection for use over jdi."
  [{:keys [queue-size] :or {queue-size 20}}]
  (let [queue (LinkedBlockingQueue. (int queue-size))
        connection (merge
                    default-connection
                    {:replies queue
                     :connected? (fn [_] true)
                     :close-connection (fn [_])
                     :write-message (fn [connection msg] (.offer queue msg))
                     :read-message (fn [_]
                                     (throw
                                      (Exception. "Reading not supported")))
                     :input-tag (atom nil)
                     :namespace (atom (ns-name *ns*))
                     :pending (atom #{})})]
    (->
     connection
     (assoc :writer-redir (make-output-redirection connection))
     (merge (zipmap
             [:input-redir :input-source :input-tag]
             (make-repl-input-stream connection))))))

(defn read-sent
  "Read a reply message from a connection."
  [connection]
  (.take ^LinkedBlockingQueue (:replies connection)))

(defn release-queue
  "Send a reply message to unblock any reader."
  [connection]
  (.offer ^LinkedBlockingQueue (:replies connection) '(:ritz/release-read-msg)))
