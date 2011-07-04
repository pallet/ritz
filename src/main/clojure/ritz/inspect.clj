(ns ritz.inspect
  "The inspector is an atom, containing parts"
  (:require
   [ritz.swank.utils :as utils]
   [ritz.logging :as logging]
   [clojure.string :as string]))

(defn reset-inspector [inspector]
  (reset! inspector {})
  inspector)

(defmulti value-as-string
  (fn [context obj] (type obj)))

(defmethod value-as-string :default
  [context obj] (pr-str obj))

(def ^{:dynamic true} *lazy-seq-items-sample-size* 10)

(defmethod value-as-string clojure.lang.LazySeq
  [context obj]
  (let [sample (take *lazy-seq-items-sample-size* obj)]
    (str "#<clojure.lang.LazySeq ("
         (string/join " " (map #(value-as-string context %) sample))
         (when (= *lazy-seq-items-sample-size* (count sample))
           " ...")
         ")>")))

(def ^{:dynamic true} *sequential-items-sample-size* 10)

(defmethod value-as-string clojure.lang.APersistentVector
  [context obj]
  (let [sample (take *sequential-items-sample-size* obj)]
    (str "["
         (string/join " " (map #(value-as-string context %) sample))
         (when (= *sequential-items-sample-size* (count sample))
           " ...")
         "]")))

(defmethod value-as-string clojure.lang.APersistentSet
  [context obj]
  (let [sample (take *sequential-items-sample-size* obj)]
    (str "#{"
         (string/join " " (map #(value-as-string context %) sample))
         (when (= *sequential-items-sample-size* (count sample))
           " ...")
         "}")))

(defmethod value-as-string clojure.lang.APersistentMap
  [context obj]
  (let [sample (apply concat (take *sequential-items-sample-size* obj))]
    (str "{"
         (string/join " " (map #(value-as-string context %) sample))
         (when (= (* 2 *sequential-items-sample-size*) (count sample))
           " ...")
         "}")))

(defmethod value-as-string clojure.lang.Sequential
  [context obj]
  (let [sample (take *sequential-items-sample-size* obj)]
    (str "("
         (string/join " " (map #(value-as-string context %) sample))
         (when (= *lazy-seq-items-sample-size* (count sample))
           " ...")
         ")")))

(defmethod value-as-string clojure.lang.Cons
  [context obj]
  (let [sample (take *lazy-seq-items-sample-size* obj)]
    (str "(" (value-as-string (.first obj))
         (when-not (= clojure.lang.PersistentList$EmptyList (class (.more obj)))
           (str " " (value-as-string (.more obj))))
         ")")))

(defn inspectee-title [context inspector]
  (value-as-string context (:inspectee @inspector)))

(defn print-part-to-string [context value]
  (value-as-string context value))

(defn inspectee-index [inspector]
  (:end-index @inspector 0))

(defn value-part [context obj s parts]
  [(list :value (or s (print-part-to-string context obj)) (count parts))
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
    (logging/trace "emacs-inspect %s" (type obj))
    (cond
     (map? obj) :map
     (vector? obj) :vector
     (var? obj) :var
     (string? obj) :string
     (seq? obj) :seq
     (instance? Class obj) :class
     (instance? clojure.lang.Namespace obj) :namespace
     (instance? clojure.lang.ARef obj) :aref
     (and obj (.isArray (class obj))) :array
     :else (class obj))))

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

(defmethod emacs-inspect :var [^clojure.lang.Var obj]
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

(defmethod emacs-inspect :class [^Class obj]
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

(defmethod emacs-inspect :aref [^clojure.lang.ARef obj]
  `("Type: " (:value ~(class obj)) (:newline)
    "Value: " (:value ~(deref obj)) (:newline)))

(defn ns-refers-by-ns [^clojure.lang.Namespace ns]
  (group-by (fn [^clojure.lang.Var v] (. v ns))
            (map val (ns-refers ns))))

(defmethod emacs-inspect :namespace [^clojure.lang.Namespace obj]
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


(defn inspector-content [context specs]
  (logging/trace "inspector-content" specs)
  (letfn [(spec-seq
           [output parts actions seq]
           (let [[f & args] seq]
             (cond
              (= f :newline) [(conj output (str \newline)) parts actions]
              (= f :value)
              (let [[obj & [str]] args]
                (let [[s parts] (value-part context obj str parts)]
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

(defmulti object-content-range
  "This is to avoid passing lazy sequences over jdwp."
  (fn [context object ^Integer start ^Integer end] (type object)))

;; (defn object-content-range-invoker
;;   "Provide a typed invoker for object-content-range"
;;   [object ^Integer start ^Integer end]
;;   (object-content-range object start end))

;; Works for infinite sequences, but it lies about length. Luckily, emacs
;; doesn't care.
(defmethod object-content-range :default
  [context object start end]
  (logging/trace "object-content-range %s %s %s" object start end)
  (let [amount-wanted (- end start)
        lst (emacs-inspect object)
        shifted (drop start lst)
        taken (seq (take amount-wanted shifted))
        amount-taken (count taken)
        content (inspector-content context taken)]
    (if (< amount-taken amount-wanted)
      (list (seq (first content)) (+ amount-taken start) start end)
      ;; There's always more until we know there isn't
      (list (seq (first content)) (+ end 500) start end))))

(defn content-range [context inspector start end]
  (swap! inspector update-in [:end-index] (fn [x] (max (or x 500) end)))
  (object-content-range context (:inspectee @inspector) start end))

(defmulti object-nth-part
  (fn [context object n max-index] (type object)))

(defmethod object-nth-part :default
  [context object n max-index]
  (logging/trace "object-nth-part %s %s" n max-index)
  (let [[content parts actions] (inspector-content
                                 context
                                 (take max-index (emacs-inspect object)))]
    (assert (< n (count parts)))
    (nth parts n)))

(defn nth-part
  [context inspector index]
  (let [{:keys [inspectee end-index] :or {end-index 500}} @inspector]
    (object-nth-part context inspectee index end-index)))

(defmulti object-call-nth-action
  (fn [context object n max-index args] (type object)))

(defmethod object-call-nth-action :default
  [context object n max-index args]
  (let [[content parts actions] (inspector-content
                                 context
                                 (take max-index (emacs-inspect object)))]
    (assert (< n (count actions)))
    (let [[fn refresh?] (nth actions n)]
      (apply fn (eval (vec args)))
      refresh?)))

(defn call-nth-action
  [context inspector index args]
  (let [{:keys [inspectee end-index] :or {end-index 0}} @inspector]
    (object-call-nth-action context inspectee index end-index args)))

(defn inspect-object [inspector object]
  (logging/trace "Inspecting %s" object)
  (swap!
   inspector
   (fn [current]
     (->
      current
      (assoc :inspectee object)
      (update-in [:inspector-stack]
                 (fn [stack]
                   (if (identical? object (first stack))
                     stack
                     (conj stack object))))
      (update-in [:inspector-history]
                 (fn [history]
                   (if (seq (filter #(identical? object %) history))
                     history
                     (conj history object)))))))
  inspector)

(defn display-values
  ([context inspector]
     (display-values context inspector 0 (:end-index @inspector 500)))
  ([context inspector start end]
     (logging/trace "display-values")
     [(inspectee-title context inspector)
      (inspectee-index inspector)
      (content-range context inspector start end)]))

(defn pop-inspectee [inspector]
  (logging/trace "pop-inspectee %s" (pr-str (:inspector-stack @inspector)))
  (if-let [object (first
                   (:inspector-stack
                    (swap! inspector update-in [:inspector-stack] pop)))]
    (inspect-object inspector object)
    (reset-inspector inspector)))


(defn next-inspectee [inspector]
  (let [pos (utils/position
             #{(:inspectee @inspector)} (:inspector-history @inspector))]
    (when-not (= (inc pos) (count (:inspector-history @inspector)))
      (inspect-object (get (:inspector-history @inspector) (inc pos))))))

(defn reinspect [inspector]
  (inspect-object inspector (:inspectee @inspector)))

(defn describe-inspectee [inspector]
  (str (:inspectee @inspector)))

(defn inspecting? [inspector]
  (boolean (:inspectee @inspector)))
