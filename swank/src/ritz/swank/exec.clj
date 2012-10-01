(ns ritz.swank.exec
  "Execute commands using swankx"
  (:use
   [clojure.stacktrace :only [print-cause-trace]]
   [ritz.repl-utils.classloader
    :only [configurable-classpath? eval-clojure has-classloader?
           requires-reset? files-to-urls]]
   [ritz.repl-utils.clojure :only [feature-cond]]
   [ritz.repl-utils.namespaces :only [namespaces-reset namespace-state]]
   [ritz.swank :only [handle-message setup-connection]]
   [ritz.swank.jdi-connection :only [make-connection read-sent release-queue]]
   [ritz.logging :only [current-log-level set-level trace]]))

;;; ## basic execution
(defonce connection (atom nil))
(defonce namespaces (atom nil))
(defonce wait-for-reinit (atom nil))

(defn set-connection!
  [t]
  (reset! connection t))

(defn current-connection
  [] @connection)

(defn exec
  "Execute a swank message, using the current connection."
  [fwd-connection message]
  (trace "exec handle %s" message)
  (handle-message (merge @connection fwd-connection) message))

(defn read-msg
  []
  (trace "swank/read-msg")
  (if @connection
    (read-sent @connection)
    (Thread/sleep 1000)))

(defn connection->cl
  [connection]
  (let [cl-kw (fn [kw] (keyword (.getName kw)))]
    (zipmap (map cl-kw (keys connection)) (vals connection))))

(defn exec-using-classloader
  "Execute a message using the classloader specified classpath if possible."
  [connection message]
  (trace "exec-using-classloader %s" (fnext message))
  (feature-cond
   (configurable-classpath?)
   (if (has-classloader?)
     (try
       (eval-clojure
        `(fn [connection# message#]
           (exec (connection->cl connection#) (read-string message#)))
        connection (pr-str message))
       (catch Exception e
         (trace "exec-using-classloader failed %s" e)
         (trace "exec-using-classloader %s"
                (with-out-str (print-cause-trace e)))
         (.println System/err e)
         (.printStackTrace e)))
     (do
       (trace "exec-using-classloader No classloader yet")
       (Thread/sleep 1000)))
   :else (exec connection message)))

(defn read-msg-using-classloader
  "Read a message using the classloader specified classpath if possible."
  []
  (feature-cond
   (configurable-classpath?)
   (if (has-classloader?)
     (do
       (when-let [p @wait-for-reinit]
         (trace "read-msg waiting for re-init")
         @p
         (trace "read-msg re-init received")
         (reset! wait-for-reinit nil)) ; this helps debugging but intros a race
       (eval-clojure `(try
                        (read-msg)
                        (catch Exception e#
                          (trace "Exception in read-msg %s" e#)))))
     (do
       (trace "read-msg-using-classloader No classloader yet")
       (Thread/sleep 1000)))
   :else (read-msg)))

(defn eval-using-classloader
  "Eval a form using the classloader specified classpath if possible."
  [form]
  (feature-cond
   (configurable-classpath?)
   (if (has-classloader?)
     (eval-clojure form)
     (do
       (trace "eval-using-classloader No classloader yet")
       (Thread/sleep 1000)))
   :else (eval form)))


(defn maybe-set-namespaces!
  []
  (feature-cond
   (configurable-classpath?)
   (if (has-classloader?)
     (eval-clojure `(when-not @namespaces
                      (reset! namespaces
                              (remove #(= 'user %) (namespace-state)))))
     (do
       (trace "reset-namespaces! No classloader yet")
       (Thread/sleep 1000)))
   :else (when-not @namespaces
           (reset! namespaces (namespace-state)))))

(defn reset-namespaces!
  []
  (feature-cond
   (configurable-classpath?)
   (if (has-classloader?)
     (eval-clojure `(when @namespaces
                      (namespaces-reset @namespaces)
                      (ns user)))
     (do
       (trace "reset-namespaces! No classloader yet")
       (Thread/sleep 1000)))
   :else (when @namespaces
             (namespaces-reset @namespaces))))

(defn block-reply-loop
  []
  (reset! wait-for-reinit (promise)))

(defn set-extra-classpath!
  [files]
  (ritz.repl-utils.classloader/set-extra-classpath! files))

(defn set-classpath!
  [files]
  (try
    (when (configurable-classpath?)
      (let [{:keys [reset? new-cl?] :as flags} (requires-reset? files)
            contribs (when (has-classloader?)
                       (eval-clojure
                        `(deref ritz.swank.commands.contrib/loaded-contribs)))]
        (trace "set-classpath!/loaded contribs %s" contribs)
        (trace "set-classpath!/release queue %s" flags)
        (when new-cl?
          (trace "set-classpath!/set wait-for-reinit")
          (block-reply-loop))
        (when (and (has-classloader?) new-cl?)
          (eval-clojure `(when (current-connection)
                           (release-queue (current-connection)))))
        (trace "set-classpath!/maybe set namespaces")
        (maybe-set-namespaces!)
        (when reset?
          (reset-namespaces!))
        (trace "set-classpath!/set classpath")
        (ritz.repl-utils.classloader/set-classpath! files)
        (eval-clojure '(require 'ritz.swank.exec 'ritz.logging
                                'ritz.swank.commands.basic
                                'ritz.swank.commands.inspector
                                'ritz.swank.commands.completion
                                'ritz.swank.commands.contrib))
        (when-let [level (current-log-level)]
          (eval-clojure `(set-level ~level)))
        (when reset?
          (trace "set-classpath!/set connection")
          (eval-clojure
           `(set-connection! (setup-connection (make-connection {}))))
          (eval-clojure
           `(ritz.swank.commands.contrib/swank-require
             (current-connection)
             ~(vec (map (fn [k] (list 'quote k)) contribs)))))
        (trace "set-classpath!/maybe set namespaces again")
        (maybe-set-namespaces!)
        (when new-cl?
          (trace "set-classpath!/deliver wait-for-reinit")
          (deliver @wait-for-reinit nil))
        nil))
    (catch Exception e
      (trace "set-classpath! exception %s" e)
      (println e)
      (trace (with-out-str (print-cause-trace e)))
      (throw e))))

;;; ## Execute arbitrary code in the controlled vm
(defn eval-with-classpath
  "Eval a form using the classloader specified classpath if possible."
  [files & forms]
  (feature-cond
   (configurable-classpath?)
   (let [cl (classlojure.core/classlojure (files-to-urls files))]
     (doseq [form forms]
       (classlojure.core/eval-in cl form)))
   :else (throw (Exception. "eval-with-classpath unsupported"))))
