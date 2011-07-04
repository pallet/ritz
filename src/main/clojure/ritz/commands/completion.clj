(ns ritz.commands.completion
  "Symbol completion commands"
  (:use
   [ritz.swank.commands :only [defslimefn]])
  (:require
   [ritz.repl-utils.completion :as completion]))

(defslimefn simple-completions [connection symbol-string package]
  (try
    (let [[completions base] (completion/simple-completion
                              symbol-string (the-ns (symbol package)))]
      (list completions base))
   (catch java.lang.Throwable t
     (list nil symbol-string))))
