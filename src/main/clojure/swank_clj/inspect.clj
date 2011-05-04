(ns swank-clj.inspect
  "The inspector is an atom, containing parts"
  (:require
   [swank-clj.swank.utils :as utils]
   [swank-clj.logging :as logging]
   [clojure.string :as string]))

(defn reset-inspector [inspector]
  (swap! inspector {:part-index (atom 0)}))

(defmulti value-as-string
  (fn [obj] (type obj)))

(defmethod value-as-string :default
  [obj] (pr-str obj))

(def *lazy-seq-items-sample-size* 10)

(defmethod value-as-string clojure.lang.LazySeq
  [obj]
  (let [sample (take *lazy-seq-items-sample-size* obj)]
    (str "#<clojure.lang.LazySeq ("
         (string/join " " (map value-as-string sample))
         (when (= *lazy-seq-items-sample-size* (count sample))
           " ...")
         ")>")))

(def *sequential-items-sample-size* 10)

(defmethod value-as-string clojure.lang.APersistentVector
  [obj]
  (let [sample (take *sequential-items-sample-size* obj)]
    (str "["
         (string/join " " (map value-as-string sample))
         (when (= *sequential-items-sample-size* (count sample))
           " ...")
         "]")))

(defmethod value-as-string clojure.lang.APersistentSet
  [obj]
  (let [sample (take *sequential-items-sample-size* obj)]
    (str "#{"
         (string/join " " (map value-as-string sample))
         (when (= *sequential-items-sample-size* (count sample))
           " ...")
         "}")))

(defmethod value-as-string clojure.lang.APersistentMap
  [obj]
  (let [sample (apply concat (take *sequential-items-sample-size* obj))]
    (str "{"
         (string/join " " (map value-as-string sample))
         (when (= (* 2 *sequential-items-sample-size*) (count sample))
           " ...")
         "}")))

(defmethod value-as-string clojure.lang.Sequential
  [obj]
  (let [sample (take *sequential-items-sample-size* obj)]
    (str "("
         (string/join " " (map value-as-string sample))
         (when (= *lazy-seq-items-sample-size* (count sample))
           " ...")
         ")")))

(defmethod value-as-string clojure.lang.Cons
  [obj]
  (let [sample (take *lazy-seq-items-sample-size* obj)]
    (str "(" (value-as-string (.first obj))
         (when-not (= clojure.lang.PersistentList$EmptyList (class (.more obj)))
           (str " " (value-as-string (.more obj))))
         ")")))

(defn inspectee-title [inspector]
  (value-as-string (:inspectee @inspector)))

(defn print-part-to-string [value]
  (value-as-string value)
  ;; (let [s (value-as-string value)
  ;;       pos (utils/position #{value} @*inspector-history*)]
  ;;   (if pos
  ;;     (str "#" pos "=" s)
  ;;     s))
  )

(defn inspectee-index [inspector]
  (:end-index @inspector 0))

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


(defn inspector-content [specs]
  (logging/trace "inspector-content" specs)
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

(defmulti object-content-range
  "This is to avoid passing lazy sequences over jdwp."
  (fn [object ^Integer start ^Integer end] (type object)))

;; (defn object-content-range-invoker
;;   "Provide a typed invoker for object-content-range"
;;   [object ^Integer start ^Integer end]
;;   (object-content-range object start end))

;; Works for infinite sequences, but it lies about length. Luckily, emacs
;; doesn't care.
(defmethod object-content-range :default
  [object start end]
  (logging/trace "object-content-range %s %s %s" object start end)
  (let [amount-wanted (- end start)
        lst (emacs-inspect object)
        shifted (drop start lst)
        taken (seq (take amount-wanted shifted))
        amount-taken (count taken)
        content (inspector-content taken)]
    (if (< amount-taken amount-wanted)
      (list (seq (first content)) (+ amount-taken start) start end)
      ;; There's always more until we know there isn't
      (list (seq (first content)) (+ end 500) start end))))

(defn content-range [inspector start end]
  (swap! inspector update-in [:end-index] (fn [x] (max (or x 0) end)))
  (object-content-range (:inspectee @inspector) start end))

(defmulti object-nth-part
  (fn [object n max-index] (type object)))

(defmethod object-nth-part :default
  [object n max-index]
  (let [[content parts actions] (inspector-content
                                 (take max-index (emacs-inspect object)))]
    (assert (< n (count parts)))
    (nth parts n)))

(defn nth-part
  [inspector index]
  (let [{:keys [inspectee end-index] :or {end-index 0}} @inspector]
    (object-nth-part inspectee index end-index)))

(defmulti object-call-nth-action
  (fn [object n max-index args] (type object)))

(defmethod object-call-nth-action :default
  [object n max-index args]
  (let [[content parts actions] (inspector-content
                                 (take max-index (emacs-inspect object)))]
    (assert (< n (count actions)))
    (let [[fn refresh?] (nth actions n)]
      (apply fn (eval (vec args)))
      refresh?)))

(defn call-nth-action [inspector index args]
  (let [{:keys [inspectee end-index] :or {end-index 0}} @inspector]
    (object-call-nth-action inspectee index end-index args)))

(defn inspect-object [inspector object]
  (logging/trace "Inspecting %s" object)
  (swap!
   inspector
   (fn [current]
     (merge
      current
      {:inspectee object
       :inspector-stack (conj (:inspector-stack current) object)
       :inspector-history (if (filter #(identical? object %)
                                      (:inspector-history current))
                            (:inspector-history current)
                            (conj (:inspector-history current) object))})))
  inspector)

(defn display-values
  ([inspector]
     (display-values inspector 0 (:end-index @inspector 500)))
  ([inspector start end]
     (logging/trace "display-values")
     [(inspectee-title inspector)
      (inspectee-index inspector)
      (content-range inspector start end)]))

(defn pop-inspectee [inspector]
  (swap! inspector update-in :inspector-stack pop)
  (when-let [object (first (:inspector-stack @inspector))]
    (inspect-object inspector object)))


(defn next-inspectee [inspector]
  (let [pos (utils/position
             #{(:inspectee @inspector)} (:inspector-history @inspector))]
    (when-not (= (inc pos) (count (:inspector-history @inspector)))
      (inspect-object (get (:inspector-history @inspector) (inc pos))))))

(defn reinspect [inspector]
  (inspect-object inspector (:inspectee @inspector)))

(defn describe-inspectee [inspector]
  (str (:inspectee @inspector)))

(defn content [inspector]
  (:inspector-content @inspector))
