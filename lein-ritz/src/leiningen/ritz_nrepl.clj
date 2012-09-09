(ns leiningen.ritz-nrepl
  "Start a nrepl session either with the current project or standalone."
  (:require clojure.main
            clojure.set
            [clojure.string :as string]
            [reply.main :as reply]
            [clojure.java.io :as io]
            [leiningen.core.eval :as eval]
            [leiningen.core.project :as project]
            [leiningen.trampoline :as trampoline]
            [clojure.tools.nrepl.ack :as nrepl.ack]
            [clojure.tools.nrepl.server :as nrepl.server]
            [leiningen.core.user :as user]
            [leiningen.core.classpath :as classpath]
            [leiningen.core.main :as main])
  (:use
   [leiningen.core.classpath :only [get-classpath]]))

(def nrepl-version "0.2.0-beta9")

(def profile {:dependencies '[[org.clojure/tools.nrepl nrepl-version
                               :exclusions [org.clojure/clojure]]
                              [clojure-complete "0.2.1"
                               :exclusions [org.clojure/clojure]]]})

(def nrepl-profile {:dependencies '[[org.clojure/tools.nrepl nrepl-version
                                     :exclusions [org.clojure/clojure]]]})

(def ritz-profile {:dependencies '[[ritz/ritz-nrepl "0.4.0-SNAPSHOT"
                                    :exclusions [org.clojure/clojure]]]})

(def lein-project-profile {:dependencies '[[leiningen "2.0.0-preview8"]]})

(def trampoline-profile {:dependencies '[[reply "0.1.0-beta9"
                                         :exclusions [org.clojure/clojure]]]})

;; (def ^{:private true} jvm-jdwp-opts
;;   "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n")
;;(update-in [:jvm-opts] conj jvm-jdwp-opts)

(defn- start-jpda-server
  "Start the JPDA nrepl server. The JPDA nrepl server will start the user
project server."
  [{{:keys [nrepl-middleware]} :repl-options :as project}
   host port ack-port {:keys [headless? debug?]}]
  {:pre [project]}
  (let [jpda-project (->
                      project
                      (project/merge-profiles
                       [ritz-profile lein-project-profile :jpda]))
        user-project (->
                      project
                      (project/merge-profiles [ritz-profile])
                      ;; ritz.nrepl.exec should be separated
                      )
        user-classpath (vec (get-classpath user-project))
        _ (require 'leiningen.ritz) ;; for add-hooks
        server-starting-form
        `(do
           (ritz.nrepl/start-jpda-server
            ~host ~port ~ack-port
            ~(str (io/file (:target-path project) "repl-port"))
            ~(string/join ":" user-classpath)
            ~(vec (map #(list 'quote %) nrepl-middleware))))]
    (eval/eval-in-project
     jpda-project
     server-starting-form
     '(do (require
           'ritz.nrepl)))))

(defn- repl-port [project]
  (Integer. (or (System/getenv "LEIN_REPL_PORT")
                (-> project :repl-options :port)
                0)))

(defn- repl-host [project]
  (or (System/getenv "LEIN_REPL_HOST")
      (-> project :repl-options :host)
      "localhost"))

(def lein-repl-server
  (delay (nrepl.server/start-server
          :host (repl-host nil)
          :handler (nrepl.ack/handle-ack nrepl.server/unknown-op))))

(defn- ack-port [project]
  (when-let [p (or (System/getenv "LEIN_REPL_ACK_PORT")
                   (-> project :repl-options :ack-port))]
    (Integer. p)))

(defn options-for-reply [project & {:keys [attach port]}]
  (let [repl-options (:repl-options project)]
    (clojure.set/rename-keys
      (merge
        repl-options
        {:init (if-let [init-ns (or (:init-ns repl-options) (:main project))]
                 `(do (require '~init-ns) (in-ns '~init-ns)
                      ~(:init repl-options))
                 (:init repl-options))}
        (cond
          attach
            {:attach (if-let [host (repl-host project)]
                       (str host ":" attach)
                       (str attach))}
          port
            {:port (str port)}
          :else
            {}))
      {:prompt :custom-prompt
       :init :custom-init})))

(defn- trampoline-repl [project]
  (let [options (options-for-reply project :port (repl-port project))
        profiles [(:repl (user/profiles) profile) trampoline-profile]]
    (eval/eval-in-project
     (project/merge-profiles project profiles)
     `(reply.main/launch-nrepl ~options)
     '(require 'reply.main 'clojure.tools.nrepl.server 'complete.core))))

(defn ^:no-project-needed ritz-nrepl
  "Start a ritz repl session either with the current project or standalone.

USAGE: lein ritz-nrepl
This will launch an nREPL server behind the scenes
that reply will connect to. If a :port key is present in
the :repl-options map in project.clj, that port will be used for the
server, otherwise it is chosen randomly. If you run this command
inside of a project, it will be run in the context of that classpath.
If the command is run outside of a project, it'll be standalone and
the classpath will be that of Leiningen.

USAGE: lein repl :headless
This will launch an nREPL server and wait, rather than connecting reply to it.

USAGE: lein repl :connect [host:]port
Connects to the nREPL server running at the given host (defaults to localhost)
and port."
  ([project]
     (if trampoline/*trampoline?*
       (trampoline-repl project)
       (let [prepped (promise)]
         (nrepl.ack/reset-ack-port!)
         (.start
          (Thread.
           (bound-fn []
             (start-jpda-server
              (and project (vary-meta project assoc :prepped prepped))
              (repl-host project) (repl-port project)
              (-> @lein-repl-server deref :ss .getLocalPort)
              {}))))
         (when project @prepped)
         (if-let [repl-port (nrepl.ack/wait-for-ack (-> project
                                                        :repl-options
                                                        (:timeout 30000)))]
           (reply/launch-nrepl (options-for-reply project :attach repl-port))
           (println "REPL server launch timed out.")))))
  ([project flag & opts]
     (case flag
       ":headless" (start-jpda-server
                    project
                    (repl-host project) (repl-port project)
                    (ack-port project)
                    {:headless? true :debug? true})
       ":connect" (do (require 'cemerick.drawbridge.client)
                      (reply/launch-nrepl {:attach (first opts)}))
       (main/abort "Unrecognized flag:" flag))))
