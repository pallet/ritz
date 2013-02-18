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
  (when-let [c (first @connections)]
    (val c)))

(defn all-connections
  "Return all connections."
  []
  (vals @connections))

(defn connection-for-event
  [_]
  (primary-connection))

;;; # Message Response Hooks
;;; In order to allow the debug server to hook into operation replies from
;;; the user server, we allow registration of a handler.  The handler
;;; should return true if it shouldn't be removed.
(defn add-message-reply-hook
  "Add a function, to call on a reply to the given message from the user
  process.  The function should take a connection, the original message and the
  message reply as arguments."
  [connection {:keys [id session] :as msg} f]
  (swap! (:msg-response-hooks connection) assoc-in [session id] #(f %1 msg %2)))

(defn call-message-reply-hook
  "Call a reply hook for the given message, if one exists.  Removes the hook
  function unless it returns a true value."
  [connection {:keys [id session] :as msg}]
  (trace "call-message-reply-hook %s %s" session id)
  (when-let [f (get-in @(:msg-response-hooks connection) [session id])]
    (trace "call-message-reply-hook found hook %s" f)
    (when-not (f connection msg)
      (swap! (:msg-response-hooks connection) update-in [session] dissoc id))))
