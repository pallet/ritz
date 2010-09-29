(ns swank-clj.dispatch)

;; DISPATCH

(defonce rpc-fn-map {})

(defn register-dispatch
  ([name fn]
    (register-dispatch name fn #'rpc-fn-map))
  ([name fn fn-map]
    (alter-var-root fn-map assoc name fn)))

(defn dispatch-message
  ([message fn-map]
    (let [operation (first message)
          operands (rest message)
          fn (fn-map operation)]
        (assert fn)
        (apply fn operands)))
  ([message]
   (dispatch-message message rpc-fn-map)))
