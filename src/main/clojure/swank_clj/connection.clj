(ns swank-clj.connection
  (:require
   [swank-clj.logging :as logging]
   [swank-clj.rpc :as rpc]
   [clojure.java.io :as java-io])
  (:import
   java.io.BufferedReader
   java.io.FileReader
   java.io.InputStreamReader
   java.io.OutputStreamWriter
   java.io.PrintWriter
   java.io.StringWriter))


(defn send-to-emacs
  "Sends a message (msg) to emacs."
  [connection msg]
  (try
    (let [writer (:writer @connection)]
      (locking (:write-monitor @connection)
        (rpc/encode-message writer msg)))
    (catch Exception e
      (logging/trace "Caught exception while writing %s" (str e))
      '(:read-error "" e))))

(defn read-from-connection
  "Read a form from the connection."
  [connection]
  (try
    (let [reader (:reader @connection)]
      (locking (:read-monitor @connection)
        (rpc/decode-message reader)))
    (catch Exception e
      (logging/trace "Caught exception while reading %s" (str e))
      '(:read-error "" e))))


(defn ^PrintWriter call-on-flush-stream
  "Creates a stream that will call a given function when flushed."
  [flushf]
  (let [closed? (atom false)]
    (PrintWriter.
     (proxy [StringWriter] []
       (close [] (reset! closed? true))
       (flush []
              (let [#^StringWriter me this
                    len (.. me getBuffer length)]
                (when (> len 0)
                  (flushf (.. me getBuffer (substring 0 len)))
                  (.. me getBuffer (delete 0 len))))))
     true)))

(defn- ^java.io.StringWriter make-output-redirection
  ([connection]
     (call-on-flush-stream
      #(send-to-emacs connection `(:write-string ~%)))))

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

(defn- initialise
  "Set up the initial state of an accepted connection."
  [socket options]
  (let [encoding (encoding-map (:encoding options) (:encoding options))]
    (doto
        (atom
         (merge
          options
          {:socket socket
           :reader (InputStreamReader. (.getInputStream socket) encoding)
           :writer (OutputStreamWriter. (.getOutputStream socket) encoding)
           :read-monitor (Object.)
           :write-monitor (Object.)
           :sldb-levels []
           :pending #{}
           :timeout nil}))
      (swap! (fn [connection]
               (assoc connection
                 :writer-redir (make-output-redirection connection)))))))

(defn add-pending-id [connection id]
  (swap! connection update-in [:pending] conj id))

(defn remove-pending-id [connection id]
  (swap! connection update-in [:pending] disj id))

(defn pending
  [connection]
  (:pending @connection))

(defn local-port
  [connection]
  (.getLocalPort (:socket @connection)))

(defn close-connection
  [connection]
  (logging/trace "close-connection")
  (.close (:socket @connection))
  (swap! connection dissoc :socket :reader :writer))

(def ^{:private true}
  slime-secret-path
  (.getPath (java-io/file (System/getProperty "user.home") ".slime-secret")))

(defn- slime-secret
  "Returns the first line from the slime-secret file, path found in
   slime-secret-path (default: .slime-secret in the user's home
   directory)."
  ([] (try
        (let [file (java-io/file slime-secret-path)]
          (when (and (.isFile file) (.canRead file))
            (with-open [secret (BufferedReader. (FileReader. file))]
              (.readLine secret)))))))

(defn- authenticate
  "Authenticate a new connection.

   Authentication depends on the contents of a slime-secret file on
   both the server (swank) and the client (emacs slime). If no
   slime-secret file is provided on the server side, all connections
   are accepted.

   See also: `slime-secret'"
  [connection]
  (if-let [secret (slime-secret)]
    (when-not (= (read-from-connection connection) secret)
      (logging/trace "authenticate: closing connection")
      (close-connection connection)
      nil)
    connection))

(defn create
  [socket options]
  (authenticate (initialise socket options)))

(defn next-sldb-level
  [connection restarts thread]
  (logging/trace "next-sldb-level")
  (swap!
   connection update-in [:sldb-levels]
   (fn [levels]
     (conj (or levels []) {:restarts restarts :thread thread})))
  (count (:sldb-levels @connection)))

(defn sldb-level
  [connection]
  (count (:sldb-levels @connection)))

(defn invoke-restart
  [connection level n]
  (let [m (nth (:sldb-levels @connection) (dec level))]
    (when-let [f (last (nth (vals (:restarts m)) n))]
      (swap! connection update-in [:sldb-levels] subvec 0 n)
      (f))
    (:thread m)))
