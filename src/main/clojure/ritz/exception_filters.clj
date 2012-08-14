(ns ritz.exception-filters
  "Exception filter relation."
  (:use
   [ritz.connection :only [debug-context debug-assoc! debug-update-in!]])
  (:require
   [clojure.java.io :as java-io]))

;;; # Query and update
(defn exception-filters-set!
  "Assign the exception filters on a connection."
  [connection filters]
  (debug-assoc! connection :exception-filters filters))

(defn exception-filters
  "Return the exception filters"
  [connection]
  (:exception-filters (debug-context connection)))

(defn exception-filter-kill!
  "Remove an exception-filter."
  [connection id]
  (debug-update-in!
   connection [:exception-filters]
   #(vec (concat (take (max id 0) %) (drop (inc id) %)))))

(defn- update-filter-exception
  [connection id f]
  (debug-update-in!
   connection [:exception-filters]
   #(vec (concat (take id %) [(f (nth % id))] (drop (inc id) %)))))

(defn exception-filter-enable!
  [connection id]
  (update-filter-exception connection id #(assoc % :enabled true)))

(defn exception-filter-disable!
  [connection id]
  (update-filter-exception connection id #(assoc % :enabled false)))

(defn exception-filter-add!
  [connection filter]
  (debug-update-in! connection [:exception-filters] conj filter))

;;; # Storage and default
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
   {:catch-location #"ritz.repl_utils.*" :enabled true}
   {:message #"Could not locate ritz/commands/contrib.*" :enabled true}
   {:message #".*accessibility.properties \(No such file or directory\)"
    :enabled true}
   {:message #".*mailcap \(No such file or directory\)" :enabled true}])
