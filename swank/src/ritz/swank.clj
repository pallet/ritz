(ns ritz.swank
  "Swank protocol"
  (:require
   [ritz.debugger.executor :as executor]
   [ritz.jpda.debug :as debug]
   [ritz.repl-utils.helpers :as helpers]
   [ritz.swank.commands :as commands]
   [ritz.swank.connection :as connection]
   [ritz.swank.core :as core]
   [ritz.swank.hooks :as hooks]
   [ritz.swank.messages :as messages]
   ritz.swank.connections)
  (:use
   [ritz.debugger.break :only [clear-aborts]]
   [ritz.debugger.connection
    :only [bindings bindings-assoc!]
    :rename {bindings connection-bindings}]
   [ritz.jpda.debug
    :only [add-connection-for-event-fn! add-all-connections-fn!]]
   [ritz.jpda.swell :only [with-swell]]
   [ritz.logging :only [trace]])
  (:import
   java.io.InputStreamReader
   java.io.OutputStreamWriter
   java.util.concurrent.TimeUnit
   java.util.concurrent.Future
   java.util.concurrent.CancellationException
   java.util.concurrent.ExecutionException
   java.util.concurrent.TimeoutException))

(add-connection-for-event-fn!
 ritz.swank.connections/connection-for-event)

(add-all-connections-fn!
 ritz.swank.connections/all-connections)

(def default-pipeline
  (core/execute-slime-fn core/command-not-found))

(defn eval-for-emacs [connection form buffer-package thread id]
  (trace "swank/eval-for-emacs: %s %s %s" form buffer-package id)
  (try
    (core/record-namespace-state!)
    (let [connection (connection/request connection buffer-package thread id)
          f (commands/slime-fn (first form))
          handler (or (connection/swank-handler connection) default-pipeline)
          _ (clear-aborts connection)
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
       (= ::pending result) (trace
                             "swank/eval-for-emacs: pending %s" id)
       :else (do
               (hooks/run core/pre-reply-hook connection)
               (connection/remove-pending-id connection id)
               (trace "swank/eval-for-emacs: result %s %s" result id)
               (connection/send-to-emacs connection (messages/ok result id)))))
    (catch Throwable t
      ;; Thread/interrupted clears this thread's interrupted status; if
      ;; Thread.stop was called on us it may be set and will cause an
      ;; InterruptedException in one of the send-to-emacs calls below
      (trace
       "swank/eval-for-emacs: exception %s %s"
       (pr-str t)
       (helpers/stack-trace-string t))
      (.printStackTrace t) (flush)
      (bindings-assoc! connection #'*e t)
      (connection/send-to-emacs connection (messages/abort id t)))))

(def repl-futures (atom []))

(def forward-rpc nil)

(defn emacs-interrupt [connection thread-id args]
  (trace "swank/interrupt: %s %s" thread-id args)
  (if forward-rpc
    (forward-rpc connection `(:emacs-interrupt ~thread-id ~@args)))
  (doseq [^Future future @repl-futures]
    (.cancel future true)))

(defn emacs-return-string [connection thread-id tag value]
  (trace "swank/read-string: %s %s" thread-id tag value)
  (if forward-rpc
    (forward-rpc connection `(:emacs-return-string ~thread-id ~tag ~value))
    (connection/write-to-input connection tag value)))

(defn dispatch-event
  "Executes a message."
  [ev connection]
  (trace "swank/dispatch-event: %s" (pr-str ev))
  (let [[action & args] ev]
    (trace "swank/dispatch-event: %s -> %s" action (pr-str args))
    (case action
      :emacs-rex
      (let [[form package thread id] args]
        (trace
         "swank/dispatch-event: :emacs-rex %s %s %s %s"
         form package thread id)
        (try
          (with-bindings (connection-bindings connection)
            (trace "swank/dispatch-event: with-bindings")
            (with-swell
              (binding [*out* (or (:writer-redir connection) *out*)
                        *in* (or (:input-redir connection) *in*)
                        *ns* (the-ns @(:namespace connection))]
                (try
                  (trace "calling eval-for-emacs on %s" form)
                  (eval-for-emacs connection form package thread id)
                  (finally (flush))))))
          (finally (flush))))

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
        (trace "swank/dispatch-event: invalid command %s" action)
        nil))))


(defn- response
  "Respond and act as watchdog"
  [^Future future form connection]
  (try
    (trace "response with timeout %s" (:timeout connection))
    (let [timeout (:timeout connection)
          result (if timeout
                   (.get future timeout TimeUnit/MILLISECONDS)
                   (.get future))])
    (catch CancellationException e
      (connection/send-to-emacs connection '(:background-message "cancelled")))
    (catch TimeoutException e
      (connection/send-to-emacs connection '(:background-message "timeout")))
    (catch ExecutionException e
      (.printStackTrace e)
      (connection/send-to-emacs connection '(:background-message "server-failure")))
    (catch InterruptedException e
      (.printStackTrace e)
      (connection/send-to-emacs connection '(:background-message "server-failure")))))


(defn send-repl-results-to-emacs
  [connection values]
  (flush)
  (if values
    (doseq [v values]
      (core/write-result-to-emacs connection v))
    (core/write-result-to-emacs connection "; No value")))

(defn setup-connection
  [connection]
  (assoc connection :send-repl-results-function send-repl-results-to-emacs))

(defn handle-message
  "Handle a message on a connection."
  [connection message]
  (trace "swank/handle-message %s" message)
  (let [future (executor/execute-request #(dispatch-event message connection))]
    (trace "swank/handle-message future started")
    (swap! repl-futures (fn [futures]
                          (conj (remove #(.isDone ^Future %) futures) future)))
    (executor/execute-request #(response future message connection))))
