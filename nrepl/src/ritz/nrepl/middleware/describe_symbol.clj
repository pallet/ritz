(ns ritz.nrepl.middleware.describe-symbol
  (:require
   [clojure.string :as string]
   [clojure.tools.nrepl.transport :as transport])
  (:use
   [clojure.tools.nrepl.middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.misc :only [response-for]]
   [ritz.nrepl.middleware :only [transform-value]]
   [ritz.repl-utils.doc :only [describe]]))

(defn describe-symbol-reply
  [{:keys [symbol ns transport] :as msg}]
  (if-let [v (ns-resolve (clojure.core/symbol ns) (clojure.core/symbol symbol))]
    (do (transport/send
         transport (response-for msg :value (transform-value (describe v))))
        (transport/send transport (response-for msg :status :done)))
    (transport/send transport (response-for msg :status :not-found))))

(defn wrap-describe-symbol
  "Middleware that looks up describe-symbol for a symbol."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "describe-symbol" op)
      (describe-symbol-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-describe-symbol
 {:handles
  {"describe-symbol"
   {:doc "Describes the specified symbol."
    :requires {"symbol" "The symbol to lookup"
               "ns" "The current namespace"}
    :returns {"status" "done"}}}})
