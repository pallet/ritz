(ns ritz.swank.connection
  "Connection specifics for swank"
  (:require
   [ritz.repl-utils.helpers :as helpers]
   [ritz.repl-utils.utils :as utils]
   [clojure.java.io :as java-io])
  (:use
   ritz.connection
   [ritz.logging :only [trace]])
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
  ((:connected? connection) connection))

(defn close
  "Close a connection"
  [connection]
  ((:close-connection connection) connection))

(defn send-to-emacs*
  "Sends a message (msg) to emacs."
  [connection msg]
  ((:write-message connection) connection msg))

(defn send-to-emacs
  "Sends a message (msg) to emacs."
  [connection msg]
  (if connection
    (send-to-emacs* connection msg)
    (trace "Unable to send message to nil connection %s" msg)))

(defn read-from-connection
  "Read a form from the connection."
  [connection]
  ((:read-message connection) connection))

(defn write-to-input
  "Read an input from the connection."
  [connection tag ^String value]
  (if (= tag @(:input-tag connection))
    (do
      (reset! (:input-tag connection) nil)
      (.write
       ^java.io.OutputStream (:input-source connection)
       (.getBytes value) 0 (.length value)))
    (trace
     "Input with tag mismatch %s %s" tag @(:input-tag connection))))


(defn ^PrintWriter call-on-flush-stream
  "Creates a stream that will call a given function when flushed."
  [flushf]
  (let [closed? (atom false)]
    (PrintWriter.
     (proxy [StringWriter] []
       (close [] (reset! closed? true))
       (flush []
              (let [^StringWriter me this
                    len (.. me getBuffer length)]
                (when (> len 0)
                  (flushf (.. me getBuffer (substring 0 len)))
                  (.. me getBuffer (delete 0 len))))))
     true)))

