(ns ritz.nrepl.exec
  "Execute commands using nrepl"
  (:use
   [clojure.stacktrace :only [print-cause-trace]]
   [clojure.tools.nrepl.server :only [handle*]]
   [ritz.nrepl.handler :only [default-handler]]
   [ritz.nrepl.transport :only [make-transport read-sent release-queue]]
   [ritz.repl-utils.classloader
    :only [configurable-classpath? eval-clojure has-classloader?
           requires-reset? files-to-urls]]
   [ritz.repl-utils.clojure :only [feature-cond]]
   [ritz.repl-utils.namespaces :only [namespaces-reset namespace-state]]
   [ritz.logging :only [set-level trace]]))

;;; ## basic execution
(defonce transport (atom nil))
(defonce middleware (atom nil))
(defonce handler (atom nil))
(defonce namespaces (atom nil))

(defn set-handler!
  [handler-fn]
  (reset! handler handler-fn))

(defn set-transport!
  [t]
  (reset! transport t))

(defn current-transport
  [] @transport)

(defn exec
  "Execute an nREPL message, using the current handler and transport."
  [msg]
  (trace "exec handle %s" msg)
  (handle* msg @handler @transport))

(defn read-msg
  "Read a message from the reply queue in the current connection."
  []
  (if @transport
    (let [reply (read-sent @transport)]
      (trace "read-msg read %s" reply)
      reply)
    (Thread/sleep 1000)))

;;; ## classloader aware execution
(defonce wait-for-reinit (atom nil))

(defn msg->cl
  [msg]
  (let [cl-kw (fn [kw] (keyword (.getName kw)))]
    (zipmap (map cl-kw (keys msg)) (vals msg))))

(defn exec-using-classloader
  "Execute a msg using the classloader specified classpath if possible."
  [msg]
  (feature-cond
   (configurable-classpath?)
   (if (has-classloader?)
     (eval-clojure
      `(fn [msg#] (exec (msg->cl msg#))) msg)
     (do
       (trace "exec-using-classloader No classloader yet")
       (Thread/sleep 1000)))
   :else (exec msg)))

(defn read-msg-using-classloader
  "Read a message using the classloader specified classpath if possible."
  []
  (try
    (feature-cond
     (configurable-classpath?)
     (if (has-classloader?)
       (do
         (when-let [p @wait-for-reinit]
           (trace "read-msg waiting for re-init")
           @p
           (trace "read-msg re-init received")
           (reset! wait-for-reinit nil)) ; this helps debugging but is a race
         (trace "read-msg-using-classloader calling eval-clojure")
         (eval-clojure `(try
                          (read-msg)
                          (catch Exception e#
                            (trace "Exception in read-msg %s" e#)
                            {:exception
                             (with-out-str (print-cause-trace e#))}))))
       (do
         (trace "read-msg-using-classloader No classloader yet")
         (Thread/sleep 1000)))
     :else (read-msg))
    (catch Exception e
      (trace "read-msg-using-classloader exception %s"
             (with-out-str (print-cause-trace e)))
      (throw e))))

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

(defn set-middleware!
  [mw]
  (reset! middleware mw))

(defn reset-middleware!
  []
  (if (configurable-classpath?)
    (do
      (eval-clojure
         `(do
            (require 'ritz.nrepl.handler 'ritz.nrepl.exec)
            (doseq [ns# ~(vec
                          (map
                           #(list `quote (symbol (namespace %)))
                           @middleware))]
              (require ns#))
            nil))
      (eval-clojure
       `(do
          (set-handler! (default-handler ~@@middleware))
          nil)))
    (do
      (require 'ritz.nrepl.handler 'ritz.nrepl.exec)
      (doseq [ns (map (comp ns-name namespace) @middleware)]
        (require ns))
      (set-handler! (apply default-handler (map resolve @middleware)))
      nil)))


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

(defn set-extra-classpath!
  [files]
  (ritz.repl-utils.classloader/set-extra-classpath! files))

(defn set-classpath!
  [files]
  (try
    (when (configurable-classpath?)
      (let [{:keys [reset? new-cl?] :as flags} (requires-reset? files)]
        (trace "set-classpath!/release queue %s" flags)
        (when new-cl?
          (trace "set-classpath!/set wait-for-reinit")
          (reset! wait-for-reinit (promise)))
        (when (and (has-classloader?) new-cl?)
          (eval-clojure `(when (current-transport)
                           (release-queue (current-transport)))))
        (trace "set-classpath!/maybe set namespaces")
        (maybe-set-namespaces!)
        (when reset?
          (reset-namespaces!))
        (trace "set-classpath!/set classpath")
        (ritz.repl-utils.classloader/set-classpath! files)
        (eval-clojure '(require 'ritz.nrepl.exec 'ritz.logging))
        (when reset?
          (trace "set-classpath!/set middleware")
          (reset-middleware!)
          (trace "set-classpath!/set transport")
          (eval-clojure `(set-transport! (make-transport {}))))
        (trace "set-classpath!/maybe set namespaces again")
        (maybe-set-namespaces!)
        (when new-cl?
          (trace "set-classpath!/deliver wait-for-reinit")
          (deliver @wait-for-reinit nil))))
    (catch Exception e
      (trace "set-classpath! exception %s" e)
      (println e)
      (print-cause-trace e))))

(defn set-log-level
  [level]
  (set-level level)
  (eval-clojure `(set-level ~level)))

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
