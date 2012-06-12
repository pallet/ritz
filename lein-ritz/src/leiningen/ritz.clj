(ns leiningen.ritz
  "Launch ritz server for Emacs to connect."
  (:require [clojure.java.io :as io])
  (:use
   [robert.hooke :only [add-hook]]
   [ritz.add-sources :only [add-source-artifacts]]))

(defn opts-list [port host opts]
  (apply concat (merge {:host host :port (Integer. port)
                        :repl-out-root true :block true}
                       (apply hash-map (map read-string opts)))))

(defn ritz-form [project port host opts]
  `(do (binding [*compile-path* ~(.getAbsolutePath
                                  (java.io.File.
                                   (or (:compile-path project)
                                       "./classes")))]
         (when-let [is# ~(:repl-init-script project)]
           (when (.exists (java.io.File. (str is#)))
             (load-file is#)))
         (when-let [repl-init# '~(:repl-init project)]
           (doto repl-init# require in-ns))
         (require '~'ritz.socket-server)
         (@(ns-resolve '~'ritz.socket-server '~'start)
          '~(merge
             (select-keys project [:jvm-opts :properties])
             (zipmap
              (map read-string (keys opts))
              (map read-string (vals opts)))
             {:port (Integer. port) :host host})))))

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
             conj ['ritz
                   (or (System/getenv "RITZ_VERSION")
                       (System/getProperty "ritz.version" "0.3.1"))]))

(defn ritz
  "Launch ritz server for Emacs to connect. Optionally takes PORT and HOST."
  ([project port host & {:as opts}]
     (eval-in-project
      (add-ritz project)
      (ritz-form project port host opts)))
  ([project port] (ritz project port "localhost"))
  ([project] (ritz project 4005)))

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
