(ns ritz.jpda.swell
  "Support for swell restarts"
  (:use
   [ritz.logging :only [trace]]))

(defmacro with-swell
  "Binds swell support, if swell is present."
  {:indent 0}
  [& body]
  `(do
     (require 'ritz.jpda.swell.impl)
     (if-let [v# (and
                  (find-ns 'swell.spi)
                  (ns-resolve 'swell.spi '~'*unhandled-hook*))]
       (do
         (trace "with-swell")
         (push-thread-bindings
          {v# (ns-resolve 'ritz.jpda.swell.impl '~'handle-restarts)})
         (try
           ~@body
           (finally (pop-thread-bindings))))
       (do
         (trace "without-swell")
         ~@body))))
