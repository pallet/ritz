(ns swank-clj.inspect
  (:require
   [swank-clj.commands :as commands]
   [swank-clj.logging :as logging]
   [swank-clj.swank.core :as core]))

(defn reset-inspector [inspector]
  (swap! inspector {:part-index (atom 0)}))

(defn inspectee-title* [obj]
  (cond
   (instance? clojure.lang.LazySeq obj) (str "clojure.lang.LazySeq@...")
   :else (str obj)))

(defn inspectee-title [inspector]
  (inspectee-title* (:inspectee @inspector)))

(defn position
  "Finds the first position of an item that matches a given predicate
   within col. Returns nil if not found. Optionally provide a start
   offset to search from."
  ([pred coll] (position pred coll 0))
  ([pred coll start]
     (loop [coll (drop start coll), i start]
       (when (seq coll)
         (if (pred (first coll))
           i
           (recur (rest coll) (inc i))))))
  {:tag Integer})

(defn print-part-to-string [value]
  (inspectee-title* value)
  ;; (let [s (inspectee-title* value)
  ;;       pos (position #{value} @*inspector-history*)]
  ;;   (if pos
  ;;     (str "#" pos "=" s)
  ;;     s))
  )

(defn inspectee-index [inspector]
  (count (:inspectee-parts @inspector)))

(defn value-part [obj s parts]
  [(list :value (or s (print-part-to-string obj)) (count parts))
   (conj parts obj)])

(defn action-part [label lambda refresh? actions]
  [(list :action label (count actions))
   (conj actions (list lambda refresh?))])

(defn label-value-line
  ([label value] (label-value-line label value true))
  ([label value newline?]
     (list* (str label) ": " (list :value value)
            (if newline? '((:newline)) nil))))

(defmacro label-value-line* [& label-values]
  `(concat ~@(map (fn [[label value]]
                    `(label-value-line ~label ~value))
                  label-values)))

;; Inspection

;; This is the simple version that only knows about clojure stuff.
;; Many of these will probably be redefined by swank-clojure-debug
(defmulti emacs-inspect
  (fn known-types [obj]
    (cond
     (map? obj) :map
     (vector? obj) :vector
     (var? obj) :var
     (string? obj) :string
     (seq? obj) :seq
     (instance? Class obj) :class
     (instance? clojure.lang.Namespace obj) :namespace
     (instance? clojure.lang.ARef obj) :aref
     (.isArray (class obj)) :array)))

(defn inspect-meta-information [obj]
  (when (> (count (meta obj)) 0)
    (concat
     '("Meta Information: " (:newline))
     (mapcat (fn [[key val]]
               `("  " (:value ~key) " = " (:value ~val) (:newline)))
             (meta obj)))))

(defmethod emacs-inspect :map [obj]
  (concat
   (label-value-line*
    ("Class" (class obj))
    ("Count" (count obj)))
   '("Contents: " (:newline))
   (inspect-meta-information obj)
   (mapcat (fn [[key val]]
             `("  " (:value ~key) " = " (:value ~val)
               (:newline)))
           obj)))

(defmethod emacs-inspect :vector [obj]
  (concat
   (label-value-line*
    ("Class" (class obj))
    ("Count" (count obj)))
   '("Contents: " (:newline))
   (inspect-meta-information obj)
   (mapcat (fn [i val]
             `(~(str "  " i ". ") (:value ~val) (:newline)))
           (iterate inc 0)
           obj)))

(defmethod emacs-inspect :array [obj]
  (concat
   (label-value-line*
    ("Class" (class obj))
    ("Count" (alength obj))
    ("Component Type" (.getComponentType (class obj))))
   '("Contents: " (:newline))
   (mapcat (fn [i val]
             `(~(str "  " i ". ") (:value ~val) (:newline)))
           (iterate inc 0)
           obj)))

(defmethod emacs-inspect :var [#^clojure.lang.Var obj]
  (concat
   (label-value-line*
    ("Class" (class obj)))
   (inspect-meta-information obj)
   (when (.isBound obj)
     `("Value: " (:value ~(var-get obj))))))

(defmethod emacs-inspect :string [obj]
  (concat
   (label-value-line*
    ("Class" (class obj)))
   (inspect-meta-information obj)
   (list (str "Value: " (pr-str obj)))))

(defmethod emacs-inspect :seq [obj]
  (concat
   (label-value-line*
    ("Class" (class obj)))
   '("Contents: " (:newline))
   (inspect-meta-information obj)
   (mapcat (fn [i val]
             `(~(str "   " i ". ") (:value ~val) (:newline)))
           (iterate inc 0)
           obj)))

(defmethod emacs-inspect :default [obj]
  (let [fields (. (class obj) getDeclaredFields)
        names (map (memfn getName) fields)
        get (fn [f]
              (try (.setAccessible f true)
                   (catch java.lang.SecurityException e))
              (try (.get f obj)
                   (catch java.lang.IllegalAccessException e
                     "Access denied.")))
        vals (map get fields)]
    (concat
     `("Type: " (:value ~(class obj)) (:newline)
       "Value: " (:value ~obj) (:newline)
       "---" (:newline)
       "Fields: " (:newline))
     (mapcat
      (fn [name val]
        `(~(str "  " name ": ") (:value ~val) (:newline))) names vals))))

(defmethod emacs-inspect :class [#^Class obj]
  (let [meths (. obj getMethods)
        fields (. obj getFields)]
    (concat
     `("Type: " (:value ~(class obj)) (:newline)
       "---" (:newline)
       "Fields: " (:newline))
     (mapcat (fn [f]
               `("  " (:value ~f) (:newline))) fields)
     '("---" (:newline)
       "Methods: " (:newline))
     (mapcat (fn [m]
               `("  " (:value ~m) (:newline))) meths))))

(defmethod emacs-inspect :aref [#^clojure.lang.ARef obj]
  `("Type: " (:value ~(class obj)) (:newline)
    "Value: " (:value ~(deref obj)) (:newline)))

(defn ns-refers-by-ns [#^clojure.lang.Namespace ns]
  (group-by (fn [#^clojure.lang.Var v] (. v ns))
            (map val (ns-refers ns))))

(defmethod emacs-inspect :namespace [#^clojure.lang.Namespace obj]
  (concat
   (label-value-line*
    ("Class" (class obj))
    ("Count" (count (ns-map obj))))
   '("---" (:newline)
     "Refer from: " (:newline))
   (mapcat (fn [[ns refers]]
             `("  "(:value ~ns) " = " (:value ~refers) (:newline)))
           (ns-refers-by-ns obj))
   (label-value-line*
    ("Imports" (ns-imports obj))
    ("Interns" (ns-interns obj)))))


(defn inspector-content [inspector specs]
  (logging/trace "inspector-content %s" (vec specs))
  (letfn [(spec-seq
           [output parts actions seq]
           (let [[f & args] seq]
             (cond
              (= f :newline) [(conj output (str \newline)) parts actions]
              (= f :value)
              (let [[obj & [str]] args]
                (let [[s parts] (value-part obj str parts)]
                  [(conj output s) parts actions]))
              (= f :action)
              (let [[label lambda & options] args
                    {:keys [refresh?]} (apply hash-map options)]
                (let [[s actions] (action-part label lambda refresh? actions)]
                  [(conj output s) parts actions])))))
          (spec-value
           [[output parts actions] val]
           (cond
            (string? val) [(conj output val) parts actions]
            (seq? val) (spec-seq output parts actions val)))]
    (reduce spec-value [[] [] []] specs)))

;; Works for infinite sequences, but it lies about length. Luckily, emacs
;; doesn't care.
(defn content-range [inspector start end]
  (let [lst (:inspectee-content @inspector)
        amount-wanted (- end start)
        shifted (drop start lst)
        taken (seq (take amount-wanted shifted))
        amount-taken (count taken)]
    (if (< amount-taken amount-wanted)
      (list taken (+ amount-taken start) start end)
      ;; There's always more until we know there isn't
      (list taken (+ end 500) start end))))

(defn inspect-object [inspector object]
  (swap!
   inspector
   (fn [current]
     (let [[output parts actions] (inspector-content
                                   current (emacs-inspect object))]
       (merge
        current
        {:inspectee object
         :inspector-stack (conj
                           (:inspector-stack current) object)
         :inspectee-content output
         :inspectee-parts parts
         :inspectee-actions actions
         :inspector-history (if (filter #(identical? object %)
                                        (:inspector-history current))
                              (:inspector-history current)
                              (conj (:inspector-history current) object))}))))
  inspector)

(defn pop-inspectee [inspector]
  (swap! inspector update-in :inspector-stack pop)
  (when-let [object (first (:inspector-stack @inspector))]
    (inspect-object inspector object)))


(defn next-inspectee [inspector]
  (let [pos (position
             #{(:inspectee @inspector)} (:inspector-history @inspector))]
    (when-not (= (inc pos) (count (:inspector-history @inspector)))
      (inspect-object (get (:inspector-history @inspector) (inc pos))))))

(defn reinspect [inspector]
  (inspect-object inspector (:inspectee @inspector)))

(defn describe-inspectee [inspector]
  (str (:inspectee @inspector)))

(defn content [inspector]
  (:inspector-content @inspector))

(defn nth-part [inspector index]
  (get (:inspectee-parts @inspector) index))

(defn call-nth-action [inspector index args]
  (let [[fn refresh?] (get (:inspectee-actions @inspector) index)]
    (apply fn args)
    (when refresh?
      (inspect-object inspector))))


;; (defn inspect-in-emacs [what]
;;   (letfn [(send-it []
;;                    (reset-inspector)
;;                    (send-to-emacs `(:inspect ~(inspect-object what))))]
;;     (cond
;;       *current-connection* (send-it)
;;       (comment (first @*connections*))
;;       ;; TODO: take a second look at this, will probably need garbage collection on *connections*
;;       (comment
;;         (binding [*current-connection* (first @*connections*)]
;;           (send-it))))))
