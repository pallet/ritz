(ns ritz.nrepl.middleware.codeq-test
  (:use
   clojure.test
   ritz.nrepl.middleware.codeq
   [cemerick.pomegranate.aether :only [dependency-files resolve-dependencies]]
   [classlojure.core :only [classlojure eval-in]]
   [clojure.java.io :only [file output-stream]]
   [clojure.stacktrace :only [print-cause-trace]]
   [clojure.tools.nrepl.server :only [handle* unknown-op]]
   [clojure.tools.nrepl.transport :only [recv]])
  (:import
   clojure.tools.nrepl.transport.QueueTransport
   java.util.concurrent.LinkedBlockingQueue
   java.util.TimeZone))

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

(defn save-properties
  [f property-map]
  (with-open [^java.io.OutputStream out (output-stream f)]
    (let [props (java.util.Properties.)]
      (doseq [[k v] property-map]
        (.setProperty props (name k) (str v)))
      (.store props out "properties for nrepl-codeq tests"))))

(defn port-reachable?
  ([ip port timeout]
     (let [socket (doto (java.net.Socket.)
                    (.setReuseAddress false)
                    (.setSoLinger false 1)
                    (.setSoTimeout timeout))]
       (try
         (.connect socket (java.net.InetSocketAddress. ip port))
         true
         (catch java.io.IOException _)
         (finally
           (try (.close socket) (catch java.io.IOException _))))))
  ([ip port]
     (port-reachable? ip port 2000)))

(defn run-transactor
  []
  (let [f (java.io.File/createTempFile "nrepl-codeq" ".properties")
        cl (classlojure
            (conj
             (dependency-files
              (resolve-dependencies
               :repositories {"clojars" "https://clojars.org/repo/"
                              "central" "http://repo1.maven.org/maven2/"}
               :coordinates '[[org.cloudhoist/datomic-free-transactor "0.8.3731"]
                              [ch.qos.logback/logback-classic "1.0.0"]]
               :retrieve true))
             "file:dev-resources/"))
        properties {:protocol "free"
                    :host "localhost"
                    :port 4334
                    :data-dir (file
                               (System/getProperty "user.dir")
                               "target" "datomic-data")
                    :log-dir (file
                              (System/getProperty "user.dir")
                              "target" "datomic-logs")}]
    (.deleteOnExit f)
    (save-properties f properties)
    (eval-in cl `(require 'datomic.launcher))
    (let [f (future
              (eval-in cl `(do
                             (datomic.launcher/-main ~(.getAbsolutePath f))
                             (Thread/sleep 20000))))
          out *out*
          t (Thread. #(deref f))]
      (.start t))
    (loop [tries 6]
      (if (pos? tries)
        (when (port-reachable? "localhost" 4334)
          (recur (dec tries)))
        (throw (Exception. "Datomic failed to start in time"))))
    (Thread/sleep 10000)))

(defn populate-codeq
  []
  (let [cl (classlojure
            (conj
             (dependency-files
              (resolve-dependencies
               :repositories {"clojars" "https://clojars.org/repo/"
                              "central" "http://repo1.maven.org/maven2/"}
               :coordinates '[[org.cloudhoist/codeq "0.1.0-SNAPSHOT"
                               :exclusions [org.slf4j/slf4j-nop]]
                              [org.clojure/clojure "1.5.0-alpha6"]
                              [ch.qos.logback/logback-classic "1.0.0"]]
               :retrieve true))
              "file:dev-resources/"))]
    (eval-in cl `(require 'datomic.codeq.core))
    (eval-in cl `(import 'org.h2.Driver))  ;; fails to find driver without this
    (eval-in cl `(datomic.codeq.core/main ~(datomic-url)))))

(deftest codeq-def-test
  (try
    (TimeZone/setDefault (TimeZone/getTimeZone "UTC"))
    (run-transactor)
    (populate-codeq)
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
              [["(defmacro trace\n  [fmt-str & args]\n  `(log :trace ~fmt-str ~@args))"
                "Sun Jul 03 02:08:35 UTC 2011"]]
              :id 0}
             (.take out)))

      (is (= #{:done} (:status (.take out))))

      (.offer in {:op "codeq-def" :symbol "trace" :ns "ritz.logging"
                  :datomic-url (datomic-url) :id 0})
      (is (= {:value
              [["(defmacro trace\n  [fmt-str & args]\n  `(log :trace ~fmt-str ~@args))"
                "Sun Jul 03 02:08:35 UTC 2011"]]
              :id 0}
             (.take out)))

      ;; shut down the handle loop
      (reset! a false)
      (.offer in {:op "something to stop the handle loop"}))))
