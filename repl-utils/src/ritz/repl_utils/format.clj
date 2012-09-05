(ns ritz.repl-utils.format
  "Formatting utils"
  (:require
   [clojure.pprint :as pprint]))

(defn pprint-code
  ([code suppress-namespaces]
     (binding [pprint/*print-suppress-namespaces* suppress-namespaces]
       (pprint/with-pprint-dispatch pprint/code-dispatch
         (pprint/write code :pretty true :stream nil))))
  ([code]
     (pprint-code code true)))
