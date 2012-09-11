(ns ritz.repl-utils.core.defonce
  "Ritz reloading of namespaces checks :defonce metadata. This ensures
the metadata gets set by clojure.core/defonce."
  (:refer-clojure :exclude [defonce defmulti])
  (:use
   [ritz.repl-utils.utils :only [alias-var alias-var-once]]))

(alias-var-once 'defonce #'clojure.core/defonce)
(alias-var-once 'defmulti #'clojure.core/defmulti)

(in-ns 'clojure.core)

;; defonce that adds :defonce metadata
(let [m (meta #'ritz.repl-utils.core.defonce/defonce)]
  (defmacro ^{:ritz/redefed true}
    defonce [name & args]
    `(do
       (ritz.repl-utils.core.defonce/defonce ~name ~@args)
       (.alterMeta (var ~name) #(assoc % :defonce true) nil)
       (var ~name)))
  (.alterMeta
   #'clojure.core/defonce
   #(merge % (select-keys m [:doc :arglists :file :line])) nil))

;; defmulti that adds :defonce metadata
(let [m (meta #'ritz.repl-utils.core.defonce/defmulti)]
  (defmacro ^{:ritz/redefed true}
    defmulti [name & args]
    `(do
       (ritz.repl-utils.core.defonce/defmulti ~name ~@args)
       (.alterMeta (var ~name) #(assoc % :defonce true) nil)
       (var ~name)))
  (.alterMeta
   #'clojure.core/defmulti
   #(merge % (select-keys m [:doc :arglists :file :line])) nil))
