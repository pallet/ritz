(ns ritz.logging
  "Logging for swank. Rudimentary for now")

(defonce ^java.io.Writer logging-out
  (or *out* (java.io.FileWriter. (java.io.File. "/tmp/swank.log"))))

(def monitor (Object.))

(def log-level (atom nil))

(defn set-level
  "Set log level"
  [level]
  (reset! log-level level))

(defmacro log
  [level fmt-str & args]
  `(when (= ~level @log-level)
     (locking monitor
       (.write logging-out (format ~fmt-str ~@args))
       (.write logging-out "\n")
       (.flush logging-out))))

(defmacro log-str
  "Log without newline or flushing"
  [level fmt-str & args]
  `(when (= ~level @log-level)
     (locking monitor
       (.write logging-out (format ~fmt-str ~@args)))))

(defmacro trace
  [fmt-str & args]
  `(log :trace ~fmt-str ~@args))

(defmacro trace-str
  [fmt-str & args]
  `(log-str :trace ~fmt-str ~@args))
