(ns ritz.debugger.break
  "Break information. Break information is maintained based on thread id. For
each thread a stack of break level-info's is maintained (since a thread may
have multiple breaks pending)."
  (:use
   [ritz.debugger.connection
    :only [break-context break-assoc-in! break-update! break-update-in!]]
   [ritz.logging :only [trace]]))

(defn break-level-add!
  "Add a new break level. This clears :abort-to-level to ensure consistency of
the break stack."
  [connection thread-id level-info]
  (trace "break-level-add!: level-info %s" level-info)
  (break-update-in!
   connection
   [thread-id]
   (fn [break-context]
     (->
      break-context
      (update-in [:break-levels]
                 (fn [levels]
                   (trace "break-level-add! %s" (count levels))
                   (conj (or levels []) level-info)))
      (dissoc :abort-to-level)))))

(defn break-level-info
  "Obtain the current level. Returns [level-info level-number]"
  [connection thread-id]
  (when-let [levels (seq (-> connection
                             break-context (get thread-id) :break-levels))]
    [(last levels) (dec (count levels))]))

(defn break-level-infos
  "Obtain the all level-infos."
  [connection thread-id]
  (-> connection break-context (get thread-id) :break-levels))

(defn break-drop-level!
  "Drop a break level."
  [connection thread-id]
  (trace "break-drop-level: :thread-id %s" thread-id)
  (break-update-in!
   connection [thread-id :break-levels]
   (fn [break-levels]
     (trace "break-drop-level: :levels %s" (count break-levels))
     (subvec break-levels 0 (dec (count break-levels))))))

(defn break-drop-levels!
  "Drop a break level."
  [connection thread-id]
  (trace "break-drop-level: :thread-id %s" thread-id)
  (break-assoc-in! connection [thread-id :break-levels] []))

(defn break-abort-to-level!
  "Set abort to level."
  [connection thread-id level]
  (trace "break-abort-to-level: :thread-id %s :level %s" thread-id level)
  (break-assoc-in! connection [thread-id :abort-to-level] level))

(defn clear-abort-for-current-level
  "Clear any abort for the current level"
  [connection thread-id]
  (break-update-in!
   connection
   [thread-id]
   (fn [c]
     (trace
         "clear-abort-for-current-level :thread %s :level %s :abort-to-level %s"
       thread-id (count (:break-levels c)) (:abort-to-level c))
     (if (and (:abort-to-level c)
              (= (count (:break-levels c)) (:abort-to-level c)))
       (dissoc c :abort-to-level)
       c))))

(defn clear-aborts
  "Clear any abort"
  [connection]
  (break-update!
   connection
   (fn [break-context]
     (into {}
           (map
            (fn [[thread-id c]]
              (trace "clear aborts %s" thread-id)
              [thread-id (dissoc c :abort-to-level)])
            break-context)))))

(defn break-threads
  "Return threads referenced by context"
  [connection]
  (distinct
   (mapcat
    #(map :thread (:break-levels %))
    (vals (break-context connection)))))

(defn remove-threads
  "Remove threads from context"
  [connection thread-ids]
  (break-update!
   connection
   (fn [break-context]
     (apply dissoc break-context thread-ids))))

(defn aborting-level?
  "Predicate to check if the current break-level is being aborted."
  [connection thread-id]
  (let [break-context (get (break-context connection) thread-id)]
    (trace
     "aborting-level? %s count %s abort-to %s"
     thread-id
     (count (:break-levels break-context))
     (:abort-to-level break-context))
    (if-let [abort-to-level (:abort-to-level break-context)]
      (>= (count (:break-levels break-context)) abort-to-level)
      (trace "aborting-level? no abort in progress"))))

(defn abort-level-not-reached?
  "If the current break level has been reached."
  [connection thread-id]
  (let [break-context (get (break-context connection) thread-id)]
    (trace
     "abort-level-reached? %s count %s abort-to %s"
     thread-id
     (count (:break-levels break-context))
     (:abort-to-level break-context))
    (if-let [abort-to-level (:abort-to-level break-context)]
      (> (count (:break-levels break-context)) (inc abort-to-level))
      (trace "abort-level-reached? no abort in progress"))))

(defn break-exception-message!
  "Set abort to level."
  [connection thread-id event exception-message]
  (trace "break-exception-message!: %s" exception-message)
  (break-assoc-in! connection [thread-id :exception-message] exception-message)
  (break-assoc-in! connection [thread-id :exception-event] event))

(defn break-exception-message
  "Set abort to level."
  [connection thread-id event]
  (let [context (get (break-context connection) thread-id)]
    (when (= event (:exception-event context))
      (:exception-message context))))
