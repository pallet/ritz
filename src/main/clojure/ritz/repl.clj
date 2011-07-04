(ns ritz.repl
  "REPL server. No JPDA functionality."
  (:require
   [ritz.rpc-server :as rpc-server]
   [ritz.logging :as logging]
   [ritz.hooks :as hooks]
   [ritz.jpda.debug :as debug]
   [ritz.swank.core :as core]
   ritz.commands.basic
   ritz.commands.inspector
   ritz.commands.completion
   ritz.commands.contrib))

(defn serve-connection
  "Serve connection for proxy rpc functions"
  []
  (logging/trace "repl/serve-connection")
  (.setName (Thread/currentThread) "REPL")
  (fn repl-connection-handler
    [socket options]
    (logging/trace "repl/repl-connection-hanler")
    (let [[connection future] (rpc-server/serve-connection socket options)]
      (hooks/run core/new-connection-hook connection)
      (logging/trace "repl/repl-connection-handler new-connection-hook ran"))
    (logging/trace "repl/repl-connection-handler running")))
