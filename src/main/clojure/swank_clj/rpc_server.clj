(ns swank-clj.rpc-server
  "RPC server"
  (:require
   [swank-clj.executor :as executor]
   [swank-clj.logging :as logging]
   [swank-clj.connection :as connection]
   [swank-clj.swank :as swank])
  (:import
   java.io.InputStreamReader
   java.io.OutputStreamWriter
   java.util.concurrent.TimeUnit
   java.util.concurrent.Future
   java.util.concurrent.CancellationException
   java.util.concurrent.ExecutionException
   java.util.concurrent.TimeoutException))

;; (def #^{:private true
;;         :doc "Futures keyed by message id"}
;;        tasks (atom {}))

;; (defn- message-ids
;;   "All active message ids"
;;   []
;;   (keys @tasks))

;; (def current-id (atom 0))

;; (defn- next-id []
;;   "Generate an id, ensuring it is not in the active id set"
;;   (loop [id (swap! current-id inc)]
;;     (if (some #(= id %) (message-ids))
;;       (recur (swap! current-id inc))
;;       id)))

(defn- dispatch-event
   [form connection]
   (logging/trace "rpc-server/dispatch-event: %s" (pr-str form))
   (swank/dispatch-event form connection))

(defn- response
  "Respond and act as watchdog"
  [#^Future future form connection]
  (try
    (let [timeout (:timeout @connection)
          result (if timeout
                   (.get future timeout TimeUnit/MILLISECONDS)
                   (.get future))])
    (catch CancellationException e
      (connection/send-to-emacs connection "cancelled"))
    (catch TimeoutException e
      (connection/send-to-emacs connection "timeout"))
    (catch ExecutionException e
      (.printStackTrace e)
      (connection/send-to-emacs connection "server-failure"))
    (catch InterruptedException e
      (.printStackTrace e)
      (connection/send-to-emacs connection "server-failure"))))

(defn- dispatch-message
  "Dispatch a message on a connection."
  [connection]
  (logging/trace "dispatch-message %s" (and (:proxy-to @connection) "PROXY"))
  (when (connection/connected? connection)
    (let [form (connection/read-from-connection connection)
          _ (logging/trace "dispatch-message: %s" form)
          future (executor/execute #(dispatch-event form connection))]
      (executor/execute #(response future form connection)))))

(defn serve-connection
  "Serve a set of RPC functions"
  [socket options]
  (logging/trace "rpc-server/serve-connection")
  (let [connection (connection/create socket options)
        future (executor/execute-loop
                (partial dispatch-message connection)
                :name "Connection dispatch loop")]
    (logging/trace "rpc-server/serve-connection returning")
    [connection future]))
