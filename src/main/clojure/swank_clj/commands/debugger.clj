(ns swank-clj.commands.debugger
  "Debugger commands.  Everything that the proxy responds to"
  (:require
   [swank-clj.logging :as logging]
   [swank-clj.jpda :as jpda]
   [swank-clj.connection :as connection]
   [swank-clj.debug :as debug]
   [swank-clj.swank.core :as core]
   [clojure.java.io :as io]
   )
  (:use
   swank-clj.commands))

(defn invoke-restart [restart]
  ((nth restart 2)))

(defslimefn invoke-nth-restart-for-emacs [level n]
  (let [thread (connection/invoke-restart core/*current-connection* level n)]
    (connection/send-to-emacs
     core/*current-connection* `(:debug-return ~thread ~level nil))
    nil))
