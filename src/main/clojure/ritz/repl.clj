(ns swank-clj.repl
  "REPL server. No JPDA functionality."
  (:require
   [swank-clj.rpc-server :as rpc-server]
   [swank-clj.logging :as logging]
   [swank-clj.hooks :as hooks]
   [swank-clj.jpda.debug :as debug]
   [swank-clj.swank.core :as core]
   swank-clj.commands.basic
   swank-clj.commands.inspector
   swank-clj.commands.completion
   swank-clj.commands.contrib))

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
