(ns ritz.nrepl
  "nrepl support for ritz.

nREPL has a concept of session, that maintains state based on a session id
that is passed back and forth from client and server. We use this session to
store some extra info. We also unify the session id across the jpda and user
processes."
  (:use
   [clojure.tools.nrepl.server :only [unknown-op]]
   [clojure.tools.nrepl.middleware.interruptible-eval
    :only [interruptible-eval]]
   [clojure.tools.nrepl.middleware.pr-values :only [pr-values]]
   [clojure.tools.nrepl.middleware.session :only [add-stdin session]]
   [clojure.tools.nrepl.misc :only [response-for returning]]
   [leiningen.core.eval :only [eval-in-project]]
   [ritz.connection :only [read-exception-filters default-exception-filters]]
   [ritz.jpda.jdi :only [connector connector-args invoke-single-threaded]]
   [ritz.jpda.jdi-clj :only [control-eval]]
   [ritz.jpda.jdi-vm
    :only [acquire-thread launch-vm start-control-thread-body vm-resume]]
   [ritz.logging :only [set-level trace]]
   [ritz.nrepl.commands :only [jpda-op]]
   [ritz.nrepl.exec :only [read-msg]]
   [ritz.nrepl.rexec :only [rexec]]
   [ritz.nrepl.simple-eval :onl [simple-eval]])
  (:require
   [clojure.java.io :as io]
   [clojure.tools.nrepl.transport :as transport]
   [ritz.jpda.jdi-clj :as jdi-clj]))

(set-level :trace)

(defonce
  ^{:doc "A map from message id to messages from the client."}
  pending-ids (atom {}))

(defonce
  ^{:doc "A map from session id to connection"}
  connections (atom {}))

