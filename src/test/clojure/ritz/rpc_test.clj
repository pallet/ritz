(ns ritz.rpc-test
  (:use clojure.test)
  (:require
   [ritz.rpc :as rpc]
   [ritz.test-utils :as test-utils]))

(deftest decode-message-test
  (is (= (list 'swank/a 123 (list (symbol "%b%") '(true nil) "c"))
         (let [msg (test-utils/msg "(swank:a 123 (%b% (t nil) \"c\"))")]
           (with-open [is (test-utils/dis msg)]
             (rpc/decode-message is)))))
  (is (= '(swank/swank-require (quote (:swank-hyperdoc :swank-asdf)))
         (let [[bs ds] (test-utils/dos)]
           (rpc/encode-message
            ds
            '(swank:swank-require
              (quote (:swank-hyperdoc :swank-asdf))))
           (with-open [is (test-utils/dis (.toByteArray bs))]
             (rpc/decode-message is))))))

(deftest encode-message-test
  (is (= (into [] (test-utils/msg "\"hello\""))
         (into [] (let [[bs ds] (test-utils/dos)]
                    (rpc/encode-message ds "hello")
                    (.toByteArray bs)))))
  (is (= (into [] (test-utils/msg "(a 123 (swank:b (t nil) \"c\"))"))
         (into [] (let [[bs ds] (test-utils/dos)]
                    (rpc/encode-message ds '(a 123 (swank:b (true false) "c")))
                    (.toByteArray bs))))))
