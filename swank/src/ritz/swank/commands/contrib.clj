(ns ritz.swank.commands.contrib
  (:use
   [ritz.logging :as logging]
   [ritz.repl-utils.helpers :as helpers]
   [ritz.swank.commands :only [defslimefn]]))

(defonce loaded-contribs (atom #{}))

(defslimefn swank-require [connection keys]
  (binding [*ns* (the-ns 'ritz.swank.commands.contrib)]
    (doseq [k (if (sequential? keys) keys (list keys))]
      (trace "contrib load %s" k)
      (try
        (let [ns-sym (symbol (str "ritz.swank.commands.contrib." (name k)))]
          (require ns-sym)
          (when-let [contrib-init (ns-resolve ns-sym 'initialize)]
            (contrib-init connection))
          (swap! loaded-contribs conj k))
        (catch java.io.FileNotFoundException e
          (logging/trace
           "Exception: %s\n%s" e (helpers/stack-trace-string e)))
        (catch Exception e
          (logging/trace
           "Exception: %s\n%s" e (helpers/stack-trace-string e)))))))
