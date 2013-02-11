(ns ritz.debugger.breakpoint
  "Setting of breakpoints"
  (:use
   [ritz.debugger.connection
    :only [debug-context debug-assoc! debug-update-in!]]))

(defn breakpoints
  [connection]
  (:breakpoints (debug-context connection)))

(defn breakpoints-set!
  [connection breakpoints]
  (debug-assoc! connection :breakpoints breakpoints))

(defn breakpoints-add!
  [connection breakpoints]
  (debug-update-in! connection [:breakpoints] concat breakpoints))

(defn breakpoint
  [connection breakpoint-id]
  (nth (breakpoints connection) breakpoint-id nil))
