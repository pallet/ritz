(ns ritz.repl-utils.compile
  "Util functions for compilation and evaluation."
  (:use
   [ritz.repl-utils.io :only [reader-for-location]])
  (:require
   [clojure.java.io :as io])
  (:import
   java.io.StringReader
   java.io.File
   java.util.zip.ZipFile
   clojure.lang.LineNumberingPushbackReader
   java.io.LineNumberReader))

(def compile-path (atom nil))

(defn- ^LineNumberingPushbackReader reader
  "This is a hack to get a line numbering pushback reader that
   doesn't start at line 1"
  [string line]
  (let [rdr1 (LineNumberReader. (StringReader. string))]
    (proxy [LineNumberingPushbackReader] (rdr1)
      (getLineNumber [] (+ line (.getLineNumber rdr1) -1)))))

(defn compile-region
  "Compile region."
  [string ^String file line]
  (with-open [rdr (reader string line)]
    (clojure.lang.Compiler/load rdr file (.getName (File. file)))))

(defn eval-region
  "Evaluate string, and return the results of the last form and the last form."
  [string ^String file line]
  ;; We can't use load, since that binds current namespace, so we would lose
  ;; namespace tracking. This is essentially clojure.lang.Compiler/load without
  ;; that namespace binding.
  (with-open [rdr (reader string line)]
    (letfn [(set-before []
              (.. clojure.lang.Compiler/LINE_BEFORE
                  (set (Integer. (.getLineNumber rdr)))))
            (set-after []
              (.. clojure.lang.Compiler/LINE_AFTER
                  (set (Integer. (.getLineNumber rdr)))))]
      ;; since these vars aren't named, we can not use `binding`
      (push-thread-bindings
       {clojure.lang.Compiler/LINE_BEFORE (Integer. (int line))
        clojure.lang.Compiler/LINE_AFTER (Integer. (int line))})
      (try
        (binding [*file* file *source-path* (.getName (File. file))]
          (loop [form (read rdr false ::eof)
                 last-form nil
                 res nil]
            (if (= form ::eof)
              [res last-form]
              (let [_ (set-after)
                    res (eval form)
                    _ (set-before)
                    next-form (read rdr false ::eof)]
                (recur next-form form res)))))
        (finally (pop-thread-bindings))))))

(defn load-file-location
  [{:keys [file zip] :as location}]
  (clojure.lang.Compiler/load
   (reader-for-location location)
   (.getAbsolutePath (io/file file))
   (.getName (io/file file))))

(defmacro with-compiler-options
  "Provides a scope within which compiler are set. `options` should be
an expression yielding a map. The :debug key in the map controls
locals clearing. This has no effect on pre clojure 1.4.0."
  {:indent 1}
  [options & body]
  (if-let [co (ns-resolve 'clojure.core '*compiler-options*)]
    `(let [c-o# ~options]
       (if (:debug c-o#)
         (binding [*compiler-options*
                   (assoc *compiler-options* :disable-locals-clearing true)]
           ~@body)
         (do ~@body)))
    `(do ~@body)))
