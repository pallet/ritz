(ns ritz.nrepl.middleware.codeq
  "Middleware for codeq"
  (:use
   [clojure.stacktrace :only [print-cause-trace]]
   [clojure.tools.nrepl.middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.misc :only [response-for]]
   [clojure.tools.nrepl.transport :only [send] :rename {send send-msg}]
   [datomic.api :only [q connect db]]
   [ritz.logging :only [trace]]))


(def rules
 '[[(node-files ?n ?f) [?n :node/object ?f] [?f :git/type :blob]]
   [(node-files ?n ?f) [?n :node/object ?t] [?t :git/type :tree]
                       [?t :tree/nodes ?n2] (node-files ?n2 ?f)]
   [(object-nodes ?o ?n) [?n :node/object ?o]]
   [(object-nodes ?o ?n)
    [?n2 :node/object ?o] [?t :tree/nodes ?n2] (object-nodes ?t ?n)]
   [(commit-files ?c ?f) [?c :commit/tree ?root] (node-files ?root ?f)]
   [(commit-codeqs ?c ?cq) (commit-files ?c ?f) [?cq :codeq/file ?f]]
   [(file-commits ?f ?c) (object-nodes ?f ?n) [?c :commit/tree ?n]]
   [(codeq-commits ?cq ?c) [?cq :codeq/file ?f] (file-commits ?f ?c)]])


(defn query-definitions
  [symbol-name url]
  (trace "query-definitions for %s in  %s" symbol-name url)
  (let [db (db (connect url))]
    (->>
     (q '[:find ?src (min ?date)
          :in $ % ?name
          :where
          [?n :code/name ?name]
          [?cq :clj/def ?n]
          [?cq :codeq/code ?cs]
          [?cs :code/text ?src]
          [?cq :codeq/file ?f]
          (file-commits ?f ?c)
          (?c :commit/authoredAt ?date)]
        db rules symbol-name)
     (map (juxt first (comp str second))))))

(defn codeq-def-reply
  "Reply to codeq-def message"
  [{:keys [project symbol ns datomic-url transport] :as msg}]
  (try
    (let [symbol (if (or (and symbol (.contains symbol "/")) (not ns))
                   symbol
                   (str ns "/" symbol))
          res (query-definitions symbol datomic-url)]
      (trace "codeq-def-reply %s" (vec res))
      (send-msg transport (response-for msg :value res)))
    (send-msg transport (response-for msg :status #{:done}))
    (catch Exception e
      (send-msg
       transport
       (response-for
        msg
        :status #{:error} :exception (with-out-str (print-cause-trace e)))))))


(defn wrap-codeq-def
  "Lookup all definitions of a symbol in codeq"
  [handler]
  (fn [{:keys [op project] :as msg}]
    (if (= "codeq-def" op)
      (codeq-def-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-codeq-def
 {:requires #{}
  :expects #{}
  :handles
  {"codeq-def"
   {:doc "Returns all def of a codeq for the specified symbol."
    :requires
    {"symbol" "The symbol to be looked up."
     "datomic-url" "The url of the datomic transactor to use."}}}})
