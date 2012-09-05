(ns ritz.nrepl.middleware.javadoc
  (:require
   [clojure.string :as string]
   [clojure.tools.nrepl.transport :as transport])
  (:use
   [clojure.tools.nrepl.middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.misc :only [response-for]]
   [ritz.repl-utils.doc :only [javadoc-local-paths javadoc-url]]))

(defn javadoc-reply
  [{:keys [symbol ns local-paths transport] :as msg}]
  (when local-paths
    (javadoc-local-paths (string/split local-paths #" ")))
  (if-let [url (javadoc-url symbol ns)]
    (do (transport/send transport (response-for msg :value url))
        (transport/send transport (response-for msg :status :done)))
    (transport/send transport (response-for msg :status :not-found))))

(defn wrap-javadoc
  "Middleware that looks up javadoc for a symbol.
Accepts local-paths, a space separated list of local paths to local javadoc."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "javadoc" op)
      (javadoc-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-javadoc
 {:handles
  {"javadoc"
   {:doc "Return url of javadoc for specified symbol."
    :requires {"symbol" "The symbol to lookup"
               "ns" "The current namespace"}
    :returns {"status" "done"}}}})
