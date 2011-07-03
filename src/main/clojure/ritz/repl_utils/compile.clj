(ns ritz.repl-utils.compile
  "Util functions for compilation and evaluation."
  (:import
   java.io.StringReader
   java.io.File
   java.util.zip.ZipFile
   clojure.lang.LineNumberingPushbackReader))

(def compile-path (atom nil))

(defn- reader
  "This is a hack to get a line numbering pushback reader that
   doesn't start at line 1"
  [string line]
  (let [rdr1 (proxy [LineNumberingPushbackReader] ((StringReader. string)))]
    (proxy [LineNumberingPushbackReader] (rdr1)
      (getLineNumber [] (+ line (.getLineNumber rdr1) -1)))))

(defn compile-region
  "Compile region."
  [string file line]
  (with-open [rdr (reader string line)]
    (clojure.lang.Compiler/load rdr file (.getName (File. file)))))

(defn eval-region
  "Evaluate string, and return the results of the last form and the last form."
  [string file line]
  (let [last-form
        (with-open [rdr (reader string line)]
          (loop [form (read rdr false rdr) last-form nil]
            (if (= form rdr)
              last-form
              (recur (read rdr false rdr) form))))]
    [(binding [*compile-path* @compile-path]
       (compile-region string file line))
     last-form]))
