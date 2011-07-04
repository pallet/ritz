(ns ritz.rpc-socket-connection
  "A socket connection that speaks rpc"
  (:require
   [ritz.logging :as logging]
   [ritz.rpc :as rpc]
   [clojure.java.io :as java-io])
  (:import
   java.io.InputStreamReader
   java.io.OutputStreamWriter
   java.net.SocketException))

(def ^{:private true :doc "Translate encoding strings from slime to java"}
  encoding-map
  {"latin-1" "iso-8859-1"
   "latin-1-unix" "iso-8859-1"
   "iso-latin-1-unix" "iso-8859-1"
   "iso-8859-1" "iso-8859-1"
   "iso-8859-1-unix" "iso-8859-1"

   "utf-8" "utf-8"
   "utf-8-unix" "utf-8"

   "euc-jp" "euc-jp"
   "euc-jp-unix" "euc-jp"

   "us-ascii" "us-ascii"
   "us-ascii-unix" "us-ascii"})


(defn close
  "Close a connection"
  [connection]
  (.close (:socket connection)))

(defn connected?
  "Predicate to test if connection is open"
  [connection]
  (let [socket (:socket connection)]
    (and (not (or (.isClosed socket)
                  (.isInputShutdown socket)
                  (.isOutputShutdown socket)))
         (.isConnected socket))))

(defn write-message
  "Sends a message."
  [connection msg]
  (try
    (let [writer (:writer connection)]
      (locking (:write-monitor connection)
        (rpc/encode-message writer msg)
        (.flush writer))
      (logging/trace "rpc-socket-connection/write-message completed"))
    (catch SocketException e
      (logging/trace "Caught exception while writing %s" (str e))
      (when (.isOutputShutdown (:socket connection))
        (.close (:socket connection)))
      (throw e))))

(defn read-message
  "Read a form from the connection."
  [connection]
  (try
    (logging/trace "rpc-socket-connection/read-mesage")
    (let [reader (:reader connection)]
      (locking (:read-monitor connection)
        (rpc/decode-message reader)))
    (catch SocketException e
      (logging/trace "Caught exception while reading %s" (str e))
      (when (.isInputShutdown (:socket connection))
        (.close (:socket connection)))
      (throw e))))

(defn local-port
  [connection]
  (.getLocalPort (:socket connection)))

(defn create [socket {:as options}]
  (let [encoding (encoding-map (:encoding options) (:encoding options))]
    (merge
     options
     {:socket socket
      :reader (InputStreamReader. (.getInputStream socket) encoding)
      :writer (OutputStreamWriter. (.getOutputStream socket) encoding)
      :read-message read-message
      :write-message write-message
      :close-connection close
      :read-monitor (Object.)
      :write-monitor (Object.)
      :connected? connected?})))
