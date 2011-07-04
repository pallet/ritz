(ns ritz.test-utils
  "Test functions and macros"
  (:require
   [ritz.swank :as swank]
   [ritz.rpc-socket-connection :as rpc-s-c])
  (:use
   clojure.test))

(defn test-connection
  [m]
  (atom (merge {:reader *in*
                :writer *out*
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
  "Create a test for eval-for-emacs. output is a string or regex literal"
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
              `(= ~output
                  (eval-for-emacs-test-body ~msg-form ~opts ~conn ~sb))
              `(re-find ~output
                        (eval-for-emacs-test-body ~msg-form ~opts ~conn ~sb))))
          (deref ~conn))))
  ([msg-form output]
     `(eval-for-emacs-test ~msg-form ~output {})))

(defmacro dispatch-event-test
  ([msg-form output {:as options}]
  `(let [options# ~options]
     (is (= ~output
            (first
             (split-indentation-response
              (with-out-str
                (let [connection# (test-connection (dissoc options# :ns))]
                  (swank/dispatch-event
                   (list
                    :emacs-rex
                    ~msg-form
                    (name (:ns options# 'user))
                    (:thread-id options# 1234)
                    (:request-id @connection# 1))
                   connection#)))))))))
  ([msg-form output]
     `(dispatch-event-test ~msg-form ~output {})))
