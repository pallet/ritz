(ns ritz.swank.rexec
  "Remote exec over JPDA using swank message maps"
  (:use
   [clojure.stacktrace :only [print-cause-trace]]
   [ritz.debugger.connection :only [vm-context]]
   [ritz.logging :only [trace]]
   [ritz.jpda.jdi :only [invoke-single-threaded]]
   [ritz.swank.exec :only [exec-using-classloader read-msg-using-classloader]])
  (:require
   [ritz.jpda.jdi-clj :as jdi-clj]))


(defn rexec [connection message]
  (let [context (vm-context connection)
        fwd-connection
        (-> connection
            (select-keys [:request-id :request-thread :buffer-ns-name])
            (assoc :request-ns (ns-name (:request-ns connection))))]
    (try
      (trace "rexec %s %s" message fwd-connection)
      (jdi-clj/control-eval-to-value context `(require 'ritz.swank.exec))
      (jdi-clj/control-eval-to-value
       context `(exec-using-classloader '~fwd-connection '~message))
      (trace "rexec done")
      (catch com.sun.jdi.InvocationException e
        (print-cause-trace e)
        (println (.. e exception )))
      (catch Exception e
        (let [root-ex (#'clojure.main/root-cause e)]
          (when-not (instance? ThreadDeath root-ex)
            (clojure.main/repl-caught e)))))))

(defn rread-msg
  [context thread]
  {:pre [context thread]}
  (try
    (trace "rread-msg")
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
