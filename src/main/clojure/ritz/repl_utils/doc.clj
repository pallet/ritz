(ns ritz.repl-utils.doc
  "Documentation utils"
  (:refer-clojure :exclude [print-doc])
  (:require
   [clojure.java.javadoc :as javadoc]
   [clojure.java.io :as io])
  (:import
   java.io.File))

(defn- print-doc*
  "Replacement for clojure.core/print-doc"
  [m]
  (println "-------------------------")
  (println (str (when-let [ns (:ns m)] (str (ns-name ns) "/")) (:name m)))
  (cond
   (:forms m) (doseq [f (:forms m)]
                (print "  ")
                (prn f))
   (:arglists m) (prn (:arglists m)))
  (if (:special-form m)
    (do
      (println "Special Form")
      (println " " (:doc m))
      (if (contains? m :url)
        (when (:url m)
          (println (str "\n  Please see http://clojure.org/" (:url m))))
        (println (str "\n  Please see http://clojure.org/special_forms#"
                      (:name m)))))
    (do
      (when (:macro m)
        (println "Macro"))
      (println " " (:doc m)))))

(def print-doc
  (let [print-doc (resolve 'clojure.core/print-doc)]
    (if (or (nil? print-doc) (-> print-doc meta :private))
      (comp print-doc* meta)
      print-doc)))

(defn doc-string
  "Return a string with a var's formatted documentation"
  [var]
  (with-out-str (print-doc var)))

(defn describe
  "Describe a var"
  [var]
  (let [m (meta var)]
    {:symbol-name (str (ns-name (:ns m)) "/" (:name m))
     :type (cond
            (:macro m) :macro
            (:arglists m) :function
            :else :variable)
     :arglists (str (:arglists m))
     :doc (:doc m)}))


(defn- make-apropos-matcher [pattern case-sensitive?]
  (let [pattern (java.util.regex.Pattern/quote pattern)
        pat (re-pattern (if case-sensitive?
                          pattern
                          (format "(?i:%s)" pattern)))]
    (fn [var] (re-find pat (pr-str var)))))

(defn- apropos-symbols [string ns public-only? case-sensitive?]
  (let [ns (if ns [ns] (all-ns))
        matcher (make-apropos-matcher string case-sensitive?)
        lister (if public-only? ns-publics ns-interns)]
    (filter matcher
            (apply concat (map (comp (partial map second) lister) ns)))))

(defn- present-symbol-before
  "Comparator such that x belongs before y in a printed summary of symbols.
Sorted alphabetically by namespace name and then symbol name, except
that symbols accessible in the current namespace go first."
  [ns x y]
  (let [accessible?
        (fn [var] (= (ns-resolve ns (:name (meta var)))
                     var))
        ax (accessible? x) ay (accessible? y)]
    (cond
     (and ax ay) (compare (:name (meta x)) (:name (meta y)))
     ax -1
     ay 1
     :else (let [nx (str (:ns (meta x))) ny (str (:ns (meta y)))]
             (if (= nx ny)
               (compare (:name (meta x)) (:name (meta y)))
               (compare nx ny))))))

(defn apropos-list
  "Find a list of matching symbols for name, restricted to ns if non-nil
   prefering symbols accessible from prefer-ns."
  [ns name public-only? case-sensitive? prefer-ns]
  (sort
   #(present-symbol-before prefer-ns %1 %2)
   (apropos-symbols name ns public-only? case-sensitive?)))

;;; javadoc
(alter-var-root
 #'javadoc/*feeling-lucky-url*
 (constantly "http://www.google.com/search?q=%2Bjavadoc+"))

(defn javadoc-local-paths
  "Set javadoc paths, filtering duplicates"
  [paths]
  (dosync (commute
           @#'javadoc/*local-javadocs*
           (fn [p] (distinct (concat p paths))))))

(defn javadoc-partial-match [file-path files]
  (let [re (re-pattern (str "HREF=\"(.*/" file-path ")"))
        finder (fn [classes-file]
                 (when-let [[s m] (re-find
                                   re
                                   (slurp classes-file))]
                   (str (.toURI (io/file (.getParent classes-file) m)))))]
    (first (filter identity (map finder files)))))

(defn javadoc-url
  "Searches for a URL for the given class name.  Tries
  *local-javadocs* first, then *remote-javadocs*.  Returns a string."
  {:tag String
   :added "1.2"}
  [^String classname]
  (let [classname (if (.endsWith classname ".")
                    (.substring classname 0 (dec (count classname)))
                    classname)
        file-path (str (-> classname
                           (.replace  \. java.io.File/separatorChar)
                           (.replace \$ \.))
                       ".html")
        url-path (.replace classname \. \/)
        files (mapcat
               (fn [base]
                 (filter
                  #(= "allclasses-noframe.html" (.getName %))
                  (file-seq (File. base))))
               @@#'javadoc/*local-javadocs*)]
    (if-let [file ^File (first
                         (filter
                          #(.exists ^File %)
                          (map #(io/file (.getParent %) file-path) files)))]
      (-> file .toURI str)
      ;; If no local file, try remote URLs:
      (or
       (some (fn [[prefix url]]
               (when (.startsWith classname prefix)
                 (str url url-path ".html")))
             @@#'javadoc/*remote-javadocs*)
       ;; lookup in indexes for partial match
       (javadoc-partial-match file-path files)
       ;; if *feeling-lucky* try a web search
       (when @#'javadoc/*feeling-lucky*
         (str @#'javadoc/*feeling-lucky-url* classname))))))
