(ns swank-clj.connection-test
  (:use
   clojure.test)
  (:require
   [swank-clj.connection :as connection]
   [swank-clj.rpc :as rpc]))

(deftest initialise-test
  (let [a (java.net.ServerSocket. 0)
        f (future (.accept a))
        s (java.net.Socket. "localhost" (.getLocalPort a))
        c (#'connection/initialise s {:encoding "iso-latin-1-unix"})]
    (try
      (is (:reader @c))
      (is (:writer @c))
      (is (:writer-redir @c))
      (finally
       (when-not (.isClosed s) (.close s))
       (when-not (.isClosed a) (.close a))))))

(deftest create-test
  (let [a (java.net.ServerSocket. 0)
        f (future (.accept a))
        s (java.net.Socket. "localhost" (.getLocalPort a))
        c (#'connection/create s {:encoding "iso-latin-1-unix"})]
    (try
      (is (:reader @c))
      (is (:writer @c))
      (is (:writer-redir @c))
      (finally
       (when-not (.isClosed s) (.close s))
       (when-not (.isClosed a) (.close a))))))

(deftest read-from-connection-test
  (let [msg '(a 123 (swank:b (true nil) "c"))]
    (is (= msg
           (connection/read-from-connection
            (atom {:reader (java.io.StringReader.
                            (with-out-str (rpc/encode-message *out* msg)))}))))))

(deftest send-to-emacs-test
  (let [msg '(a 123 (swank:b (true false) "c"))]
    (is (= "00001d(a 123 (swank:b (t nil) \"c\"))"
           (with-out-str
             (connection/send-to-emacs
              (atom {:writer *out*})
              msg))))))
