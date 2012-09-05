(ns ritz.nrepl.handler
  "Handlers for ritz nrepl"
  (:use
   [ritz.nrepl.middleware.apropos :only [wrap-apropos]]
   [ritz.nrepl.middleware.describe-symbol :only [wrap-describe-symbol]]
   [ritz.nrepl.middleware.doc :only [wrap-doc]]
   [ritz.nrepl.middleware.javadoc :only [wrap-javadoc]]))

(def ritz-middlewares
  [#'wrap-apropos #'wrap-describe-symbol #'wrap-doc #'wrap-javadoc])
