(ns ritz.repl-utils.classloader
  "Provide a classloader for a project and functions to use it if available.
This depends on having classlojure on the classpath."
  (:use
   [clojure.java.io :only [file]]
   [clojure.set :only [difference union]]
   [ritz.logging :only [trace]]
   [ritz.repl-utils.clojure :only [feature-cond]]))

(def classlojure-on-classpath true)

(try
  (use '[classlojure.core :only [eval-in eval-in* ext-classloader]])
  (catch Exception e
    (alter-var-root #'classlojure-on-classpath (constantly false))))

(defonce ^{:private true :doc "The classloader"} cl (atom nil))
(defonce ^{:private true :doc "The classloader file list"} cl-files (atom nil))
(defonce ^{:private true :doc "The classloader extra file list"}
  cl-extra-files (atom nil))

(defn configurable-classpath?
  []
  classlojure-on-classpath)

(defn has-classloader?
  []
  (boolean @cl))

(defn absolute-filename [filename]
  (.getPath (file filename)))

(defn filename-to-url-string
  [filename]
  (str (.toURL (file filename))))

(defn files-to-urls
  [files]
  (->> files (map absolute-filename) (map filename-to-url-string)))

(defn requires-reset?
  "Predicate to test if a new classpath would require a classpath reset.
Returns a map with reset? and new-cl? flags."
  [files]
  (let [all-files (union (set files) @cl-extra-files)]
    {:reset? (or (not (seq @cl-files))
                 (seq (difference @cl-files all-files)))
     :new-cl? (not= @cl-files all-files)}))

(defn set-extra-classpath!
  "Set the extra files for the classpath."
  [files]
  (reset! cl-extra-files (set files))
  nil)

(defn set-classpath!
  "Set the ClassLoader to the specified files."
  [files]
  (feature-cond
   classlojure-on-classpath
   (let [file-set (union (set files) @cl-extra-files)
         new-files (difference file-set @cl-files)
         removed-files (difference @cl-files file-set)
         reset-required (or (seq removed-files) (not (seq @cl-files)))
         loader (if reset-required
                  (doto (#'classlojure.core/url-classloader
                         (files-to-urls file-set)
                         ext-classloader)
                    (.loadClass "clojure.lang.RT")
                    (eval-in* '(require 'clojure.main)))
                  (if (seq new-files)
                    (#'classlojure.core/url-classloader
                     (->>
                      new-files
                      (map absolute-filename)
                      (map filename-to-url-string))
                     @cl)
                    @cl))]
     (reset! cl loader)
     (reset! cl-files file-set)
     nil)
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
