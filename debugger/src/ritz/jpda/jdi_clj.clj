(ns ritz.jpda.jdi-clj
  "Clojure execution over jdi. Uses a context map to to represent the vm."
  (:refer-clojure :exclude [eval assoc var-get clojure-version])
  (:require
   [ritz.jpda.jdi :as jdi]
   [ritz.logging :as logging]
   [clojure.string :as string])
  (:use
   [clojure.stacktrace :only [print-cause-trace]]
   [ritz.jpda.jdi :only [with-remote-value]])
  (:import
   (com.sun.jdi
    Value BooleanValue ByteValue CharValue DoubleValue FloatValue IntegerValue
    LongValue ShortValue ThreadReference ObjectReference ClassLoaderReference
    ReferenceType StringReference VirtualMachine Method)
   (com.sun.jdi.event
    BreakpointEvent ExceptionEvent StepEvent VMStartEvent VMDeathEvent)
   com.sun.jdi.request.ExceptionRequest
   java.lang.ref.SoftReference))


;;; # Introspection of Clojure Runtimes in the VM

;;; ## Runtime Classes
;;; These are just the classes used by the debugger, and is not an exhaustive
;;; list.
(def ^{:doc "A map of the names of the clojure runtime classes."}
  clojure-runtime-class-names
  {:RT "clojure.lang.RT"
   :Compiler "clojure.lang.Compiler"
   :Var "clojure.lang.Var"
   :Deref "clojure.lang.IDeref"})

(def ^{:doc "A map of the names of the java runtime classes."}
  java-runtime-class-names
  {:Throwable "java.lang.Throwable"})

