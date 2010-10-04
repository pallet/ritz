(ns swank-clj.messages
  (:require
   [swank-clj.inspect :as inspect]
   [swank-clj.logging :as logging]))

(defn abort
  "Command aborted message."
  [id]
  `(:return (:abort) ~id))

(defn ok
  "Command completed message."
  [result id]
  `(:return (:ok ~result) ~id))

(defn repl-result [val]
  `(:write-string ~(str (pr-str val) "\n") :repl-result))

(defn inspector
  "Message for an inspector"
  [[title id content]]
  (logging/trace "messages/inspector %s %s %s" title id content)
  `(:title ~title :id ~id :content ~(seq content)))

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
