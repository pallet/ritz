(ns ritz.jpda.debug
  "Debug functions using jpda/jdi, used to implement debugger commands via jpda.
   The aim is to move all return messaging back up into ritz.commands.*
   and to accept only vm-context aguments, rather than a connection"
  (:require
   [ritz.connection :as connection]
   [ritz.executor :as executor]
   [ritz.jpda.disassemble :as disassemble]
   [ritz.jpda.jdi :as jdi]
   [ritz.jpda.jdi-clj :as jdi-clj]
   [ritz.jpda.jdi-vm :as jdi-vm]
   [ritz.jpda.swell.impl :as swell-impl]
   [ritz.logging :as logging]
   [ritz.repl-utils.find :as find]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string])
  (:import
   java.io.File
   java.net.Socket
   java.net.InetSocketAddress
   java.net.InetAddress
   com.sun.jdi.event.BreakpointEvent
   com.sun.jdi.event.ExceptionEvent
   com.sun.jdi.event.StepEvent
   com.sun.jdi.request.ExceptionRequest
   com.sun.jdi.event.VMStartEvent
   com.sun.jdi.event.VMDeathEvent
   com.sun.jdi.VirtualMachine
   com.sun.jdi.ObjectReference
   com.sun.jdi.ThreadReference
   com.sun.jdi.StackFrame
   (com.sun.jdi
    BooleanValue ByteValue CharValue DoubleValue FloatValue IntegerValue
    LongValue ShortValue StringReference)))

(def exception-suspend-policy :suspend-all)
(def breakpoint-suspend-policy :suspend-all)

(def exception-policy
  (atom {:uncaught-only true
         :class-exclusion ["java.net.URLClassLoader*"
                           "java.lang.ClassLoader*"
                           "*ClassLoader.java"]
         :system-thread-names [jdi-vm/control-thread-name
                               "REPL" "Accept loop"
                               "Connection dispatch loop :repl"]}))

;; (defn request-events
;;   "Add event requests that should be set before resuming the vm."
;;   [context]
;;   (let [req (doto (jdi/exception-request (:vm context) nil true true)
;;               (jdi/suspend-policy :suspend-event-thread)
;;               (.enable))]
;;     (swap! context assoc :exception-request req)))

(defn launch-vm
  "Launch and configure the vm for the debugee."
  [{:keys [classpath main] :as options}]
  (jdi-vm/launch-vm (or classpath (jdi-vm/current-classpath)) main))

;; (defn stop-vm
;;   [context]
;;   (when context
;;     (.exit (:vm context) 0)
;;     (reset! (:continue-handling context) nil)
;;     nil))





;;; threads

(defn threads
  "Return a sequence containing a thread reference for each remote thread."
  [context]
  (jdi/threads (:vm context)))

