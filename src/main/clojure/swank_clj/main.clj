(ns swank-clj.main
  (:gen-class))

(def *default-port* 4005)

(defn main
  "Main for launching a socket server."
  ([port & {:as options}]
     (require 'swank-clj.socket-server)
     ((ns-resolve 'swank-clj.socket-server 'start) (assoc options :port port)))
  ([] (main *default-port*)))

(def -main main)
