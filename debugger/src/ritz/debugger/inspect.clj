(ns ritz.debugger.inspect
  "Inspection of values")

(defmulti value-as-string
  "Return the string representation of a value"
  (fn [context value] (type value)))

(defmethod value-as-string :default
  [context value] (pr-str value))

(defn reset-inspector [connection]
  (reset! (:inspector connection) {}))