(def
  ^{:doc "The initial connection information"}
  default-connection
  {:sldb-levels []
   :pending #{}
   :timeout nil
   ;; :writer-redir (make-output-redirection
   ;;                io-connection)
   :inspector (atom {})
   :result-history nil
   :last-exception nil
   :indent-cache-hash (atom nil)
   :indent-cache (ref {})
   :send-repl-results-function nil
   :exception-filters (or (read-exception-filters)
                          default-exception-filters)
   :namespace 'user})


(defonce vm (atom nil))

(defn set-vm [vm]
  (reset! ritz.nrepl/vm vm))

(defn make-connection
  "Return a new connection map, saving the message's transport for later
reference."
  [msg]
  (->
   default-connection
   (assoc :vm-context @vm)
   (merge (select-keys msg [:transport]))))

;;; # jpda command execution
(defn execute-jpda
  "Execute a jpda action"
  [host port {:keys [op transport] :as msg}]
  (trace "execute-jpda %s" op)
  (let [connection (::connection msg)]
    (jpda-op (keyword op) connection msg)))

(defn return-execute-jpda
  [host port {:keys [op transport] :as msg}]
  (let [value (execute-jpda host port msg)]
    (transport/send
     transport
     (response-for msg :status :done :value value :op op))
    value))

;;; # nREPL handler and middleware

;;; ## Message log
(defn log-message
  [handler]
  (fn [{:keys [id session] :as msg}]
    (trace "Message %s" msg)
    (handler msg)))

;;; ## Connection middleware

;;; This looks up a connection map based on session id. If the session id is not
;;; set yet, then a new connection is made and stored in the pending-ids map, so
;;; that on reply the link from session to connection can be established. Since
;;; this expects the session id, it has to before any session middleware in the
;;; handler.

(defn connection
  [handler]
  (fn [{:keys [id session] :as msg}]
    (if session
      (handler (assoc msg ::connection (@connections session)))
      (let [connection (make-connection msg)]
        (swap! pending-ids assoc id connection)
        (handler (assoc msg ::connection connection))))))

;;; ## jpda command execution
(defn jpda-middleware
  "Handler for jpda actions"
  [host port]
  (fn [handler]
    (fn [{:keys [op transport] :as msg}]
      (if (= op "jpda")
        (return-execute-jpda host port msg)
        (handler msg)))))

;;; ## `eval` in jpda process
;;; This lets us eval in the jpda process, just as we would normally do in the
;;; user process
(defn jpda-eval-handler
  []
  (-> unknown-op simple-eval pr-values))

(defn jpda-eval-middleware
  "Handler for jpda actions"
  []
  (let [sub-handler (jpda-eval-handler)]
    (fn [handler]
      (fn [{:keys [op transport] :as msg}]
        (if (= op "jpda-eval")
          (do
            (trace "jpda-eval-handler %s" msg)
            (sub-handler (assoc msg :op "eval")))
          (handler msg))))))

;;; ## `eval`, etc, in user process
;;; We forward the message to the user process. The reply pump
;;; will pump all replies back to the client.
(defn execute-eval
  "Execute a jpda action"
  [host port {:keys [op transport] :as msg}]
  (trace "execute-jpda %s" op)
  (let [connection (::connection msg)]
    (rexec (:vm-context connection) msg)))

(defn return-execute-eval
  [host port {:keys [id op transport] :as msg}]
  (let [value (execute-eval host port msg)]
    (trace "return-execute-eval %s" value)
    value))

(defn rexec-handler
  "Handler for jpda actions"
  [host port]
  (fn [handler]
    (fn [{:keys [op transport] :as msg}]
      (if (#{"eval" "clone" "stdin" "interrupt"} op)
        (return-execute-eval host port msg)
        (handler msg)))))

;;; ## The overall nREPL handler for the jpda process
(defn debug-handler
  "nrepl handler with debug support"
  [host port]
  (let [rexec (rexec-handler host port)
        jpda (jpda-middleware host port)
        jpda-eval (jpda-eval-middleware)]
    (-> unknown-op rexec jpda-eval jpda ;; session ;; add-stdin session
        connection log-message)))

;;; # Reply pump
;;; The reply pump takes all nREPL replies sent from the user process, and
;;; forwards them to the client.
(defn process-reply
  "Process a reply. The reply is expected to have a session id. If this is the
first reply for a session, the connection is looked up in the pending-ids map
based on the message id and added to the connections map. Otherwise the
connection is found in the connections map based on the session id."
  [{:keys [id new-session session status] :as msg}]
  (trace "Read reply %s" msg)
  (assert session)
  (let [connection (or
                    (@connections session)
                    (let [connection (@pending-ids id)]
                      (trace "Looking for connection in pending-ids")
                      (assert connection)
                      (swap! connections assoc session connection)
                      (swap! pending-ids dissoc id)
                      connection))]
    (assert connection)
    (when new-session
      (trace "New session %s" new-session)
      (swap! connections assoc new-session connection)
      (swap! connections dissoc session))
    (let [transport (:transport connection)]
      (assert transport)
      (transport/send transport msg)))
  (trace "Reply forwarded"))

(defn reply-pump
  [vm thread]
  (loop []
    (try
      (process-reply
       (jdi-clj/eval
        vm thread invoke-single-threaded
        `(read-msg)))
      (catch Exception e
        (trace "Unexpected exception in reply-pump %s" e)))
    (recur)))

(defn start-reply-pump
  [server vm]
  (let [thread (:msg-pump-thread vm)]
    (assert thread)
    (jdi-clj/eval vm thread invoke-single-threaded `(require 'ritz.nrepl.exec))
    (doto (Thread. (partial reply-pump vm thread))
      (.setName "Ritz-reply-msg-pump")
      (.setDaemon false)
      (.start))
    (trace "Reply pump started")))

;;; # Server set-up
(defn start-remote-thread
  "Start a remote thread in the specified vm context, using thread-name to
generate a name for the thread."
  [vm thread-name]
  (let [msg-thread-name (name (gensym thread-name))]
    (acquire-thread
     vm msg-thread-name
     (fn [context thread-name]
       (control-eval
        context (start-control-thread-body msg-thread-name))))))

(defn start-jpda-server
  [host port ack-port repl-port-path classpath]
  (println "start-jpda-server")
  (let [server (clojure.tools.nrepl.server/start-server
                :bind "localhost" :port 0 :ack-port ack-port
                :handler (ritz.nrepl/debug-handler host port))
        vm (launch-vm classpath `@(promise))
        msg-thread (start-remote-thread vm "msg-pump")
        vm (assoc vm :msg-pump-thread msg-thread)
        _ (ritz.nrepl/set-vm vm)
        port (-> server deref :ss .getLocalPort)]
    (vm-resume vm)
    (start-reply-pump server vm)
    (println "nREPL server started on port" port)
    (spit repl-port-path port)))
