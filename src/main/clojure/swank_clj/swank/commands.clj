(ns swank-clj.swank.commands
  "Namespace for swank functions. Slime calls functions in the swank package,
   so we need to be able o lookup symbols with a swank/ prefix. This is solved
   by interning every slime function into this namespace, and providing a
   namespace alias for itself.  The namespace alias is then used when looking up
   symbols via ns-resolve."
  (:refer-clojure :exclude [load-file])
  (:require
   [swank-clj.logging :as logging]
   ;; aliases for ns lookups in namespaces expected by slime
   [swank-clj.swank.commands :as swank]
   [swank-clj.swank.cl :as cl]))

;;; Allow definition of swank functions from various namespaces
(defmacro ^{:indent 'defun} defslimefn
  "Defines a swank command. Metadata is added to allow recognition of swank
   functions."
  [fname & body]
  (let [fname (vary-meta fname assoc ::swank-fn true)]
    `(do
       (defn ~fname ~@body)
       (intern '~'swank-clj.swank.commands '~fname ~fname))))

(defn slime-fn
  "Resolve a slime function."
  [sym]
  (ns-resolve (the-ns 'swank-clj.swank.commands) sym))
