(ns ritz.rpc-test
  (:use clojure.test)
  (:require
   [ritz.rpc :as rpc]))

(deftest decode-message-test
  (is (= (list 'swank/a 123 (list (symbol "%b%") '(true nil) "c"))
         (let [msg "00001f(swank:a 123 (%b% (t nil) \"c\"))"]
           (with-open [rdr (java.io.StringReader. msg)]
             (rpc/decode-message rdr)))))
  (is (= '(swank/swank-require (quote (:swank-hyperdoc :swank-asdf)))
         (let [msg
               (with-out-str
                 (rpc/encode-message
                  *out*
                  '(swank:swank-require
                    (quote (:swank-hyperdoc :swank-asdf)))))]
           (with-open [rdr (java.io.StringReader. msg)]
             (rpc/decode-message rdr))))))

(deftest encode-message-test
  (is (= "000007\"hello\""
         (with-out-str
           (rpc/encode-message *out* "hello"))))
  (is (= "00001d(a 123 (swank:b (t nil) \"c\"))"
         (with-out-str
           (rpc/encode-message *out* '(a 123 (swank:b (true false) "c")))))))
