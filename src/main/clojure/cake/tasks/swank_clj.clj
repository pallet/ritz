(ns cake.tasks.swank-clj
  "A cake task for running swank-clj. Modified from cake.tasks.swank."
  (:use cake cake.core
        [useful.utils :only [if-ns]]
        [bake.core :only [current-context]]))

(def current-port (atom nil))

(defn- serve-swank
  "Run swank-clj connection thread in the project classloader."
  [context options]
  (bake (:use [bake.core :only [set-context!]])
        (:require swank-clj.socket-server)
        [context context options options]
        (let [start (ns-resolve 'swank-clj.socket-server 'start)
              opts {:encoding (or (System/getProperty "swank.encoding")
                                  "iso-latin-1-unix")}]
          (eval (:swank-init *project*))
          (set-context! context)
          (start (merge opts options)))))

(if-ns (:use [swank-clj.socket-server :only [start]])

 (defn start-swank [options]
   (let [out (with-out-str (serve-swank (current-context) options))]
     (if (.contains out "java.net.BindException")
       (println "unable to start swank-clojure server, port already in use")
       (do (compare-and-set! current-port nil (:port options))
           (println "started swank-clojure server on port" @current-port)))))

 (defn start-swank [host]
   (println "error loading swank-clj.")
   (println
    "see http://clojure-cake.org/swank for installation instructions")))

(deftask swank-clj #{compile-java}
  "Report status of swank-clj server and start it if not running."
  {opts :swank-clj}
  (if @current-port
    (println "swank-clj currently running on port" @current-port)
    (let [[host port & {:as options}] opts]
      (start-swank (merge {:host (or host "localhost")
                           :port (or port 4005)}
                          options)))))
