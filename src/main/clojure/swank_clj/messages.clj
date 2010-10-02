(ns swank-clj.messages
  (:require
   [swank-clj.inspect :as inspect]))


(defn inspector
  "Message for an inspector"
  [inspector]
  (list :title (inspect/inspectee-title inspector)
        :id (inspect/inspectee-index inspector)
        :content (inspect/content-range inspector 0 500)))

(defn inspect
  "Message to request an inspector for given content, as a readable string."
  [object-string]
  `(:inspect ~object-string))

(defn frame-locals
  "Message to return frame locals for slime."
  [locals-map]
  (seq
   (sort-by
    second
    (map
     #(list :name (:name %) :id 0 :value (:string-value %))
     locals-map))))

(defn debug-return
  "Message to end debugging."
  [thread-id level]
  `(:debug-return ~thread-id ~level nil))
