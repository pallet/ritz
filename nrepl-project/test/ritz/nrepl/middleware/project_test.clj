(ns ritz.nrepl.middleware.project-test
  (:use
   clojure.test
   ritz.nrepl.middleware.project
   [cemerick.pomegranate.aether :only [dependency-files resolve-dependencies]]
   [clojure.stacktrace :only [print-cause-trace]]
   [clojure.tools.nrepl.server :only [handle* unknown-op]]
   [clojure.tools.nrepl.transport :only [piped-transports recv]]
   [leiningen.core.classpath :only [get-classpath]]
   [ritz.logging :only [set-level]])
  (:import
   clojure.tools.nrepl.transport.QueueTransport
   java.util.concurrent.LinkedBlockingQueue))

(set-level :trace)

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

(deftest unknown-op-test
  (let [[in out transport] (queue-transport)
        handler (-> unknown-op)
        [handle-f a] (stoppable-handle handler transport)
        handle-loop (future
                      (try
                        (handle-f)
                        (catch Exception e
                          (print-cause-trace e)
                          flush)))]
    (.offer in {:op "nonsense"})
    (is (= {:op "nonsense", :status #{:unknown-op :error :done}} (.take out)))
    (reset! a false)
    (.offer in {:op "something to stop the handle loop"})))


(deftest project-classpath-test
  (let [[in out transport] (queue-transport)
        handler (-> unknown-op wrap-eval-in-project wrap-project-classpath)
        [handle-f a] (stoppable-handle handler transport)
        handle-loop (future
                      (try
                        (handle-f)
                        (catch Exception e (print-cause-trace e))))
        classpath (dependency-files
                   (resolve-dependencies
                    :repositories {"clojars" "https://clojars.org/repo/"
                                   "central" "http://repo1.maven.org/maven2/"}
                    :coordinates '[[org.clojure/clojure "1.4.0"]
                                   [org.clojure/tools.nrepl "0.2.0-beta10"]]
                    :retrieve true))]
    ;; set the classpath
    (.offer in {:op "project-classpath" :classpath classpath :project "p1"
                :id 0})
    (is (= {:status #{:done} :id 0} (.take out)))

    (.offer in {:op "clone" :id 1 :project "p1"})
    (let [{:keys [new-session status id] :as msg} (.take out)]
      (is (= 1 id))
      (is new-session)
      (is (not (:unknown-op status)))

      (testing "simple expression"
        (.offer in {:op "eval" :code "1" :project "p1" :session new-session
                    :id 2})
        (is (= {:ns "user" :value "1" :id 2 :session new-session} (.take out)))
        (is (= {:status #{:done} :id 2 :session new-session} (.take out))))
      (testing "clojure version"
        (.offer in {:op "eval" :code "(clojure-version)" :project "p1"
                    :session new-session :id 3})
        (is (= {:ns "user" :value "\"1.4.0\"" :id 3 :session new-session}
               (.take out)))
        (is (= {:status #{:done} :id 3 :session new-session} (.take out))))
      (reset! a false)
      (.offer in {:op "something to stop the handle loop"}))))

(deftest project-test
  (let [[in out transport] (queue-transport)
        handler (-> unknown-op wrap-eval-in-project wrap-project)
        [handle-f a] (stoppable-handle handler transport)
        handle-loop (future
                      (try
                        (handle-f)
                        (catch Exception e (print-cause-trace e))))
        project '(defproject test-prj "0.1.0"
                   :dependencies [[org.clojure/clojure "1.4.0"]])
        project-name "test-prj/test-prj"]
    ;; set the project
    (.offer in {:op "project" :project-clj (pr-str project) :id 0})
    (is (= {:status #{:done} :value project-name :id 0} (.take out)))

    (.offer in {:op "clone" :id 1 :project project-name})
    (let [{:keys [new-session status id] :as msg} (.take out)]
      (println "clone reply" msg)
      (is (= 1 id))
      (is new-session)
      (is (not (:unknown-op status)))

      (testing "simple expression"
        (.offer in {:op "eval" :code "1" :project project-name
                    :session new-session :id 2})
        (is (= {:ns "user" :value "1" :id 2 :session new-session} (.take out)))
        (is (= {:status #{:done} :id 2 :session new-session} (.take out))))
      (testing "clojure version"
        (.offer in {:op "eval" :code "(clojure-version)" :project project-name
                    :session new-session :id 3})
        (is (= {:ns "user" :value "\"1.4.0\"" :id 3 :session new-session}
               (.take out)))
        (is (= {:status #{:done} :id 3 :session new-session} (.take out))))
      (reset! a false)
      (.offer in {:op "something to stop the handle loop"}))))
