(ns ritz.nrepl.rexec
  "Remote exec over JPDA using nrepl message maps"
  (:use
   [clojure.stacktrace :only [print-cause-trace]]
   [clojure.tools.nrepl.misc :only [response-for]]
   [ritz.logging :only [trace]]
   [ritz.jpda.jdi :only [invoke-single-threaded]]
   [ritz.nrepl.exec :only [exec-using-classloader read-msg-using-classloader]])
  (:require
   [clojure.tools.nrepl.transport :as transport]
   [ritz.jpda.jdi-clj :as jdi-clj]))


(defn rexec [context {:keys [transport] :as msg}]
  (try
    (trace "rexec %s" (select-keys msg [:op :id]))
    (jdi-clj/control-eval-to-value context `(require 'ritz.nrepl.exec))
    (jdi-clj/control-eval-to-value
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


(defn rread-msg
  [context thread]
  {:pre [context thread]}
  (try
    (trace "rread-msg")
    (jdi-clj/eval-to-value
     context thread invoke-single-threaded `(require 'ritz.nrepl.exec))
    (let [result (jdi-clj/eval
                  context thread invoke-single-threaded
                  `(read-msg-using-classloader))]
      (trace "rread-msg read %s" result)
      result)
    (catch com.sun.jdi.InvocationException e
      (trace "rread-msg failed %s" (with-out-str (print-cause-trace e)))
      (print-cause-trace e)
      (println (.. e exception )))
    (catch Exception e
      (trace "rread-msg failed %s" (with-out-str (print-cause-trace e)))
      (let [root-ex (#'clojure.main/root-cause e)]
        (when-not (instance? ThreadDeath root-ex)
          (clojure.main/repl-caught e))))))
