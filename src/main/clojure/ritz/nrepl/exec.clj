(ns ritz.nrepl.exec
  "Execute commands using nrepl"
  (:use
   [clojure.tools.nrepl.server :only [default-handler handle*]]
   [ritz.nrepl.transport :only [make-transport read-sent]]))

(defonce transport (make-transport {}))
(defonce handler (default-handler))

(defn exec
  [msg]
  (handle* msg handler transport))

(defn read-msg
  []
  (read-sent transport))
