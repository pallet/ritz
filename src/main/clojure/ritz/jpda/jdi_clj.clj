(ns ritz.jpda.jdi-clj
  "Clojure execution over jdi. Uses a context map to to represent the vm."
  (:refer-clojure :exclude [eval assoc var-get clojure-version])
  (:require
   [ritz.executor :as executor]
   [ritz.jpda.jdi :as jdi]
   [ritz.logging :as logging]
   [clojure.string :as string])
  (:import
   com.sun.jdi.event.BreakpointEvent
   com.sun.jdi.event.ExceptionEvent
   com.sun.jdi.event.StepEvent
   com.sun.jdi.request.ExceptionRequest
   com.sun.jdi.event.VMStartEvent
   com.sun.jdi.event.VMDeathEvent
   com.sun.jdi.VirtualMachine
   com.sun.jdi.ObjectReference
   (com.sun.jdi
    Value BooleanValue ByteValue CharValue DoubleValue FloatValue IntegerValue
    LongValue ShortValue)))


;;; Remote evaluation
(defn ^Value eval-to-value
  "Evaluate the specified form on the given thread. Returns a Value."
  [context thread options form]
  {:pre [thread options
         (:RT context) (:read-string context) (:Compiler context)
         (:eval context)]}
  (logging/trace "debug/eval-to-value %s" form)
  (->>
   (str form)
   (jdi/mirror-of (:vm context))
   jdi/arg-list
   (jdi/invoke-method thread options (:RT context) (:read-string context))
   jdi/arg-list
   (jdi/invoke-method thread options (:Compiler context) (:eval context))))

(defn ^String eval-to-string
  "Evaluate a form, which must result in a string value. The string
   is return as a local value."
  [context thread options form]
  (logging/trace "debug/eval-to-string %s" form)
  (when-let [rv (eval-to-value context thread options form)]
    (jdi/string-value rv)))

(defn eval
  "eval a form on the remote machine and return a local value.  Relies on the
   value being readable. If it is unreadable, then a string as output by pr-str
   on the remote machine is returned."
  [context thread options form]
  (let [s (eval-to-string context thread options `(pr-str ~form))]
    (try
      (when s
        (read-string s))
      (catch com.sun.jdi.InvocationException e
        (logging/trace
         "Unexpected exception %s %s" e)
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
      (eval-to-value context thread jdi/invoke-single-threaded form))))

(defn control-eval
  "Eval an expression on the control thread. This uses single-threaded
   invocation, since the control thread is suspended in isolation."
  [context form]
  {:pre [(map? context) (:control-thread context)]}
  (let [thread (:control-thread context)]
    (locking thread
      (eval context thread jdi/invoke-single-threaded form))))

(defmacro with-caught-jdi-exceptions
  [& body]
  `(try
     ~@body
     (catch com.sun.jdi.InternalException e#
       (logging/trace "w-c-j-e: Caught %s" (.getMessage e#)))))

(defn exception-message
  "Returns a local string containing the remote exception's message."
  [context event]
  (with-caught-jdi-exceptions
    (jdi/exception-message context event)))

;;; Mirroring of values
(defn remote-str
  "Create a remote string"
  [context s]
  (jdi/mirror-of (:vm context) s))

(defprotocol RemoteObject
  "Protocol for obtaining a remote object reference"
  (remote-object [value context thread]))

(let [st jdi/invoke-single-threaded]
  (extend-protocol RemoteObject
    ObjectReference (remote-object [o _ _] o)
    BooleanValue (remote-object
                  [o context thread]
                  (eval-to-value context thread (list 'boolean (.value o)) st))
    ByteValue (remote-object
                  [o context thread]
                  (eval-to-value context thread (list 'byte (.value o)) st))
    CharValue (remote-object
                  [o context thread]
                  (eval-to-value context thread (list 'char (.value o)) st))
    DoubleValue (remote-object
                 [o context thread]
                 (eval-to-value context thread (list 'double (.value o)) st))
    FloatValue (remote-object
                  [o context thread]
                  (eval-to-value context thread (list 'float (.value o)) st))
    IntegerValue (remote-object
                  [o context thread]
                  (eval-to-value context thread (list 'int (.value o)) st))
    LongValue (remote-object
               [o context thread]
               (eval-to-value context thread (list 'long (.value o)) st))
    ShortValue (remote-object
                [o context thread]
                (eval-to-value context thread (list 'short (.value o)) st))))

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
  (let [object (jdi/invoke-method
                thread options
                (:RT context) (:var context)
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
     (when-let [var (jdi/invoke-method
                     thread options
                     (:RT context) (:var context)
                     [(jdi/mirror-of (:vm context) ns)
                      (jdi/mirror-of (:vm context) name)])]
       (when-let [f (jdi/invoke-method thread options var (:deref context) [])]
         [f (first
             (jdi/methods (.referenceType f) "invoke" (invoke-signature n)))])))
  ([context thread options ns name]
     {:pre [thread]}
     (when-let [var (jdi/invoke-method
                     thread options
                     (:RT context) (:var context)
                     [(jdi/mirror-of (:vm context) ns)
                      (jdi/mirror-of (:vm context) name)])]
       (when-let [f (jdi/invoke-method thread options var (:deref context) [])]
         [f (remove
             #(or (.isAbstract %) (.isObsolete %))
             (concat
              (jdi/methods (.referenceType f) "invoke")
              (jdi/methods (.referenceType f) "invokePrim")))]))))

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
  (logging/trace "remote-call %s %s" (pr-str sym) args)
  (let [[object method] (clojure-fn
                         context thread options
                         (namespace sym) (name sym) (count args))]
    (logging/trace "clojure fn is  %s %s" object method)
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
  (jdi/invoke-method thread options value (:get context) []))

(defn assoc
  [context thread options & values]
  (jdi/invoke-method thread options values (:RT context) (:assoc context)))

(defn swap-root
  [context thread options var value]
  (jdi/invoke-method thread options var (:swap-root context) [value]))

(defn pr-str-arg
  "Read the value of the given arg"
  [context thread options arg]
  (-> (invoke-clojure-fn context thread options "clojure.core" "pr-str" arg)
      jdi/string-value))

(defn read-arg
  "Read the value of the given arg"
  [context thread arg]
  (-> (pr-str-arg context thread jdi/invoke-single-threaded arg)
      read-string))

(defn clojure-version
  [context thread]
  (eval context thread jdi/invoke-single-threaded
        `(clojure.core/clojure-version)))
