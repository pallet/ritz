(ns leiningen.ritz-nrepl
  "Start a nrepl session either with the current project or standalone."
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [leiningen.core.eval :as eval]
   [leiningen.core.main :refer [debug]]
   [leiningen.core.project :as project]
   [clojure.tools.nrepl.server :as nrepl.server])
  (:use
   [clojure.tools.cli :only [cli]]
   [clojure.set :only [difference]]
   [leiningen.core.classpath :only [get-classpath]]
   [lein-ritz.plugin-helpers
    :only [classlojure-profile clojure-profile lein-profile]]))

(def nrepl-profile {:dependencies '[[org.clojure/tools.nrepl "0.2.3"
                                     :exclusions [org.clojure/clojure]]]})

(def ritz-profile {:dependencies '[[ritz/ritz-nrepl "0.7.1-SNAPSHOT"
                                    :exclusions [org.clojure/clojure]]]})
(def ritz-nrepl-core-profile {:dependencies
                              '[[ritz/ritz-nrepl-core "0.7.1-SNAPSHOT"
                                 :exclusions [org.clojure/clojure]]]})
(def repl-utils-profile {:dependencies '[[ritz/ritz-repl-utils "0.7.1-SNAPSHOT"
                                          :exclusions [org.clojure/clojure]]]})


(defn- start-jpda-server
  "Start the JPDA nrepl server. The JPDA nrepl server will start the user
project server."
  [{{:keys [nrepl-middleware]} :repl-options :as project}
   host port ack-port {:keys [log-level]}]
  {:pre [project]}
  (let [jpda-project (->
                      project
                      (project/merge-profiles
                       [clojure-profile lein-profile ritz-profile])
                      (dissoc :test-paths :source-paths :resource-paths)
                      (assoc :jvm-opts ["-Djava.awt.headless=true"
                                        "-XX:+TieredCompilation"]))
        vm-project (->
                    project
                    (dissoc :test-paths :source-paths :dependencies)
                    (project/merge-profiles
                     [ritz-profile classlojure-profile]))
        user-project (->
                      project
                      (project/merge-profiles [ritz-nrepl-core-profile]))
        vm-classpath (vec (get-classpath vm-project))
        user-classpath (vec (get-classpath user-project))
        user-classpath-no-ritz (vec (get-classpath project))
        extra-classpath (difference
                         (set user-classpath) (set user-classpath-no-ritz))
        server-starting-form
        `(do
           (ritz.nrepl/start-jpda-server
            {:host ~host
             :port ~port
             :ack-port ~ack-port
             :repl-port-path ~(str (io/file (:target-path project) "repl-port"))
             :classpath ~(vec user-classpath)
             :vm-classpath ~(vec vm-classpath)
             :extra-classpath ~(vec extra-classpath)
             :middleware ~(vec (map #(list 'quote %) nrepl-middleware))
             :jvm-opts ~(vec (:jvm-opts user-project))
             :log-level ~log-level})
           @(promise))]
    (debug "user-classpath" (pr-str user-classpath))
    (debug "vm-classpath" (pr-str vm-classpath))
    (debug "extra-classpath" (pr-str extra-classpath))
    (debug "jpda-project" (pr-str jpda-project))
    (debug "server-starting-form" (pr-str server-starting-form))
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

(defn- ack-port [project]
  (when-let [p (or (System/getenv "LEIN_REPL_ACK_PORT")
                   (-> project :repl-options :ack-port))]
    (Integer. p)))

(defn ^:no-project-needed ritz-nrepl
  "Start a ritz repl session either with the current project or standalone."
  [project & args]
  (let [[{:keys [debug] :as opts} [port host]]
        (cli args
             ["-l" "--log-level" :default nil]
             ["-f" "--port-file"])
        opts (update-in opts [:log-level] #(when % (keyword %)))]
    (start-jpda-server
     project
     (or host (repl-host project))
     (or port (repl-port project))
     (ack-port project)
     opts)))
