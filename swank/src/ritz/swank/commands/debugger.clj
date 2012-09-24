(ns ritz.swank.commands.debugger
  "Debugger commands.  Everything that the proxy responds to."
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [ritz.debugger.break :as break]
   [ritz.jpda.debug :as jpda-debug]
   [ritz.jpda.jdi :as jdi]
   [ritz.logging :as logging]
   [ritz.swank.commands.contrib.ritz]
   [ritz.swank.connection :as connection]
   [ritz.swank.debug :as debug]
   [ritz.swank.inspect :as inspect]
   [ritz.swank.messages :as messages])
  (:use
   [ritz.debugger.connection :only [vm-context]]
   [ritz.debugger.inspect :only [reset-inspector]]
   [ritz.jpda.debug
    :only [invoke-restart invoke-named-restart
           frame-locals-with-string-values nth-frame-var]]
   [ritz.swank.commands :only [defslimefn]]))

;; (defn invoke-restart [restart]
;;   ((nth restart 2)))
(defslimefn break-on-exception [connection flag]
  (if flag
    (jdi/enable-exception-request-states (:vm (vm-context connection)))
    (jdi/disable-exception-request-states (:vm (vm-context connection)))))

(defslimefn backtrace [connection start end]
  (messages/stacktrace-frames
   (debug/backtrace connection start end) start))

(defslimefn debugger-info-for-emacs [connection start end]
  (debug/debugger-info-for-emacs connection start end))

(defslimefn invoke-nth-restart-for-emacs [connection level n]
  (jpda-debug/invoke-restart connection (:request-thread connection) level n))

(defslimefn throw-to-toplevel [connection]
  (invoke-named-restart connection (:request-thread connection) :quit))

(defslimefn sldb-continue [connection]
  (invoke-named-restart connection (:request-thread connection) :continue))

(defslimefn sldb-abort [connection]
  (invoke-named-restart connection (:request-thread connection) :abort))

(defslimefn frame-catch-tags-for-emacs [connection n]
  nil)

(defslimefn frame-locals-for-emacs [connection n]
  (let [[level-info level] (break/break-level-info
                            connection (:request-thread connection))]
    (messages/frame-locals
     (frame-locals-with-string-values
       (:vm-context connection)
       (:thread level-info) n))))

(defslimefn frame-locals-and-catch-tags [connection n]
  (list (frame-locals-for-emacs connection n)
        (frame-catch-tags-for-emacs connection n)))

(defslimefn frame-source-location [connection frame-number]
  (let [[level-info level] (break/break-level-info
                            connection (:request-thread connection))]
    (messages/location
     (jpda-debug/frame-source-location (:thread level-info) frame-number))))

(defslimefn inspect-frame-var [connection frame index]
  (let [inspector (connection/inspector connection)
        [level-info level] (break/break-level-info
                            connection (:request-thread connection))
        vm-context (vm-context connection)
        thread (:thread level-info)
        object (nth-frame-var vm-context thread frame index)]
    (when object
      (reset-inspector connection)
      (inspect/inspect-object inspector object)
      (messages/inspector
       (inspect/display-values
        (assoc vm-context :current-thread thread) inspector)))))

(defslimefn inspect-nth-part [connection index]
  (let [inspector (connection/inspector connection)
        [level-info level] (break/break-level-info
                            connection (:request-thread connection))
        vm-context (vm-context connection)
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
  (let [threads (debug/thread-list connection)
        labels '(:id :name :state :at-breakpoint? :suspended? :suspends)]
    (cons labels (map thread-data-fn threads))))

(defslimefn kill-nth-thread
  [connection index]
  (logging/trace "kill-nth-thread %s" index)
  (when index
    (debug/kill-nth-thread connection index)))

;;; stepping
(defslimefn sldb-step [connection frame]
  (invoke-named-restart connection (:request-thread connection) :step-into))

(defslimefn sldb-next [connection frame]
  (invoke-named-restart connection (:request-thread connection) :step-next))

(defslimefn sldb-out [connection frame]
  (invoke-named-restart connection (:request-thread connection) :step-out))

;; eval
(defslimefn eval-string-in-frame [connection expr n]
  (let [[level-info level] (break/break-level-info
                            connection (:request-thread connection))
        thread (:thread level-info)]
    (jpda-debug/eval-string-in-frame
     connection (vm-context connection) thread expr n)))

(defslimefn pprint-eval-string-in-frame [connection expr n]
  (let [[level-info level] (break/break-level-info
                            connection (:request-thread connection))
        thread (:thread level-info)]
    (jpda-debug/pprint-eval-string-in-frame
     connection (vm-context connection) thread expr n)))

;; disassemble
(defslimefn sldb-disassemble [connection frame-index]
  (let [[level-info level] (break/break-level-info
                            connection (:request-thread connection))
        thread (:thread level-info)]
    (string/join \newline
     (debug/disassemble-frame
      (vm-context connection) thread frame-index))))

(defslimefn disassemble-form [connection ^String form-string]
  (when (.startsWith form-string "'")
    (let [sym (eval (read-string form-string))
          vm-context (vm-context connection)]
      (string/join \newline
                   (debug/disassemble-symbol
                    vm-context (:control-thread vm-context)
                    (or (namespace sym) (connection/buffer-ns-name))
                    (name sym))))))
