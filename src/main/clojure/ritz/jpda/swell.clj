(ns ritz.jpda.swell
  "Support for swell restarts")

(defmacro with-swell
  "Binds swell support, if swell is present."
  {:indent 1}
  [& body]
  `(if-let [v# (and
                (find-ns 'swell.spi)
                (ns-resolve 'swell.spi '~'*unhandled-hook*))]
     (do
       (logging/trace "with-swell")
       (require 'ritz.jpda.swell.impl)
       (push-thread-bindings
        {v# (ns-resolve 'ritz.jpda.swell.impl '~'handle-restarts)})
       (try
         ~@body
         (finally (pop-thread-bindings))))
     (do
       (logging/trace "without-swell")
       ~@body)))
