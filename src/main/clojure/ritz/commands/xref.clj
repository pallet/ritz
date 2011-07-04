(ns ritz.commands.xref
  (:use
   clojure.walk
   ritz.commands)
  (:require
   [ritz.repl-utils.core :as core]
   [ritz.repl-utils.find :as find])
  (:import (clojure.lang RT)
           (java.io LineNumberReader InputStreamReader PushbackReader)))

;; Yoinked and modified from clojure.contrib.repl-utils.
;; Now takes a var instead of a sym in the current ns
(defn- get-source-from-var
  "Returns a string of the source code for the given symbol, if it can
find it. This requires that the symbol resolve to a Var defined in
a namespace for which the .clj is in the classpath. Returns nil if
it can't find the source.
Example: (get-source-from-var 'filter)"
  [v] (when-let [filepath (:file (meta v))]
        (when-let [strm (.getResourceAsStream (RT/baseLoader) filepath)]
          (with-open [rdr (LineNumberReader. (InputStreamReader. strm))]
            (dotimes [_ (dec (:line (meta v)))] (.readLine rdr))
            (let [text (StringBuilder.)
                  pbr (proxy [PushbackReader] [rdr]
                        (read [] (let [i (proxy-super read)]
                                   (.append text (char i))
                                   i)))]
              (read (PushbackReader. pbr))
              (str text))))))

(defn- recursive-contains? [coll obj]
  "True if coll contains obj. Obj can't be a seq"
  (not (empty? (filter #(= obj %) (flatten coll)))))

(defn- does-var-call-fn [var fn]
  "Checks if a var calls a function named 'fn"
  (if-let [source (get-source-from-var var)]
    (let [node (read-string source)]
     (if (recursive-contains? node fn)
       var
       false))))

(defn- does-ns-refer-to-var? [ns var]
  (ns-resolve ns var))

(defn all-vars-who-call [sym]
  (filter
   ifn?
   (filter
    #(identity %)
    (map #(does-var-call-fn % sym)
         (flatten
          (map vals
               (map ns-interns
                    (filter #(does-ns-refer-to-var? % sym)
                            (all-ns)))))))))

(defn who-specializes [class]
  (letfn [(xref-lisp [sym] ; see find-definitions-for-emacs
            (if-let [meta (and sym (meta sym))]
              (if-let [path (find/slime-find-file (:file meta))]
                      `((~(str "(method " (:name meta) ")")
                          (:location
                           ~path
                           (:line ~(:line meta))
                           nil)))
                      `((~(str (:name meta))
                          (:error "Source definition not found."))))
              `((~(str "(method " (.getName sym) ")")
                  (:error ~(format "%s - definition not found." sym))))))]
         (let [methods (try (. class getMethods)
                            (catch java.lang.IllegalArgumentException e nil)
                            (catch java.lang.NullPointerException e nil))]
              (map xref-lisp methods))))

(defn who-calls [name]
  (letfn [(xref-lisp [sym-var]        ; see find-definitions-for-emacs
                     (when-let [meta (and sym-var (meta sym-var))]
                       (if-let [path (find/slime-find-file (:file meta))]
                         `((~(str (:name meta))
                            (:location
                             ~path
                             (:line ~(:line meta))
                             nil)))
                         `((~(str (:name meta))
                            (:error "Source definition not found."))))))]
    (let [callers (all-vars-who-call name) ]
      (map first (map xref-lisp callers)))))

(defslimefn xref [connection type name]
  (let [sexp (ns-resolve (connection/ns connection) (symbol name))]
       (condp = type
              :specializes (who-specializes sexp)
              :calls   (who-calls (symbol name))
              :callers nil
              :not-implemented)))
