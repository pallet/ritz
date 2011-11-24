(ns ritz.swank.messages
  "Swank messages"
  (:require
   [ritz.inspect :as inspect]
   [ritz.logging :as logging]
   [clojure.string :as string]))

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


(defn default-repl-result [val {:keys [terminator] :or {terminator "\n"}}]
  `(:write-string ~(str (pr-str val) terminator) :repl-result))

(defn repl-result [val & {:keys [terminator] :as options}]
  (default-repl-result val options))

(defn write-string [val & {:keys [terminator] :or {terminator "\n"}}]
  `(:write-string ~(str val terminator) :repl-result))

(defn connection-info
  [pid clojure-version ns-name protocol-version
   & {:keys [style ns-prompt] :or {style :spawn}}]
  `(:pid ~pid
         :style ~style
         :lisp-implementation (:type "Clojure"
                                     :name "clojure"
                                     :version ~clojure-version)
         :package (:name ~ns-name :prompt ~(or ns-prompt ns-name))
         :version ~protocol-version))

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
     #(list :name (:unmangled-name %) :id 0 :value (:string-value %))
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

(defn debug-info
  "Provide debugger information"
  [condition-map restarts backtrace continutions]
  (list
   (condition condition-map)
   (doall (map restart restarts))
   (stacktrace-frames backtrace 0)
   (list* continutions)))

(defn debug
  "Provide debugger information"
  [thread-id level condition-map restarts backtrace continutions]
  (logging/trace "building debug info message")
  (list*
   :debug thread-id level
   (debug-info condition-map restarts backtrace continutions)))

(defn debug-activate
  "Activate debugger"
  ([thread-id level activate]
     `(:debug-activate ~thread-id ~level ~activate))
  ([thread-id level]
     (debug-activate thread-id level nil)))

(defn debug-return
  "Message to end debugging."
  ([thread-id level stepping]
     `(:debug-return ~thread-id ~level ~stepping))
  ([thread-id level]
     (debug-return thread-id level nil)))

(defn symbol-indentation
  [name body-position]
  (list name '. body-position))

(defn indentation-update
  [delta]
  `(:indentation-update ~delta))

(defn compiler-message
  "A compiler message"
  [m]
  `(:message ~(:message m)
             :severity ~(:severity m :error)
             :location ~(if-let [l (:location m)]
                          (location l)
                          '(:error "No error location available"))
             :references ~(:references m)
             :short-message ~(:message m)))

(defn compilation-result
  "Return a compilation result. Clojure doesn't have fasl files so the
   loadp and faslfile are ommited from the end of the message."
  [notes result duration-s]
  `(:compilation-result
    ~(list* (map compiler-message notes))
    ~(when result (pr-str result))
    ~duration-s))

(defn describe
  [{:keys [symbol-name type arglists doc] :as options}]
  (logging/trace "messages/describe %s" (pr-str options))
  (list :designator symbol-name
        type (str arglists " " doc)))

(defn presentation-start
  [id]
  (logging/trace "messages/presentation-start %s" id)
  (list :presentation-start id :repl-result))

(defn presentation-end
  [id]
  (logging/trace "messages/presentation-end %s" id)
  (list :presentation-end id :repl-result))

(defn eval-no-wait
  [form]
  `(:eval-no-wait ~(str form)))
