(ns ritz.hooks
  "Define, and run hooks.")

(defmacro defhook
  "Define a hook. Add to a hook with `add`, and run with `run`."
  [name & hooks]
  `(defonce ~name (atom [~@hooks])))

(defn add
  "Add a function to a hook."
  [hook function]
  (swap! hook conj function))

(defn run
  "Run specified `hook`, applying each hook function with `arguments`."
  [hook & arguments]
  (doseq [f @hook]
    (apply f arguments)))
