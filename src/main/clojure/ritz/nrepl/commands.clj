(ns ritz.nrepl.commands
  (:use
   [ritz.logging :only [set-level trace]]))

(set-level :trace)

(defmulti jpda-op
  "Execute a jpda operation"
  (fn [op connection msg]
    (trace "jpda-op %s" op)
    op))

(defmethod jpda-op :ritz-version
  [_ connection message]
  "it's alive!")

(defmethod jpda-op :jpda
  [_ connection message]
  (pr-str "it's alive!"))
