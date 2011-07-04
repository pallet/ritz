(ns ritz.repl-utils.find
  "Find source for a given path."
  (:require
   [ritz.repl-utils.mangle :as mangle])
  (:import java.io.File))

;;; Provide source form tracking
;; TODO work out how to call eval-clear-form, or use weak refs
(def ^{:private true} source-form-map (atom {}))

(defn source-form!
  [id form]
  (swap! source-form-map assoc id form))

(defn source-form
  [id]
  (@source-form-map id))

(defn source-forms
  "All source forms entered at the reply"
  []
  (map second (sort-by key @source-form-map)))

(defn source-form-clear
  [id]
  (swap! source-form-map dissoc id))

;;; Provide a mapping from source path to source form
(def ^{:private true} source-form-name "SOURCE_FORM_")
(def ^{:private true} source-form-name-count (count source-form-name))

(defn source-form-path
  [id]
  (str source-form-name id))

(defn source-form-from-path
  [path]
  (when (> (count path) source-form-name-count)
    (let [id-string (.substring path (count source-form-name))
          id (eval (read-string id-string))]
      {:source-form (source-form id)})))

(defn source-form-path?
  [source-path]
  (when source-path
    (.startsWith source-path source-form-name)))

;;; File and Resource Paths
(defn- clean-windows-path
  "Decode file URI encoding and remove an opening slash from
   /c:/program%20files/... in jar file URLs and file resources."
  [^String path]
  (or (and (.startsWith (System/getProperty "os.name") "Windows")
           (second (re-matches #"^/([a-zA-Z]:/.*)$" path)))
      path))

(defn- zip-resource [^java.net.URL resource]
  (let [jar-connection ^java.net.JarURLConnection (.openConnection resource)
        jar-file (.getPath (.toURI (.getJarFileURL jar-connection)))]
    {:zip [(clean-windows-path jar-file) (.getEntryName jar-connection)]}))

(defn- file-resource [^java.net.URL resource]
  {:file (clean-windows-path (.getFile resource))})

(defn find-resource
  [^String file]
  (when-let [resource (.getResource (clojure.lang.RT/baseLoader) file)]
    (if (= (.getProtocol resource) "jar")
      (zip-resource resource)
      (file-resource resource))))

(defn find-source-path
  "Find source file or source string for specified source-path"
  [^String source-path]
  (if (source-form-path? source-path)
    (source-form-from-path source-path)
    (if (.isAbsolute (File. source-path))
      {:file source-path}
      (find-resource source-path))))

(def ^{:private true
       :doc "Regex for extacting file and line from a compiler exception"}
  compiler-exception-location-re
  #".*Exception:.*\(([^:]+):([0-9]+)\)")

(defn find-compiler-exception-location
  "Return a location vector [source-buffer position], for the given
   throwable."
  [^Throwable t]
  (let [[match file line] (re-find compiler-exception-location-re (str t))]
    (when (and file line)
      [{:file (or (when (instance? clojure.lang.Compiler$CompilerException t)
                    (try (.source t) (catch Exception _)))
                  file)}
       {:line (Integer/parseInt line)}])))

(defn java-source-path
  "Take a class-name and a file-name, and generate a file path"
  [class-name file-name]
  (.. class-name
      (replace \. \/)
      (substring 0 (.lastIndexOf class-name "."))
      (concat (str File/separator file-name))))

(defn clojure-source-path
  "Take a class-name and a file-name, and generate a file path"
  [class-name file-name]
  (let [ns-name (mangle/clojure-class-name->namespace-name class-name)
        i (.lastIndexOf ns-name ".")]
    (if (pos? i)
      (str
       (mangle/namespace-name->path (.substring ns-name 0 i))
       File/separator
       file-name)
      file-name)))

(defn source-location-for-stack-trace-element
  [^StackTraceElement frame]
  (let [line (.getLineNumber frame)
        filename (.getFileName frame)
        classname (.getClassName frame)
        path (if (.endsWith filename ".java")
               (java-source-path classname filename)
               (clojure-source-path classname filename))]
    [(find-source-path path) (:line line)]))

(defn source-location-for-var
  "Try and find the source location for a var"
  [v]
  (when-let [m (and v (meta v))]
    (when-let [path (find-source-path (:file m))]
      [path {:line (:line m)}])))

(defn source-location-for-namespace-sym
  "Try and find the source location for a namespace"
  [sym]
  (when-let [n (and sym (name sym))]
    (when-let [path (find-source-path
                     (str (mangle/namespace-name->path n) ".clj"))]
      [path {:line (:line 1)}])))

(defn source-location-for-class
  "Try and find the source location for a class"
  [sym]
  (when-let [class-name (and sym (name sym))]
    (when-let [path (find-source-path
                     (str (mangle/namespace-name->path class-name) ".java"))]
      [path {:line (:line 1)}])))
