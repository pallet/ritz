(ns ritz.connection-test
  (:use
   clojure.test)
  (:require
   [ritz.connection :as connection]
   [ritz.rpc :as rpc]
   [ritz.rpc-socket-connection :as rpc-s-c]
   [ritz.test-utils :as test-utils]))

(deftest initialise-test
  (let [a (java.net.ServerSocket. 0)
        f (future (.accept a))
        s (java.net.Socket. "localhost" (.getLocalPort a))
        c (#'connection/initialise
           {:socket s
            :reader (java.io.StringReader. "s")
            :writer (java.io.StringWriter.)}
           {:encoding "iso-latin-1-unix"})]
    (try
      (is (:reader @c))
      (is (:writer @c))
      (is (:indent-cache-hash @c))
      (is (deref (:indent-cache @c)))
      (is (:writer-redir @c))
      (is (set? (:pending @c)))
      (is (map? @(:inspector @c)))
      (is (vector? (:exception-filters @c)))
      (finally
       (when-not (.isClosed s) (.close s))
       (when-not (.isClosed a) (.close a))))))

(deftest create-test
  (let [a (java.net.ServerSocket. 0)
        f (future (.accept a))
        s (java.net.Socket. "localhost" (.getLocalPort a))
        c (#'connection/create
           {:socket s
            :reader (java.io.StringReader. "s")
            :writer (java.io.StringWriter.)}
           {:encoding "iso-latin-1-unix"})]
    (try
      (is (:reader @c))
      (is (:writer @c))
      (is (:writer-redir @c))
      (finally
       (when-not (.isClosed s) (.close s))
       (when-not (.isClosed a) (.close a))))))

(deftest read-from-connection-test
  (let [msg '(a 123 (swank/b (true nil) "c"))]
    (is (= msg
           (connection/read-from-connection
            (atom
             {:input-stream
              (let [[bs ds] (test-utils/dos)]
                (rpc/encode-message ds msg)
                (test-utils/dis (.toByteArray bs)))
              :read-message rpc-s-c/read-message
              :read-monitor (Object.)}))))))

(deftest send-to-emacs-test
  (let [msg '(a 123 (swank:b (true false) "c"))]
    (is (= (into [] (test-utils/msg "(a 123 (swank:b (t nil) \"c\"))"))
           (into [] (let [[bs ds] (test-utils/dos)]
                      (connection/send-to-emacs
                       (atom {:output-stream ds
                              :write-message rpc-s-c/write-message
                              :write-monitor (Object.)})
                       msg)
                      (.toByteArray bs)))))))

(deftest request!-test
  (is (every?
       #(= % {:request-id 2
              :buffer-ns-name "fred"
              :request-ns (the-ns 'user)
              :pending #{2}})
       (let [connection (atom {:pending #{}})]
         [(connection/request! connection "fred" 2)
          @connection]))))
