(ns ritz.commands.inspector
  (:require
   [ritz.inspect :as inspect]
   [ritz.swank.messages :as messages]
   [ritz.connection :as connection])
  (:use
   [ritz.swank.commands :only [defslimefn]]))


(defslimefn init-inspector [connection string]
  (let [inspector (connection/inspector connection)
        vm-context (connection/vm-context connection)]
    (inspect/reset-inspector inspector)
    (inspect/inspect-object inspector (eval (read-string string)))
    (messages/inspector (inspect/display-values vm-context inspector))))

(defslimefn inspect-nth-part [connection index]
  (let [inspector (connection/inspector connection)
        vm-context (connection/vm-context connection)]
    (inspect/inspect-object
     inspector (inspect/nth-part vm-context inspector index))
    (messages/inspector (inspect/display-values vm-context inspector))))

(defslimefn inspector-range [connection from to]
  (let [inspector (connection/inspector connection)
        vm-context (connection/vm-context connection)]
    (inspect/content-range vm-context inspector from to)))

(defslimefn inspector-call-nth-action [connection index & args]
  (let [inspector (connection/inspector connection)
        vm-context (connection/vm-context connection)]
    (when (inspect/call-nth-action vm-context index args)
      (messages/inspector (inspect/display-values vm-context inspector)))))

(defslimefn inspector-pop [connection]
  (let [inspector (inspect/pop-inspectee (connection/inspector connection))]
    (when (inspect/inspecting? inspector)
      (let [[level-info level] (connection/current-sldb-level-info connection)
            vm-context (connection/vm-context connection)
            thread (or (:thread level-info) (:control-thread vm-context))
            vm-context (assoc vm-context :current-thread thread)]
        (messages/inspector (inspect/display-values vm-context inspector))))))

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
