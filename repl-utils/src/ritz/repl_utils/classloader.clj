(ns ritz.repl-utils.classloader
  "Provide a classloader for a project and functions to use it if available.
This depends on having classlojure on the classpath."
  (:use
   [clojure.java.io :only [file]]
   [ritz.logging :only [trace]]
   [ritz.repl-utils.clojure :only [feature-cond]]))


(def classlojure-on-classpath true)

(try
  (use '[classlojure.core :only [eval-in eval-in* ext-classloader]])
  (catch Exception e
    (alter-var-root #'classlojure-on-classpath (constantly false))))

(def ^{:private true :doc "The classloader"} cl (atom nil))

(defn configurable-classpath?
  []
  classlojure-on-classpath)

(defn has-classloader?
  []
  @cl)

(defn absolute-filename [filename]
  (.getPath (file filename)))

(defn filename-to-url-string
  [filename]
  (str (.toURL (file filename))))

(defn set-classpath!
  "Set the ClassLoader to the specified files."
  [files]
  (feature-cond
   classlojure-on-classpath
   (let [loader (#'classlojure.core/url-classloader
                    (->>
                     files
                     (map absolute-filename)
                     (map filename-to-url-string))
                    ext-classloader)]
     (.loadClass loader "clojure.lang.RT")
     (eval-in* loader '(require 'clojure.main))
     (reset! cl loader))
   :else (throw (Exception. "Can not configure classpath"))))

(defn classpath
  []
  (when-let [cl @cl] (.getURLs cl)))

(defn eval-clojure
  "Evaluate a closure form in a classloader with the specified paths."
  [form & args]
  (trace "eval-clojure %s %s" form (vec args))
  (feature-cond
   classlojure-on-classpath (apply eval-in @cl form args)
   :else (throw (Exception. "Can not eval in configurable classpath"))))
