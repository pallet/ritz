(ns ritz.nrepl.project
  "Project.clj functions"
  (:require
   [leiningen.core.project :as project])
  (:use
   [leiningen.core.classpath :only [get-classpath]]
   [ritz.debugger.connection :only [vm-context]]
   [ritz.logging :only [trace]]))


(defn set-classpath!
  [vm classpath]
  (ritz.jpda.jdi-clj/control-eval
   vm `(ritz.nrepl.exec/set-classpath! ~(vec classpath))))

(defn reload
  [connection]
  (let [project (project/read)
        classpath (get-classpath project)]
    (trace "Resetting classpath to %s" (vec classpath))
    (set-classpath! (vm-context connection) classpath)))

(defn reset-namespaces
  [vm]
  (ritz.jpda.jdi-clj/control-eval vm `(ritz.nrepl.exec/reset-namespaces!)))

(defn reset-repl
  [connection]
  (reset-namespaces (vm-context connection)))
