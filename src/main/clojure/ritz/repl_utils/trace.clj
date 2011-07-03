(ns ritz.repl-utils.trace
  "Provide function tracing")

(defonce ^{:private true} traced-vars (atom {}))

(defn traced
  "A sequence of traced functions"
  []
  @traced-vars)

(defn- trace-fn
  "A traced version of the var v"
  [old-f m f]
  (let [fname (str (ns-name (:ns m)) "/" (:name m))]
    (fn [& args]
      (println fname (apply str (take 240 (pr-str args))))
      (let [result (apply f args)]
        (println fname "=>" (apply str (take 240 (pr-str result))))
        result))))

(defn- trace-var
  [traced v]
  (if (traced v)
    traced
    (let [f (var-get v)]
      (alter-var-root v trace-fn (meta v) f)
      (assoc traced v f))))

(defn- untrace-var
  [traced v]
  (if (traced v)
    (let [f (traced v)]
      (alter-var-root v (constantly f))
      (dissoc traced v))
    traced))

(defn trace!
  "Start tracing a function var"
  [v]
  (swap! traced-vars trace-var v))

(defn untrace!
  "Stop tracing a function var"
  [v]
  (swap! traced-vars untrace-var v))

(defn untrace-all!
  "Untrace everything"
  []
  (swap!
   traced-vars
   (fn [traced]
     (doseq [[v f] traced]
       (alter-var-root v (constantly f)))
     nil)))

(defn toggle-trace!
  "Toggle the tracing of a function."
  [v]
  ((swap!
    traced-vars
    (fn [traced]
      (if (traced v)
        (untrace-var traced v)
        (trace-var traced v)))) v))
