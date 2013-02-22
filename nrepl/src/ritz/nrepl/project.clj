(ns ritz.nrepl.project
  "Project.clj functions"
  (:require
   [leiningen.core.main :as main]
   [leiningen.core.project :as project]
   [leiningen.core.user :as user])
  (:use
   [clojure.string :only [join]]
   [leiningen.core.classpath :only [get-classpath]]
   [leiningen.core.eval :only [eval-in]]
   [ritz.debugger.connection :only [vm-context]]
   [ritz.logging :only [trace]]))


(defn set-classpath!
  [vm classpath]
  (ritz.jpda.jdi-clj/control-eval
   vm `(ritz.nrepl.exec/set-classpath! ~(vec classpath))))

(defonce cache-classpath (atom {}))

(def ritz-profile {:dependencies '[[ritz/ritz-nrepl "0.7.1-SNAPSHOT"
                                    :exclusions [org.clojure/clojure]]]})

(defn project-classpath
  [project]
  (let [project (project/merge-profiles project [ritz-profile])
        {:keys [project-hash classpath]} @cache-classpath
        lookup-hash (hash project)]
    (if (= project-hash lookup-hash)
      classpath
      (let [classpath (get-classpath project)]
        (trace "classpath cache miss")
        (reset!
         cache-classpath
         {:project-hash lookup-hash :classpath classpath})
        classpath))))

(defonce project-path (atom "project.clj"))

(defn reload
  [connection]
  (let [project (project/read @project-path)
        classpath (project-classpath project)]
    (trace "Resetting classpath to %s" (vec classpath))
    (set-classpath! (vm-context connection) classpath)))

(defn load-project
  [connection project-file]
  (let [project (project/read project-file)
        classpath (project-classpath project)]
    (reset! project-path project-file)
    (trace "Setting classpath to %s" (vec classpath))
    (set-classpath! (vm-context connection) classpath)))

(defn reset-namespaces
  [vm]
  (ritz.jpda.jdi-clj/control-eval vm `(ritz.nrepl.exec/reset-namespaces!)))

(defn reset-repl
  [connection]
  (reset-namespaces (vm-context connection)))

(defn lein-requires-form
  []
  '(require '[leiningen.core.main :as main]))

(defn lein-exec-form
  [project]
  `(do
     (in-ns '~'leiningen.core.main)
     ))

(defmethod eval-in ::vm [project form]
  (let [classpath (project-classpath project)]
    (ritz.jpda.jdi-clj/control-eval
     (::vm project)
     `(ritz.nrepl.exec/eval-with-classpath
        ~(vec classpath)
        ~form))))

(defn lein
  [connection args]
  (let [project (project/read)]
    (binding [main/*exit-process?* false]
      (try
        (println "lein" (join " " args))
        (user/init)
        (let [project (assoc project ::vm (vm-context connection)
                             :eval-in ::vm)
              [task-name args] (main/task-args args project)]
          (when (:min-lein-version project)
            (#'main/verify-min-version project))
          (#'main/configure-http)
          (#'main/warn-chaining task-name args)
          (main/apply-task task-name project args)
          (println "lein completed"))
        (catch Exception e
          (if (or main/*debug* (not (:exit-code (ex-data e))))
            (.printStackTrace e)
            (println (.getMessage e)))
          (println "Lein failed - exit code" (:exit-code (ex-data e) 1)))))))
