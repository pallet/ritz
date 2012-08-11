(ns ritz.nrepl.connections
  "Track client connections"
  (:use
   [ritz.logging :only [trace]]))

(defonce
  ^{:doc "A map from message id to messages from the client."}
  pending-ids (atom {}))

(defonce
  ^{:doc "A map from session id to connection"}
  connections (atom {}))

(defn connection-for-session
  [session]
  (@connections session))

(defn add-pending-connection
  [id connection]
  (swap! pending-ids assoc id connection))

(defn promote-pending-connection
  "Promote the pending connection for message id to a connection for the
  session-id. Returns the connection."
  [id session-id]
  (let [connection (@pending-ids id)]
    (trace "Looking for connection in pending-ids")
    (assert connection)
    (swap! connections assoc session-id connection)
    (swap! pending-ids dissoc id)
    connection))

(defn rename-connection
  [connection old-session-id new-session-id]
  (swap! connections assoc new-session-id connection)
  (swap! connections dissoc old-session-id))

(defn primary-connection
  "Return the primary connection. This can be used to obtain a connection to use
for event notifications in the absence of a client message."
  []
  (val (first @connections)))

(defn all-connections
  "Return all connections."
  []
  (vals @connections))
