(ns ritz.swank.indent
  (:require
   [ritz.connection :as connection]
   [ritz.hooks :as hooks]
   [ritz.repl-utils.helpers :as helpers]
   [ritz.swank.core :as core]
   [ritz.swank.messages :as messages]
   [ritz.swank.utils :as utils]))

(defn- need-full-indentation-update?
  "Return true if the indentation cache should be updated for all
   namespaces.

   This is a heuristic so as to avoid scanning all symbols from all
   namespaces. Instead, we only check whether the set of namespaces in
   the cache match the set of currently defined namespaces."
  [connection]
  (not= (hash (all-ns)) (:indent-cache-hash @connection)))

(defn- find-args-body-position
  "Given an arglist, return the number of arguments before
     [... & body]
   If no & body is found, nil will be returned"
  [args]
  (when (coll? args)
    (when-let [amp-position (utils/position '#{&} args)]
      (when-let [body-position (utils/position '#{body clauses} args)]
        (when (= (inc amp-position) body-position)
          amp-position)))))

(defn- find-arglists-body-position
  "Find the smallest body position from an arglist"
  [arglists]
  (let [positions (remove nil? (map find-args-body-position arglists))]
    (when-not (empty? positions)
      (apply min positions))))

(defn- find-var-body-position
  "Returns a var's :indent override or the smallest body position of a
   var's arglists"
  [var]
  (let [var-meta (meta var)]
    (or (:indent var-meta)
        (find-arglists-body-position (:arglists var-meta)))))

(defn- var-indent-representation
  "Returns the slime indentation representation (name . position) for
   a given var. If there is no indentation representation, nil is
   returned."
  [var]
  (when-let [body-position (find-var-body-position var)]
    (when (or (= body-position 'defun) (not (neg? body-position)))
      (messages/symbol-indentation (name (:name (meta var))) body-position))))

(defn- get-cache-update-for-var
  "Checks whether a given var needs to be updated in a cache. If it
   needs updating, return [var-name var-indentation-representation].
   Otherwise return nil"
  [find-in-cache var]
  (when-let [indent (var-indent-representation var)]
    (let [name (:name (meta var))]
      (when-not (= (find-in-cache name) indent)
        [name indent]))))

(defn- get-cache-updates-in-namespace
  "Finds all cache updates needed within a namespace"
  [find-in-cache ns]
  (->>
   (vals (ns-interns ns))
   (map (partial get-cache-update-for-var find-in-cache) )
   (remove nil?)))

(defn- update-indentation-delta
  "Update the cache and return the changes in a (symbol '. indent) list.
   If FORCE is true then check all symbols, otherwise only check
   symbols belonging to the buffer package"
  [ns cache-ref load-all-ns?]
  (let [find-in-cache @cache-ref]
    (let [namespaces (if load-all-ns? (all-ns) [ns])
          updates (mapcat
                   (partial get-cache-updates-in-namespace find-in-cache)
                   namespaces)]
      (when (seq updates)
        (dosync (alter cache-ref into updates))
        (map second updates)))))

(defn- perform-indentation-update
  "Update the indentation cache in connection and update emacs.
   If force is true, then start again without considering the old cache."
  [connection force]
  (let [cache (:indent-cache @connection)]
    (let [delta (update-indentation-delta
                 (connection/request-ns connection) cache force)]
      (reset! (:indent-cache-hash @connection) (hash (all-ns)))
      (when (seq delta)
        (connection/send-to-emacs
         connection
         (messages/indentation-update delta))))))

(defn- sync-indentation-to-emacs
  "Send any indentation updates to Emacs via emacs-connection"
  [connection]
  (perform-indentation-update
   connection
   (need-full-indentation-update? connection)))

(hooks/add core/pre-reply-hook sync-indentation-to-emacs)
