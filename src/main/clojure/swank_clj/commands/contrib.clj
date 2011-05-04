(ns swank-clj.commands.contrib
  (:use
   [swank-clj.logging :as logging]
   [swank-clj.repl-utils.helpers :as helpers]
   [swank-clj.commands :only [defslimefn]]))

(defslimefn swank-require [connection keys]
  (binding [*ns* (the-ns 'swank-clj.commands.contrib)]
    (doseq [k (if (seq? keys) keys (list keys))]
      (try
        (require (symbol (str "swank-clj.commands.contrib." (name k))))
        (catch java.io.FileNotFoundException e
          (logging/trace
           "Exception: %s\n%s" e (helpers/stack-trace-string e)))
        (catch Exception e
          (logging/trace
           "Exception: %s\n%s" e (helpers/stack-trace-string e)))))))
