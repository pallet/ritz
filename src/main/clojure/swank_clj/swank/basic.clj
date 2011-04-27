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

(defn eval-region
  "Evaluate string, return the results of the last form as a list and
   a secondary value the last form."
  ([string]
     (eval-region string "NO_SOURCE_FILE" 1))
  ([string file line]
     (core/with-package-tracking
       (with-open [rdr (proxy [LineNumberingPushbackReader]
                           ((StringReader. string))
                         (getLineNumber [] line))]
         (binding [*file* file]
           (loop [form (read rdr false rdr), value nil, last-form nil]
             (if (= form rdr)
               [value last-form]
               (recur (read rdr false rdr)
                      (eval form)
                      form))))))))

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


(defn compile-region
  "Compile region."
  ([string file line]
     (with-open [rdr1 (proxy [LineNumberingPushbackReader]
                          ((StringReader. string)))
                 rdr (proxy [LineNumberingPushbackReader] (rdr1)
                       (getLineNumber [] (+ line (.getLineNumber rdr1) -1)))]
       (clojure.lang.Compiler/load rdr file (.getName (File. file))))))
