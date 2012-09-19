(ns ritz.swank.connections)

;;; # Connections state
(defonce connections (atom #{}))

(defn add-connection [connection]
  (swap! connections conj connection))

(defn remove-connection [connection]
  (swap! connections disj connection))

(defn connection-for-event
  [_]
  (first @connections))

(defn all-connections
  "Return all connections."
  []
  @connections)
