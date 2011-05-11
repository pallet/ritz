(ns swank-clj.proxy
  "Proxy server.  Sits between slime and the target swank process"
  (:require
   [clojure.pprint :as pprint]
   [swank-clj.executor :as executor]
   [swank-clj.swank :as swank]
   [swank-clj.swank.core :as core]
   [swank-clj.rpc-server :as rpc-server]
   [swank-clj.logging :as logging]
   [swank-clj.jpda.debug :as debug]
   [swank-clj.jpda.jdi :as jdi]
   [swank-clj.jpda.jdi-clj :as jdi-clj]
   [swank-clj.jpda.jdi-vm :as jdi-vm]
   swank-clj.commands.debugger
   swank-clj.commands.inspector
   swank-clj.commands.contrib.swank-clj))

(defn forward-commands
  "Alter eval-for-emacs to forward unrecognised commands to proxied connection."
  []
  ;; (alter-var-root
  ;;  #'swank/command-not-found
  ;;  (fn [_ x] x)
  ;;  debug/forward-command)
  (alter-var-root
   #'swank/forward-rpc
   (fn [_] debug/forward-rpc)))

(def swank-pipeline
  (debug/execute-if-inspect-frame-var
   (debug/execute-inspect-if-inspector-active
    (debug/execute-unless-inspect
     (debug/execute-peek
      (debug/forward-command
       core/command-not-found))))))

(defn serve-connection
  "Serve connection for proxy rpc functions"
  []
  (logging/trace "proxy/serve-connection")
  (.setName (Thread/currentThread) "REPL Proxy")
  (fn proxy-connection-handler
    [io-connection options]
    (logging/trace "proxy/proxy-connection-handler")
    (forward-commands)
    (let [options (->
                   options
                   (dissoc :announce)
                   (merge {:port 0 :join true :server-ns 'swank-clj.repl}))
          vm-context (debug/launch-vm-with-swank options)
          options (assoc options :vm-context (atom vm-context))]
      (logging/trace "proxy/connection-handler: runtime set")
      (logging/trace "proxy/connection-handler: thread-groups")
      (logging/trace (with-out-str
                       (pprint/pprint (jdi/thread-groups (:vm vm-context)))))
      (debug/add-exception-event-request vm-context)
      (logging/trace "proxy/connection-handler: resume")
      (.resume (:vm vm-context))
      (logging/trace "proxy/connection-handler: thread-groups")
      (logging/trace (with-out-str
                       (pprint/pprint (jdi/thread-groups (:vm vm-context)))))
      (Thread/sleep 3000) ;; let swank start up
      (let [port (debug/remote-swank-port vm-context)]
        (logging/trace "proxy/connection-handler proxied server on %s" port)
        (if (= port (:port options))
          (do
            (logging/trace "invalid port")
            ((:close-connection io-connection) io-connection))
          (let [proxied-connection (debug/create-connection
                                    (assoc options :port port))
                _ (logging/trace
                   "proxy/connection-handler connected to proxied")
                [connection future] (rpc-server/serve-connection
                                     io-connection
                                     (merge
                                      options
                                      {:proxy-to proxied-connection
                                       :swank-handler swank-pipeline}))]
            (logging/trace "proxy/connection-handler running")
            (executor/execute-loop
             (partial debug/forward-reply connection) :name "Reply pump")
            (logging/trace "proxy/connection-handler reply-pump running")
            (debug/add-connection connection proxied-connection)))))))
