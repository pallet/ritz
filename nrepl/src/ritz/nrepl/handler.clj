(ns ritz.nrepl.handler
  "Handlers for ritz nrepl"
  (:use
   [ritz.nrepl.middleware.javadoc :only [wrap-javadoc]]))

(def ritz-middlewares
  [#'wrap-javadoc])
