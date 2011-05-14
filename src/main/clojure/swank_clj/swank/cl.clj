(ns swank-clj.swank.cl
  "cl commands used by slime whan calling swank"
  (:require
   [swank-clj.logging :as logging]))

(defn ^{:swank-clj.swank.commands/swank-fn true} mapc
  "Map for side effects only."
  [connection f & args]
  (logging/trace "cl/mapc %s %s" f (pr-str args))
  (dorun
   (apply map
          ((resolve 'swank-clj.swank.commands/slime-fn) f)
          (repeat connection)
          args))
  (first args))
