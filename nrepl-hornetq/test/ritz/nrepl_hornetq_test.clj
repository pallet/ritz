(ns ritz.nrepl-hornetq-test
  (:refer-clojure :exclude [send])
  (:use
   [clojure.pprint :only [pprint]]
   [clojure.tools.nrepl :only [url-connect]]
   [clojure.tools.nrepl.transport :only [send recv]]
   [hornetq-clj.server
    :only [server locate-queue] :rename {server hornetq-server}]
   [ritz.logging :only [trace set-level]]
   ritz.nrepl-hornetq
   [ritz.nrepl-hornetq.client :only [start-client stop-client send-message]]
   clojure.test)
  (:import
   [java.util.logging LogManager Logger Level]
   org.slf4j.bridge.SLF4JBridgeHandler))


(defn install-slf4j-bridge
  []
  (.. (LogManager/getLogManager) reset)
  (SLF4JBridgeHandler/install)
  (.. (Logger/getLogger "global") (setLevel Level/FINEST)))

(defonce use-slf4 (or (install-slf4j-bridge) 1))

;; (set-level :trace)

(defonce ^{:defonce true} server
  (doto (hornetq-server {:in-vm true :netty 55445}) .start))

(defn show-queues []
  (println (locate-queue server "/nrepl/client"))
  (println (locate-queue server "/nrepl/server")))

(defn queues []
  [(locate-queue server "/nrepl/client")
   (locate-queue server "/nrepl/server")])

(defn pprint-queues []
  (pprint (map bean (queues))))

(deftest start-test
  (testing "start and stop"
    (let [server (start-server {:transport :in-vm})]
      (is server)
      (stop-server server)))
  (testing "simple eval"
    (let [server (start-server {:transport :in-vm})
          client (start-client {:transport :in-vm})
          p (promise)]
      (try
        (is server)
        (is client)
        (is (:id @client))
        (Thread/sleep 100)
        (send-message
         client
         {:op "eval" :code "(let [x 1] (* 2 x))"}
         #(deliver p (:value %)))
        (is (= "2" @p))
        (finally
         (stop-client client)
         (stop-server server))))))

(deftest url-connect-test
  (is (url-connect "hornetq://localhost/?transport=in-vm"))
  (is (url-connect "hornetq://localhost:55445/?transport=netty")))
