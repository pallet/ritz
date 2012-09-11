(ns ritz.nrepl.rexec
  "Remote exec over JPDA using nrepl message maps"
  (:use
   [clojure.tools.nrepl.misc :only [response-for]]
   [ritz.logging :only [trace]]
   [ritz.nrepl.exec :only [exec-using-classloader]])
  (:require
   [clojure.tools.nrepl.transport :as transport]
   [ritz.jpda.jdi-clj :as jdi-clj]))


(defn rexec [context {:keys [transport] :as msg}]
  (try
    (trace "rexec %s" msg)
    (jdi-clj/control-eval
     context
     `(require 'ritz.nrepl.exec))
    (jdi-clj/control-eval
     context
     `(exec-using-classloader ~(dissoc msg :transport :ritz.nrepl/connection)))
    (catch Exception e
      (let [root-ex (#'clojure.main/root-cause e)]
        (when-not (instance? ThreadDeath root-ex)
          (transport/send
           transport
           (response-for
            msg {:status :eval-error
                 :ex (-> e class str)
                 :root-ex (-> root-ex class str)}))
          (clojure.main/repl-caught e))))))
