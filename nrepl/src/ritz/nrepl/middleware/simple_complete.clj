(ns ritz.nrepl.middleware.simple-complete
  (:require
   [clojure.string :as string]
   [clojure.tools.nrepl.transport :as transport])
  (:use
   [clojure.tools.nrepl.middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.misc :only [response-for]]
   [ritz.nrepl.middleware :only [read-when transform-value]]
   [ritz.repl-utils.completion :only [simple-completion]])
  (:import clojure.tools.nrepl.transport.Transport))

(defn simple-complete-reply
  [{:keys [symbol ns public-only? case-sensitive? prefer-ns transport] :as msg}]
  (let [results (simple-completion
                 symbol
                 (when ns (clojure.core/symbol ns)))]
    (transport/send
     transport (response-for msg :value (transform-value results)))
    (transport/send transport (response-for msg :status :done))))

(defn wrap-simple-complete
  "Middleware that looks up possible functions for the given (partial) symbol."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "complete" op)
      (simple-complete-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-simple-complete
 {:handles
  {"complete"
   {:doc "Return a list of symbols matching the specified (partial) symbol."
    :requires {"symbol" "The symbol to lookup"
               "ns" "The current namespace"}
    :returns {"status" "done"}}}})
