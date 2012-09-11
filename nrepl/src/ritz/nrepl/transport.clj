(ns ritz.nrepl.transport
  "A transport for nrepl over jpda connection"
  (:require
   clojure.tools.nrepl.transport)
  (:import
   java.util.concurrent.LinkedBlockingQueue))

(defprotocol ReadSent
  "Adds a read-sent function for retrieving queued messages"
  (read-sent [_] "Return a sent message, or block")
  (release-queue [_] "Return a release message, as the queue is disappearing"))


(defrecord JpdaTransport [^LinkedBlockingQueue queue]
  clojure.tools.nrepl.transport/Transport
  (recv [this] (assert false))
  (recv [this timeout] (assert false))
  (send [this msg]
    (.put queue msg))
  ReadSent
  (read-sent [this]
    (.take queue))
  (release-queue [this]
    (.offer queue {:op "ritz/release-read-msg"})))


(defn make-transport
  [{:keys [queue-size] :or {queue-size 20}}]
  (JpdaTransport. (LinkedBlockingQueue. (int queue-size))))
