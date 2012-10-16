(ns ritz.nrepl.middleware.codeq-test
  (:use
   clojure.test
   ritz.nrepl.middleware.codeq
   [clojure.stacktrace :only [print-cause-trace]]
   [clojure.tools.nrepl.server :only [handle* unknown-op]]
   [clojure.tools.nrepl.transport :only [recv]])
  (:import
   clojure.tools.nrepl.transport.QueueTransport
   java.util.concurrent.LinkedBlockingQueue))

(defn queue-transport
  "Return in and out queues, and atransport that uses them."
  []
  (let [in (LinkedBlockingQueue.)
        out (LinkedBlockingQueue.)]
    [in out (QueueTransport. in out)]))

(defn stoppable-handle
  "Returns a vector with a function and an atom. The function handles messages
on the given transport with the handler, until the atom is set to false."
  [handler transport]
  (let [a (atom true)]
    [(fn []
       (when @a
         (let [msg (recv transport)]
           (handle* msg handler transport)
           (recur))))
     a]))

(defn datomic-url []
  (or (System/getProperty "ritz.datomic.url")
      "datomic:free://localhost:4334/git"))

(deftest codeq-def-test
  (let [[in out transport] (queue-transport)
        handler (-> unknown-op wrap-codeq-def)
        [handle-f a] (stoppable-handle handler transport)
        handle-loop (future
                      (try
                        (handle-f)
                        (catch Exception e (print-cause-trace e))))]
    ;; set the classpath
    (.offer in {:op "codeq-def" :symbol "ritz.logging/trace"
                :datomic-url (datomic-url) :id 0})
    (is (= {:value
            [["(defmacro trace\n  [fmt-str & args]\n  `(log :trace ~fmt-str ~@args))" "Sat Jul 02 22:08:35 EDT 2011"]]
            :id 0}
           (.take out)))

    ;; shut down the handle loop
    (reset! a false)
    (.offer in {:op "something to stop the handle loop"})))
