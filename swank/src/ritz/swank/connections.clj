(ns ritz.swank.connections)

;;; # Connections state
(defonce connections (atom {}))

(defn add-connection [connection proxied-connection]
  (swap! connections assoc connection proxied-connection))

(defn remove-connection [connection]
  (swap! connections dissoc connection))

(defn connection-for-event
  [_]
  (ffirst @connections))

(defn all-connections
  "Return all connections."
  []
  (keys @connections))
