(ns swank-clj.swank.basic
  (:require
   [swank-clj.swank.core :as core]
   [swank-clj.logging :as logging]
   [swank-clj.connection :as connection])
  (:import
   java.io.StringReader
   java.io.File
   java.util.zip.ZipFile
   clojure.lang.LineNumberingPushbackReader))

(defn- reader
  "This is a hack to get a line numbering pushback reader that
   doesn't start at line 1"
  [string line]
  (let [rdr1 (proxy [LineNumberingPushbackReader] ((StringReader. string)))]
    (proxy [LineNumberingPushbackReader] (rdr1)
      (getLineNumber [] (+ line (.getLineNumber rdr1) -1)))))

(defn compile-region
  "Compile region."
  ([string file line]
     (with-open [rdr (reader string line)]
       (clojure.lang.Compiler/load rdr file (.getName (File. file))))))

(defn eval-region
  "Evaluate string, return the results of the last form as a list and
   a secondary value the last form."
  ([string]
     (eval-region
      string (str core/source-form-name core/*current-id*) 0))
  ([string file line]
     (core/with-package-tracking
       (let [last-form
             (with-open [rdr (reader string line)]
               (loop [form (read rdr false rdr) last-form nil]
                 (if (= form rdr)
                   last-form
                   (recur (read rdr false rdr) form))))]
         [(compile-region string file line) last-form]))))

(defn eval-form
  "Evaluate form. maintaining recent result history."
  [connection form]
  (logging/trace "eval-form %s" form)
  (let [[value last-form exception] (try
                                      (eval-region form)
                                      (catch Exception e
                                        [nil nil e]))]
    (logging/trace "eval-form: value %s" value)
    (when (and last-form (not (#{'*1 '*2 '*3 '*e} last-form)))
      (let [history (drop
                     1 (connection/add-result-to-history connection value))]
        (set! *3 (fnext history))
        (set! *2 (first history))
        (set! *1 value)))
    (when exception
      (set! *e exception))
    [value exception]))
