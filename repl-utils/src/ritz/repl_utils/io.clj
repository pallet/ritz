(ns ritz.repl-utils.io
  "io for reading clojure files"
  (:require
   [clojure.java.io :as io])
  (:import
   java.util.jar.JarFile
   java.util.zip.ZipException))

(defn guess-namespace [^java.io.File file]
  (->>
   (reverse (.split (.getParent file) "/"))
   (reductions #(str %1 "." %2))
   (map symbol)
   (filter find-ns)
   first))

(defn- line-at-position [^java.io.File file position]
  (try
    (with-open [f (java.io.LineNumberReader. (java.io.FileReader. file))]
      (.skip f position)
      (.getLineNumber f))
    (catch Exception e 1)))

(defn read-position-line [^java.io.File file position]
  (if (number? position)
    (if (.isFile file)
      (line-at-position file  position)
      0)
    (when (list? position)
      (or
       (second (first (filter #(= :line (first %)) position)))
       (when-let [p (second (first (filter #(= :position (first %)) position)))]
         (line-at-position file p))))))

(defn read-ns
  "Given a reader on a Clojure source file, read until an ns form is found."
  [rdr]
  (let [form (try (read rdr false ::done)
                  (catch Exception e ::done))]
    (if (try
          (and (list? form) (= 'ns (first form)))
          (catch Exception _))
      (try
        (str form) ;; force the read to read the whole form, throwing on error
        (let [sym (second form)]
          (when (instance? clojure.lang.Named sym)
            sym))
        (catch Exception _))
      (when-not (= ::done form)
        (recur rdr)))))

(defn reader-for-location
  [{:keys [file zip] :as location-map}]
  (if zip
    (let [jarfile (JarFile. (io/file zip))]
      (-> jarfile
          (.getInputStream (.getEntry jarfile file))
          (java.io.InputStreamReader.)))
    (io/reader file)))

;;; ## Piped Streams
(def
  ^{:dynamic true
    :doc (str "The buffer size (in bytes) for the piped stream used to implement
    the :stream option for :out. If your ssh commands generate a high volume of
    output, then this buffer size can become a bottleneck. You might also
    increase the frequency with which you read the output stream if this is an
    issue.")}
  *piped-stream-buffer-size* (* 1024 10))

(defn streams-for-out
  ([buffer-size]
     (let [os (java.io.PipedOutputStream.)]
       [os (java.io.PipedInputStream. os buffer-size)]))
  ([] (streams-for-out *piped-stream-buffer-size*)))

(defn streams-for-in
  ([buffer-size]
     (let [os (java.io.PipedInputStream. (int buffer-size))]
       [os (java.io.PipedOutputStream. os)]))
  ([] (streams-for-in *piped-stream-buffer-size*)))
