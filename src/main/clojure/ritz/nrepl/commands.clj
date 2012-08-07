(ns ritz.nrepl.commands
  (:use
   [ritz.logging :only [set-level trace]])
  (:require
   [ritz.jpda.debug :as debug]))

(defn threads
  "Return a sequence containing a thread reference for each remote thread."
  [context]
  (vec (debug/thread-list (:vm-context context))))
