(ns swank-clj.swank.find
  (:import
   java.io.File))

(defn- clean-windows-path [#^String path]
  ;; Decode file URI encoding and remove an opening slash from
  ;; /c:/program%20files/... in jar file URLs and file resources.
  (or (and (.startsWith (System/getProperty "os.name") "Windows")
           (second (re-matches #"^/([a-zA-Z]:/.*)$" path)))
      path))

(defn- slime-zip-resource [#^java.net.URL resource]
  (let [jar-connection #^java.net.JarURLConnection (.openConnection resource)
        jar-file (.getPath (.toURI (.getJarFileURL jar-connection)))]
    (list :zip (clean-windows-path jar-file) (.getEntryName jar-connection))))

(defn- slime-file-resource [#^java.net.URL resource]
  (list :file (clean-windows-path (.getFile resource))))

(defn slime-find-resource [#^String file]
  (if-let [resource (.getResource (clojure.lang.RT/baseLoader) file)]
    (if (= (.getProtocol resource) "jar")
      (slime-zip-resource resource)
      (slime-file-resource resource))))

(defn slime-find-file [#^String file]
  (if (.isAbsolute (File. file))
    (list :file file)
    (slime-find-resource file)))
