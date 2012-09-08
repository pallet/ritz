(ns ritz.repl-utils.core.defprotocol
  "Provides a version of defprotocol that only redefines it's interface
when the signatures change"
  (:use
   [ritz.repl-utils.clojure :only [feature-cond clojure-1-3-or-greater]]))

(feature-cond
 clojure-1-3-or-greater
 (do
   (defn emit-protocol [name opts+sigs]
     (let [iname (symbol (str (munge (namespace-munge *ns*)) "." (munge name)))
           [opts sigs]
           (loop [opts {:on (list 'quote iname) :on-interface iname}
                  sigs opts+sigs]
             (condp #(%1 %2) (first sigs)
               string? (recur (assoc opts :doc (first sigs)) (next sigs))
               keyword? (recur
                         (assoc opts (first sigs) (second sigs)) (nnext sigs))
               [opts sigs]))
           sigs (when sigs
                  (#'clojure.core/reduce1
                   (fn [m s]
                     (let [name-meta (meta (first s))
                           mname (with-meta (first s) nil)
                           [arglists doc]
                           (loop [as [] rs (rest s)]
                             (if (vector? (first rs))
                               (recur (conj as (first rs)) (next rs))
                               [(seq as) (first rs)]))]
                       (when (some #{0} (map count arglists))
                         (throw
                          (IllegalArgumentException.
                           (str "Protocol fn: " mname
                                " must take at least one arg"))))
                       (assoc m
                         (keyword mname)
                         (merge
                          name-meta
                          {:name (vary-meta mname assoc
                                            :doc doc
                                            :arglists arglists)
                           :arglists arglists
                           :doc doc}))))
                   {} sigs))
           meths (mapcat
                  (fn [sig]
                    (let [m (munge (:name sig))]
                      (map
                       #(vector m (vec (repeat (dec (count %))'Object)) 'Object)
                       (:arglists sig))))
                  (vals sigs))]
       `(do
          (defonce ~name {})
          ~(let [v (ns-resolve *ns* name)]
             (when (or (nil? v) (not= (:sigs @v) sigs))
               `(gen-interface :name ~iname :methods ~meths)))
          (alter-meta! (var ~name) assoc :doc ~(:doc opts))
          ~(when sigs
             `(#'clojure.core/assert-same-protocol
               (var ~name) '~(map :name (vals sigs))))
          (alter-var-root
           (var ~name) merge
           (assoc ~opts
             :sigs '~sigs
             :var (var ~name)
             :method-map
             ~(and (:on opts)
                   (apply hash-map
                          (mapcat
                           (fn [s]
                             [(keyword (:name s))
                              (keyword (or (:on s) (:name s)))])
                           (vals sigs))))
             :method-builders
             ~(apply hash-map
                     (mapcat
                      (fn [s]
                        [`(intern
                           *ns*
                           (with-meta '~(:name s)
                             (merge '~s {:protocol (var ~name)})))
                         (#'clojure.core/emit-method-builder
                          (:on-interface opts)
                          (:name s) (:on s) (:arglists s))])
                      (vals sigs)))))
          (-reset-methods ~name)
          '~name)))

   (when (not= emit-protocol @#'clojure.core/emit-protocol)
     (alter-var-root #'clojure.core/emit-protocol (constantly emit-protocol)))))
