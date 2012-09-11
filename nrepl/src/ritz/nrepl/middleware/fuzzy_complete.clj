(ns ritz.nrepl.middleware.fuzzy-complete
  (:require
   [clojure.string :as string]
   [clojure.tools.nrepl.transport :as transport])
  (:use
   [clojure.tools.nrepl.middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.misc :only [response-for]]
   [ritz.nrepl.middleware :only [read-when transform-value]]
   [ritz.repl-utils.fuzzy-completion :only [fuzzy-generate-matchings]]
   [ritz.repl-utils.timeout :only [with-timeout]])
  (:import clojure.tools.nrepl.transport.Transport))

(defn fuzzy-complete-reply
  [{:keys [symbol ns timeout-ms limit prefer-ns transport] :as msg}]
  (let [limit (read-when limit)
        [matchings] (with-timeout [timed-out? (or timeout-ms 2000)]
                      (fuzzy-generate-matchings
                       symbol
                       (when ns (clojure.core/symbol ns))
                       timed-out?))
        results (map (comp name :symbol)
                     (if limit (take limit matchings) matchings))]
    (transport/send
     transport (response-for msg :value (transform-value [results symbol])))
    (transport/send transport (response-for msg :status :done))))

(defn wrap-fuzzy-complete
  "Middleware that looks up possible functions for the given (partial) symbol."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "complete" op)
      (fuzzy-complete-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-fuzzy-complete
 {:handles
  {"complete"
   {:doc "Return a list of symbols matching the specified (partial) symbol."
    :requires {"symbol" "The symbol to lookup"
               "ns" "The current namespace"}
    :optional {"limit" "Limit the number of results to the given value"
               "timeout-ms" "Timeout the operation in the given time in ms"}
    :returns {"status" "done"}}}})
