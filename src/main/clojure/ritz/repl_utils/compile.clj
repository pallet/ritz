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

(def ns-tracker (atom {}))

(defn eval-region
  "Evaluate string, and return the results of the last form and the last form."
  [string file line]
  (let [last-form
        (with-open [rdr (reader string line)]
          (loop [form (read rdr false rdr) last-form nil]
            (if (= form rdr)
              last-form
              (recur (read rdr false rdr) form))))]
    (let [s (gensym "evalns")
          result [(binding [*compile-path* @compile-path]
                    (compile-region
                     (str
                      "(try " string \newline
                      "(finally "
                      `(swap!
                        ritz.repl-utils.compile/ns-tracker
                        assoc '~s (ns-name *ns*))
                      "))")
                     file line))
                  last-form]]
      (when-let [ns (get @ns-tracker s)]
        (swap! ns-tracker dissoc s)
        (in-ns ns))
      result)))

;; For some reason, this does not source and line info on the generated code.
;;
;; (defn eval-region
;;   "Evaluate string, and return the results of the last form and the last form."
;;   [string file line]
;;   (println file line)
;;   (with-open [rdr (reader string line)]
;;     (binding [*file* file *source-path* (.getName (File. file))]
;;       (loop [form (read rdr false ::eof)
;;              last-form nil
;;              res nil]
;;         (if (= form ::eof)
;;           [res last-form]
;;           (let [res (eval form)
;;                 next-form (read rdr false ::eof)]
;;             (recur next-form form res)))))))
