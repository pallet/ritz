(ns ritz.main
  (:gen-class))

(def *default-port* 4005)

(defn main
  "Main for launching a socket server."
  ([port & {:as options}]
     (require 'ritz.socket-server)
     ((ns-resolve 'ritz.socket-server 'start) (assoc options :port port)))
  ([] (main *default-port*)))

(def -main main)
