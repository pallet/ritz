(ns ritz.swank.repl
  "REPL server. No JPDA functionality."
  (:require
   [ritz.jpda.debug :as debug]
   [ritz.logging :as logging]
   [ritz.swank.core :as core]
   [ritz.swank.hooks :as hooks]
   [ritz.swank.rpc-server :as rpc-server]
   ritz.repl-utils.core.defprotocol
   ritz.swank.commands.basic
   ritz.swank.commands.inspector
   ritz.swank.commands.completion
   ritz.swank.commands.contrib))

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
