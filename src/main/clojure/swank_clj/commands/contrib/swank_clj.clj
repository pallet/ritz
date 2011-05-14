(ns swank-clj.commands.contrib.swank-clj
  "Contrib for providing swank-clj specific functions"
  (:use
   [swank-clj.swank.commands :only [defslimefn]])
  (:require
   [swank-clj.connection :as connection]
   [swank-clj.jpda.debug :as debug]
   [swank-clj.logging :as logging]
   [swank-clj.swank.messages :as messages]))

;;; Breakpoints

(defslimefn line-breakpoint
  "Set a breakpoint at the specified line. Updates the vm-context in the
   connection."
  [connection namespace filename line]
  (let [context (:vm-context @connection)
        n (count (:breakpoints @context))
        new-context (swap!
                     context debug/line-breakpoint namespace filename line)]
    (format
     "Set %d breakpoints"
     (- (count (:breakpoints new-context)) n))))

;; (defslimefn break-on-exceptions
;;   "Control which expressions are trapped in the debugger"
;;   [connection filter-caught? class-exclusions])

(defslimefn quit-breakpoint-browser [connection])


(def ^{:private true} breakpoint-data-fn
  (comp
   seq
   (juxt #(:id % "")
         :file
         :line
         :enabled)))

(defslimefn list-breakpoints [connection]
    "Return a list (LABELS (ID FILE LINE ENABLED ATTRS ...) ...).
LABELS is a list of attribute names and the remaining lists are the
corresponding attribute values per thread."
  [connection]
  (let [context (swap! (:vm-context @connection) debug/breakpoint-list)
        breakpoints (:breakpoints context)
        labels '(:id :file :line :enabled)]
    (cons labels (map breakpoint-data-fn breakpoints))))

(defslimefn breakpoint-kill
  [connection breakpoint-id]
  (debug/breakpoint-kill (connection/vm-context connection) breakpoint-id))

(defslimefn breakpoint-enable
  [connection breakpoint-id]
  (debug/breakpoint-enable (connection/vm-context connection) breakpoint-id))

(defslimefn breakpoint-disable
  [connection breakpoint-id]
  (debug/breakpoint-disable (connection/vm-context connection) breakpoint-id))

(defslimefn breakpoint-location
  [connection breakpoint-id]
  (messages/location
   (debug/breakpoint-location
    (connection/vm-context connection) breakpoint-id)))


;;; swank development utilities
(defslimefn toggle-swank-logging
  "Control logging level"
  [connection]
  (swap! logging/log-level (fn [lvl] (if lvl nil :trace))))

(defslimefn resume-vm
  "Resume the vm.  If the vm becomes suspended for some reason, you can
   use this to unsuspend it"
  [connection]
  (.resume (:vm (connection/vm-context connection))))
