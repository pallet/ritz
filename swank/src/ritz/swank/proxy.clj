(ns ritz.swank.proxy
  "Swank proxy server.  Sits between slime and the target swank process"
  (:require
   [clojure.pprint :as pprint]
   [ritz.debugger.executor :as executor]
   [ritz.jpda.jdi :as jdi]
   [ritz.jpda.jdi-clj :as jdi-clj]
   [ritz.jpda.jdi-vm :as jdi-vm]
   [ritz.repl-utils.compile :as compile]
   [ritz.repl-utils.io :as io]
   [ritz.swank :as swank]
   [ritz.swank.commands :as commands]
   [ritz.swank.core :as core]
   [ritz.swank.debug :as debug]
   [ritz.swank.hooks :as hooks]
   [ritz.swank.messages :as messages]
   [ritz.swank.rpc-server :as rpc-server]
   ;; order is important for these to overide functions defined on local
   ;; vm, vs functions defined for jpda/jdi connection
   ritz.swank.commands.inspector
   ritz.swank.commands.debugger
   ritz.swank.commands.contrib.ritz)
  (:use
   [clojure.string :only [join]]
   [ritz.jpda.debug :only [launch-vm]]
   [ritz.jpda.jdi :only [invoke-single-threaded]]
   [ritz.jpda.jdi-clj :only [control-eval]]
   [ritz.jpda.jdi-vm
    :only [acquire-thread start-control-thread-body vm-resume]]
   [ritz.logging :only [trace]]
   [ritz.swank.connections :only [add-connection]]
   [ritz.swank.rexec :only [rexec rread-msg]]
   [ritz.swank.exec :only [block-reply-loop]])
  (:import
   com.sun.jdi.VirtualMachine))

(defn forward-commands
  "Alter eval-for-emacs to forward unrecognised commands to proxied connection."
  []
  (alter-var-root
   #'swank/forward-rpc
   (fn [_] debug/forward-rpc)))

(def swank-pipeline
  (debug/execute-if-quit-lisp
   (debug/execute-if-inspect-frame-var
    (debug/execute-inspect-if-inspector-active
     (debug/execute-unless-inspect
      (debug/execute-peek
       (debug/forward-command
        core/command-not-found)))))))

(defn start-remote-thread
  "Start a remote thread in the specified vm context, using thread-name to
generate a name for the thread."
  [vm thread-name]
  (let [msg-thread-name (name (gensym thread-name))]
    (acquire-thread
     vm msg-thread-name
     (fn [context thread-name]
       (control-eval
        context (start-control-thread-body msg-thread-name))))))

;;; # Serve a connection
(defn serve-connection
  "Serve connection for proxy rpc functions"
  []
  (trace "proxy/serve-connection")
  (.setName (Thread/currentThread) "REPL Proxy")
  (fn proxy-connection-handler
    [io-connection {:keys [log-level classpath extra-classpath vm-classpath]
                    :as options}]
    (let [options (->
                   options
                   (dissoc :announce)
                   (merge {:port 0 :join true :server-ns 'ritz.repl}))
          cp (join java.io.File/pathSeparatorChar vm-classpath)
          vm (launch-vm
              (merge {:classpath cp :main `@(promise)}
                     (select-keys options [:jvm-opts])))
          msg-thread (start-remote-thread vm "msg-pump")
          vm (assoc vm :msg-pump-thread msg-thread)
          options (assoc options :vm-context vm)]

      (debug/add-exception-event-request vm)
      (trace
       "proxy/connection-handler exeception events requested")

      (trace "proxy/connection-handler: resume vm")
      (vm-resume vm)

      (ritz.jpda.jdi-clj/control-eval   ; require ritz in the vm
       vm `(require 'ritz.swank.exec 'ritz.logging))
      (when log-level                   ; forward the log-level setting
        (ritz.jpda.jdi-clj/control-eval
         vm `(ritz.logging/set-level ~log-level)))
      (trace "proxy/connection-handler: set extra classpath")
      (ritz.jpda.jdi-clj/control-eval   ; set the extra classpath for ritz
       vm `(ritz.swank.exec/set-extra-classpath! ~(vec extra-classpath)))
      (trace "proxy/connection-handler: set classpath")
      (ritz.jpda.jdi-clj/control-eval   ; set the project classpath
       vm `(ritz.swank.exec/set-classpath! ~(vec classpath)))

      (trace "proxy/proxy-connection-handler")
      (forward-commands)

      (let [[connection future] (rpc-server/serve-connection
                                 io-connection
                                 (merge
                                  options
                                  {:swank-handler swank-pipeline}))]
        (add-connection connection)
        (trace "proxy/connection-handler running")
        (executor/execute-loop
         (partial debug/forward-reply connection) :name "Reply pump")
        (trace "proxy/connection-handler reply-pump running")
        (hooks/run core/new-connection-hook connection)
        (trace "proxy/connection-handler new-connection-hook ran")))))
