(ns ritz.commands.debugger
  "Debugger commands.  Everything that the proxy responds to."
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [ritz.connection :as connection]
   [ritz.jpda.debug :as debug]
   [ritz.inspect :as inspect]
   [ritz.logging :as logging]
   [ritz.swank.messages :as messages]
   [ritz.commands.contrib.ritz])
  (:use
   [ritz.swank.commands :only [defslimefn]]))

(defn invoke-restart [restart]
  ((nth restart 2)))

(defslimefn backtrace [connection start end]
  (messages/stacktrace-frames
   (debug/backtrace connection start end) start))

(defslimefn debugger-info-for-emacs [connection start end]
  (debug/debugger-info-for-emacs connection start end))

(defslimefn invoke-nth-restart-for-emacs [connection level n]
  (debug/invoke-restart connection level n))

(defn invoke-named-restart
  [connection kw]
  (debug/invoke-named-restart connection kw))

(defslimefn throw-to-toplevel [connection]
  (invoke-named-restart connection :quit))

(defslimefn sldb-continue [connection]
  (invoke-named-restart connection :continue))

(defslimefn sldb-abort [connection]
  (invoke-named-restart connection :abort))

(defslimefn frame-catch-tags-for-emacs [connection n]
  nil)

(defslimefn frame-locals-for-emacs [connection n]
  (let [[level-info level] (connection/current-sldb-level-info connection)]
    (messages/frame-locals
     (debug/frame-locals-with-string-values
       @(:vm-context @connection)
       (:thread level-info) n))))

(defslimefn frame-locals-and-catch-tags [connection n]
  (list (frame-locals-for-emacs connection n)
        (frame-catch-tags-for-emacs connection n)))

(defslimefn frame-source-location [connection frame-number]
  (let [[level-info level] (connection/current-sldb-level-info connection)]
    (messages/location
     (debug/frame-source-location (:thread level-info) frame-number))))

(defslimefn inspect-frame-var [connection frame index]
  (let [inspector (connection/inspector connection)
        [level-info level] (connection/current-sldb-level-info connection)
        vm-context (connection/vm-context connection)
        thread (:thread level-info)
        object (debug/nth-frame-var vm-context thread frame index)]
    (when object
      (inspect/reset-inspector inspector)
      (inspect/inspect-object inspector object)
      (messages/inspector
       (inspect/display-values
        (assoc vm-context :current-thread thread) inspector)))))

(defslimefn inspect-nth-part [connection index]
  (let [inspector (connection/inspector connection)
        [level-info level] (connection/current-sldb-level-info connection)
        vm-context (connection/vm-context connection)
        thread (or (:thread level-info) (:control-thread vm-context))
        vm-context (assoc vm-context :current-thread thread)]
    (inspect/inspect-object
     inspector (inspect/nth-part vm-context inspector index))
    (messages/inspector (inspect/display-values vm-context inspector))))

;;; Threads
(def ^{:private true} thread-data-fn
  (comp
   seq
   (juxt #(:id % "")
         :name
         #(:status % "")
         #(:at-breakpoint? % "")
         #(:suspended? % "")
         #(:suspend-count % ""))))

(defslimefn list-threads
  "Return a list (LABELS (ID NAME STATUS ATTRS ...) ...).
LABELS is a list of attribute names and the remaining lists are the
corresponding attribute values per thread."
  [connection]
  (let [context (swap! (:vm-context @connection) debug/thread-list)
        labels '(:id :name :state :at-breakpoint? :suspended? :suspends)]
    (cons labels (map thread-data-fn (:threads context)))))

;;; TODO: Find a better way, as Thread.stop is deprecated
(defslimefn kill-nth-thread
  [connection index]
  (logging/trace "kill-nth-thread %s" index)
  (when index
    (let [context (connection/vm-context connection)]
      (when-let [thread (debug/nth-thread context index)]
        (debug/stop-thread context (:id thread))))))

;;; stepping
(defslimefn sldb-step [connection frame]
  (invoke-named-restart connection :step-into))

(defslimefn sldb-next [connection frame]
  (invoke-named-restart connection :step-next))

(defslimefn sldb-out [connection frame]
  (invoke-named-restart connection :step-out))

;; eval
(defslimefn eval-string-in-frame [connection expr n]
  (let [[level-info level] (connection/current-sldb-level-info connection)
        thread (:thread level-info)]
    (debug/eval-string-in-frame
     connection (connection/vm-context connection) thread expr n)))

(defslimefn pprint-eval-string-in-frame [connection expr n]
  (let [[level-info level] (connection/current-sldb-level-info connection)
        thread (:thread level-info)]
    (debug/pprint-eval-string-in-frame
     connection (connection/vm-context connection) thread expr n)))

;; disassemble
(defslimefn sldb-disassemble [connection frame-index]
  (let [[level-info level] (connection/current-sldb-level-info connection)
        thread (:thread level-info)]
    (string/join \newline
     (debug/disassemble-frame
      (connection/vm-context connection) thread frame-index))))

(defslimefn disassemble-form [connection form-string]
  (when (.startsWith form-string "'")
    (let [sym (eval (read-string form-string))
          vm-context (connection/vm-context connection)]
      (string/join \newline
                   (debug/disassemble-symbol
                    vm-context (:control-thread vm-context)
                    (or (namespace sym) (connection/buffer-ns-name))
                    (name sym))))))
