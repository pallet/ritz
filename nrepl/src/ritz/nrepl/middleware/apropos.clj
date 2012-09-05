(ns ritz.nrepl.middleware.apropos
  (:require
   [clojure.string :as string]
   [clojure.tools.nrepl.transport :as transport])
  (:use
   [clojure.tools.nrepl.middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.misc :only [response-for]]
   [ritz.nrepl.middleware :only [read-when transform-value]]
   [ritz.repl-utils.doc :only [apropos-doc]])
  (:import clojure.tools.nrepl.transport.Transport))

(defn apropos-reply
  [{:keys [symbol ns public-only? case-sensitive? prefer-ns transport] :as msg}]
  (let [results (apropos-doc
                 (when ns (clojure.core/symbol ns))
                 symbol
                 (read-when public-only?)
                 (read-when case-sensitive?)
                 (when prefer-ns (clojure.core/symbol prefer-ns)))]
    (transport/send
     transport (response-for msg :value (transform-value results)))
    (transport/send transport (response-for msg :status :done))))

(defn wrap-apropos
  "Middleware that looks up possible functions for the given (partial) symbol."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "apropos" op)
      (apropos-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-apropos
 {:handles
  {"apropos"
   {:doc "Return a list of functions matching the specified (partial) symbol."
    :requires {"symbol" "The symbol to lookup"
               "ns" "The current namespace"
               "public-only?" "Public vars only"
               "case-sensitive?" "Whether match should be case sensitive"
               "prefer-ns" "The namespace to prefer in the results"}
    :returns {"status" "done"}}}})
