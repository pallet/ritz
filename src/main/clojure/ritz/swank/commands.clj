(ns ritz.swank.commands
  "Namespace for swank functions. Slime calls functions in the swank package,
   so we need to be able o lookup symbols with a swank/ prefix. This is solved
   by interning every slime function into this namespace, and providing a
   namespace alias for itself.  The namespace alias is then used when looking up
   symbols via ns-resolve."
  (:refer-clojure :exclude [load-file])
  (:require
   [ritz.logging :as logging]
   ;; aliases for ns lookups in namespaces expected by slime
   [ritz.swank.commands :as swank]
   [ritz.swank.cl :as cl]))

;;; Allow definition of swank functions from various namespaces
(defmacro ^{:indent 'defun} defslimefn
  "Defines a swank command. Metadata is added to allow recognition of swank
   functions."
  [fname & body]
  (let [fname (vary-meta fname assoc ::swank-fn true)]
    `(do
       (defn ~fname ~@body)
       (intern '~'ritz.swank.commands '~fname ~fname))))

(defn slime-fn
  "Resolve a slime function."
  [sym]
  (logging/trace "slime-fn trying to resolve %s" (pr-str sym))
  (ns-resolve (the-ns 'ritz.swank.commands) sym))
