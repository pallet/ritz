(ns ritz.nrepl.rexec
  "Remote exec over JPDA using nrepl message maps"
  (:use
   [ritz.logging :only [trace]]
   [ritz.nrepl.exec :only [exec read-msg]])
  (:require
   [ritz.jpda.jdi-clj :as jdi-clj]))


(defn rexec [context msg]
  (trace "rexec %s" msg)
  (jdi-clj/control-eval
   context
   `(require 'ritz.nrepl.exec))
  (jdi-clj/control-eval
   context
   `(exec ~(dissoc msg :transport :ritz.nrepl/connection))))
