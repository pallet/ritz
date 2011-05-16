(ns leiningen.swank-clj
  (:use [leiningen.compile :only [eval-in-project]]))

(defn swank-clj
  "Launch swank server for Emacs to connect. Optionally takes PORT and HOST."
  ([project port host & {:as opts}]
     (eval-in-project
      project
      `(do (require '~'swank-clj.socket-server)
           (import '~'java.io.File)
           (binding [*compile-path* ~(.getAbsolutePath
                                      (java.io.File.
                                       (or (:compile-path project)
                                           "./classes")))]
             (@(ns-resolve '~'swank-clj.socket-server '~'start)
              '~(merge
                 (zipmap
                  (map read-string (keys opts))
                  (map read-string (vals opts)))
                 {:port (Integer. port) :host host}))))))
  ([project port] (swank-clj project port "localhost"))
  ([project] (swank-clj project 4005)))
