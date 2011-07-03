(ns ritz.commands.contrib.swank-arglists
  (:use
   [ritz.swank.commands :only [defslimefn]])
  (:require
   [ritz.commands.basic :as basic]
   [ritz.connection :as connection]
   [ritz.logging :as logging]
   [ritz.repl-utils.arglist :as arglist]
   [ritz.swank.utils :as utils]
   [ritz.swank.commands :as commands]))


(defn position-in-arglist? [arglist pos]
  (or (some #(= '& %) arglist)
      (<= pos (count arglist))))

(defn highlight-position [arglist pos]
  (if (zero? pos)
    arglist
    ;; i.e. not rest args
    (let [num-normal-args (count (take-while #(not= % '&) arglist))]
      (if (<= pos num-normal-args)
        (into [] (concat (take (dec pos) arglist)
                         '(===>)
                         (list (nth arglist (dec pos)))
                         '(<===)
                         (drop pos arglist)))
        (let [rest-arg? (some #(= % '&) arglist)]
          (if rest-arg?
            (into [] (concat (take-while #(not= % '&) arglist)
                             '(===>)
                             '(&)
                             (list (last arglist))
                             '(<===)))))))))

(defn highlight-arglists [arglists pos]
  (loop [checked []
         current (first arglists)
         remaining (rest arglists)]
    (if (position-in-arglist? current pos)
      (apply list (concat checked
                          [(highlight-position current pos)]
                          remaining))
      (when (seq remaining)
        (recur (conj checked current)
               (first remaining)
               (rest remaining))))))

(defn message-format [cmd arglists pos]
  (str ;;(when cmd (str cmd ": "))
       (when arglists
         (if pos
           (highlight-arglists arglists pos)
           arglists))))

(defslimefn autodoc
  "Return a string representing the arglist for the deepest subform in
RAW-FORM that does have an arglist.
TODO: The highlighted parameter is wrapped in ===> X <===."
  [connection raw-specs & {:keys [arg-indices
                                  print-right-margin
                                  print-lines]
                           :as options}]
  (logging/trace "swank-arglists/autodoc")
  (if (and raw-specs (seq? raw-specs))
    (if-let [[arglists index] (arglist/arglist-at-terminal
                               raw-specs :ritz/cursor-marker
                               (connection/request-ns connection))]
      (message-format raw-specs arglists index)
      `:not-available)
    `:not-available))
