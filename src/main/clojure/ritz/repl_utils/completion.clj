(ns ritz.repl-utils.completion
  "Completion functions"
  (:require
   [ritz.repl-utils.helpers :as helpers]
   [ritz.repl-utils.java :as java]
   [ritz.repl-utils.class-browse :as class-browse]))

(defn ^String largest-common-prefix
  "Returns the largest common prefix of two strings."
  [^String a ^String b]
  (apply str (take-while identity (map #(when (= %1 %2) %1) a b))))

(defn ns-name-list
  "Returns a list of potential namespaces for a given namespace"
  [ns]
  (map name (concat (keys (ns-aliases (ns-name ns))) (map ns-name (all-ns)))))

(defn public-var-name-list
  "Returns a list of public var names for a given namespace"
  [ns]
  (map name (keys (ns-publics ns))))

(defn var-name-list
  "Returns a list of all var names for a given
   namespace"
  [ns]
  (for [[sym v] (ns-map ns)
        :when (var? v)]
    (name sym)))

(defn class-name-list
  "Returns a list of class names for a given namespace"
  [ns]
  (map name (keys (ns-imports ns))))

(defn method-name-list
  "Returns a list of method names (with leading dot) for a given namespace"
  [ns]
  (->>
   (ns-imports ns)
   (vals)
   (mapcat java/instance-methods)
   (map java/member-name)
   (set)
   (map #(str "." %))))

(defn static-member-name-list
  "Returns a list of static members for a given class"
  [^Class class]
  (map java/member-name
       (concat (java/static-methods class) (java/static-fields class))))

(defn classes-on-path
  "Returns a list of Java class and Clojure package names found on the current
  classpath. To minimize noise, list is nil unless a '.' is present in the
  search string, and nested classes are only shown if a '$' is present."
  [symbol-string]
  (when (.contains symbol-string ".")
    (if (.contains symbol-string "$")
      @class-browse/nested-classes
      @class-browse/top-level-classes)))

(defn resolve-class
  "Attempts to resolve a symbol into a java Class. Returns nil on failure."
  [sym]
  (try
    (let [res (resolve sym)]
      (when (class? res)
        res))
    (catch Throwable t)))

(defn resolve-ns [sym ns]
  (or (find-ns sym)
      (get (ns-aliases ns) sym)))

(defn- maybe-alias
  [sym ns]
  (or
   (resolve-ns sym (the-ns ns))
   (the-ns ns)))

(defn candidate-list [symbol-ns ns]
  (if symbol-ns
    (map #(str symbol-ns "/" %)
         (if-let [class (resolve-class symbol-ns)]
           (static-member-name-list class)
           (public-var-name-list (maybe-alias symbol-ns ns))))
    (concat (var-name-list ns)
            (when-not symbol-ns
              (ns-name-list ns))
            (class-name-list ns)
            (method-name-list  ns))))

(defn simple-completion
  [symbol-name ns]
  (try
    (let [[sym-ns sym-name] (helpers/symbol-name-parts (symbol symbol-name))
          matches (->>
                   (concat (candidate-list
                            (when sym-ns (symbol sym-ns))
                            (ns-name (the-ns ns)))
                           (classes-on-path symbol-name))
                   (filter #(.startsWith ^String % symbol-name))
                   (sort)
                   (seq))]
      [matches
       (if matches
         (reduce largest-common-prefix matches)
         symbol-name)])
    (catch java.lang.Throwable t
      (list nil symbol-name))))
