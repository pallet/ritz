;;; class-browse.clj -- Java classpath and Clojure namespace browsing

;; by Jeff Valk
;; created 2009-10-14

;; Scans the classpath for all class files, and provides functions for
;; categorizing them.

;; See the following for JVM classpath and wildcard expansion rules:
;;   http://java.sun.com/javase/6/docs/technotes/tools/findingclasses.html
;;   http://java.sun.com/javase/6/docs/technotes/tools/solaris/classpath.html

(ns ritz.repl-utils.class-browse
  "Provides Java classpath and (compiled) Clojure namespace browsing.
  Scans the classpath for all class files, and provides functions for
  categorizing them. Classes are resolved on the start-up classpath only.
  Calls to 'add-classpath', etc are not considered.

  Class information is built as a list of maps of the following keys:
    :name  Java class or Clojure namespace name
    :loc   Classpath entry (directory or jar) on which the class is located
    :file  Path of the class file, relative to :loc"
  (:import [java.io File FilenameFilter]
           [java.util StringTokenizer]
           [java.util.jar JarFile JarEntry]
           [java.util.regex Pattern]))

;;; Class file naming, categorization

(defn jar-file? [^String n] (.endsWith n ".jar"))
(defn class-file? [^String n] (.endsWith n ".class"))
(defn clojure-ns-file? [^String n] (.endsWith n "__init.class"))
(defn clojure-fn-file? [^String n] (re-find #"\$.*__\d+\.class" n))
(defn top-level-class-file? [^String n] (re-find #"^[^\$]+\.class" n))
(defn nested-class-file? [^String n]
  ;; ^ excludes anonymous classes
  (re-find #"^[^\$]+(\$[^\d]\w*)+\.class" n))

(def clojure-ns? (comp clojure-ns-file? :file))
(def clojure-fn? (comp clojure-fn-file? :file))
(def top-level-class? (comp top-level-class-file? :file))
(def nested-class? (comp nested-class-file? :file))

(defn class-or-ns-name
  "Returns the Java class or Clojure namespace name for a class relative path."
  [^String n]
  (.replace
   (if (clojure-ns-file? n)
     (-> n (.replace "__init.class" "") (.replace "_" "-"))
     (.replace n ".class" ""))
   File/separator "."))

;;; Path scanning

(defmulti path-class-files
  "Returns a list of classes found on the specified path location
  (jar or directory), each comprised of a map with the following keys:
    :name  Java class or Clojure namespace name
    :loc   Classpath entry (directory or jar) on which the class is located
    :file  Path of the class file, relative to :loc"
  (fn [^File f & _]
    (cond (.isDirectory f)           :dir
          (jar-file? (.getName f))   :jar
          (class-file? (.getName f)) :class)))

(defmethod path-class-files :default [& _] [])

(defmethod path-class-files :jar
  ;; Build class info for all jar entry class files.
  [^File f]
  (try
    (->>
     (.entries (JarFile. f))
     (enumeration-seq)
     (map #(.getName ^JarEntry %))
     (filter class-file?)
     (map (fn [fp] {:loc f :file fp :name (class-or-ns-name fp)})))
    (catch Exception e [])))            ; fail gracefully if jar is unreadable

(defmethod path-class-files :dir
  ;; Dispatch directories and files (excluding jars) recursively.
  [^File d & [^File base]]
  (let [fs (.listFiles d (proxy [FilenameFilter] []
                           (accept [d n] (not (jar-file? n)))))]
    (mapcat #(path-class-files % (or base d)) fs)))

(defmethod path-class-files :class
  ;; Build class info using file path relative to parent classpath entry
  ;; location. Make sure it decends; a class can't be on classpath directly.
  [^File f ^File base]
  (let [fp (.getPath f)
        lp (.getPath base)
        m (re-matcher (re-pattern (Pattern/quote
                                   (str "^" base File/separator))) fp)]
    (if (not (.find m))                 ; must be descendent of loc
      []
      (let [fpr (.substring fp (.end m))]
        [{:loc base :file fpr :name (class-or-ns-name fpr)}]))))

;;; Classpath expansion
(def java-version
     (Float/parseFloat (.substring (System/getProperty "java.version") 0 3)))

(defn scan-paths
  "Takes one or more classpath strings, scans each classpath entry location, and
  returns a list of all class file paths found, each relative to its parent
  directory or jar on the classpath."
  [files]
  (reduce concat (for [loc files] (path-class-files loc))))

(defn file-for-url
  "Convert a URL to a File. "
  [^java.net.URL url]
  (try
    (java.io.File. (.toURI url))
    (catch java.net.URISyntaxException _
      (java.io.File. (.getPath url)))))

(defn classpath-urls
  "Return the classpath URL's for the current clojure classloader."
  []
  (.getURLs (.getClassLoader clojure.lang.RT)))

(defn classpath
  "Return the classpath File's for the current clojure classloader."
  []
  (map file-for-url (classpath-urls)))

;;; Class browsing

(def available-classes
  (filter (complement clojure-fn?)  ; omit compiled clojure fns
          (scan-paths (classpath))))

;; Force lazy seqs before any user calls, and in background threads; there's
;; no sense holding up SLIME init. (It's usually quick, but a monstrous
;; classpath could concievably take a while.)

(def top-level-classes
  (map
   (comp class-or-ns-name :name)
   (filter top-level-class? available-classes)))

(def nested-classes
  (map
   (comp class-or-ns-name :name)
   (filter nested-class? available-classes)))
