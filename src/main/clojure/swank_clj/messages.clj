(ns swank-clj.messages
  (:require
   [swank-clj.inspect :as inspect]
   [swank-clj.logging :as logging]))

(defn abort
  "Command aborted message."
  ([id]
     `(:return (:abort "NIL") ~id))
  ([id t]
     `(:return (:abort ~(str t)) ~id)))

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

(defn location
  "A source location. Only one key can be specifed for each map.
From slime-goto-source-location docstring:
<buffer>   ::= (:file <filename>)
             | (:buffer <buffername>)
             | (:source-form <string>)
             | (:zip <file> <entry>)

<position> ::= (:position <fixnum>) ; 1 based (for files)
             | (:offset <start> <offset>) ; start+offset (for C-c C-c)
             | (:line <line> [<column>])
             | (:function-name <string>)
             | (:source-path <list> <start-position>)
             | (:method <name string> <specializer strings> . <qualifiers>)"
  [[{:keys [file buffer source-form zip] :as buffer-path}
    {:keys [position offset line column function-name method source-path eof]
     :as buffer-position}
    hints]]
  (let [f (fn transform-location-map [m]
            (let [l (mapcat
                     (fn [[k v]] (if (sequential? v) (list* k v) (list k v)))
                     m)]
              (when (seq l) l)))]
    (list :location (f buffer-path) (f buffer-position)
          (if (sequential? hints) (seq hints) (when hints (list hints))))))

(defn condition
  "A condition message component"
  [condition-map]
  (list (:message condition-map "condition")
        (:type condition-map "")
        (:extras condition-map)))

(defn restart
  "A restart message component"
  [{:keys [name description]}]
  (list name description))

(defn stacktrace-frames
  [frames start]
  (map
   #(list
     %1
     (format "%s (%s:%s)" (:function %2) (:source %2) (:line %2))
     (list :restartable (:restartable %2)))
   (iterate inc start)
   frames))

(defn debug
  "Provide debugger information"
  [thread-id level condition-map restarts backtrace continutions]
  (list*
   :debug thread-id level
   (list
    (condition condition-map)
    (doall (map restart restarts))
    (stacktrace-frames backtrace 0)
    (list* continutions))))

(defn debug-activate
  "Activate debugger"
  ([thread-id level activate]
     `(:debug-activate ~thread-id ~level ~activate))
  ([thread-id level]
     (debug-activate thread-id level nil)))

(defn debug-return
  "Message to end debugging."
  ([thread-id level stepping]
     `(:debug-return ~thread-id ~level stepping))
  ([thread-id level]
     (debug-return thread-id level nil)))
