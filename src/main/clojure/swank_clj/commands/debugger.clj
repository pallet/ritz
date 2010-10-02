(ns swank-clj.commands.debugger
  "Debugger commands.  Everything that the proxy responds to"
  (:require
   [swank-clj.logging :as logging]
   [swank-clj.jpda :as jpda]
   [swank-clj.connection :as connection]
   [swank-clj.debug :as debug]
   [swank-clj.inspect :as inspect]
   [swank-clj.messages :as messages]
   [swank-clj.swank.core :as core]
   [clojure.java.io :as io]
   )
  (:use
   [swank-clj.commands :only [defslimefn]]))

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
     core/*current-connection* (messages/debug-return thread-id level))
    nil))

(defslimefn frame-catch-tags-for-emacs [n]
  nil)

(defslimefn frame-locals-for-emacs [n]
  (let [level-info (connection/sldb-level-info core/*current-connection*)]
    (messages/frame-locals
     (debug/frame-locals-with-string-values level-info n))))

(defslimefn frame-locals-and-catch-tags [n]
  (list (frame-locals-for-emacs n)
        (frame-catch-tags-for-emacs n)))

(defslimefn frame-source-location [n]
  (let [level-info (connection/sldb-level-info core/*current-connection*)]
    (debug/source-location-for-frame level-info n)))

(defslimefn inspect-frame-var [frame index]
  (let [inspector (connection/inspector core/*current-connection*)
        level-info (connection/sldb-level-info core/*current-connection*)
        object (debug/nth-frame-var level-info frame index)]
    (inspect/reset-inspector inspector)
    (messages/inspector
     (inspect/inspect-object inspector object))))
