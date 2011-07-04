(ns ritz.repl-utils.mangle
  "Mangling, unmangling, etc"
  (:require
   [clojure.string :as string]))

(defn clojure->java
  "Mangle a clojure symbol"
  [s]
  (clojure.lang.Compiler/munge s))

(defn java->clojure
  "Unmangle a clojure symbol"
  [s]
  (reduce
   #(string/replace %1 (val %2) (str (key %2)))
   s
   clojure.lang.Compiler/CHAR_MAP))

(defn namespace-name->path
  "Convert a namespace name to a path"
  [ns]
  (-> ns (.replace \- \_) (.replace \. \/)))

(defn path->namespace-name
  "Convert a path to namespace name"
  [path]
  (-> path (.replace \_ \-) (.replace \/ \.)))

(defn clojure-class-name->namespace-name
  "Return the clojure namespace name for the given mangled class name"
  [class-name]
  (.replace ((re-find #"(.*?)\$" class-name) 1) \_ \-))
