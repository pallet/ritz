(ns ritz.swank
  "Swank protocol"
  (:require
   [ritz.connection :as connection]
   [ritz.jpda.debug :as debug]
   [ritz.executor :as executor]
   [ritz.hooks :as hooks]
   [ritz.logging :as logging]
   [ritz.repl-utils.helpers :as helpers]
   [ritz.swank.core :as core]
   [ritz.swank.commands :as commands]
   [ritz.swank.indent :as indent]
   [ritz.swank.messages :as messages])
  (:import
   java.io.InputStreamReader
   java.io.OutputStreamWriter
   java.util.concurrent.TimeUnit
   java.util.concurrent.Future
   java.util.concurrent.CancellationException
   java.util.concurrent.ExecutionException
   java.util.concurrent.TimeoutException))

(def default-pipeline
  (core/execute-slime-fn core/command-not-found))

(defn eval-for-emacs [connection form buffer-package id]
  (logging/trace "swank/eval-for-emacs: %s %s %s" form buffer-package id)
  (try
    (connection/request! connection buffer-package id)
    (let [f (commands/slime-fn (first form))
          handler (or (connection/swank-handler connection) default-pipeline)
          result (handler connection form buffer-package id f)]
      (cond
       (= ::abort result) (do
                            (connection/send-to-emacs
                             connection (messages/abort id))
                            (connection/remove-pending-id connection id))
       (and
        (vector? result)
        (= ::abort (first result))) (do
        (connection/send-to-emacs
         connection
         (messages/abort id (second result)))
        (connection/remove-pending-id
         connection id))
       (= ::pending result) (logging/trace
                             "swank/eval-for-emacs: pending %s" id)
       :else (do
               (hooks/run core/pre-reply-hook connection)
               (connection/remove-pending-id connection id)
               (logging/trace "swank/eval-for-emacs: result %s %s" result id)
               (connection/send-to-emacs connection (messages/ok result id)))))
    (catch Throwable t
      ;; Thread/interrupted clears this thread's interrupted status; if
      ;; Thread.stop was called on us it may be set and will cause an
      ;; InterruptedException in one of the send-to-emacs calls below
      (logging/trace
       "swank/eval-for-emacs: exception %s %s"
       (pr-str t)
       (helpers/stack-trace-string t))
      (.printStackTrace t)
      ;;(Thread/interrupted)
      (connection/send-to-emacs connection (messages/abort id t))
      ;; (finally
      ;;  (connection/remove-pending-id connection id))
      )))

(def repl-futures (atom []))

(def forward-rpc nil)

(defn emacs-interrupt [connection thread-id args]
  (logging/trace "swank/interrupt: %s %s" thread-id args)
  (if forward-rpc
    (forward-rpc connection `(:emacs-interrupt ~thread-id ~@args)))
  (doseq [future @repl-futures]
    (.cancel future true)))

(defn emacs-return-string [connection thread-id tag value]
  (logging/trace "swank/read-string: %s %s" thread-id tag value)
  (if forward-rpc
    (forward-rpc connection `(:emacs-return-string ~thread-id ~tag ~value))
    (connection/write-to-input connection tag value)))

(defn dispatch-event
  "Executes a message."
  [ev connection]
  (logging/trace "swank/dispatch-event: %s" (pr-str ev))
  (let [[action & args] ev]
    (logging/trace "swank/dispatch-event: %s -> %s" action (pr-str args))
    (case action
      :emacs-rex
      (let [[form-string package thread id] args]
        (logging/trace
         "swank/dispatch-event: :emacs-rex %s %s %s %s"
         form-string package thread id)
        (let [last-values (:result-history @connection)]
          (try
            (clojure.main/with-bindings
              (binding [*1 (first last-values)
                        *2 (fnext last-values)
                        *3 (first (nnext last-values))
                        *e (:last-exception @connection)
                        *out* (:writer-redir @connection)
                        *in* (:input-redir @connection)]
                (try
                  (eval-for-emacs connection form-string package id)
                  (finally (flush)))))
            (finally (flush)))))

      :emacs-return-string
      (let [[thread tag value] args]
        (emacs-return-string connection thread tag value))

      :emacs-interrupt
      (let [[thread & args] args]
        (emacs-interrupt connection thread args))

      ;; (= action :return)
      ;; (let [[thread & ret] args]
      ;;   (binding [*print-level* nil, *print-length* nil]
      ;;     (write-to-connection connection `(:return ~@ret))))

      ;; (one-of? action
      ;;          :presentation-start :presentation-end
      ;;          :new-package :new-features :ed :percent-apply
      ;;          :indentation-update
      ;;          :eval-no-wait :background-message :inspect)
      ;; (binding [*print-level* nil, *print-length* nil]
      ;;   (write-to-connection connection ev))

      ;; (= action :write-string)
      ;; (write-to-connection connection ev)

      ;; (one-of? action
      ;;          :debug :debug-condition :debug-activate :debug-return)
      ;; (let [[thread & args] args]
      ;;   (write-to-connection connection `(~action ~(thread-map-id thread) ~@args)))

      ;; (= action :emacs-interrupt)
      ;; (let [[thread & args] args]
      ;;   (dosync
      ;;    (cond
      ;;     (and (true? thread) (seq @*active-threads*))
      ;;     (.stop ^Thread (first @*active-threads*))
      ;;     (= thread :repl-thread) (.stop ^Thread @(connection :repl-thread)))))
      :else
      (do
        (logging/trace "swank/dispatch-event: invalid command %s" action)
        nil))))


(defn- response
  "Respond and act as watchdog"
  [^Future future form connection]
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


(defn send-repl-results-to-emacs
  [connection values]
  (flush)
  (if values
    (doseq [v values]
      (core/write-result-to-emacs connection v))
    (core/write-result-to-emacs connection "; No value")))

(defn setup-connection
  [connection]
  (swap! connection
         assoc :send-repl-results-function send-repl-results-to-emacs)
  connection)

(defn handle-message
  "Handle a message on a connection."
  [connection message]
  (logging/trace "swank/handle-message %s" message)
  (let [future (executor/execute #(dispatch-event message connection))]
    (swap! repl-futures (fn [futures]
                          (conj (remove #(.isDone %) futures) future)))
    (executor/execute #(response future message connection))))
