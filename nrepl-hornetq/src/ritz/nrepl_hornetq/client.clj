(ns ritz.nrepl-hornetq.client
  "A client for a nrepl-hornetq server."
  (:refer-clojure :exclude [send])
  (:use
   [clojure.stacktrace :only [print-cause-trace]]
   [clojure.string :only [split]]
   [clojure.tools.nrepl.transport :only [send recv]]
   [clojure.tools.nrepl :only [url-connect] :as nrepl]
   [ritz.nrepl-hornetq.transport :only [make-transport]]
   [ritz.logging :only [trace]])
  (:require
   [clojure.tools.nrepl.middleware.interruptible-eval :as interruptible-eval]))


(defn next-id [{:keys [id] :as client}]
  (swap! id inc))

(defn set-handler [{:keys [handlers] :as client} id response-handler]
  (swap! handlers assoc id response-handler))

(defn handler [{:keys [handlers] :as client} id]
  (get @handlers id))

(defn remove-handler [{:keys [handlers] :as client} id]
  (swap! handlers dissoc id))

(defn handle-message
  [executor handler msg]
  (trace "client/handle-message %s" msg)
  (.execute executor #(handler msg)))

(defn handle-messages
  [{:keys [continue handlers transport executor] :as client}]
  {:pre [transport]}
  (trace "client/handle-messages")
  (when @continue
    (when-let [{:keys [id status] :as msg} (recv transport)]
      (trace "client/handle-messages read a message")
      (handle-message executor (handler client id) msg)
      (when (#{:done :error :interrupted} status)
        (trace "client/handle-messages remove handler %s" id)
        (remove-handler client id))
      (recur client))))

(defn handle
  "Handles replies received via `transport`."
  [{:keys [transport] :as client}]
  (try
    (trace "client/handle")
    (do ;; with-open [_ transport]
      (handle-messages client)
      (trace "client/handle done"))
    (catch Exception e
      (trace "client/handle exception %s" (with-out-str (print-cause-trace e)))
      (throw e))))

(defn send-message
  [client msg response-handler]
  (let [{:keys [id transport] :as client} @client
        id (next-id client)]
    (set-handler client id response-handler)
    (send transport (assoc msg :id id))))

(defn stop-client
  "Stops a client started via `start-client`."
  [{:keys [transport] :as client}]
  (trace "stop-client %s" client)
  (send-off client (fn [client] (reset! (:continue client) nil) client)))

(defn start-client
  [{:keys [executor in-vm] :as options}]
  (let [options (merge {:producer-queue "/nrepl/server"
                        :consumer-queue "/nrepl/client"}
                       options)
        smap {:continue (atom true)
              :transport (make-transport options)
              :id (atom 0)
              :handlers (atom {})
              :executor (or executor (#'interruptible-eval/configure-executor))}
        client (proxy [clojure.lang.Agent java.io.Closeable] [smap]
                 (close [] (stop-client this)))]
    (send-off client handle)
    client))

(def hornetq-defaults
  {:host "localhost"
   :port 5445
   :transport :netty
   :consumer-queue "/nrepl/client"
   :producer-queue "/nrepl/server"})

(defn query-params
  "Return a map of query parameters (last value wins if key specified multiple
  times."
  [^java.net.URI uri]
  (into {}
        (map
         #(vector (keyword (key %)) (last (val %)))
         (.getParameters
          (org.jboss.netty.handler.codec.http.QueryStringDecoder. uri)))))

(defn uri-options
  "Convert a hornetq uri to an options map for make-transport"
  [uri]
  (let [uri (#'nrepl/to-uri uri)
        port (.getPort uri)
        [user password] (when-let [user-info (.getUserInfo uri)]
                          (split user-info #"/"))]
    (merge hornetq-defaults
           (query-params uri)
           (when (pos? port) {:port port})
           (when user {:user user})
           (when password {:password password})
           {:host (.getHost uri)})))

(defmethod url-connect "hornetq"
  [uri]
  (make-transport (uri-options uri)))
