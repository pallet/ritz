(ns ritz.nrepl
  "nrepl support for ritz"
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
   [ritz.nrepl.rexec :only [rexec]])
  (:require
   [clojure.java.io :as io]
   [clojure.tools.nrepl.transport :as transport]
   [ritz.jpda.jdi-clj :as jdi-clj]))

(set-level :trace)

(defonce vm (atom nil))

(defn set-vm [vm]
  (reset! ritz.nrepl/vm vm))

(defonce pending-ids (atom {}))

(defonce connections (atom {}))

(defn connect
  [host port]
  (let [c (connector :attach-socket)]
    (.attach
     c
     (connector-args c {"port" port "hostname" (or host "localhost")}))))

(def default-connection
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

(defn make-connection
  [host port]
  ;; (assoc default-connection
  ;;   :vm (connect host port))
  (assoc default-connection
    :vm @vm))

(defn connection-for [host port]
  (let [m {:host host :port port}]
    (or (get @connections m)
        (let [connection (make-connection host port)]
          (swap! connections assoc m connection)
          connection))))

(defn execute-jpda
  "Execute a jpda action"
  [host port {:keys [op transport] :as msg}]
  (trace "execute-jpda %s" op)
  (let [connection (connection-for host port)]
    (jpda-op (keyword op) connection msg)))

(defn execute-eval
  "Execute a jpda action"
  [host port {:keys [op transport] :as msg}]
  (trace "execute-jpda %s" op)
  (let [connection (connection-for host port)]
    (rexec (:vm connection) msg)))

(defn return-execute-jpda
  [host port {:keys [op transport] :as msg}]
  (let [value (execute-jpda host port msg)]
    (transport/send
     transport
     (response-for msg :status :done :value value :op op))
    value))

(defn return-execute-eval
  [host port {:keys [id op transport] :as msg}]
  (let [value (execute-eval host port msg)]
    (trace "return-execute-eval %s" value)
    (swap! pending-ids assoc id transport)
    value))

(defn jpda-handler
  "Handler for jpda actions"
  [host port]
  (fn [handler]
    (fn [{:keys [op transport] :as msg}]
      (if (= op "jpda")
        (return-execute-jpda host port msg)
        (handler msg)))))

(defn jpda-eval-middleware
  []
  (-> unknown-op interruptible-eval pr-values add-stdin))

(defn jpda-eval-handler
  "Handler for jpda actions"
  []
  (let [mw (jpda-eval-middleware)]
    (fn [handler]
      (fn [{:keys [op transport] :as msg}]
        (if (= op "jpda-eval")
          (do
            (trace "jpda-eval-handler %s" msg)
            (mw (assoc msg :op "eval")))
          (handler msg))))))

(defn rexec-handler
  "Handler for jpda actions"
  [host port]
  (fn [handler]
    (fn [{:keys [op transport] :as msg}]
      (if (= op "eval")
        (return-execute-eval host port msg)
        (handler msg)))))

(defn debug-handler
  "nrepl handler with debug support"
  [host port]
  (let [rexec (rexec-handler host port)
        jpda (jpda-handler host port)
        jpda-eval (jpda-eval-handler)]
    (-> unknown-op rexec jpda-eval jpda session ;; add-stdin session
        )))

(defn start-reply-pump
  [server vm]
  (let [transport (:transport @server)
        thread (:msg-pump-thread vm)]
    (assert transport)
    (assert thread)
    (jdi-clj/eval
     vm thread invoke-single-threaded
     `(require 'ritz.nrepl.exec))
    (doto (Thread.
           (fn []
             (loop []
               (try
                 (let [{:keys [id status] :as msg} (jdi-clj/eval
                                             vm thread invoke-single-threaded
                                             `(read-msg))]
                   (trace "Read reply %s" msg)
                   (transport/send (get @pending-ids id) msg)
                   (trace "Reply forwarded")
                   (when (= :done status)
                     (swap! pending-ids dissoc id)))
                 (catch Exception e
                   (trace "Unexpected exception in reply-pump %s" e)))
               (recur))))
      (.setName "Ritz-reply-msg-pump")
      (.setDaemon false)
      (.start))))

