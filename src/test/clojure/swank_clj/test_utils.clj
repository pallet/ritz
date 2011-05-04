(ns swank-clj.test-utils
  "Test functions and macros"
  (:require
   [swank-clj.swank :as swank]
   [swank-clj.commands :as commands]
   [swank-clj.rpc-socket-connection :as rpc-s-c])
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
                :indent-cache-pkg (ref nil)
                :indent-cache (ref {})
                :request-id 1}
               m)))

(defn split-indentation-response
  [^String msg]
  (let [index (.indexOf msg "(:indentation-update")]
    (if (pos? index)
      (let [len (.substring msg (- index 6) index)
            len (Integer/parseInt len 16)]
        [(str (.substring msg 0 (- index 6)) (.substring msg (+ index len)))
         (.substring msg (- index 6) (+ index len))])
      [msg])))

(defn eval-for-emacs-test-body
  [msg-form {:as options}]
  `(let [options# ~options]
     (first
      (split-indentation-response
       (with-out-str
         (let [connection# (test-connection (dissoc options# :ns))]
           (swank/eval-for-emacs
            connection# '~msg-form
            (:ns options# 'user)
            (:request-id @connection# 1))))))))

(defmacro eval-for-emacs-test
  "Create a test for eval-for-emacs. output is a string or regex literal"
  ([msg-form output {:as options}]
     `(let [options# ~options]
        (is
         ~(if (or (string? output) (list? output))
            `(= ~output
                ~(eval-for-emacs-test-body msg-form options))
            `(re-find ~output
                      ~(eval-for-emacs-test-body msg-form options))))))
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
