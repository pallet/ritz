(ns lein-ritz.middleware
  "Project middleware useful in ritz")

(defn set-properties [project]
  (if-let [properties (:properties project)]
    (case (:eval-in project)
      :subprocess (update-in project [:jvm-opts] concat
                             (map
                              (fn [[k v]] (format "-D%s=\"%s\"" k v))
                              properties))
      (doseq [[k v] properties]
        (System/setProperty (str k) (str v))))
    project))
