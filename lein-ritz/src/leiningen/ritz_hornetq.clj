(ns leiningen.ritz-hornetq
  "Start a nrepl session over HornetQ."
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [leiningen.core.eval :as eval]
   [leiningen.core.project :as project]
   [clojure.tools.nrepl.server :as nrepl.server])
  (:use
   [clojure.tools.cli :only [cli]]
   [leiningen.core.main :only [debug]]))

(def nrepl-profile {:dependencies '[[org.clojure/tools.nrepl "0.2.1"
                                     :exclusions [org.clojure/clojure]]]})

(def ritz-profile {:dependencies '[[ritz/ritz-nrepl-hornetq "0.7.1-SNAPSHOT"
                                    :exclusions [org.clojure/clojure]]]})

(defn- start-nrepl-server
  "Start the nrepl server."
  [{{:keys [nrepl-middleware]} :repl-options :as project}
   hornetq-server-form hornetq-opts
   {:keys [log-level] :as opts}]
  {:pre [project]}
  (debug "start-nrepl-server hornetq-opts" hornetq-opts)
  (let [project (project/merge-profiles project [ritz-profile])
        form
        `(do
           ~hornetq-server-form
           (ritz.nrepl-hornetq/start-server
            ~(merge hornetq-opts opts))
           @(promise))]
    (eval/eval-in-project project form '(do (require 'ritz.nrepl-hornetq)))))

(defn hornetq-server-form
  [hornetq-server stomp]
  (let [opts {:netty hornetq-server :stomp stomp :in-vm true}]
    `(do
       (require '~'hornetq-clj.server)
       (let [server# (@(ns-resolve
                        '~'hornetq-clj.server
                        '~'server)
                      '~opts)]
         (.start server#)
         (doto
             (Thread.
              #(loop []
                 (when (.isStarted server#)
                   (Thread/sleep 10000)
                   (recur))))
           (.start))))))

(defn hornetq-netty-opts
  [port host user password]
  {:transport :netty
   :host (or host "localhost")
   :port port
   :user user
   :password password})

(defn hornetq-in-vm-opts
  []
  {:transport :in-vm})


(defn- hornetq-user [project]
  (or (System/getenv "LEIN_HORNETQ_USER")
      (-> project :repl-options :hornetq :user)
      ""))

(defn- hornetq-password [project]
  (or (System/getenv "LEIN_HORNETQ_PASSWORD")
      (-> project :repl-options :hornetq :password)
      ""))

(defn- hornetq-port [project]
  (Integer. ^String (or (System/getenv "LEIN_HORNETQ_PORT")
                        (-> project :repl-options :hornetq :port)
                        "5445")))

(defn- hornetq-host [project]
  (or (System/getenv "LEIN_HORNETQ_HOST")
      (-> project :repl-options :hornetq :host)
      "localhost"))

(defn integer-or-bool [^String arg]
  (println "integer-or-bool" arg)
  (try
    (Integer. arg)
    (catch Exception _
      (try
        (boolean (Boolean. arg))
        (catch Exception _
          arg)))))

(defn ^:no-project-needed ritz-hornetq
  "Start a ritz repl session over HornetQ."
  [project & args]
  (debug "ritz-hornetq")
  (let [[{:keys [log-level hornetq-server stomp user password] :as opts}
         [port host]]
        (cli args
             ["-l" "--log-level" "Set the log level" :default nil]
             ["-u" "--user" "HornetQ user name" :default ""]
             ["-p" "--password" "HornetQ password" :default ""]
             ["-h" "--hornetq-server" "Run a HornetQ server (can specify port)"
              :default nil :parse-fn integer-or-bool]
             ["-s" "--stomp"
              "Enable STOMP in the HornetQ server (can specify port)"
              :default nil :parse-fn integer-or-bool])
        opts (update-in opts [:log-level] #(when % (keyword %)))
        run-hornetq? (or hornetq-server stomp)
        port (or port (hornetq-port project))
        host (or host (hornetq-host project))
        user (or user (hornetq-user project))
        password (or password (hornetq-password project))]
    (debug "ritz-hornetq opts" opts)
    (start-nrepl-server
     project
     (when run-hornetq? (hornetq-server-form hornetq-server stomp))
     (if run-hornetq?
       (hornetq-in-vm-opts)
       (hornetq-netty-opts port host user password))
     opts)))
