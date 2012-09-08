(ns ritz.repl-utils.core.test-protocol
  (:require
   ritz.repl-utils.core.defprotocol))

(in-ns 'ritz.repl-utils.core.defprotocol-test)

(defmacro gen-P
  []
  (if (= ritz.repl-utils.core.defprotocol-test/protocol-args 1)
    '(defprotocol P (f [_]))
    '(defprotocol P (f [_ _]))))

(gen-P)
