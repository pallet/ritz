(ns leiningen.ritz
  "Launch ritz server for Emacs to connect."
  (:require
   [clojure.java.io :as io]
   [leiningen.core.eval :as eval]
   [leiningen.core.project :as project])
  (:use
   [clojure.set :only [difference]]
   [clojure.tools.cli :only [cli]]
   [leiningen.core.classpath :only [get-classpath]]
   [ritz.add-sources :only [add-source-artifacts]]
   [robert.hooke :only [add-hook]]))

(defn opts-list [port host opts]
  (apply concat (merge {:host host :port (Integer. port)
                        :repl-out-root true :block true}
                       (apply hash-map (map read-string opts)))))

(def ritz-profile {:dependencies '[[ritz/ritz-swank "0.4.3-SNAPSHOT"
                                    :exclusions [org.clojure/clojure]]]})

(def lein-project-profile {:dependencies '[[leiningen "2.0.0-preview10"]]})

(def classlojure-profile {:dependencies '[[classlojure "0.6.6"]]})

(def clojure-profile {:dependencies '[[org.clojure/clojure "1.4.0"]]})

(defn ritz-form [project port host {:keys [debug] :as opts}]
  (let [jpda-project (->
                      project
                      (project/merge-profiles
                       [ritz-profile lein-project-profile]))
        vm-classes (io/file (:compile-path project) ".." "vm-classes")
        vm-project (->
                    project
                    (project/unmerge-profiles [:default])
                    (project/merge-profiles
                     [ritz-profile classlojure-profile])
                    (dissoc :test-paths :source-paths :resource-paths)
                    (assoc :compile-path (.getAbsolutePath vm-classes)))
        user-project (->
                      project
                      (project/merge-profiles [ritz-profile]))
        vm-classpath (get-classpath vm-project)
        user-classpath (get-classpath user-project)
        user-classpath-no-ritz (get-classpath project)
        extra-classpath (difference
                         (set user-classpath) (set user-classpath-no-ritz))
        user-classpath (if (seq user-classpath)
                         user-classpath
                         (get-classpath
                          (project/merge-profiles
                           user-project [clojure-profile])))]
    (.mkdirs vm-classes)
    `(do (binding [*compile-path* ~(.getAbsolutePath
                                    (java.io.File.
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
               {:port (Integer. port)
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

(defn add-jpda-jars
  "JPDA is in the JDK's tools.jar and sa-jdi.jar. Add them to the classpath."
  [f project]
  (let [libdir (io/file (System/getProperty "java.home") ".." "lib")
        extra-cp (for [j ["tools.jar" "sa-jdi.jar"]
                       :when (.exists (io/file libdir j))]
                   (.getCanonicalPath (io/file libdir j)))]
    (concat (f project) extra-cp)))

(defn add-ritz
  "JPDA is in the JDK's tools.jar and sa-jdi.jar. Add them to the classpath."
  [project]
  (update-in project [:dependencies]
             conj ['ritz/ritz-swank
                   (or (System/getenv "RITZ_VERSION")
                       (System/getProperty "ritz.version" "0.4.3-SNAPSHOT"))]))

(defn ritz
  "Launch ritz server for Emacs to connect. Optionally takes PORT and HOST.

-d   --[no-]debug      Enable debugger
-f   --port-file       File to write port info to
-m   --message         announce message"
  ([project & args]
     (let [[{:keys [debug] :as opts} [port host]]
           (cli args
                ["-d" "--[no-]debug" :default true]
                ["-b" "--backlog" :parse-fn #(Integer. %) :default 0]
                ["-l" "--log-level" :default nil]
                ["-m" "--message"]
                ["-f" "--port-file"])
           opts (->
                 opts
                 (assoc :server-ns
                   (if debug 'ritz.swank.proxy 'ritz.swank.repl))
                 (update-in [:log-level] #(when % (keyword %))))
           start-project (if debug
                           (->
                            project
                            (project/unmerge-profiles [:default])
                            (project/merge-profiles
                             [clojure-profile ritz-profile])
                            (dissoc :test-paths :source-paths :resource-paths))
                           project)]
       (eval-in-project
        start-project
        (ritz-form project (or port 0) (or host "localhost") opts)))))

(defmacro add-hooks
  []
  (if (and
       (find-ns 'leiningen.core.classpath)
       (ns-resolve 'leiningen.core.classpath 'get-classpath))
    `(do
       (add-hook
        #'leiningen.core.classpath/get-classpath add-jpda-jars)
       (add-hook
        #'leiningen.core.classpath/get-classpath add-source-artifacts))
    `(do
       (require 'leiningen.classpath)
       (add-hook
        #'leiningen.classpath/get-classpath add-jpda-jars)
       (add-hook
        #'leiningen.classpath/get-classpath add-source-artifacts))))

(add-hooks)
