(ns ritz.connection
  "A connection is a map in an atom."
  (:require
   [ritz.logging :as logging]
   [ritz.repl-utils.helpers :as helpers]
   [ritz.repl-utils.utils :as utils]
   [clojure.java.io :as java-io])
  (:import
   java.io.BufferedReader
   java.io.FileReader
   java.io.InputStreamReader
   java.io.OutputStreamWriter
   java.io.PrintWriter
   java.io.StringWriter))

(defn vm-context
  [connection]
  (:vm-context connection))

(defn debug-context
  "Return the debug context"
  [connection]
  @(:debug connection))


(defn read-exception-filters
  []
  (let [filter-file (java-io/file ".ritz-exception-filters")]
    (when (.exists filter-file)
      (read-string (slurp filter-file)))))

(defn spit-exception-filters
  [connection]
  (let [filter-file (java-io/file ".ritz-exception-filters")]
    (spit filter-file
          (pr-str (:exception-filters (debug-context connection))))))

(def default-exception-filters
  [{:type "clojure.lang.LockingTransaction$RetryEx" :enabled true}
   {:type "com.google.inject.internal.ErrorsException" :enabled true}
   {:catch-location #"com.sun.*" :enabled true}
   {:catch-location #"sun.*" :enabled true}
   {:catch-location #"ritz.commands.*" :enabled true}
   {:message #"Could not locate ritz/commands/contrib.*" :enabled true}
   {:message #".*accessibility.properties \(No such file or directory\)"
    :enabled true}
   {:message #".*mailcap \(No such file or directory\)" :enabled true}])
