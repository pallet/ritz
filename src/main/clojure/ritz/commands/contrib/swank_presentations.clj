(ns ritz.commands.contrib.swank-presentations
  "Adapted from slime's swank-presentations.lisp"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [ritz.connection :as connection]
   [ritz.logging :as logging]
   [ritz.swank.core :as core]
   [ritz.swank.messages :as messages]
   [ritz.swank.utils :as utils]
   [ritz.swank.commands :as commands]
   [ritz.commands.emacs :as emacs])
  (:import
   java.util.WeakHashMap))

;;;; Recording and accessing results of computations

(def ^{:dynamic true
       :doc "Non-nil means that REPL results are saved for later lookup."}
  *record-repl-results* true)

(defonce ^{:doc "Store the mapping of objects to numeric identifiers"}
  object-to-presentation-id
  (ref (WeakHashMap.)))

(defonce ^{:doc "Store the mapping of numeric identifiers to objects"}
  presentation-id-to-object
  (ref (WeakHashMap.)))

(defn clear-presentation-tables
  []
  (sync
   (.clear @object-to-presentation-id)
   (.clear @presentation-id-to-object)))

(defonce ^{:doc "identifier counter"}
  presentation-counter (atom 0))

(def nil-surrogate (gensym "nil-surrogate"))

(defn save-presented-object
  "Save OBJECT and return the assigned id.
If OBJECT was saved previously return the old id."
  [object]
  (sync nil
   (let [object (if (nil? object) nil-surrogate object)]
     ;; We store *nil-surrogate* instead of nil, to distinguish it from
     ;; an object that was garbage collected.
     (or
      (.get (ensure object-to-presentation-id) object)
      (let [id (swap! presentation-counter inc)]
        (assert (number? id))
        (alter presentation-id-to-object #(do (.put % id object) %))
        (alter object-to-presentation-id #(do (.put % object id) %))
        id)))))

(defn clean-id [id]
  (cond
   (number? id) (int id)
   (symbol? id) (Long/parseLong (name id))
   :else id))

(commands/defslimefn lookup-presented-object
  "Retrieve the object corresponding to ID.
The secondary value indicates the absence of an entry."
  [_ id]
  (logging/trace "lookup-presented-object %s" (pr-str id))
  (let [id (clean-id id)]
    (cond
     (and id (number? id)) (let [object (.get @presentation-id-to-object id)]
                             (cond
                              (= object nil-surrogate) [nil true] ;; A stored nil object
                              (nil? object) [nil nil]
                              :else [object true]))
     true [nil nil]
     ;; (let [[ref-type & args] id]
     ;;   (case ref-type
     ;;     :frame-var
     ;;     (let [[thread-id frame index] args]
     ;;       (handler-case
     ;;           (frame-var-value frame index)
     ;;         (t (condition)
     ;;            (declare (ignore condition))
     ;;            (values nil nil))
     ;;         (:no-error (value)
     ;;                    (values value t))))
     ;;     :inspected-part
     ;;     (let [[part-index] args]
     ;;       (if (< part-index (length *inspectee-parts*))
     ;;         (values (inspector-nth-part part-index) t)
     ;;         (values nil nil)))))
     )))

(commands/defslimefn lookup-presented-object-or-lose
  "Get the result of the previous REPL evaluation with ID."
  ([_ id]
     (let [[object foundp] (lookup-presented-object _ id)]
       (if foundp
         object
         (logging/trace "Attempt to access unrecorded object (id %s)." id))))
  ([id] (lookup-presented-object-or-lose nil id)))

(commands/defslimefn clear-repl-results
  "Forget the results of all previous REPL evaluations."
  [connection]
  (clear-presentation-tables)
  true)

(defn present-repl-results
  ;; Override a function in swank.lisp, so that
  ;; presentations are associated with every REPL result.
  [connection values]
  (letfn [(send-value [value]
            (let [id (and *record-repl-results* (save-presented-object value))]
              (connection/send-to-emacs
               connection (messages/presentation-start id))
              (core/write-result-to-emacs connection value :terminator nil)
              (connection/send-to-emacs
               connection (messages/presentation-end id))
              (connection/send-to-emacs
               connection (messages/write-string nil))))]
    ;; (fresh-line)
    ;; (finish-output)
    (if (seq values)
      (doseq [value values] (send-value value))
      (connection/send-to-emacs
               connection (messages/repl-result "; No value")))))

;;;; Presentation menu protocol
;;
;; To define a menu for a type of object, define a method
;; menu-choices-for-presentation on that object type.  This function
;; should return a list of two element lists where the first element is
;; the name of the menu action and the second is a function that will be
;; called if the menu is chosen. The function will be called with 3
;; arguments:
;;
;; choice: The string naming the action from above
;;
;; object: The object
;;
;; id: The presentation id of the object
;;
;; You might want append (when (next-method-p) (call-next-method)) to
;; pick up the Menu actions of superclasses.
;;

(defmulti menu-choices-for-presentation (fn [obj connection] (type obj)))

(commands/defslimefn menu-choices-for-presentation-id
  [connection id]
  (logging/trace "menu-choices-for-presentation-id %s" id)
  (let [id (clean-id id)
        [ob presentp] (lookup-presented-object connection id)]
    (if presentp
      (let [menu-and-actions (menu-choices-for-presentation ob connection)]
        (swap! connection
               assoc :presentation-active-menu {:id id :menu menu-and-actions})
        (when menu-and-actions (map first menu-and-actions)))
      'not-present)))

(defn swank-ioify
  [thing]
  (cond
   (keyword? thing) thing
   (and (symbol? thing)
        (not
         (some
          #(= % \/)
          (name thing)))) (symbol "swank-io-package" (name thing))
   (sequential? thing) (map swank-ioify thing)
   :else thing))

(commands/defslimefn execute-menu-choice-for-presentation-id
  [connection id count item]
  (let [id (clean-id id)
        ob (lookup-presented-object connection id)
        menu (:presentation-active-menu @connection)]
    (assert (= id (:id menu)))
    (let [action (second (nth (:menu menu) (dec count)))]
      (swank-ioify (action item ob id)))))


(defmethod menu-choices-for-presentation :default
  [object connection]
  (logging/trace "No menu choices for object")
  nil)

;; files
(defmethod menu-choices-for-presentation
  java.io.File
  [ob connection]
  (let [file-exists (.exists ob)
        pathname (.getPath ob)
        source-file (when (re-matches #".*\.clj" pathname) pathname)]
    (filter
     identity
     [(and file-exists
           ["Edit this file"
            (fn [choice object id]
              (emacs/ed-in-emacs connection pathname)
              nil)])
      (and file-exists
           ["Dired containing directory"
            (fn [choice object id]
              (emacs/ed-in-emacs connection pathname)
              nil)])
      (and source-file
           ["Edit lisp source file"
            (fn [choice object id]
              (emacs/ed-in-emacs connection pathname)
              nil)])
      (and source-file
           ["Load lisp source file"
            (fn [choice object id]
              (load (string/replace source-file #".clj$" ""))
              nil)])])))

;; (defmethod menu-choices-for-presentation
;;   clojure.lang.IFn
;;   [ob]
;;   [["Disassemble"
;;     (fn [choice object id]
;;       (disassemble object))]])

;; (defslimefn inspect-presentation (id reset-p)
;;   (let ((what (lookup-presented-object-or-lose id)))
;;     (when reset-p
;;       (reset-inspector))
;;     (inspect-object what)))


(defn initialize
  "Set up the connection to use slime presentations"
  [connection]
  (swap! connection assoc :send-repl-results-function #'present-repl-results)
  connection)
