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

(defslimefn backtrace [start end]
  (let [level-info (connection/sldb-level-info core/*current-connection*)]
    (debug/build-backtrace level-info start end)))

(defslimefn invoke-nth-restart-for-emacs [level n]
  (let [level-info (connection/sldb-level-info core/*current-connection* level)
        thread-id (debug/invoke-restart level-info n)]
    (connection/sldb-drop-level core/*current-connection* n)
    (connection/send-to-emacs
     core/*current-connection* `(:debug-return ~thread-id ~level nil))
    nil))

(defslimefn frame-catch-tags-for-emacs [n]
  nil)

(defslimefn frame-locals-for-emacs [n]
  (let [level-info (connection/sldb-level-info core/*current-connection*)]
    (debug/frame-locals level-info n)))

(defslimefn frame-locals-and-catch-tags [n]
  (list (frame-locals-for-emacs n)
        (frame-catch-tags-for-emacs n)))
