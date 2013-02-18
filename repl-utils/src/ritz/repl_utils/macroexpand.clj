(ns ritz.repl-utils.macroexpand
  (:require
   [clojure.walk :as walk]))

(defmacro macroexpand-all [form]
  `(walk/macroexpand ~form))
