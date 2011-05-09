(ns swank-clj.repl
  "REPL server. No JPDA functionality."
  (:require
   [swank-clj.rpc-server :as rpc-server]
   [swank-clj.logging :as logging]
   [swank-clj.jpda.debug :as debug]
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
    (rpc-server/serve-connection socket options)
    (logging/trace "repl/repl-connection-handler running")))
