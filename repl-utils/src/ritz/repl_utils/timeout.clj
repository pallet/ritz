(ns ritz.repl-utils.timeout
  "Provides a timeout thunk")

(defn call-with-timeout
  "Executes a function `f`, passing a timeout function that returns true when
  `time-limit-in-msec` has been elapsed as argument. Returns a 3 element vector,
  containg: the result of calling f, a flag whether timeout occured, and the
  elapsed time in milliseconds."
  [time-limit-in-msec f]
  (let [timed-out (atom false)
        start! (fn []
                 (future (do
                           (Thread/sleep time-limit-in-msec)
                           (swap! timed-out (constantly true)))))
        timed-out? (fn [] @timed-out)
        started-at (System/nanoTime)]
    (start!)
    [(f timed-out?)
     @timed-out
     (/ (double (- (System/nanoTime) started-at)) 1000000.0)]))

(defmacro with-timeout
  "Provides a scope that binds a function to timed-out? that returns true if
  given time-limit-in-msec has been elapsed. It then executes it's body."
  [[timed-out? time-limit-in-msec] & body]
  `(call-with-timeout ~time-limit-in-msec (fn [~timed-out?] ~@body)))
