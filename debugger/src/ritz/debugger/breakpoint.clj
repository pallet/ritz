(ns ritz.debugger.breakpoint
  "Setting of breakpoints"
  (:use
   [ritz.debugger.connection
    :only [debug-context debug-assoc! debug-update-in!]]
   [ritz.jpda.jdi :only [breakpoint-data]]))

(defn breakpoints
  [connection]
  (:breakpoints (debug-context connection)))

(defn breakpoints-set!
  [connection breakpoints]
  (debug-assoc! connection :breakpoints breakpoints))

(defn breakpoints-add!
  [connection breakpoints]
  (debug-update-in! connection [:breakpoints] concat breakpoints))

(defn breakpoints-remove!
  "Remove the breakpoints that match the keys in breakpoint."
  [connection breakpoint]
  (debug-update-in!
   connection [:breakpoints]
   (fn [breakpoints]
     (remove #(= breakpoint (select-keys % (keys breakpoint))) breakpoints))))

(defn breakpoint
  [connection breakpoint-id]
  (nth (breakpoints connection) breakpoint-id nil))
