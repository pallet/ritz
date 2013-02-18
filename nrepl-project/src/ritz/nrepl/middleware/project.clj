(ns ritz.nrepl.middleware.project
  "Project loading middleware.

Provides middleware to control evaluation in a classloader."
  (:require
   [clojure.tools.nrepl.transport :as transport])
  (:use
   [clojure.java.io :only [copy resource]]
   [clojure.stacktrace :only [print-cause-trace]]
   [clojure.tools.nrepl.middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.misc :only [response-for]]
   [clojure.tools.nrepl.server :only [handle]]
   [clojure.tools.nrepl.transport :only [send] :rename {send send-msg}]
   [leiningen.core.project :only [read] :rename {read read-project}]
   [leiningen.core.classpath :only [get-classpath]]
   [ritz.logging :only [trace]]
   [ritz.repl-utils.classloader :only [classloader eval-clojure-in]]))

(defonce
  ^{:doc "A map of all project specific classloaders"}
  projects (atom {}))

(defn cl-transport
  []
  (read-string
   (slurp (resource "ritz/nrepl/middleware/project/transport.clj"))))

(defn reply-pump
  "Returns a function of no arguments, that will pump replies from the
  output-queue in the classloader `cl` and push them to `transport`"
  [cl transport]
  (fn []
    (let [resp-form '(ritz.nrepl.middleware.project.transport/read-response)
          msg (eval-clojure-in cl resp-form)]
      (send-msg transport msg))
    (recur)))

(defn start-server-form
  "Return forms to start an nREPL server in a classloader."
  [middleware]
  (let [mw-ns (distinct (map (comp symbol namespace) middleware))]
    `(do
       (ns ritz.nrepl.middleware.project.server
         ~@(when (seq mw-ns) [`(:require ~@mw-ns)])
         (:use [clojure.tools.nrepl.server :only [~'default-handler ~'handle]]))
       (defonce ~'handler (~'default-handler ~@middleware))
       (defonce ~'server
         (future
           (~'handle ~'handler
             ritz.nrepl.middleware.project.transport/transport))))))

(defn classloader-for
  [projects project classpath cl-extra-files]
  (let [{:keys [cl cp extra-files]} (projects project)]
    (classloader classpath cl cp (or cl-extra-files extra-files))))

(defn set-project-classpath
  "Set the classpath for the specified project."
  [project classpath cl-extra-files]
  (trace "set-project-classpath %s %s" project (vec classpath))
  (get (swap! projects
              (fn [projects]
                (assoc projects
                  project
                  (classloader-for projects project classpath cl-extra-files))))
       project))

(defn set-project
  [project classpath cl-extra-files transport middleware]
  (let [{:keys [cl reset-required?] :as cl-info}
        (set-project-classpath project classpath cl-extra-files)]
    (when reset-required?
      (eval-clojure-in cl (cl-transport))
      (eval-clojure-in cl (start-server-form middleware))
      (swap! projects assoc-in [project :reply-pump]
             (future (try
                       ((reply-pump cl transport))
                       (catch Exception e
                         (print-cause-trace e))))))))


(defn project-classpath-reply
  "Reply to a project-classpath message"
  [{:keys [project classpath extra-files transport middleware] :as msg}]
  (set-project project classpath extra-files transport middleware)
  (transport/send transport (response-for msg :status #{:done})))

(defmacro with-temp-file
  "Create a block where `varname` is a temporary `File` containing `content`."
  [[varname content] & body]
  `(let [~varname (java.io.File/createTempFile "nrepl-project", ".tmp")]
     (copy ~content ~varname)
     (let [rv# (do ~@body)]
       (.delete ~varname)
       rv#)))

(defn project-reply
  "Reply to a project message"
  [{:keys [project-clj transport] :as msg}]

  (let [{:keys [name group] :as project}
        (with-temp-file [project-file project-clj]
          (read-project (.getAbsolutePath project-file)))

        project-name (str group "/" name)]
    (set-project
     project-name
     (get-classpath project)
     (get-classpath
      (assoc project :dependencies '[[org.clojure/tools.nrepl "0.2.1"]]))
     transport
     (-> project :repl-options :nrepl-middleware))
    (transport/send transport
                    (response-for msg :status #{:done} :value project-name))))

(defn eval-in-project
  [{:keys [project code transport] :as msg}]
  (trace "eval-in-project %s %s" project code)
  (if-let [{:keys [cl]} (get @projects project)]
    (do
      (eval-clojure-in
       cl
       `(ritz.nrepl.middleware.project.transport/write-message
         ~(dissoc msg :transport)))
      ;; (transport/send transport (response-for msg {:status #{:done}}))
      )
    (transport/send
     transport (response-for msg {:status #{:error :no-project-classloader}}))))

(defn wrap-project-classpath
  "Middleware that sets up a project classpath."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "project-classpath" op)
      (project-classpath-reply msg)
      (handler msg))))

(defn wrap-project
  "Middleware that sets up a project classpath based on a leiningen project."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "project" op)
      (project-reply msg)
      (handler msg))))

(defn wrap-eval-in-project
  "Middleware that evaluates an nrepl message in a project classpath.
   Note that this evaluates all ops so should be at the bottom of the handler
  chain."
  [handler]
  (fn [{:keys [op project] :as msg}]
    (if project
      (eval-in-project msg)
      (handler msg))))

(set-descriptor!
 #'wrap-project
 {:requires #{}
  :expects #{}
  :handles
  {"project"
   {:doc "Sets the classloader for a project."
    :requires
    {:project-clj "Leiningen project definition"}}}})

(set-descriptor!
 #'wrap-project-classpath
 {:requires #{}
  :expects #{}
  :handles
  {"project-classpath"
   {:doc "Sets the classloader for a project."
    :requires
    {"classpath" "The classpath to be evaluated."
     "session" "The ID of the session within which to evaluate the code."}
    :optional
    {"middleware" "A list of middleware symbols"}}}})

(set-descriptor!
 #'wrap-eval-in-project
 {:requires #{}
  :expects #{}
  :handles
  {"*"
   {:doc "Evaluates code in a project classloader."
    :requires
    {"project" "The project name"}}}})
