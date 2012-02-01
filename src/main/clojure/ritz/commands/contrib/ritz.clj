(ns ritz.commands.contrib.ritz
  "Contrib for providing ritz specific functions"
  (:use
   [ritz.swank.commands :only [defslimefn]])
  (:require
   [clojure.string :as string]
   [clojure.java.javadoc :as javadoc]
   [ritz.connection :as connection]
   [ritz.jpda.debug :as debug]
   [ritz.logging :as logging]
   [ritz.repl-utils.doc :as doc]
   [ritz.repl-utils.find :as find]
   [ritz.swank.messages :as messages]))

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

;;; Exception Filters
(defslimefn quit-exception-filter-browser [connection])

(defn ^{:private true} exception-filter-data-fn
  [i {:keys [type location catch-location message enabled]}]
  (list i type location catch-location message enabled))

(defslimefn list-exception-filters [connection]
  "Return a list
  (LABELS (ID TYPE LOCATION CATCH-LOCATION ENABLED ATTRS ...) ...).
LABELS is a list of attribute names and the remaining lists are the
corresponding attribute values per thread."
  [connection]
  (let [filters (debug/exception-filter-list @connection)
        labels '(:id :type :location :catch-location :message :enabled)]
    (cons labels (map exception-filter-data-fn (range) filters))))

(defslimefn exception-filter-kill
  [connection exception-filter-id]
  (debug/exception-filter-kill connection exception-filter-id)
  nil)

(defslimefn exception-filter-enable
  [connection exception-filter-id]
  (debug/exception-filter-enable connection exception-filter-id)
  nil)

(defslimefn exception-filter-disable
  [connection exception-filter-id]
  (debug/exception-filter-disable connection exception-filter-id)
  nil)

(defslimefn save-exception-filters [connection]
  (connection/spit-exception-filters connection))

;;; javadoc
(defslimefn javadoc-local-paths
  [connection & paths]
  (doc/javadoc-local-paths paths)
  nil)

(defslimefn javadoc-url
  [connection symbol-name]
  (doc/javadoc-url symbol-name))

;;; list repl source forms
(defslimefn list-repl-source-forms
  "List all the source forms entered in the REPL"
  [connection]
  (string/join \newline (find/source-forms)))

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
