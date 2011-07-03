(ns cake.tasks.ritz
  "A cake task for running ritz. Modified from cake.tasks.swank."
  (:use cake cake.core
        [useful.utils :only [if-ns]]
        [bake.core :only [current-context]]))

(def current-port (atom nil))

(defn- serve-swank
  "Run ritz connection thread in the project classloader."
  [context options]
  (bake (:use [bake.core :only [set-context!]])
        (:require ritz.socket-server)
        [context context options options]
        (let [start (ns-resolve 'ritz.socket-server 'start)
              opts {:encoding (or (System/getProperty "swank.encoding")
                                  "iso-latin-1-unix")}]
          (eval (:swank-init *project*))
          (set-context! context)
          (start (merge opts options)))))

(if-ns (:use [ritz.socket-server :only [start]])

 (defn start-swank [options]
   (let [out (with-out-str (serve-swank (current-context) options))]
     (if (.contains out "java.net.BindException")
       (println "unable to start swank-clojure server, port already in use")
       (do (compare-and-set! current-port nil (:port options))
           (println "started swank-clojure server on port" @current-port)))))

 (defn start-swank [host]
   (println "error loading ritz.")
   (println
    "see http://clojure-cake.org/swank for installation instructions")))

(deftask ritz #{compile-java}
  "Report status of ritz server and start it if not running."
  {opts :ritz}
  (if @current-port
    (println "ritz currently running on port" @current-port)
    (let [[host port & {:as options}] opts]
      (start-swank (merge {:host (or host "localhost")
                           :port (or port 4005)}
                          options)))))
