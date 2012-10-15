(do
  ;; this is in a do so we can read it as a single expression
  (ns ritz.nrepl.middleware.project.transport
    "Defines a transport for use within a project classloader."
    (:require
     clojure.tools.nrepl.transport)
    (:import
     clojure.tools.nrepl.transport.QueueTransport
     java.util.concurrent.LinkedBlockingQueue))


  (defonce input-queue (LinkedBlockingQueue.))
  (defonce response-queue (LinkedBlockingQueue.))

  ;; Note that we can't use 1.4's deftype constructor functions, as we wish to
  ;; be usable in 1.2.1 onward.
  (defonce transport
    (QueueTransport. input-queue response-queue))

  (defn write-message
    "Write a message to the transport"
    [msg]
    (.put input-queue (assoc msg :transport transport)))

  (defn read-response
    "Read a response from the transport"
    []
    (dissoc (.take response-queue) :transport))

  (defn release-response-queue
    "Write an artificial response to the response queue, so the reader of the
  queue can shut down."
    []
    (.offer response-queue {:op "ritz/release-read-msg"})))
