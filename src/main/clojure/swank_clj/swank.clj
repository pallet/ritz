(ns swank-clj.swank
  "Swank protocol"
  (:require
   [swank-clj.executor :as executor]
   [swank-clj.debug :as debug]
   [swank-clj.logging :as logging]
   [swank-clj.connection :as connection]
   [swank-clj.commands :as commands]
   [swank-clj.swank.core :as core])
  (:import
   java.io.InputStreamReader
   java.io.OutputStreamWriter
   java.util.concurrent.TimeUnit
   java.util.concurrent.Future
   java.util.concurrent.CancellationException
   java.util.concurrent.ExecutionException
   java.util.concurrent.TimeoutException))

(defmacro with-package [package & body]
  `(binding [*ns* (core/maybe-ns ~package)
             core/*current-package* (core/maybe-ns ~package)]
     ~@body))

(defn- cmd [cmd]
  (if (.startsWith cmd "swank:") (.substring cmd 6) cmd))

(defn command-not-found [connection form buffer-package id]
  (logging/trace
   "swank/eval-for-emacs: could not find fn %s" (first form))
  `(:return (:abort) ~id))

(defn eval-for-emacs [connection form buffer-package id]
  (logging/trace "swank/eval-for-emacs: %s %s %s" form buffer-package id)
  (try
    (connection/add-pending-id connection id)
    (if-let [f (commands/slime-fn (cmd (name (first form))))]
      (let [result (with-package buffer-package
                     (apply f (eval (vec (rest form)))))]
        ;; TODO (run-hook *pre-reply-hook*)
        (logging/trace "swank/eval-for-emacs: result %s %s" result id)
        (connection/send-to-emacs connection `(:return (:ok ~result) ~id))
        (connection/remove-pending-id connection id))
      ;; swank function not defined, abort
      (command-not-found connection form buffer-package id))
    (catch Throwable t
      ;; Thread/interrupted clears this thread's interrupted status; if
      ;; Thread.stop was called on us it may be set and will cause an
      ;; InterruptedException in one of the send-to-emacs calls below
      (.printStackTrace t)
      (logging/trace
       "swank/eval-for-emacs: exception %s %s"
       (pr-str t)
       (with-out-str (.printStackTrace t)))
      ;;(Thread/interrupted)
      (connection/send-to-emacs connection`(:return (:abort) ~id))
      ;; (finally
      ;;  (connection/remove-pending-id connection id))
      )))

(def repl-futures (atom []))

(def forward-rpc nil)

(defn emacs-interrupt [connection thread-id args]
  (logging/trace "swank/interrupt: %s %s" thread-id args)
  (if forward-rpc
    (forward-rpc connection `(:emacs-interrupt ~thread-id ~@args))
    (doseq [future @repl-futures]
      (.cancel future true))))

(defn emacs-return-string [connection thread-id tag value]
  (logging/trace "swank/read-string: %s %s" thread-id tag value)
  (if forward-rpc
    (forward-rpc connection `(:emacs-return-string ~thread-id ~tag ~value))
    (connection/write-to-input connection tag value)))

(defn dispatch-event
  "Executes a message."
  [ev connection]
  (logging/trace "swank/dispatch-event: %s" (pr-str ev))
  (when debug/vm
    (debug/ensure-exception-event-request))
  (let [[action & args] ev]
    (logging/trace "swank/dispatch-event: %s -> %s" action (pr-str args))
    (case action
      :emacs-rex
      (let [[form-string package thread id] args]
        (logging/trace
         "swank/dispatch-event: :emacs-rex %s %s %s %s"
         form-string package thread id)
        (let [last-values (:last-values @connection)]
          (binding [*1 (first last-values)
                    *2 (second last-values)
                    *3 (nth last-values 2)
                    *e (:last-exception @connection)
                    core/*current-connection* connection
                    *out* (:writer-redir @connection)
                    *in* (:input-redir @connection)]
            (eval-for-emacs connection form-string package id))))

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
      ;;     (.stop #^Thread (first @*active-threads*))
      ;;     (= thread :repl-thread) (.stop #^Thread @(connection :repl-thread)))))
      :else
      (do
        (logging/trace "swank/dispatch-event: invalid command %s" action)
        nil))))


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


(defn handle-message
  "Handle a message on a connection."
  [connection message]
  (logging/trace "swank/handle-message %s" message)
  (let [future (executor/execute #(dispatch-event message connection))]
    (swap! repl-futures (fn [futures]
                          (conj futures future)
                          (remove #(.isDone %) futures)))
    (executor/execute #(response future message connection))))
