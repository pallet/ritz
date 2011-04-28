(ns swank-clj.commands.contrib
  (:use
   [swank-clj.commands :only [defslimefn]]))

(defslimefn swank-require [connection keys]
  (binding [*ns* (find-ns 'swank.commands.contrib)]
    (doseq [k (if (seq? keys) keys (list keys))]
      (try
       (require (symbol (str "swank-clj.commands.contrib." (name k))))
       (catch java.io.FileNotFoundException _ nil)
       (catch Exception _ nil)))))