(defn start-jpda-server
  [host port ack-port repl-port-path classpath]
  (println "start-jpda-server")
  (let [server (clojure.tools.nrepl.server/start-server
                :bind "localhost" :port 0 :ack-port ack-port
                :handler (ritz.nrepl/debug-handler host port))
        vm (launch-vm classpath `@(promise))
        msg-thread-name "msg-pump"
        msg-thread (acquire-thread
                    vm msg-thread-name
                    (fn [context thread-name]
                      (control-eval
                       context (start-control-thread-body msg-thread-name))))
        vm (assoc vm :msg-pump-thread msg-thread)
        _ (ritz.nrepl/set-vm vm)
        port (-> server deref :ss .getLocalPort)]
    (vm-resume vm)
    (start-reply-pump server vm)
    (println "nREPL server started on port" port)
    (spit repl-port-path port)))


;; (defn- pump [reader out]
;;   (let [buffer (make-array Character/TYPE 1000)]
;;     (loop [len (.read reader buffer)]
;;       (when-not (neg? len)
;;         (.write out buffer 0 len)
;;         (.flush out)
;;         (Thread/sleep 100)
;;         (recur (.read reader buffer))))))

;; (defn sh
;;   "A version of clojure.java.shell/sh that streams out/err."
;;   [& cmd]
;;   (let [env (System/getenv)
;;         dir (System/getProperty "user.dir")
;;         proc (.exec (Runtime/getRuntime) (into-array cmd) env (io/file dir))]
;;     (.addShutdownHook (Runtime/getRuntime)
;;                       (Thread. (fn [] (.destroy proc))))
;;     (with-open [out (io/reader (.getInputStream proc))
;;                 err (io/reader (.getErrorStream proc))]
;;       (let [pump-out (doto (Thread. (bound-fn [] (pump out *out*))) .start)
;;             pump-err (doto (Thread. (bound-fn [] (pump err *err*))) .start)]
;;         (.join pump-out)
;;         (.join pump-err))
;;       (.waitFor proc))))

;; ;; work around java's command line handling on windows
;; ;; http://bit.ly/9c6biv This isn't perfect, but works for what's
;; ;; currently being passed; see http://www.perlmonks.org/?node_id=300286
;; ;; for some of the landmines involved in doing it properly
;; (defn- form-string [form]
;;   (if (= (get-os) :windows)
;;     (pr-str (pr-str form))
;;     (pr-str form)))

;; (defn- classpath-arg [project]
;;   (if (:bootclasspath project)
;;     [(apply str "-Xbootclasspath/a:"
;;             (interpose java.io.File/pathSeparatorChar
;;                        (classpath/get-classpath project)))]
;;     ["-cp" (string/join java.io.File/pathSeparatorChar
;;                         (classpath/get-classpath project))]))

;; (defn shell-command [project form]
;;   `(~(or (:java-cmd project) (System/getenv "JAVA_CMD") "java")
;;     ~@(classpath-arg project)
;;     ~@(get-jvm-args project)
;;     "clojure.main" "-e" ~(form-string form)))

;; (defn eval-in-subprocess
;;   [project form]
;;   (let [exit-code (apply sh (shell-command project form))]
;;     (when (pos? exit-code)
;;       (throw (ex-info "Subprocess failed" {:exit-code exit-code})))))

;; (defn eval-in-project
;;   "Executes form in isolation with the classpath and compile path set correctly
;;   for the project. If the form depends on any requires, put them in the init arg
;;   to avoid the Gilardi Scenario: http://technomancy.us/143"
;;   [project form init]
;;   (prep project)
;;   (eval-in project
;;            `(do ~init
;;                 ~@(:injections project)
;;                 (set! ~'*warn-on-reflection*
;;                       ~(:warn-on-reflection project))
;;                 ~form)))


;; (defn start-nrepl-server
;;   "Start the user nrepl server"
;;   [project host port ack-port {:keys [headless? debug?]}]
;;   (println "start-jpda-server user repl")
;;   (trace "start-nrepl-server: host %s port %s project %s"
;;     host port project)
;;   (let [server-starting-form
;;         `(let [server# (clojure.tools.nrepl.server/start-server
;;                         :bind ~host :port ~port :ack-port ~ack-port)
;;                port# (-> server# deref :ss .getLocalPort)]
;;            (println "nREPL user server started on port" port#)
;;            (spit
;;             ~(str (io/file (:target-path project) "user-repl-port")) port#))]
;;     (eval-in-project
;;      project
;;      server-starting-form
;;      '(do
;;         (require
;;          'clojure.tools.nrepl.server
;;          'complete.core)))))
