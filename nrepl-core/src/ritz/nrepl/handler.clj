(ns ritz.nrepl.handler
  "Handlers for ritz nrepl"
  (:require
   [clojure.tools.nrepl.middleware pr-values session])
  (:use
   [clojure.tools.nrepl.middleware :only [linearize-middleware-stack]]
   [clojure.tools.nrepl.server :only [unknown-op]]
   [ritz.nrepl.middleware.apropos :only [wrap-apropos]]
   [ritz.nrepl.middleware.describe-symbol :only [wrap-describe-symbol]]
   [ritz.nrepl.middleware.doc :only [wrap-doc]]
   [ritz.nrepl.middleware.javadoc :only [wrap-javadoc]]
   [ritz.nrepl.middleware.load-file :only [wrap-load-file]]
   [ritz.nrepl.middleware.simple-complete :only [wrap-simple-complete]]
   [ritz.nrepl.middleware.tracking-eval :only [tracking-eval]]))

(def ritz-middlewares
  [#'clojure.tools.nrepl.middleware/wrap-describe
   #'tracking-eval
   #'wrap-load-file
   #'clojure.tools.nrepl.middleware.session/add-stdin
   #'clojure.tools.nrepl.middleware.session/session
   #'wrap-apropos
   #'wrap-describe-symbol
   #'wrap-doc
   #'wrap-javadoc
   #'wrap-simple-complete])

(defn default-handler
  "A default handler supporting tracking evaluation, stdin, sessions, and
   readable representations of evaluated expressions via `pr`.

   Additional middlewares to mix into the default stack may be provided; these
   should all be values (usually vars) that have an nREPL middleware descriptor
   in their metadata (see clojure.tools.nrepl.middleware/set-descriptor!)."
  [& additional-middlewares]
  (let [stack (linearize-middleware-stack
               (concat ritz-middlewares additional-middlewares))]
    ((apply comp (reverse stack)) unknown-op)))
