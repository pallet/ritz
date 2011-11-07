(ns ritz.test-utils
  "Test functions and macros"
  (:require
   [ritz.swank :as swank]
   [ritz.rpc-socket-connection :as rpc-s-c])
  (:use
   clojure.test))

(def test-out-atom (atom []))

(defn add-to-test-out
  [output-stream message]
  (swap!
   test-out-atom conj (with-out-str (#'ritz.rpc/write-form *out* message))))

(defn test-out []
  (first (remove #(.startsWith % "(:indentation-update") @test-out-atom)))

(defmacro with-test-out-fn
  [& body]
  `(do
     (reset! test-out-atom [])
     (binding [ritz.rpc/encode-message add-to-test-out]
       ~@body)))

(defn dis [bytes]
  (java.io.DataInputStream.
   (java.io.ByteArrayInputStream.
    bytes)))

(defn dos []
  (let [bs (java.io.ByteArrayOutputStream.)
        ds (java.io.DataOutputStream. bs)]
    [bs ds]))

(defn msg [msg]
  (let [[bs ds] (dos)
        bytes (.getBytes msg "UTF-8")]
    (doto ds
      (.writeInt (count bytes))
      (.write bytes 0 (count bytes))
      (.flush))
    (.toByteArray bs)))

(defn test-connection
  [m]
  (atom (merge {:output-stream (java.io.DataOutputStream.
                                (java.io.ByteArrayOutputStream.))
                :result-history []
                :last-exception nil
                :writer-redir *out*
                :input-redir *in*
                :write-message rpc-s-c/write-message
                :write-monitor (Object.)
                :pending #{}
                :indent-cache-hash (atom nil)
                :indent-cache (ref {})
                :request-id 1
                :inspector (atom {})}
               m)))

(defn split-indentation-response
  "Splits a response into [non-indentation-response indentation-response]"
  [^String msg]
  (let [index (.indexOf msg "(:indentation-update")]
    (if (pos? index)
      (let [len (.substring msg (- index 6) index)
            len (Integer/parseInt len 16)]
        [(str (.substring msg 0 (- index 6)) (.substring msg (+ index len)))
         (.substring msg (- index 6) (+ index len))])
      [msg])))

(defmacro eval-for-emacs-test-body
  [msg-form options connection sb]
  `(let [options# ~options]
     (first
      (split-indentation-response
       (do
         (swank/eval-for-emacs
          ~connection ~msg-form
          (:ns options# 'user)
          (:request-id ~connection 1))
         (str ~sb))))))

(defmacro eval-for-emacs-test
  "Create a test for eval-for-emacs. Output is a string or regex literal test."
  ([msg-form output options]
     (let [opts (gensym "opts")
           conn (gensym "conn")
           sb (gensym "sb")]
       `(let [~opts ~options
              ~sb (new java.io.StringWriter)
              ~conn (binding [*out* ~sb] (test-connection
                                          (dissoc ~opts :ns :writer)))]
          (is
           ~(if (or (string? output) (list? output))
              `(with-test-out-fn
                 (eval-for-emacs-test-body ~msg-form ~opts ~conn ~sb)
                 (= ~output (test-out)))
              `(with-test-out-fn
                 (eval-for-emacs-test-body ~msg-form ~opts ~conn ~sb)
                 (re-find ~output (test-out)))))
          (deref ~conn))))
  ([msg-form output]
     `(eval-for-emacs-test ~msg-form ~output {})))

(defmacro dispatch-event-test
  ([msg-form output {:as options}]
     `(let [options# ~options]
        (is (= ~output
               (with-test-out-fn
                 (let [connection# (test-connection (dissoc options# :ns))]
                   (swank/dispatch-event
                    (list
                     :emacs-rex
                     ~msg-form
                     (name (:ns options# 'user))
                     (:thread-id options# 1234)
                     (:request-id @connection# 1))
                    connection#))
                 (test-out))))))
  ([msg-form output]
     `(dispatch-event-test ~msg-form ~output {})))
