(ns ritz.swank.commands.contrib.ritz
  "Contrib for providing ritz specific functions"
  (:use
   [clojure.string :only [blank? split]]
   [ritz.debugger.connection :only [debug-context vm-context]]
   [ritz.debugger.exception-filters
    :only [spit-exception-filters exception-filters exception-filter-kill!
           exception-filter-enable! exception-filter-disable!]]
   [ritz.repl-utils.source-forms :only [source-forms]]
   [ritz.swank.commands :only [defslimefn]]
   [ritz.swank.connection :only [current-namespace request-ns]]
   [ritz.swank.core :only [reset-namespaces]])
  (:require
   [clojure.java.javadoc :as javadoc]
   [clojure.string :as string]
   [ritz.debugger.connection :as connection]
   [ritz.logging :as logging]
   [ritz.repl-utils.doc :as doc]
   [ritz.swank.debug :as debug]
   [ritz.swank.messages :as messages]
   [ritz.swank.project :as project]))

;;; Breakpoints
(defslimefn line-breakpoint
  "Set a breakpoint at the specified line. Updates the vm-context in the
   connection."
  [connection namespace filename line]
  (let [n (debug/line-breakpoint connection namespace filename line)]
    (format "Set %d breakpoints" n)))

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
  (let [breakpoints (debug/breakpoint-list connection)
        labels '(:id :file :line :enabled)]
    (cons labels (map breakpoint-data-fn breakpoints))))

(defslimefn breakpoint-kill
  [connection breakpoint-id]
  (debug/breakpoint-kill connection breakpoint-id))

(defslimefn breakpoint-enable
  [connection breakpoint-id]
  (debug/breakpoint-enable connection breakpoint-id))

(defslimefn breakpoint-disable
  [connection breakpoint-id]
  (debug/breakpoint-disable connection breakpoint-id))

(defslimefn breakpoint-location
  [connection breakpoint-id]
  (messages/location (debug/breakpoint-location connection breakpoint-id)))

;;; Exception Filters
(defslimefn quit-exception-filter-browser [connection])

(defn ^{:private true} exception-filter-data-fn
  [i {:keys [type location catch-location message enabled]}]
  (list i type location catch-location message enabled))


(defn exception-filter-list
  "Return a sequence of exception filters, ensuring that expressions are strings
   and not regexes."
  [filters]
  (map
   (fn [filter]
     (->
      filter
      (update-in [:location] str)
      (update-in [:catch-location] str)
      (update-in [:message] str)))
   filters))


(defslimefn list-exception-filters [connection]
  "Return a list
  (LABELS (ID TYPE LOCATION CATCH-LOCATION ENABLED ATTRS ...) ...).
LABELS is a list of attribute names and the remaining lists are the
corresponding attribute values per thread."
  [connection]
  (let [filters (exception-filter-list (exception-filters connection))
        labels '(:id :type :location :catch-location :message :enabled)]
    (cons labels (map exception-filter-data-fn (range) filters))))

(defslimefn exception-filter-kill
  [connection exception-filter-id]
  (exception-filter-kill! connection exception-filter-id)
  nil)

(defslimefn exception-filter-enable
  [connection exception-filter-id]
  (exception-filter-enable! connection exception-filter-id)
  nil)

(defslimefn exception-filter-disable
  [connection exception-filter-id]
  (exception-filter-disable! connection exception-filter-id)
  nil)

(defslimefn save-exception-filters [connection]
  (spit-exception-filters connection))

;;; javadoc
(defslimefn javadoc-local-paths
  [connection & paths]
  (doc/javadoc-local-paths paths)
  nil)

(defslimefn javadoc-url
  [connection symbol-name local-javadoc-paths]
  (doc/javadoc-local-paths local-javadoc-paths)
  (doc/javadoc-url symbol-name (ns-name (request-ns connection))))

;;; Namespaces
(defslimefn reset-repl
  "Reset the repl to an initial state"
  [connection]
  (reset-namespaces))

;;; ## Leiningen

(defslimefn lein
  "Run lein on the current project."
  [connection arg-string]
  (project/lein connection (remove blank? (split arg-string #" "))))

;;; ### Loading of project.clj
(defslimefn reload-project
  "Reload the current project, adjusting the classpath as necessary."
  [connection]
  (project/reload connection))

(defslimefn load-project
  "Load the specified project, adjusting the classpath as necessary."
  [connection project-file]
  (project/load-project connection project-file))


;;; list repl source forms
(defslimefn list-repl-source-forms
  "List all the source forms entered in the REPL"
  [connection]
  (string/join \newline (source-forms)))

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
