(ns ritz.swank.main
  (:gen-class))

(def ^{:dynamic true} *default-port* 4005)

(defn main
  "Main for launching a socket server."
  ([port & {:as options}]
     (require 'ritz.swank.socket-server)
     ((ns-resolve 'ritz.swank.socket-server 'start) (assoc options :port port)))
  ([] (main *default-port*)))

(def -main main)
