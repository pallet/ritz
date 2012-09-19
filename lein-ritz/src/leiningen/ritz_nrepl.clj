(ns leiningen.ritz-nrepl
  "Start a nrepl session either with the current project or standalone."
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [leiningen.core.eval :as eval]
   [leiningen.core.project :as project]
   [clojure.tools.nrepl.server :as nrepl.server])
  (:use
   [clojure.tools.cli :only [cli]]
   [clojure.set :only [difference]]
   [leiningen.core.classpath :only [get-classpath]]))

(def nrepl-version "0.2.0-beta9")

(def profile {:dependencies '[[org.clojure/tools.nrepl nrepl-version
                               :exclusions [org.clojure/clojure]]
                              [clojure-complete "0.2.1"
                               :exclusions [org.clojure/clojure]]]})

(def nrepl-profile {:dependencies '[[org.clojure/tools.nrepl nrepl-version
                                     :exclusions [org.clojure/clojure]]]})

(def nrepl-profile {:dependencies '[[org.clojure/tools.nrepl nrepl-version
                                     :exclusions [org.clojure/clojure]]]})


(def ritz-profile {:dependencies '[[ritz/ritz-nrepl "0.5.1-SNAPSHOT"
                                    :exclusions [org.clojure/clojure]]]})

(def repl-utils-profile {:dependencies '[[ritz/ritz-repl-utils "0.5.1-SNAPSHOT"
                                          :exclusions [org.clojure/clojure]]]})

(def lein-project-profile {:dependencies '[[leiningen "2.0.0-preview10"]]})

(def classlojure-profile {:dependencies '[[classlojure "0.6.6"]]})

(defn- start-jpda-server
  "Start the JPDA nrepl server. The JPDA nrepl server will start the user
project server."
  [{{:keys [nrepl-middleware]} :repl-options :as project}
   host port ack-port {:keys [log-level]}]
  {:pre [project]}
  (let [jpda-project (->
                      project
                      (project/merge-profiles
                       [ritz-profile lein-project-profile]))
        vm-project (->
                      project
                      (dissoc :test-paths :source-paths :dependencies)
                      (project/merge-profiles
                       [ritz-profile classlojure-profile]))
        user-project (->
                      project
                      (project/merge-profiles [ritz-profile]))
        vm-classpath (vec (get-classpath vm-project))
        user-classpath (vec (get-classpath user-project))
        user-classpath-no-ritz (vec (get-classpath project))
        extra-classpath (difference
                         (set user-classpath) (set user-classpath-no-ritz))
        _ (require 'leiningen.ritz) ;; for add-hooks
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
             :log-level ~log-level})
           @(promise))]
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
