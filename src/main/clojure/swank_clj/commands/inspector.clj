(ns swank-clj.commands.inspector
  (:require
   [swank-clj.swank.core :as core]
   [swank-clj.inspect :as inspect]
   [swank-clj.messages :as messages]
   [swank-clj.connection :as connection])
  (:use
   [swank-clj.commands :only [defslimefn]]))


(defslimefn init-inspector [connection string]
  (let [inspector (connection/inspector connection)]
    (inspect/reset-inspector inspector)
    (inspect/inspect-object inspector (eval (read-string string)))
    (messages/inspector (inspect/display-values inspector))))

(defslimefn inspect-nth-part [connection index]
  (let [inspector (connection/inspector connection)]
    (inspect/inspect-object inspector (inspect/nth-part inspector index))
    (messages/inspector (inspect/display-values inspector))))

(defslimefn inspector-range [connection from to]
  (let [inspector (connection/inspector connection)]
    (inspect/content-range inspector from to)))

(defslimefn inspector-call-nth-action [connection index & args]
  (let [inspector (connection/inspector connection)]
    (when (inspect/call-nth-action index args)
      (messages/inspector (inspect/display-values inspector)))))

(defslimefn inspector-pop [connection]
  (messages/inspector
   (inspect/pop-inspectee
    (connection/inspector connection))))

(defslimefn inspector-next [connection]
  (messages/inspector
   (inspect/next-inspectee
    (connection/inspector connection))))

(defslimefn inspector-reinspect [connection]
  (let [inspector
        (connection/inspector connection)]
    (messages/inspector (inspect/reinspect inspector))))

(defslimefn quit-inspector [connection]
  (inspect/reset-inspector (connection/inspector connection))
  nil)

(defslimefn describe-inspectee [connection]
  (inspect/describe-inspectee (connection/inspector connection)))
