(ns ritz.commands.emacs
  "Emacs interaction"
  (:require
   [clojure.java.io :as io]
   [ritz.connection :as connection]
   [ritz.logging :as logging]
   [ritz.swank.messages :as messages]
   [ritz.swank.utils :as utils]
   [ritz.swank.commands :as commands])
  (:import
   java.util.WeakHashMap))

(commands/defslimefn ed-in-emacs
  "Edit WHAT in Emacs.

WHAT can be:
  A pathname or a string,
  A list (PATHNAME-OR-STRING &key LINE COLUMN POSITION),
  A function name (symbol or cons),
  NIL. "
  ([connection what]
     (letfn [(file? [what] (or (string? what) (instance? java.io.File what)))]
       (let [target
             (cond
              (nil? what)  nil
              (file? what) `(:filename ~(.getPath (io/file what)))
              (let [[w] what]
                (file? w))
              `(:filename ~[(.getPath (io/file (first what))) (second what)])
              (or (symbol? what) (sequential? what))
              `(:function-name ,(prn-str what)))]
         (cond
          connection (connection/send-to-emacs connection `(:ed ~target))
          :else (logging/trace "No connection for ed-in-emacs")))))
  ([connection] (ed-in-emacs connection nil)))

(defn eval-in-emacs
  "eval form in Emacs."
  [connection form & {:keys [nowait] :or {nowait true}}]
  (cond
   nowait (connection/send-to-emacs connection (messages/eval-no-wait form))
   true (do
          (flush)
          (connection/send-to-emacs connection (messages/eval-no-wait form)))
   ;; (let ((tag (make-tag)))
   ;;   (send-to-emacs `(:eval ,(current-thread-id) ,tag
   ;;                          ,(process-form-for-emacs form)))
   ;;   (let ((value (caddr (wait-for-event `(:emacs-return ,tag result)))))
   ;;     (destructure-case value
   ;;                       ((:ok value) value)
   ;;                       ((:abort) (abort)))))
   ))
