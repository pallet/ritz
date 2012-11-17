(ns ritz.nrepl-hornetq.transport
  "An nREPL transport using a HornetQ client."
  (:require
   clojure.tools.nrepl.transport)
  (:use
   [cheshire.core :as json]
   [clojure.stacktrace :only [print-cause-trace]]
   [hornetq-clj.core-client
    :only [create-consumer create-producer create-queue
           create-message query-queue read-message-string write-message-string
           send-message session-factory session
           create-session-factory]
    :rename {session make-session send-message send-hornetq-message}]
   [ritz.logging :only [trace]]))

;;; # Session
(defn hornetq-session
  "Returns a function of no arguments that returns a session for the HornetQ
server.

Specify user, password, host, port, in-vm for the HornetQ server.

Use session-options to specify:
  xa, auto-commit-sends, auto-commit-acks, pre-acknowledge, and ack-batch-size."
  [{:keys [user password host port transport locator-type session-options]
    :or {user "" password "" locator-type :static}
    :as options}]
  (trace "hornetq-session %s" (:transport options))
  (let [session-factory (create-session-factory
                         locator-type
                         (merge
                          {:block-on-durable-send true
                           :block-on-non-durable-send true}
                          options)
                         options)]
    (trace "hornetq-session factory %s" session-factory)
    #(make-session session-factory user password session-options)))

(defn session-map
  [s {:keys [consumer-queue producer-queue] :as options} session-fn]
  (let [session (or (:session s) (doto (session-fn) (.start)))]
    (-> s
        (assoc :session session)
        (update-in [:consumer]
                   #(or
                     %
                     (try
                       (trace "create-consumer %s" consumer-queue)
                       (create-consumer session consumer-queue options)
                       (catch Exception e
                         (trace "create consumer %s" e)))))
        (update-in [:producer]
                   #(or
                     %
                     (try
                       (trace "create-producer %s" producer-queue)
                       (create-producer session producer-queue)
                       (catch Exception e
                         (trace "create producer %s" e))))))))

(defn ensure-session
  [{:keys [consumer-queue producer-queue] :as options} session-fn session-atom]
  {:pre [consumer-queue producer-queue]}
  (trace "ensure-session")
  (let [s @session-atom]
    (or (and (every? s [:session :consumer :producer]) s)
        (swap! session-atom session-map options session-fn))))

(defn session
  [connection]
  (:session connection))

(defn consumer
  [connection]
  (:consumer connection))

(defn producer
  [connection]
  (:producer connection))


;;; # Queues
(defn hornetq-make-queue
  "Make a queue on the hornet-server"
  [connection queue-name queue-options]
  (trace "hornetq-make-queue %s" queue-name)
  (create-queue (session connection) queue-name queue-options))

(defn hornetq-queue-info
  "Make a queue on the hornet-server"
  [connection queue-name]
  (trace "hornetq-queue-info %s" queue-name)
  (query-queue (session connection) queue-name))

(defn hornetq-ensure-queue
  "Make a queue on the hornet-server if it doesn't already exist."
  [connection queue-name queue-options]
  (trace "hornetq-ensure-queue %s" queue-name)
  (when-not (.isExists (hornetq-queue-info connection queue-name))
    (hornetq-make-queue connection queue-name queue-options)))


;;; # Send and receive
(defn process-received-message
  "Take a hornetq message, and return the nREPL message from it."
  [message]
  (trace "process-received-message")
  (when message
    (.acknowledge message)
    (let [s (read-message-string message)
          _ (trace "process-received-message s %s" s)
          msg (json/parse-string s true)]
      (trace "process-received-message msg %s" msg)
      msg)))

(defn send-message
  "Send a message from the client to the nREPL server."
  [{:keys [durable producer-queue] :as options}
   {:keys [session producer] :as session}
   msg]
  {:pre [session producer]}
  (trace "send-message %s to %s as %s"
         msg producer-queue (json/generate-string msg))
  (let [message (create-message session durable)]
    (write-message-string message (json/generate-string msg))
    (send-hornetq-message producer message producer-queue)))

;;; # Types
(defprotocol QueueBuilder
  (ensure-queue [_ queue-name options]
    "Make a queue using the specified options"))

;;; # Transport
(defrecord HornetQTransport [options session-fn connection]
  clojure.tools.nrepl.transport/Transport
  (recv [this]
    (trace "recv")
    (let [c (consumer (ensure-session options session-fn connection))]
      (trace "recv consumer %s" c)
      (assert (not (.isClosed c)))
      (process-received-message (.receive c))))
  (recv [this timeout] (process-received-message
                        (.receive
                         (consumer
                          (ensure-session options session-fn connection))
                         timeout)))
  (send [this msg] (send-message
                    options
                    (ensure-session options session-fn connection)
                    msg))
  QueueBuilder
  (ensure-queue [this queue-name queue-options]
    (hornetq-ensure-queue
     (ensure-session options session-fn connection)
     queue-name
     queue-options))
  java.io.Closeable
  (close [_] (when-let [session (:session @connection)]
               (.close session))))


(defn make-transport
  "Return an nREPL transport using a HornetQ message broker."
  [{:keys [consumer-queue producer-queue queue-options]
    :as options}]
  {:pre [consumer-queue producer-queue]}
  (let [transport (HornetQTransport.
                   options (hornetq-session options) (atom {}))]
    (ensure-queue transport consumer-queue queue-options)
    (ensure-queue transport producer-queue queue-options)
    transport))
