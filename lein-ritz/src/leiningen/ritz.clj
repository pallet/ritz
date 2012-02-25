(ns leiningen.ritz
  "Launch ritz server for Emacs to connect."
  (:require [clojure.java.io :as io]))

(defn opts-list [port host opts]
  (apply concat (merge {:host host :port (Integer. port)
                        :repl-out-root true :block true}
                       (apply hash-map (map read-string opts)))))

(defn ritz-form [project port host opts]
  `(do (require '~'ritz.socket-server)
       (import '~'java.io.File)
       (binding [*compile-path* ~(.getAbsolutePath
                                  (java.io.File.
                                   (or (:compile-path project)
                                       "./classes")))]
         (when-let [is# ~(:repl-init-script project)]
           (when (.exists (java.io.File. (str is#)))
             (load-file is#)))
         (when-let [repl-init# '~(:repl-init project)]
           (doto repl-init# require in-ns))
         (@(ns-resolve '~'ritz.socket-server '~'start)
          '~(merge
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

(defn ritz
  "Launch ritz server for Emacs to connect. Optionally takes PORT and HOST."
  ([project port host & {:as opts}]
     (eval-in-project
      (update-in project [:dependencies]
                 conj ['ritz
                       (or (System/getenv "RITZ_VERSION")
                           (System/getProperty
                            "ritz.version" "0.3.0-SNAPSHOT"))])
      (ritz-form project port host opts)))
  ([project port] (ritz project port "localhost"))
  ([project] (ritz project 4005)))
