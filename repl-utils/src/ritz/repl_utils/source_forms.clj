(ns ritz.repl-utils.source-forms
 "Tracking of source forms.

Provides for registering source forms for requests, using an opaque request id
as a key.

Provides for using source paths to link compiled code to source forms.")

;; TODO work out how to expire forms automatically

;;; # Source form tracking

;;; ## Source form state
(def ^{:private true
       :doc "A map from request id to source form"}
  source-form-map (atom {}))

;;; ## Source form update and query
;;;
;;; Provides for registering source forms for requests, using an
;;; opaque request id as a key.
(defn source-form!
  "Register the source `form` for the specified request `id`."
  [id form]
  (swap! source-form-map assoc (str id) form))

(defn source-form
  "Return the source form for the given request `id`."
  [id]
  (@source-form-map (str id)))

(defn source-forms
  "Returns the sequence of all source forms that have been stored."
  []
  (map second (sort-by key @source-form-map)))

(defn remove-source-form
  "Removes the source form for the specified request `id`."
  [id]
  (swap! source-form-map dissoc (str id)))

(defn clear-source-forms!
  "Remove all source forms."
  [id]
  (reset! source-form-map {}))

;;; ## Source forms as source paths for compiled code
;;;
;;; When compiling code, we can specify a source path for the code being
;;; compiled. Here we provide functions to create and look-up source
;;; paths based on our store of source forms.
(def ^{:private true} source-form-name "SOURCE_FORM_")
(def ^{:private true} source-form-name-count (count source-form-name))

(defn source-form-path
  "Return a source form path for the given request `id`."
  [id]
  (str source-form-name id))

(defn source-form-from-path
  "Given a source form path, return the corresponding source path."
  [^String path]
  (when (> (count path) source-form-name-count)
    (let [id (.substring path (count source-form-name))]
      {:source-form (source-form id)})))

(defn source-form-path?
  "Predicate to test if the given source path is for a source form."
  [^String source-path]
  (when source-path
    (.startsWith source-path source-form-name)))
