(ns leiningen.ritz
  (:use [leiningen.compile :only [eval-in-project]]))

(defn ritz
  "Launch swank server for Emacs to connect. Optionally takes PORT and HOST."
  ([project port host & {:as opts}]
     (eval-in-project
      project
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
                 {:port (Integer. port) :host host}))))))
  ([project port] (ritz project port "localhost"))
  ([project] (ritz project 4005)))
