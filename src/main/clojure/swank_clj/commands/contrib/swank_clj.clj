(ns swank-clj.commands.contrib.swank-clj
  "Contrib for providing swank-clj specific functions"
  (:use
   [swank-clj.commands :only [defslimefn]])
  (:require
   [swank-clj.jpda.debug :as debug]
   [swank-clj.swank.messages :as messages]))

;;; Breakpoints

(defslimefn line-breakpoint
  [connection namespace filename line]
  (debug/line-breakpoint connection namespace filename line))

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
