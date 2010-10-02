(ns swank-clj.commands.inspector
  (:require
   [swank-clj.swank.core :as core]
   [swank-clj.inspect :as inspect]
   [swank-clj.messages :as messages]
   [swank-clj.connection :as connection])
  (:use
   [swank-clj.commands :only [defslimefn]]))


(defslimefn init-inspector [string]
  (let [inspector (connection/inspector core/*current-connection*)]
    (inspect/reset-inspector inspector)
    (messages/inspector
     (inspect/inspect-object inspector (eval (read-string string))))))

(defslimefn inspector-nth-part [index]
  (inspect/nth-part (connection/inspector core/*current-connection*) index))

(defslimefn inspect-nth-part [index]
  (let [inspector (connection/inspector core/*current-connection*)]
    (messages/inspector
     (inspect/inspect-object inspector (inspector-nth-part index)))))

(defslimefn inspector-range [from to]
  (let [inspector (connection/inspector core/*current-connection*)]
    (inspect/content-range inspector from to)))

(defslimefn inspector-call-nth-action [index & args]
  (when-let [inspector (inspect/call-nth-action
                        (connection/inspector core/*current-connection*)
                        index args)]
    (messages/inspector inspector)))

(defslimefn inspector-pop []
  (messages/inspector
   (inspect/pop-inspectee
    (connection/inspector core/*current-connection*))))

(defslimefn inspector-next []
  (messages/inspector
   (inspect/next-inspectee
    (connection/inspector core/*current-connection*))))

(defslimefn inspector-reinspect []
  (let [inspector
        (connection/inspector core/*current-connection*)]
    (messages/inspector (inspect/reinspect inspector))))

(defslimefn quit-inspector []
  (inspect/reset-inspector (connection/inspector core/*current-connection*))
  nil)

(defslimefn describe-inspectee []
  (inspect/describe-inspectee (connection/inspector core/*current-connection*)))
