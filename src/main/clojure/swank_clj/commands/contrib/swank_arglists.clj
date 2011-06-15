(ns swank-clj.commands.contrib.swank-arglists
  (:use
   [swank-clj.swank.commands :only [defslimefn]])
  (:require
   [swank-clj.commands.basic :as basic]
   [swank-clj.connection :as connection]
   [swank-clj.logging :as logging]
   [swank-clj.swank.utils :as utils]
   [swank-clj.swank.commands :as commands]))


(defslimefn arglist-for-echo-area [connection raw-specs & options]
  (let [{:keys [arg-indices
                print-right-margin
                print-lines]} (apply hash-map options)]
    ;; Yeah, I'm lazy -- I'll flesh this out later
    (if (and raw-specs
             (seq? raw-specs)
             (seq? (first raw-specs)))
      (basic/operator-arglist
       connection (ffirst raw-specs)
       (connection/request-ns connection/request-ns))
      nil)))

(defslimefn variable-desc-for-echo-area [connection variable-name]
  (or
   (try
     (when-let [sym (read-string variable-name)]
       (when-let [var (resolve sym)]
         (when (.isBound #^clojure.lang.Var var)
           (str variable-name " => " (var-get var)))))
     (catch Exception e nil))
   ""))


(defn autodoc* [connection raw-specs & options]
  (logging/trace "autodoc*")
  (let [{:keys [print-right-margin
                print-lines]} (if (first options)
                                (apply hash-map options)
                                {})]
    (if (and raw-specs (seq? raw-specs))
      (let [expr (some
                  #(and (seq? %) (some #{:swank-clj/cursor-marker} %) %)
                  (tree-seq seq? seq raw-specs))]
        (logging/trace "autodoc* expr %s" expr)
        (if (and (seq? expr) (not (= (first expr) "")))
          (or
           (basic/operator-arglist
            connection
            (first expr)
            (connection/request-ns connection))
           `:not-available)
          `:not-available))
      `:not-available)))

(defslimefn autodoc
  "Return a string representing the arglist for the deepest subform in
RAW-FORM that does have an arglist.
TODO: The highlighted parameter is wrapped in ===> X <===."
  [connection raw-specs & options]
  (logging/trace "autodoc")
  (apply autodoc* connection raw-specs options))
