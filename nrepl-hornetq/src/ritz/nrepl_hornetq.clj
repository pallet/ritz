(ns ritz.nrepl-hornetq
  (:use
   [clojure.tools.nrepl.server :only [handle* default-handler]]
   [clojure.tools.nrepl.transport :only [recv]]
   [ritz.logging :only [trace]]
   [ritz.nrepl-hornetq.transport :only [make-transport ]]))


;;; Need an embedded server, with no classpath magic, as well as a server with a
;;; configurable classpath.

(defn handle-messages
  [{:keys [continue handler transport] :as server}]
  (when @continue
    (when-let [msg (recv transport)]
      (trace "handle %s" msg)
      (handle* msg handler transport)
      (recur server))))

(defn handle
  "Handles requests received via `transport` using `handler`.
   Returns nil when `recv` returns nil for the given transport."
  [{:keys [continue handler transport] :as server}]
  (trace "server handle")
  (with-open [transport transport]
    (handle-messages server))
  (trace "server handle done"))

(defn stop-server
  "Stops a server started via `start-server`."
  [server]
  (trace "stop-server %s" server)
  (send-off server (fn [server] (reset! (:continue server) nil) server)))

(defn start-server
  "Starts a HornetQ nREPL server.  Configuration options include:

Returns a handle to the server that is started, which may be stopped either via
`stop-server`, (.close server), or automatically via `with-open`.

consumer-queue
: the name of the queue the nrepl-server should read from.

service-queue
: the name of the queue the nrepl-server should write to.

queue-options
: queue options for creating the queues.

host
: host name or ip of the HornetQ server

port
: port of the HornetQ server

user
: user login for the HornetQ server

password
: password for the HornetQ server

transport
: which HornetQ transport to use (:in-vm or :netty)

session-options
: options to pass to the HornetQ session

"
  [{:keys [consumer-queue producer-queue queue-options handler] :as options}]
  (let [options (merge {:producer-queue "/nrepl/client"
                        :consumer-queue "/nrepl/server"}
                       options)
        smap {:continue (atom true)
              :transport (make-transport options)
              :handler (or handler (default-handler))}
        server (proxy [clojure.lang.Agent java.io.Closeable] [smap]
                 (close [] (stop-server this)))]
    (send-off server handle)
    server))
