(ns ritz.nrepl.transport
  "A transport for nrepl over jpda connection"
  (:require
   clojure.tools.nrepl.transport)
  (:import
   java.util.concurrent.LinkedBlockingQueue))

(defprotocol ReadSent
  "Adds a read-sent function for retrieving queued messages"
  (read-sent [_] "Return a sent message, or block"))


(defrecord JpdaTransport [queue]
  clojure.tools.nrepl.transport/Transport
  (recv [this] (assert false))
  (recv [this timeout] (assert false))
  (send [this msg]
    (.put queue msg))
  ReadSent
  (read-sent [this]
    (.take queue)))


(defn make-transport
  [{:keys [queue-size] :or {queue-size 20}}]
  (JpdaTransport. (LinkedBlockingQueue. queue-size)))