(defn- ^java.io.StringWriter make-output-redirection
  ([io-connection]
     (call-on-flush-stream
      #((:write-message io-connection) io-connection `(:write-string ~%)))))


(def tag-counter (atom 0))
(defn make-tag []
  (swap! tag-counter (fn [x] (mod (inc x) Long/MAX_VALUE))))

(defn thread-id
  ([] (thread-id (Thread/currentThread)))
  ([^Thread thread]
     (.getId thread)))

(defn make-repl-input-stream
  "Creates a stream that will ask emacs for input."
  [connection]
  (trace "make-repl-input-stream")
  (let [out-to-in (java.io.PipedOutputStream.)
        request-pending (atom nil)
        request-input (fn [] (let [tag (make-tag)]
                               (when (compare-and-set! request-pending nil tag)
                                 ;; (trace
                                 ;;  "make-repl-input-stream: requesting..")
                                 (send-to-emacs*
                                  connection
                                  `(:read-string ~(thread-id) ~tag)))))
        in (proxy [java.io.PipedInputStream] [out-to-in]
             (read ([]
                      ;; (trace "make-repl-input-stream: read")
                      ;; (when (zero? (.available this))
                      ;;   (request-input))
                      (proxy-super read))
                   ([b s l]
                      (trace "make-repl-input-stream: read 3")
                      (when (zero? (.available this))
                        (request-input))
                      (proxy-super read b s l))))]
    [(java.io.PushbackReader. (java.io.InputStreamReader. in))
     out-to-in request-pending]))

(defn- initialise
  "Set up the initial state of an accepted connection."
  [io-connection options]
  (let [connection (merge
                    options
                    io-connection
                    {:debug (atom {:sldb-levels []
                                   :exception-filters
                                   (or (read-exception-filters)
                                       default-exception-filters)})
                     :pending (atom #{})
                     :timeout nil
                     :writer-redir (make-output-redirection
                                    io-connection)
                     :inspector (atom {})
                     :result-history nil
                     :last-exception nil
                     :indent (atom {:indent-cache-hash nil
                                    :indent-cache {}})
                     :send-repl-results-function nil
                     :namespace 'user})]
    (merge connection
           (zipmap
            [:input-redir :input-source :input-tag]
            (make-repl-input-stream connection)))))

(defn request
  "Set the request details on the connection"
  [connection buffer-ns id]
  (swap! (:pending connection) conj id)
  (->
   connection
   (assoc-in [:request-id] id)
   (assoc-in [:buffer-ns-name] buffer-ns)
   (assoc-in [:request-ns] (utils/maybe-ns buffer-ns))))

(defn remove-pending-id [connection id]
  (swap! (:pending connection) disj id))

(defn request-id
  [connection]
  (:request-id connection))

(defn buffer-ns-name
  "The buffer namespace name"
  [connection]
  (:buffer-ns-name connection))

(defn request-ns
  "The current namespace for the request"
  [connection]
  (:request-ns connection))

(defn pending
  "A vector of pending continuations"
  [connection]
  @(:pending connection))

(defn close-connection
  [connection]
  (trace "close-connection")
  ((:close-connection connection) connection)
  (dissoc connection :read-message :reader :write-message :writer))

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
      (trace "authenticate: closing connection")
      (close-connection connection)
      nil)
    connection))

(defn create
  [io-connection options]
  (authenticate (initialise io-connection options)))

(defn next-sldb-level
  [connection level-info]
  (trace "next-sldb-level: level-info %s" level-info)
  (let [debug (swap!
               (:debug connection)
               (fn [current]
                 (->
                  current
                  (update-in [:sldb-levels]
                             (fn [levels]
                               (trace
                                "next-sldb-level %s" (count levels))
                               (conj (or levels []) level-info)))
                  (dissoc :abort-to-level))))]
    (-> debug :sldb-levels count)))

(defn sldb-level
  [connection]
  (count (-> connection :debug deref :sldb-levels)))

(defn sldb-drop-level
  ([connection]
     (trace
      "sldb-drop-level: :levels %s"
      (sldb-level connection))
     (swap! (:debug connection) update-in
            [:sldb-levels] subvec 0 (dec (count (:sldb-levels connection)))))
  ([connection n]
     (trace
      "sldb-drop-level: :levels %s :level %s" (sldb-level connection) n)
     (swap! (:debug connection) update-in [:sldb-levels] subvec 0 n)))

(defn current-sldb-level-info
  "Obtain the current level. Returns [level-info level-number]"
  [connection]
  (when-let [levels (seq (-> connection :debug deref :sldb-levels))]
    [(last levels) (count levels)]))

(defn sldb-level-info
  "Obtain the specified level"
  [connection level]
  (let [levels (-> connection :debug deref :sldb-levels)]
    (trace "sldb-level-info: :levels %s :level %s" (count levels) level)
    (nth levels (dec level) nil)))

(defn resume-sldb-level-infos
  [connection]
  (-> connection :debug deref :resume-sldb-levels))

(defn clear-abort-for-current-level
  "Clear any abort for the current level"
  [connection]
  (swap!
   (:debug connection)
   (fn [c]
     (trace
      "clear-abort-for-current-level %s %s"
      (count (:sldb-levels c)) (:abort-to-level c))
     (if (and (:abort-to-level c)
              (= (count (:sldb-levels c)) (:abort-to-level c)))
       (dissoc c :abort-to-level)
       c))))

(defn aborting-level?
  "Aborting predicate."
  [connection]
  (let [debug @(:debug connection)]
    (if-let [abort-to-level (:abort-to-level debug)]
      (>= (count (:sldb-levels debug)) abort-to-level))))

(defn inspector
  "Return the connection's inspector information."
  [connection]
  (:inspector connection))

(defn swank-handler
  [connection]
  (:swank-handler connection))


;; FIXME
(defn add-result-to-history
  "Add result to history, returning a history vector"
  [connection result]
  (->
   (update-in connection [:result-history]
          (fn [history]
            (take 3 (conj history result))))
   :result-history))

(defn set-last-exception
  "Add an exception"
  [connection e]
  (assoc connection :last-exception e)
  nil)

(defn connection-type
  [connection]
  (if (:proxy-to connection)
    :proxy
    :repl))

(defn set-namespace
  [connection ns]
  (assoc connection :namespace ns))

(defn current-namespace
  [connection]
  (:namespace connection))
