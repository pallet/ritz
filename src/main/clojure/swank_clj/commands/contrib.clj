(ns swank-clj.commands.contrib
  (:use
   swank-clj.commands))

(defslimefn swank-require [keys]
  (binding [*ns* (find-ns 'swank.commands.contrib)]
    (doseq [k (if (seq? keys) keys (list keys))]
      (try
       (require (symbol (str "swank-clj.commands.contrib." (name k))))
       (catch java.io.FileNotFoundException fne nil)))))
