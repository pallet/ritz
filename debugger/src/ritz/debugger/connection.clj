(ns ritz.debugger.connection
  "A connection is a map that maintains state for a client."
  (:require
   [ritz.logging :as logging]))

;;; Default connection
(def default-connection
  {:debug (atom {:breakpoints []})
   :break (atom {:break-levels {}})
   :inspector (atom {})
   :bindings (atom (dissoc (get-thread-bindings) #'*agent*))
   :indent (atom {:indent-cache-hash nil
                  :indent-cache {}})
   :exception-filters (atom [])})

;;; # Query
(defn vm-context
  [connection]
  (:vm-context connection))

;;; # Debug context
(defn debug-context
  "Query the debug context"
  [connection]
  @(:debug connection))

(defn debug-assoc!
  "Assoc values into the bindings"
  [connection & var-bindings]
  (swap!
   (:debug connection)
   (fn [debug-context]
     (apply assoc debug-context var-bindings))))

(defn debug-update-in!
  "Assoc values into the debug-context"
  [connection keys f & args]
  (swap!
   (:debug connection)
   (fn [debug-context]
     (apply update-in debug-context keys f args))))

;;; # Break context
(defn break-context
  "Query the break context"
  [connection]
  @(:break connection))

(defn break-assoc!
  "Assoc values into the bindings"
  [connection & var-bindings]
  (swap!
   (:break connection)
   (fn [break-context]
     (apply assoc break-context var-bindings))))

(defn break-update-in!
  "Update values in the break-context"
  [connection keys f & args]
  (swap!
   (:break connection)
   (fn [break-context]
     (apply update-in break-context keys f args))))

(defn break-assoc-in!
  "Assoc values into the break-context"
  [connection keys arg]
  (swap!
   (:break connection)
   (fn [break-context]
     (assoc-in break-context keys arg))))

(defn break-update!
  "Apply a function on the break-context"
  [connection f & args]
  (apply swap! (:break connection) f args))

;;; # Bindings
(defn bindings
  "Query the connection bindings."
  [connection]
  @(:bindings connection))

(defn bindings-assoc!
  "Assoc values into the bindings"
  [connection & var-bindings]
  (swap!
   (:bindings connection)
   (fn [bindings]
     (apply assoc bindings var-bindings))))

(defn bindings-merge!
  "Merge values into the bindings"
  [connection & maps]
  (swap!
   (:bindings connection)
   (fn [bindings]
     (apply merge bindings maps))))

;;; Operations
(defmulti connection-close "Close a connection" :type)
