(ns swank-clj.connection
  (:require
   [swank-clj.logging :as logging]
   [clojure.java.io :as java-io])
  (:import
   java.io.BufferedReader
   java.io.FileReader
   java.io.InputStreamReader
   java.io.OutputStreamWriter
   java.io.PrintWriter
   java.io.StringWriter))


(defn connected?
  "Predicate to test if connection is open"
  [connection]
  (let [connection @connection]
    ((:connected? connection) connection)))

(defn close
  "Close a connection"
  [connection]
  (let [connection @connection]
    ((:close-connection connection) connection)))

(defn send-to-emacs
  "Sends a message (msg) to emacs."
  [connection msg]
  (let [connection @connection]
    ((:write-message connection) connection msg)))

(defn read-from-connection
  "Read a form from the connection."
  [connection]
  (let [connection @connection]
    ((:read-message connection) connection)))


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


(defn- initialise
  "Set up the initial state of an accepted connection."
  [io-connection options]
  (doto
      (atom
       (merge
        options
        io-connection
        {:sldb-levels []
         :pending #{}
         :timeout nil
         :writer-redir (make-output-redirection io-connection)}))))

(defn add-pending-id [connection id]
  (swap! connection update-in [:pending] conj id))

(defn remove-pending-id [connection id]
  (swap! connection update-in [:pending] disj id))

(defn pending
  [connection]
  (:pending @connection))

(defn close-connection
  [connection]
  (logging/trace "close-connection")
  ((:close-connection @connection) @connection)
  (swap! connection dissoc :read-message :reader :write-message :writer))

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
  [io-connection options]
  (authenticate (initialise io-connection options)))

(defn next-sldb-level
  [connection level-info]
  (logging/trace "next-sldb-level")
  (swap!
   connection update-in [:sldb-levels]
   (fn [levels]
     (conj (or levels []) level-info)))
  (count (:sldb-levels @connection)))

(defn sldb-drop-level [connection n]
  (swap! connection update-in [:sldb-levels] subvec 0 n))

(defn sldb-level
  [connection]
  (count (:sldb-levels @connection)))

(defn sldb-level-info
  ([connection]
     (last (:sldb-levels @connection)))
  ([connection level]
     (nth (:sldb-levels @connection) (dec level))))
