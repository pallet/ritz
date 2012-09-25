(ns ritz.plugin-helpers
  "Helpers for use in the lein plugins"
  (:use
   [clojure.java.io :only [file]]
   [ritz.add-sources :only [add-source-artifacts]]
   [robert.hooke :only [add-hook]]))

;;; Dependencies used in vms and classloaders
(def lein-profile {:dependencies '[[leiningen "2.0.0-preview10"]]})
(def classlojure-profile {:dependencies '[[classlojure "0.6.6"]]})
(def clojure-profile {:dependencies '[[org.clojure/clojure "1.4.0"]]})

;;; Locate JDK extra jars
(defn jpda-jars
  []
  (let [libdir (file (System/getProperty "java.home") ".." "lib")]
    (for [j ["tools.jar" "sa-jdi.jar"]
          :when (.exists (file libdir j))]
      (.getCanonicalPath (file libdir j)))))

(defn add-jpda-jars
  "JPDA is in the JDK's tools.jar and sa-jdi.jar. Add them to the classpath."
  [f project]
  (concat (f project) (jpda-jars)))

;;; Add hooks
(defmacro add-hooks
  []
  (if (and
       (find-ns 'leiningen.core.classpath)
       (ns-resolve 'leiningen.core.classpath 'get-classpath))
    `(do
       (add-hook
        #'leiningen.core.classpath/get-classpath add-jpda-jars)
       (add-hook
        #'leiningen.core.classpath/get-classpath add-source-artifacts))
    `(do
       (require 'leiningen.classpath)
       (add-hook
        #'leiningen.classpath/get-classpath add-jpda-jars)
       (add-hook
        #'leiningen.classpath/get-classpath add-source-artifacts))))

(add-hooks)
