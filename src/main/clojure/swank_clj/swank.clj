(ns swank-clj.swank
  "Swank protocol"
  (:require
   [swank-clj.logging :as logging]
   [swank-clj.connection :as connection]
   [swank-clj.commands :as commands]
   [swank-clj.swank.core :as core]))

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
    ;; (connection/add-pending-id connection id)
    (if-let [f (commands/slime-fn (cmd (name (first form))))]
      (let [result (with-package buffer-package
                     (apply f (eval (vec (rest form)))))]
        ;; TODO (run-hook *pre-reply-hook*)
        (logging/trace "swank/eval-for-emacs: result %s %s" result id)
        (connection/send-to-emacs connection `(:return (:ok ~result) ~id)))
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
      (connection/send-to-emacs connection`(:return (:abort) ~id)))
    ;; (finally
    ;;  (connection/remove-pending-id connection id))
    ))

(defn dispatch-event
  "Executes a message."
  [ev connection]
  (logging/trace "swank/dispatch-event: %s" (pr-str ev))
  (let [[action & args] ev]
    (logging/trace "swank/dispatch-event: %s -> %s" action (pr-str args))
    (cond
     (= action :emacs-rex)
     (let [[form-string package thread id] args]
       (logging/trace
        "swank/dispatch-event: :emacs-rex %s %s %s %s"
        form-string package thread id)
       (let [last-values (:last-values @connection)]
         (binding [*1 (first last-values)
                   *2 (second last-values)
                   *3 (nth last-values 2)
                   *e (:last-exception @connection)
                   core/*current-connection* connection]
           (eval-for-emacs connection form-string package id))))

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
