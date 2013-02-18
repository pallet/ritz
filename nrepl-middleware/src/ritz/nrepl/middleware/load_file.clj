(ns ritz.nrepl.middleware.load-file
  "A load file operation that adds options to turn off locals
clearing, and to clean the namespace before loading."
  (:require
   [clojure.tools.nrepl.middleware.interruptible-eval :as eval])
  (:use
   [clojure.tools.nrepl.middleware :as middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.middleware.load-file :as load-file
    :only [load-file-code]]
   [ritz.repl-utils.compile :only [with-compiler-options]]
   [ritz.repl-utils.namespaces :only [with-var-clearing]]))

(def options-fmt
  "(ritz.repl-utils.compile/with-compiler-options {:debug %s} %s)")
(def clear-fmt
  "(ritz.repl-utils.namespaces/with-var-clearing %s)")

(defn load-file-reply
  [{:keys [op file file-name file-path debug clear] :as msg}]
  (let [code ((if (thread-bound? #'load-file-code)
                load-file-code
                #'load-file/load-large-file-code)
              file file-path file-name)
        code (format options-fmt (pr-str debug) code)
        code (if clear (format clear-fmt code) code)]
    (assoc msg :op "eval" :code code)))

(defn wrap-load-file
  "Middleware that evaluates a file's contents, as per load-file,
   but with all data supplied in the sent message (i.e. safe for use
   with remote REPL environments).

   This middleware depends on the availability of an :op \"eval\"
   middleware below it (such as interruptable-eval)."
  [h]
  (fn [{:keys [op file file-name file-path] :as msg}]
    (if (not= op "load-file")
      (h msg)
      (h (load-file-reply msg)))))

(set-descriptor!
 #'wrap-load-file
 {:requires #{}
  :expects #{"eval"}
  :handles
  {"load-file"
   (update-in
    (-> (meta #'load-file/wrap-load-file)
        ::middleware/descriptor :handles (get "load-file"))
    [:optional] merge
    {"debug" "Flag to disable locals clearing."
     "clear" "Flag to cleanup namespace for vars no longer defined."})}})
