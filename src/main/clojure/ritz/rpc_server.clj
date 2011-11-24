(ns ritz.rpc-server
  "RPC server"
  (:require
   [ritz.executor :as executor]
   [ritz.logging :as logging]
   [ritz.connection :as connection]
   [ritz.swank :as swank]))

(defn- dispatch-message
  "Dispatch a message on a connection."
  [connection]
  (logging/trace "dispatch-message %s" (connection/connection-type connection))
  (if (connection/connected? connection)
    (let [form (connection/read-from-connection connection)
          _ (logging/trace "dispatch-message: %s" form)]
      (swank/handle-message connection form))
    (logging/trace "dispatch-message not connected"))
  (logging/trace
   "dispatch-message %s done"
   (connection/connection-type connection)))

(defn serve-connection
  "Serve a set of RPC functions"
  [socket options]
  (logging/trace "rpc-server/serve-connection")
  (let [connection (swank/setup-connection (connection/create socket options))
        future (executor/execute-loop
                (partial dispatch-message connection)
                :name (format
                       "Connection dispatch loop %s"
                       (connection/connection-type connection)))]
    (logging/trace "rpc-server/serve-connection returning")
    [connection future]))
