(ns swank-clj.repl-utils.format
  "Formatting utils"
  (:require
   [clojure.pprint :as pprint]))

(defn pprint-code
  [code]
  (binding [pprint/*print-suppress-namespaces* true]
     (pprint/with-pprint-dispatch pprint/code-dispatch
       (pprint/write code :pretty true :stream nil))))