(defn- transform-thread-group
  [pfx [group groups threads]]
  [(->
    group
    (dissoc :id)
    (update-in [:name] (fn [s] (str pfx s))))
   (map #(transform-thread-group (str pfx "  ") %) groups)
   (map #(update-in % [:name] (fn [s] (str pfx "  " s))) threads)])

(defn thread-list
  "Provide a list of threads. The list is cached in the context
   to allow it to be retrieved by index."
  [context]
  (flatten
   (map
    #(transform-thread-group "" %)
    (jdi/thread-groups (:vm context)))))

(defn nth-thread
  [context index]
  (nth (:threads context) index nil))

(defn stop-thread
  [context thread-id]
  (when-let [thread (some
                     #(and (= thread-id (.uniqueID %)) %)
                     (threads context))]
    (.stop
     thread
     (jdi-clj/control-eval-to-value
      context `(new java.lang.Throwable "Stopped by swank")))))

(defn level-info-thread-id
  [level-info]
  (.uniqueID (:thread level-info)))


;;; stacktrace
(defn- frame-data
  "Extract data from a stack frame"
  [^StackFrame frame]
  (jdi/location-data (jdi/location frame)))

(defn- exception-stacktrace [frames]
  (logging/trace "exception-stacktrace")
  (logging/trace "exception-stacktrace: %s frames" (count frames))
  (map frame-data frames))

(defn build-backtrace
  ([^ThreadReference thread]
     (doall (exception-stacktrace (.frames thread))))
  ([^ThreadReference thread start end]
     (logging/trace "build-backtrace: %s %s" start end)
     (doall (exception-stacktrace
             (take (- end start) (drop start (.frames thread)))))))


;;; breakpoints
;;; todo - use the event manager's event list
(defn breakpoint-list
  "Update the context with a list of breakpoints. The list is cached in the
   context to allow it to be retrieveb by index."
  [context]
  (let [breakpoints (map
                     #(->
                       (jdi/breakpoint-data %1)
                       (assoc :id %2))
                     (jdi/breakpoints (:vm context))
                     (iterate inc 0))]
    (assoc-in context [:breakpoints] breakpoints)))

(defn line-breakpoint
  "Set a line breakpoint."
  [context namespace filename line]
  (let [breakpoints (jdi/line-breakpoints
                     (:vm context) breakpoint-suspend-policy
                     namespace filename line)]
    (update-in context [:breakpoints] concat breakpoints)))

(defn remove-breakpoint
  "Set a line breakpoint."
  [context event]
  (update-in context [:breakpoints]
             (fn [breakpoints]
               (when-let [request (.request event)]
                 (.disable request)
                 (.deleteEventRequest
                  (.eventRequestManager (.virtualMachine event)) request)
                 (remove #(= % request) breakpoints)))))

(defn breakpoints-for-id
  [context id]
  (let [vm (:vm context)]
    (when-let [breakpoints (:breakpoints context)]
      (when-let [{:keys [file line]} (nth breakpoints id nil)]
        (doall (filter
                #(let [location (.location %)]
                   (and (= file (.sourcePath location))
                        (= line (.lineNumber location))))
                (jdi/breakpoints vm)))))))

(defn breakpoint-kill
  [context breakpoint-id]
  (doseq [breakpoint (breakpoints-for-id context breakpoint-id)]
    (.disable breakpoint)
    (.. breakpoint (virtualMachine) (eventRequestManager)
        (deleteEventRequest breakpoint))))

(defn breakpoint-enable
  [context breakpoint-id]
  (doseq [breakpoint (breakpoints-for-id context breakpoint-id)]
    (.enable breakpoint)))

(defn breakpoint-disable
  [context breakpoint-id]
  (doseq [breakpoint (breakpoints-for-id context breakpoint-id)]
    (.disable breakpoint)))

(defn breakpoint-location
  [context breakpoint-id]
  (when-let [breakpoint (first (breakpoints-for-id context breakpoint-id))]
    (let [location (.location breakpoint)]
      (logging/trace
       "debug/breakpoint-location %s %s %s"
       (jdi/location-source-name location)
       (jdi/location-source-path location)
       (jdi/location-line-number location))
      (when-let [path (find/find-source-path
                       (jdi/location-source-path location))]
        [path {:line (jdi/location-line-number location)}]))))

;;; exception-filters
(defn exception-filter-list
  "Return a sequence of exception filters, ensuring that expressions are strings
   and not regexes."
  [connection]
  (map
   (fn [filter]
     (->
      filter
      (update-in [:location] str)
      (update-in [:catch-location] str)
      (update-in [:message] str)))
   (:exception-filters connection)))

(defn exception-filter-kill
  "Remove an exception-filter."
  [connection id]
  (swap! connection update-in [:exception-filters]
         #(vec (concat
                (take (max id 0) %)
                (drop (inc id) %)))))

(defn update-filter-exception
  [connection id f]
  (swap! connection update-in [:exception-filters]
         #(vec (concat
               (take id %)
               [(f (nth % id))]
               (drop (inc id) %)))))

(defn exception-filter-enable
  [connection id]
  (update-filter-exception connection id #(assoc % :enabled true)))

(defn exception-filter-disable
  [connection id]
  (update-filter-exception connection id #(assoc % :enabled false)))


;;; debug methods

;; This is a synthetic Event for an InvocationException delivered to the debug
;; thread calling invokeMethod.  These exceptions are (unfortunately) not
;; reported through the jdi debug event loop.
(deftype InvocationExceptionEvent
    [exception thread]
  com.sun.jdi.event.ExceptionEvent
  (catchLocation [_] nil)
  (exception [_] exception)
  (location [_] (.location exception))
  (thread [_] thread)
  (virtualMachine [_] (.virtualMachine exception))
  (request [_] nil))

;; Restarts
(defn resume
  [thread-ref suspend-policy]
  (case suspend-policy
    :suspend-all (.resume (.virtualMachine thread-ref))
    :suspend-event-thread (.resume thread-ref)
    nil))


(defn resume-sldb-levels
  "Resume sldb levels specified in the connection.
   This is side effecting, so can not be used within swap!"
  [connection]
  (doseq [level-info (:resume-sldb-levels connection)
          :let [event (:event level-info)]
          :when (not (instance? InvocationExceptionEvent event))]
    (logging/trace
     "resuming threads for sldb-level %s"
     (:user-threads level-info))
    (jdi/resume-threads (:user-threads level-info)))
  connection)


(defn aborting-level?
  "Aborting predicate."
  [connection]
  (connection/aborting-level? connection))

(defn clear-abort-for-current-level
  "Clear any abort for the current level"
  [connection]
  (swap!
   connection
   (fn [c]
     (logging/trace
      "clear-abort-for-current-level %s %s"
      (count (:sldb-levels c)) (:abort-to-level c))
     (if (and (:abort-to-level c)
              (= (count (:sldb-levels c)) (:abort-to-level c)))
       (dissoc c :abort-to-level)
       c))))

(defn step-request
  [thread size depth]
  (doto (jdi/step-request thread size depth)
    (.addCountFilter 1)
    (jdi/suspend-policy breakpoint-suspend-policy)
    (.enable)))

(defn ignore-exception-type
  "Add the specified exception to the connection's never-break-exceptions set."
  [connection exception-type]
  (logging/trace "Adding %s to never-break-exceptions" exception-type)
  (swap! connection update-in [:exception-filters] conj
         {:type exception-type :enabled true}))

(defn ignore-exception-message
  "Add the specified exception to the connection's never-break-exceptions set."
  [connection exception-message]
  (logging/trace "Adding %s to never-break-exceptions" exception-message)
  (swap! connection update-in [:exception-filters] conj
         {:message exception-message :enabled true}))

(defn ignore-exception-catch-location
  "Add the specified exception catch location to the connection's
   never-break-exceptions set."
  [connection catch-location]
  (logging/trace "Adding %s to never-break-exceptions" catch-location)
  (swap! connection update-in [:exception-filters] conj
         {:catch-location catch-location :enabled true}))

(defn ignore-exception-location
  "Add the specified exception throw location to the connection's
   never-break-exceptions set."
  [connection catch-location]
  (logging/trace "Adding %s to never-break-exceptions" catch-location)
  (swap! connection update-in [:exception-filters] conj
         {:location catch-location :enabled true}))


;;; events
(defn add-exception-event-request
  [context]
  (logging/trace "add-exception-event-request")
  (doto (jdi/exception-request (:vm context) nil true true)
    (jdi/suspend-policy exception-suspend-policy)
    (.enable)))

;;; VM events

;; macros like `binding`, that use (try ... (finally ...)) cause exceptions
;; within their bodies to be considered caught.  We therefore need some
;; way for the user to be able to maintain a list of catch locations that
;; should not be considered as "caught".

(defn break-for?
  [connection exception-type location-name catch-location-name
   exception-message]
  (letfn [(equal-or-matches? [expr value]
            (logging/trace "checking equal-or-matches? %s %s" expr value)
            (cond
             (string? expr) (= expr value)
             (string? value) (re-matches expr value)
             :else false))
          (matches? [{:keys [type location catch-location enabled message]
                      :as filter}]
            (and
             enabled
             (or (not type) (= type exception-type))
             (or (not location)
                 (equal-or-matches? location location-name))
             (or (not catch-location) (not catch-location-name)
                 (equal-or-matches? catch-location catch-location-name))
             (or (not message)
                 (equal-or-matches? message exception-message))))]
    (not (some matches? (:exception-filters @connection)))))

(defn break-for-exception?
  "Predicate to check whether we should invoke the debugger for the given
   exception event"
  [exception-event connection]
  (let [catch-location (jdi/catch-location exception-event)
        location (jdi/location exception-event)
        location-name (jdi/location-type-name location)
        exception (.exception exception-event)
        exception-type (.. exception referenceType name)
        catch-location-name (when catch-location
                              (jdi/location-type-name catch-location))
        exception-msg (jdi-clj/exception-message
                       @(:vm-context @connection) exception-event)]
    (logging/trace
     "break-for-exception? %s %s %s"
     catch-location-name location-name exception-msg)
    (swap! connection assoc :exception-message exception-msg)
    (or (not catch-location)
        (break-for?
         connection
         exception-type location-name catch-location-name
         exception-msg))))


(defn add-op-location [method {:keys [code-index] :as op}]
  (if code-index
    (merge op (jdi/location-data (.locationOfCodeIndex method code-index)))
    op))

(defn format-arg
  [arg]
  (case (:type arg)
        :methodref (format
                    "%s/%s%s"
                    (string/replace (:class-name arg) "/" ".")
                    (:name arg) (:descriptor arg))
        :fieldref (format
                    "^%s %s.%s"
                     (:descriptor arg)
                     (string/replace (:class-name arg) "/" ".")
                     (:name arg))
        :interfacemethodref (format
                             "%s/%s%s"
                             (string/replace (:class-name arg) "/" ".")
                             (:name arg) (:descriptor arg))
        :nameandtype (format "%s%s" (:name arg) (:descriptor arg))
        :class (format "%s" (:name arg))
        (if-let [value (:value arg)]
          (format "%s" (pr-str value))
          (format "%s" (pr-str arg)))))

(defn format-ops
  [ops]
  (first
   (reduce
    (fn [[output location] op]
      (let [line (:line location)
            s (format
               "%5d  %s %s"
               (:code-index op) (:mnemonic op)
               (string/join " " (map format-arg (:args op))))]
        (if (= line (:line op))
          [(conj output s) location]
          [(->
            output
            (conj (format
                   "%s:%s"
                   (or (:source-path op) (:source op) (:function op))
                   (:line op)))
            (conj s))
           (select-keys op [:function :line])])))
    [[] {}]
    ops)))

(defn disassemble-method
  "Dissasemble a method, adding location info"
  [const-pool method]
  (let [ops (disassemble/disassemble const-pool (.bytecodes method))]
    (format-ops (map #(add-op-location method %) ops))))

(defn disassemble-frame
  [context ^ThreadReference thread frame-index]
  (let [^StackFrame frame (nth (.frames thread) frame-index)
        location (.location frame)]
    (if-let [method (.method location)]
      (let [const-pool (disassemble/constant-pool
                        (.. method declaringType constantPool))]
        (disassemble-method const-pool method))
      "Method not found")))

(defn disassemble-symbol
  "Dissasemble a symbol var"
  [context ^ThreadReference thread sym-ns sym-name]
  (logging/trace "disassemble-symbol %s %s" sym-ns sym-name)
  (when-let [[f methods] (jdi-clj/clojure-fn-deref
                          context thread
                          jdi/invoke-single-threaded
                          sym-ns sym-name)]
    (let [const-pool (disassemble/constant-pool
                      (.. f referenceType constantPool))]
      (apply
       concat
       (for [method methods
             :let [ops (disassemble-method const-pool method)]
             :let [clinit (first (drop 3 ops))]
             :when (not (.contains clinit ":invokevirtual \""))]
         (concat
          [(str sym-ns "/" sym-name
                "(" (string/join " " (.argumentTypeNames method)) ")\n")]
          ops
          ["\n\n"]))))))
