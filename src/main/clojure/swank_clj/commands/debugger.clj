(ns swank-clj.commands.debugger
  "Debugger commands.  Everything that the proxy responds to."
  (:require
   [clojure.java.io :as io]
   [swank-clj.connection :as connection]
   [swank-clj.jpda.debug :as debug]
   [swank-clj.inspect :as inspect]
   [swank-clj.logging :as logging]
   [swank-clj.swank.messages :as messages]
   [swank-clj.commands.contrib.swank-clj])
  (:use
   [swank-clj.commands :only [defslimefn]]))

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
     (connection/vm-context connection) thread expr n)))
