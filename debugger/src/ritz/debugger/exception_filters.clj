(ns ritz.debugger.exception-filters
  "Exception filter relation."
  (:use
   [ritz.debugger.connection
    :only [debug-context debug-assoc! debug-update-in!]])
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
   #(vec (concat (subvec % 0 (max id 0)) (subvec % (inc id))))))

(defn- update-filter-exception
  [connection id f]
  (debug-update-in!
   connection [:exception-filters]
   #(vec (concat (subvec % 0 id) [(f (nth % id))] (subvec % (inc id))))))

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
(def exception-filters-file (java-io/file ".ritz-exception-filters"))

(defn read-exception-filters
  []
  (when (.exists exception-filters-file)
    (read-string (slurp exception-filters-file))))

(defn spit-exception-filters
  [connection]
  (spit exception-filters-file
        (pr-str (:exception-filters (debug-context connection)))))

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