(defn clojure-runtime-classes
  "Lookup the clojure runtime classes. Returns a map with a sequence of
instances of each runtime class. There can be more than one instance of
each class if there are multiple runtimes running in the vm."
  [vm]
  (zipmap (keys clojure-runtime-class-names)
          (map #(jdi/classes vm %) (vals clojure-runtime-class-names))))

(defn java-runtime-classes
  "Lookup the java runtime classes. Returns a map with an instance of each
runtime class. There can only be one instance of each class."
  [vm]
  (zipmap (keys java-runtime-class-names)
          (map (comp first #(jdi/classes vm %))
               (vals java-runtime-class-names))))

;;; ## Runtime Methods
(def ^{:private true
       :doc "Signature of the var overload that we wish to use."}
  var-signature "(Ljava/lang/String;Ljava/lang/String;)Lclojure/lang/Var;")

(defn clojure-runtime-methods
  "Given a map of the classes of a single clojure runtime in a vm, returns a map
with some useful methods on those classes"
  [{:keys [RT Compiler Deref Var] :as classes}]
  {:read-string (first (jdi/methods RT "readString"))
   :var (first (jdi/methods RT "var" var-signature))
   :assoc (first (jdi/methods RT "assoc"))
   :eval (first (jdi/methods Compiler "eval"))
   :get (first (jdi/methods Var "get"))
   :deref (first (jdi/methods Deref "deref"))
   :swap-root (first (jdi/methods Var "swapRoot"))
   :exception-message (first (jdi/methods (:Throwable classes) "getMessage"))})

;;; ## Default Runtime Lookup

(defn default-clojure-runtime
  "Lookup clojure runtime. This only works if there is only one runtime in the
  vm."
  [vm]
  (let [classes (clojure-runtime-classes vm)
        classes (zipmap (keys classes) (map first (vals classes)))
        classes (merge classes (java-runtime-classes vm))]
    (when (:RT classes)
      (merge classes (clojure-runtime-methods classes)))))

(defn vm-rt
  "Add the default clojure runtime to the context."
  [context]
  (logging/trace "vm-rt")
  (if-let [runtime (default-clojure-runtime (:vm context))]
    (do
      (logging/trace "vm-rt: Clojure runtime found")
      (merge context runtime))
    (do
      (logging/trace "vm-rt: Clojure runtime not found")
      (throw (Exception. "No clojure runtime found in vm")))))

;;; ## Thread Specific Runtime Lookup
(defn clojure-runtime-for-thread
  "Return a reference to the clojure.lang.RT class for the given thread and the
  threads current classloader. The runtime classes are found by comparing the
  thread's classloader and it's parent chain to the classloader of each of every
  runtime defined in the virtual machine."
  [^ThreadReference thread ^ClassLoaderReference location-cl]
  (let [get-parent-method (first
                           (jdi/methods
                            (.referenceType location-cl) "getParent"))
        get-parent #(jdi/invoke-method thread {} % get-parent-method nil)
        location-cls (set
                      (take-while identity (iterate get-parent location-cl)))
        selector (fn [classes]
                   (->> classes
                        (filter #(location-cls (.classLoader ^ReferenceType %)))
                        first))
        classes (clojure-runtime-classes (jdi/virtual-machine thread))
        classes (zipmap (keys classes) (map selector (vals classes)))
        classes (merge
                 classes
                 (java-runtime-classes (jdi/virtual-machine thread)))]
    (assert (:RT classes))
    (merge classes (clojure-runtime-methods classes))))


;;; ## Runtime Accessor

;;; The control thread is started on machine launch, so uses the default
;;; clojure runtime, which is cached in the context.

;;; The runtime for other threads is cached, so we don't need to continually
;;; look it up.

;;; TODO, ensure the WeakHashMap and SoftReference actually allow cache
;;; expiry. I suspect it doesn't, since it is the map within the SoftReference
;;; that holds the runtime classes, which could reference the classloader in the
;;; key. On the other hand, the threads usually run in a classloader that is a
;;; child of the classloader of the runtime classes, so this could work.

(def ^{:private true
       :tag java.util.WeakHashMap}
  clojure-runtime-cache (java.util.WeakHashMap.))

(defn clojure-runtime
  "Return a map of classes and methods for the runtime in the debug vm used by
the specified thread."
  [context thread]
  (if (= thread (:control-thread context))
    context
    (let [cl (jdi/thread-classloader thread)]
      (if-let [^SoftReference runtime (locking clojure-runtime-cache
                                        (.get clojure-runtime-cache cl))]
        (.get runtime)
        (let [runtime (clojure-runtime-for-thread thread cl)]
          (locking clojure-runtime-cache
            (.put clojure-runtime-cache cl (SoftReference. runtime)))
          runtime)))))

;;; # Remote evaluation
(defn ^Value eval-to-value
  "Evaluate the specified form on the given thread. Returns a Value."
  [context thread options form]
  {:pre [thread options
         (:RT context) (:read-string context) (:Compiler context)
         (:eval context)]}
  (logging/trace "jdi-clj/eval-to-value %s %s" form options)
  (let [rt (clojure-runtime context thread)]
    (assert rt)
    (assert (:RT rt))
    (assert (:read-string rt))
    (assert (:Compiler rt))
    (assert (:eval rt))
    (with-remote-value
      [^StringReference f (jdi/mirror-of-string (:vm context) (str form))
       rv (jdi/invoke-method
           thread options (:RT rt) (:read-string rt) (jdi/arg-list f))]
      (jdi/invoke-method
       thread options (:Compiler rt) (:eval rt) (jdi/arg-list rv)))))

(defn ^String eval-to-string
  "Evaluate a form, which must result in a string value. The string
   is return as a local value."
  [context thread options form]
  (logging/trace "jdi-clj/eval-to-string %s %s" form options)
  (when-let [rv (eval-to-value context thread options form)]
    (jdi/string-value rv)))

(defn eval
  "eval a form on the remote machine and return a local value.  Relies on the
   value being readable. If it is unreadable, then a string as output by pr-str
   on the remote machine is returned."
  [context thread options form]
  (logging/trace "jdi-clj/eval %s %s" form options)
  (let [s (eval-to-string
           context thread options
           `(try (pr-str ~form)
                 (catch Exception e#
                   (.println System/err e#)
                   (.printStackTrace e#))))]
    (try
      (when s
        (read-string s))
      (catch com.sun.jdi.InvocationException e
        (logging/trace
         "Unexpected exception %s %s" e)
        (print-cause-trace e)
        (throw e))
      (catch Exception e
        (if (and (.getMessage e) (re-find #"Unreadable form" (.getMessage e)))
          s
          (throw e))))))

;;; eval on control thread
;; NB. control thread is for use outside of the event handlers
(defn control-eval-to-value
  [context form]
  {:pre [(map? context) (:control-thread context)]}
  (let [thread (:control-thread context)]
    (locking thread
      (eval-to-value
       context thread {} form))))

(defn control-eval
  "Eval an expression on the control thread. This uses single-threaded
   invocation, since the control thread is suspended in isolation."
  ([context form options]
     {:pre [(map? context) (:control-thread context)]}
     (let [thread (:control-thread context)]
       (locking thread
         (eval context thread options form))))
  ([context form]
     (control-eval context form {})))

(defmacro with-caught-jdi-exceptions
  [& body]
  `(try
     ~@body
     (catch com.sun.jdi.InternalException e#
       (logging/trace "w-c-j-e: Caught %s" e#)
       (logging/trace "w-c-j-e: Caught %s" (.getMessage e#)))))

(defn exception-message
  "Returns a local string containing the remote exception's message."
  [context event]
  (with-caught-jdi-exceptions
    (jdi/exception-event-message context event)))

;;; Mirroring of values
(defn ^StringReference remote-str
  "Create a remote string"
  [context s]
  (jdi/mirror-of (:vm context) s))

(defprotocol RemoteObject
  "Protocol for obtaining a remote object reference"
  (^ObjectReference remote-object [value context thread]))

(let [st {}]
  (extend-protocol RemoteObject
    ObjectReference (remote-object [o _ _] o)
    BooleanValue (remote-object
                  [o context thread]
                  (eval-to-value context thread st (list 'boolean (.value o))))
    ByteValue (remote-object
                  [o context thread]
                  (eval-to-value context thread st (list 'byte (.value o))))
    CharValue (remote-object
                  [o context thread]
                  (eval-to-value context thread st (list 'char (.value o))))
    DoubleValue (remote-object
                 [o context thread]
                 (eval-to-value context thread st (list 'double (.value o))))
    FloatValue (remote-object
                  [o context thread]
                  (eval-to-value context thread st (list 'float (.value o))))
    IntegerValue (remote-object
                  [o context thread]
                  (eval-to-value context thread st (list 'int (.value o))))
    LongValue (remote-object
               [o context thread]
               (eval-to-value context thread st (list 'long (.value o))))
    ShortValue (remote-object
                [o context thread]
                (eval-to-value context thread st (list 'short (.value o))))))

(def jni-object "Ljava/lang/Object;")

(defn invoke-signature
  "Clojure invoke signature for the specified number of arguments"
  [n]
  (str "(" (string/join (repeat n jni-object)) ")" jni-object))

(defn clojure-fn
  "Resolve a clojure function in the remote vm. Returns an ObjectReference and
   a Method for n arguments."
  [context thread options ns name n]
  {:pre [thread]}
  (logging/trace "clojure-fn %s %s %s" ns name n)
  (let [rt (clojure-runtime context thread)
        ^ObjectReference object (jdi/invoke-method
                                 thread options
                                 (:RT rt) (:var rt)
                                 [(jdi/mirror-of (:vm context) ns)
                                  (jdi/mirror-of (:vm context) name)])]
    [object (or
             (first
              (jdi/methods
               (.referenceType object) "invoke" (invoke-signature n)))
             (first
              (jdi/methods
               (.referenceType object) "invokePrim" (invoke-signature n)))) ]))

(defn clojure-fn-deref
  "Resolve a clojure function in the remote vm. Returns an ObjectReference and
   a Method for n arguments."
  ([context thread options ns name n]
     {:pre [thread]}
     (let [rt (clojure-runtime context thread)]
       (when-let [var (jdi/invoke-method
                       thread options
                       (:RT rt) (:var rt)
                       [(jdi/mirror-of (:vm context) ns)
                        (jdi/mirror-of (:vm context) name)])]
         (when-let [^ObjectReference f (jdi/invoke-method
                                        thread options var (:deref rt) [])]
           [^Method f (first
                       (jdi/methods
                        (.referenceType f) "invoke" (invoke-signature n)))]))))
  ([context thread options ns name]
     {:pre [thread]}
     (let [rt (clojure-runtime context thread)]
       (when-let [var (jdi/invoke-method
                       thread options
                       (:RT rt) (:var rt)
                       [(jdi/mirror-of (:vm context) ns)
                        (jdi/mirror-of (:vm context) name)])]
         (when-let [^ObjectReference f (jdi/invoke-method
                                        thread options var (:deref rt) [])]
           [f (remove
               #(or (.isAbstract ^Method %) (.isObsolete ^Method %))
               (concat
                (jdi/methods (.referenceType f) "invoke")
                (jdi/methods (.referenceType f) "invokePrim")))])))))

(defn invoke-clojure-fn
  "Invoke a clojure function on the specified thread with the given remote
   arguments."
  [context thread options ns name & args]
  (logging/trace "invoke-clojure-fn %s %s %s" ns name args)
  (let [[object method]
        (clojure-fn context thread options ns name (count args))]
    (jdi/invoke-method thread options object method args)))

(defn remote-call
  "Call a function using thread with the given remote arguments."
  [context thread options sym & args]
  (logging/trace "remote-call %s %s %s" (pr-str sym) args options)
  (let [[object method] (clojure-fn
                         context thread options
                         (namespace sym) (name sym) (count args))]
    (logging/trace "clojure fn is %s %s" object method)
    (jdi/invoke-method thread options object method args)))

(defn remote-thread-form
  "Returns a form to start a thread to execute the specified form."
  [form thread-options]
  `(do
      (doto (Thread. (fn [] ~form))
        ~@(when-let [thread-name (:name thread-options)]
            `[(.setName ~thread-name)])
        ~@(when-let [daemon (:daemon thread-options)]
            `[(.setDaemon (boolean ~daemon))])
        (.start))))

(defn remote-thread
  "Start a remote thread. `thread-options are:
   - :name    set the name of the thread
   - :daemon  daemonise the thread"
  [context thread options form thread-options]
  (eval-to-value
   context thread options (remote-thread-form form thread-options)))

(defn var-get
  [context thread options value]
  (let [rt (clojure-runtime context thread)]
    (jdi/invoke-method thread options value (:get rt) [])))

(defn assoc
  [context thread options & values]
  (let [rt (clojure-runtime context thread)]
    (jdi/invoke-method thread options (:RT rt) (:assoc rt) values)))

(defn swap-root
  [context thread options var value]
  (let [rt (clojure-runtime context thread)]
    (jdi/invoke-method thread options var (:swap-root rt) [value])))

(defn pr-str-arg
  "Read the value of the given arg"
  [context thread options arg]
  (-> (invoke-clojure-fn context thread options "clojure.core" "pr-str" arg)
      jdi/string-value))

(defn read-arg
  "Read the value of the given arg"
  [context thread arg]
  (-> (pr-str-arg context thread {} arg) read-string))

(defn clojure-version
  [context thread]
  (eval context thread {} `(clojure.core/clojure-version)))
