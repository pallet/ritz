(ns leiningen.ritz
  "Launch ritz server for Emacs to connect."
  (:require
   [clojure.java.io :as io]
   [leiningen.core.eval :as eval]
   [leiningen.core.project :as project])
  (:use
   [clojure.set :only [difference union]]
   [clojure.tools.cli :only [cli]]
   [leiningen.core.classpath :only [get-classpath]]
   [leiningen.core.main :as main]
   [lein-ritz.plugin-helpers
    :only [classlojure-profile clojure-profile lein-profile jpda-jars]]))


(def ritz-profile {:dependencies '[[ritz/ritz-swank "0.7.1-SNAPSHOT"
                                    :exclusions [org.clojure/clojure]]]})

(defn ritz-form [project port host {:keys [debug] :as opts}]
  (let [vm-classes (io/file (:compile-path project) ".." "vm-classes")
        vm-project (->
                    project
                    (project/unmerge-profiles [:default])
                    (project/merge-profiles
                     [ritz-profile lein-profile classlojure-profile])
                    (dissoc :test-paths :source-paths :resource-paths)
                    (assoc :compile-path (.getAbsolutePath vm-classes)))
        user-project (->
                      project
                      (project/merge-profiles [ritz-profile]))
        vm-classpath (get-classpath vm-project)
        user-classpath (get-classpath user-project)
        user-classpath-no-ritz (get-classpath project)
        extra-classpath (union
                         (difference
                          (set user-classpath) (set user-classpath-no-ritz))
                         (set (jpda-jars)))
        user-classpath (if (seq user-classpath)
                         user-classpath
                         (get-classpath
                          (project/merge-profiles
                           user-project [clojure-profile])))]
    (.mkdirs vm-classes)
    (main/debug "user-classpath" user-classpath)
    `(do (binding [*compile-path* ~(.getAbsolutePath
                                    (io/file
                                     (or (:compile-path project)
                                         "./classes")))]
           (when-let [is# ~(:repl-init-script project)]
             (when (.exists (java.io.File. (str is#)))
               (load-file is#)))
           (when-let [repl-init# '~(:repl-init project)]
             (doto repl-init# require in-ns))
           (require '~'ritz.swank.socket-server)
           (@(ns-resolve '~'ritz.swank.socket-server '~'start)
            '~(merge
               (select-keys project [:jvm-opts :properties])
               opts
               {:port port
                :host host
                :classpath (vec user-classpath)
                :vm-classpath (vec vm-classpath)
                :extra-classpath (vec extra-classpath)}))))))

(defn eval-in-project
  "Support eval-in-project in both Leiningen 1.x and 2.x."
  [& args]
  (let [eip (or (try (require 'leiningen.core.eval)
                     (resolve 'leiningen.core.eval/eval-in-project)
                     (catch java.io.FileNotFoundException _))
                (try (require 'leiningen.compile)
                     (resolve 'leiningen.compile/eval-in-project)
                     (catch java.io.FileNotFoundException _)))]
    (apply eip args)))

(defn- repl-port [project]
  (Integer. (or (System/getenv "LEIN_REPL_PORT")
                (-> project :repl-options :port)
                0)))

(defn- repl-host [project]
  (or (System/getenv "LEIN_REPL_HOST")
      (-> project :repl-options :host)
      "localhost"))

(defn ritz
  "Launch ritz server for Emacs to connect. Optionally takes PORT and HOST.

-d   --[no-]debug      Enable debugger
-f   --port-file       File to write port info to
-m   --message         announce message"
  [project & args]
  (let [[{:keys [debug] :as opts} [^String port host]]
        (cli args
             ["-d" "--[no-]debug" :default true]
             ["-b" "--backlog" :parse-fn #(Integer. ^String %) :default 0]
             ["-l" "--log-level" :default nil]
             ["-m" "--message"]
             ["-f" "--port-file"])
        port (if port (Integer. port) (repl-port project))
        host (or host (repl-host project))
        opts (->
              opts
              (assoc :server-ns
                (if debug 'ritz.swank.proxy 'ritz.swank.repl))
              (update-in [:log-level] #(when % (keyword %))))
        jvm-eopts (filter #(re-matches #"^-D(swank|file).encoding.*" %) (:jvm-opts project))
        start-project (if debug
                        (->
                         project
                         (project/unmerge-profiles [:default])
                         (project/merge-profiles
                          [clojure-profile lein-profile ritz-profile])
                         (dissoc :test-paths :source-paths :resource-paths)
                         (assoc :jvm-opts (concat ["-Djava.awt.headless=true" "-XX:+TieredCompilation"]
                                                  jvm-eopts)))
                        (project/merge-profiles project [ritz-profile]))]
    (main/debug "Start project" project)
    (eval-in-project start-project (ritz-form project port host opts))))
