(ns ritz.nrepl.middleware.doc
  (:require
   [clojure.string :as string]
   [clojure.tools.nrepl.transport :as transport])
  (:use
   [clojure.tools.nrepl.middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.misc :only [response-for]]))

(defn doc-reply
  [{:keys [symbol ns transport] :as msg}]
  (if-let [v (ns-resolve (clojure.core/symbol ns) (clojure.core/symbol symbol))]
    (do (transport/send transport (response-for msg :value (-> v meta :doc)))
        (transport/send transport (response-for msg :status :done)))
    (transport/send transport (response-for msg :status :not-found))))


(defn wrap-doc
  "Middleware that looks up doc for a symbol."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "doc" op)
      (doc-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-doc
 {:handles
  {"doc"
   {:doc "Return doc for the specified."
    :requires {"symbol" "The symbol to lookup"}
    :returns {"status" "done"}}}})
