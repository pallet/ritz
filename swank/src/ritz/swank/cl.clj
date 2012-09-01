(ns ritz.swank.cl
  "cl commands used by slime whan calling swank"
  (:require
   [ritz.logging :as logging]))

(defn ^{:ritz.swank.commands/swank-fn true} mapc
  "Map for side effects only."
  [connection f & args]
  (logging/trace "cl/mapc %s %s" f (pr-str args))
  (dorun
   (apply map
          ((resolve 'ritz.swank.commands/slime-fn) f)
          (repeat connection)
          args))
  (first args))

(defn ^{:ritz.swank.commands/swank-fn true} nth-value
  "Nth value"
  [connection i s]
  (logging/trace "cl/nth-value %s %s" i s)
  (nth s i))
