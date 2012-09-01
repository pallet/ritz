(ns ritz.swank.indent
  (:require
   [ritz.swank.connection :as connection]
   [ritz.swank.core :as core]
   [ritz.swank.hooks :as hooks]
   [ritz.swank.messages :as messages])
  (:use
   [ritz.repl-utils.indent :only [indentation-update]]
   [ritz.logging :only [trace]]))

(defn- indent-context
  [connection]
  @(:indent connection))

(defn perform-indentation-update
  "Update the indentation cache in connection and update emacs.
   If force is true, then start again without considering the old cache."
  [connection]
  (let [{:keys [updates cache-hash]} (indentation-update
                                      (indent-context connection)
                                      (connection/request-ns connection))]
    (trace "indentation updates %s" (vec updates))
    (when (seq updates)
      (swap! (:indent connection)
             (fn [context]
               (->
                context
                (assoc :cache-hash cache-hash)
                (update-in [:cache] into updates))))
      (connection/send-to-emacs
       connection
       (messages/indentation-update
        (map (comp messages/symbol-indentation second) updates))))))

(hooks/add core/pre-reply-hook perform-indentation-update)
