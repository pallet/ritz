(ns ritz.swank.debug
  "Debugger interaction for swank"
  (:use
   [ritz.jpda.debug
    :only [step-request resume-sldb-levels level-info-thread-id
           clear-abort-for-current-level
           ignore-exception-type ignore-exception-message
           ignore-exception-location ignore-exception-catch-location
           remove-breakpoint
           build-backtrace
           aborting-level? break-for-exception?
           nth-thread stop-thread threads]]
   [ritz.logging :only [trace trace-str]])
  (:require
   [clojure.string :as string]
   [ritz.connection :as connection]
   [ritz.executor :as executor]
   [ritz.hooks :as hooks]
   [ritz.inspect :as inspect]
   [ritz.jpda.jdi :as jdi]
   [ritz.jpda.jdi-clj :as jdi-clj]
   [ritz.jpda.jdi-vm :as jdi-vm]
   [ritz.jpda.swell.impl :as swell-impl]
   [ritz.repl-utils.find :as find]
   [ritz.repl-utils.helpers :as helpers]
   [ritz.rpc-socket-connection :as rpc-socket-connection]
   [ritz.swank.core :as core]
   [ritz.swank.messages :as messages])
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
    LongValue ShortValue StringReference)
   ritz.jpda.debug.InvocationExceptionEvent))

(def ^{:dynamic true} *sldb-initial-frames* 10)

(defonce first-eval-seen (atom false))

(defonce connections (atom {}))

(defn add-connection [connection proxied-connection]
  (swap! connections assoc connection proxied-connection))

(defn remove-connection [connection]
  (swap! connections dissoc connection))

(defn log-exception [e]
  (trace
   "Caught exception %s %s"
   (pr-str e)
   (helpers/stack-trace-string e)))

;;; debugee function for starting a thread that may be used from the debugger
(defn- vm-swank-main
  [options]
  `(try
     (require '~'ritz.socket-server)
     ((resolve '~'ritz.socket-server/start) ~options)
     (catch Exception e#
       (println e#)
       (.printStackTrace e#))))

;;; functions for acquiring the control thread in the proxy
(defn launch-vm-with-swank
  "Launch and configure the vm for the debugee."
  [{:keys [port announce log-level classpath] :as options}]
  (apply
   jdi-vm/launch-vm
   (or classpath (jdi-vm/current-classpath))
   (vm-swank-main {:port port
                   :announce announce
                   :server-ns `(quote ritz.repl)
                   :log-level (keyword log-level)})
   (mapcat identity options)))

;; (defn launch-vm-without-swank
;;   "Launch and configure the vm for the debugee."
;;   [classpath {:as options}]
;;   (trace "launch-vm-without-swank %s" classpath)
;;   (jdi-vm/launch-vm classpath ))


(defn connect-to-repl-on-vm [port]
  (trace "debugger/connect-to-repl-on-vm port %s" port)
  (Socket. "localhost" port))

(defn create-connection [options]
  (trace "debugger/create-connection: connecting to proxied connection")
  (->
   (connect-to-repl-on-vm (:port options))
   (rpc-socket-connection/create options)
   (connection/create options)))

(defn remote-swank-port
  "Obtain the swank port on the remote vm"
  [context]
  (letfn [(get-port []
            (try
              (jdi-clj/control-eval
               context
               ;; NB very important that this doesn't throw
               ;; as that can cause hangs in the startup
               `(try
                  (when (find-ns '~'ritz.socket-server)
                    (when-let [v# (ns-resolve
                                   '~'ritz.socket-server
                                   '~'acceptor-port)]
                      (when-let [a# (var-get v#)]
                        (when (instance? clojure.lang.Atom a#)
                          (deref a#)))))
                  (catch Exception _#)))
              (catch Exception _)))]
    (loop []
      (trace "debug/remote-swank-port: loop")
      (if-let [port (get-port)]
        port
        (do
          (trace "debug/remote-swank-port: no port yet ...")
          (Thread/sleep 1000)
          (recur))))))


;;; interactive form tracking
(defn swank-peek
  [connection form buffer-package id f]
  (when (= (first form) 'swank/listener-eval)
    (find/source-form! id (second form))))

;;; execute functions and forwarding don't belong in this
;;; namespace
(defn execute-if-inspect-frame-var
  [handler]
  (fn [connection form buffer-package id f]
    (if (and f (= "inspect-frame-var" (name (first form))))
      (core/execute-slime-fn* connection f (rest form) buffer-package)
      (handler connection form buffer-package id f))))

(defn execute-inspect-if-inspector-active
  [handler]
  (fn [connection form buffer-package id f]
    (trace
     "inspect-if-inspector-active %s %s %s"
     f
     (re-find #"inspect" (name (first form)))
     (inspect/inspecting? (connection/inspector connection)))
    (if (and f
             (re-find #"inspect" (name (first form)))
             (inspect/inspecting? (connection/inspector connection)))
      (core/execute-slime-fn* connection f (rest form) buffer-package)
      (handler connection form buffer-package id f))))

(defn execute-peek
  [handler]
  (fn [connection form buffer-package id f]
    (trace "execute-peek %s" f)
    (swank-peek connection form buffer-package id f)
    (handler connection form buffer-package id f)))

(defn execute-unless-inspect
  "If the function has resolved (in the debug proxy) then execute it,
otherwise pass it on."
  [handler]
  (fn [connection form buffer-package id f]
    (trace "execute-unless-inspect %s %s" f form)
    (if (and f
         (not (re-find #"inspect" (name (first form))))
         (not (re-find #"nth-value" (name (first form)))) ;; hack
         (:ritz.swank.commands/swank-fn (meta f)))
  (core/execute-slime-fn* connection f (rest form) buffer-package)
  (handler connection form buffer-package id f))))

(declare format-thread)

(defn forward-command
  [handler]
  (fn [connection form buffer-package id f]
    (let [proxied-connection (:proxy-to @connection)]
      (trace
       "debugger/forward-command: forwarding %s to proxied connection"
       (first form))
      (trace
       "VM threads:\n%s"
       (string/join
        "\n"
        (map format-thread (threads (connection/vm-context connection)))))
      (clear-abort-for-current-level connection)
      (executor/execute-request
       (partial
        connection/send-to-emacs
        proxied-connection (list :emacs-rex form buffer-package true id)))
      :ritz.swank/pending)))

(defn forward-rpc
  [connection rpc]
  (let [proxied-connection (:proxy-to @connection)]
    (trace
     "debugger/forward-command: forwarding %s to proxied connection" rpc)
    (executor/execute-request
     (partial connection/send-to-emacs proxied-connection rpc))))

(defn forward-reply
  [connection]
  (trace
   "debugger/forward-command: waiting reply from proxied connection")
  (let [proxied-connection (:proxy-to @connection)]
    (let [reply (connection/read-from-connection proxied-connection)
          id (last reply)]
      (when (or (not (number? id)) (not (zero? id))) ; filter (= id 0)
        (executor/execute-request
         (partial connection/send-to-emacs connection reply))
        (trace "removing pending-id %s" id)
        (connection/remove-pending-id connection id)))))

(defn- return-or-activate-sldb-levels
  "Return the nested debug levels"
  [connection connection-map]
  (if-let [[level-info level] (connection/current-sldb-level-info connection)]
    (connection/send-to-emacs
     connection
     (messages/debug-activate (.uniqueID (:thread level-info)) level))
    (let [resume-levels (:resume-sldb-levels connection-map)]
      (connection/send-to-emacs
       connection
       (messages/debug-return
        (.uniqueID (:thread (first resume-levels))) (count resume-levels)))))
  connection)

(defn continue-level
  "Continue the current level"
  [connection]
  (trace "continue-level")
  (return-or-activate-sldb-levels
   connection
   (resume-sldb-levels
    (swap!
     connection
     (fn [current]
       (->
        current
        (assoc-in [:resume-sldb-levels] [(last (:sldb-levels current))])
        (update-in
         [:sldb-levels]
         (fn [levels] (subvec levels 0 (max 0 (dec (count levels)))))))))))
  nil)

(defn quit-level
  "Abort the current level"
  [connection]
  (trace "quit-level")
  (return-or-activate-sldb-levels
   connection
   (resume-sldb-levels
    (swap!
     connection
     (fn [current]
       (->
        current
        (assoc :abort-to-level (dec (count (:sldb-levels current))))
        (assoc-in [:resume-sldb-levels] [(last (:sldb-levels current))])
        (update-in
         [:sldb-levels]
         (fn [levels] (subvec levels 0 (dec (count levels))))))))))
  nil)

(defn abort-all-levels
  [connection]
  (trace "abort-all-levels")
  (return-or-activate-sldb-levels
   connection
   (resume-sldb-levels
    (swap!
     connection
     (fn [connection]
       (->
        connection
        (assoc-in [:resume-sldb-levels] (reverse (:sldb-levels connection)))
        (assoc-in [:sldb-levels] [])
        (assoc :abort-to-level 0))))))
  nil)

;;; Backtrace
(defn backtrace
  "Create a backtrace for the specified frames.
   TODO: seperate out return message generation."
  [connection start end]
  (when-let [[level-info level] (connection/current-sldb-level-info connection)]
    (build-backtrace (:thread level-info) start end)))

;;; Source location
(defn frame-source-location
  "Return a source location vector [buffer position] for the specified
   frame number."
  [thread frame-number]
  (when-let [frame (nth (.frames thread) frame-number nil)]
    (let [location (.location frame)]
      [(find/find-source-path (jdi/location-source-path location))
       {:line (jdi/location-line-number location)}])))

;;; Threads
(defn format-thread
  [thread-reference]
  (format
   "%s %s (suspend count %s)"
   (.name thread-reference)
   (jdi/thread-states (.status thread-reference))
   (.suspendCount thread-reference)))

(defn thread-list
  "Provide a list of threads. The list is cached in the context
   to allow it to be retrieved by index."
  [context]
  (ritz.jpda.debug/thread-list context))

(defn kill-nth-thread
  [context index]
  (when-let [thread (nth-thread context index)]
    (stop-thread context (:id thread))))

;;; Restarts
(defn make-restart
  "Make a restart map.
   Contains
     :id keyword id
     :name short name
     :descritpton longer description
     :f restart function to invoke"
  [kw name description f]
  ;;[kw [name description f]]
  {:id kw :name name :description description :f f})

(defn- make-step-restart
  [thread id name description size depth]
  (make-restart
   id name description
   (fn [connection]
     (trace "restart %s" name)
     (step-request thread size depth)
     (continue-level connection))))

(defn stepping-restarts
  [thread]
  [(make-step-restart
    thread :step-into "STEP" "Step into the next line" :line :into)
   (make-step-restart
    thread :step-next "STEP-NEXT" "Step to the next line" :line :over)
   (make-step-restart
    thread :step-out "STEP-OUT" "Step out of current frame" :line :out)])

(def remote-condition-printer-sym-value (atom nil))
(def remote-condition-printer-fn (atom nil))

;; TODO investigate what happens if a data carrying exception contains a lazy
;; sequence that throws when realised.
(defn remote-condition-printer
  "Return the remote symbol for a var to use for the condition printer"
  [context thread]
  (or @remote-condition-printer-fn
      (if (compare-and-set!
           remote-condition-printer-sym-value
           nil
           (jdi-clj/eval
            context thread jdi/invoke-single-threaded `(gensym "swank")))
        (let [s (name @remote-condition-printer-sym-value)
              c `(do
                   (require '~'clojure.pprint)
                   (defn ~(symbol s) [c#]
                     (try
                       (let [f# (fn ~'classify-exception-fn [e#]
                                  (case (.getName (class e#))
                                    "clojure.contrib.condition.Condition"
                                    :condition

                                    "slingshot.Stone" :stone
                                    "slingshot.ExceptionInfo" :exception-info
                                    "clojure.lang.ExceptionInfo" :exception-info

                                    "clojure.lang.PersistentHashMap"
                                    :stone-context

                                    "clojure.lang.PersistentArrayMap"
                                    :stone-context

                                    :throwable))
                             gc# (fn ~'get-cause-fn [e#]
                                   (case (f# e#)
                                     :stone (:cause (.context e#))
                                     :stone-context (:next e#)
                                     (.getCause e#)))
                             pc# (fn ~'print-cause-fn [e#]
                                   (case (f# e#)
                                     :condition [(:message e#)
                                                 (first (:stack-trace e#))]
                                     :throwable [(.getMessage e#)
                                                 (first (.getStackTrace e#))]
                                     :stone [(dissoc (.context e#) :stack :next)
                                             (first (:stack (.context e#)))]
                                     :exception-info [(dissoc (.getData e#)
                                                              :stack :next)
                                                      (first
                                                       (:stack (.getData e#)))]
                                     :stone-context [(dissoc e# :stack :next)
                                                     (first (:stack e#))]))
                             ca# (fn ~'cause-chain-fn [e#]
                                   (vec
                                    (map
                                     pc#
                                     (take-while identity (iterate gc# e#)))))]
                         (case (f# c#)
                           :condition
                           (with-out-str
                             (println (:message @(.state c#)))
                             (clojure.pprint/pprint
                              [(dissoc @(.state c#) :message)
                               (ca# c#)]))
                           :exception-info
                           (with-out-str
                             (println (.getMessage c#))
                             (clojure.pprint/pprint (.getData c#)))
                           :stone
                           (with-out-str
                             (println (.messagePrefix c#))
                             (clojure.pprint/pprint
                              (.object c#))
                             (clojure.pprint/pprint
                              (.context c#)))
                           :throwable
                           (with-out-str
                             (clojure.pprint/pprint
                              [(.getMessage c#)
                               (ca# c#)]))))
                       (catch Exception e#
                         (.printStackTrace e#))))
                   ;; force loading of some classes
                   (~(symbol s) (Exception. "")))]
          (trace "remote-condition-printer code %s" c)
          (trace "remote-condition-printer set sym %s" (pr-str s))
          (jdi-clj/eval context thread jdi/invoke-single-threaded c)
          (trace "remote-condition-printer defined fn")
          (reset!
           remote-condition-printer-fn
           (jdi-clj/clojure-fn
            context thread jdi/invoke-single-threaded
            (jdi-clj/eval-to-string
             context thread jdi/invoke-single-threaded
             `(name (ns-name *ns*)))
            s 1))
          (trace
           "remote-condition-printer resolved fn %s"
           (pr-str @remote-condition-printer-fn))
          @remote-condition-printer-fn))
      @remote-condition-printer-fn))

(defprotocol Debugger
  (condition-info [event connection])
  (restarts [event condition connection]))

(def data-carrying-exception
  #{"clojure.contrib.condition.Condition"
    "slingshot.Stone"
    "slingshot.ExceptionInfo"
    "clojure.lang.ExceptionInfo"})

(extend-type ExceptionEvent
  Debugger
  (condition-info
    [event connection]
    (let [context @(:vm-context connection)
          exception (.exception event)
          exception-type (.. exception referenceType name)
          thread (jdi/event-thread event)
          msg (:exception-message connection)
          data-carrying-exception (data-carrying-exception exception-type)]
      (trace
       "condition-info for %s %s" exception-type data-carrying-exception)
      {:exception-message msg
       :message (if data-carrying-exception
                  (let [[object method] (remote-condition-printer
                                         context thread)]
                    (str (jdi/invoke-method
                          thread
                          jdi/invoke-single-threaded
                          object method [exception])))
                  (or msg "No message."))
       :type (str "  [Thrown " exception-type "]")}))

  (restarts
    [^ExceptionEvent exception condition connection]
    (trace "calculate-restarts exception")
    (let [thread (.thread exception)
          context @(:vm-context @connection)]
      (if (.request exception)
        (filter
         identity
         (concat
          [(make-restart
            :continue "CONTINUE" "Pass exception to program"
            (fn [connection]
              (trace "restart Continuing")
              (continue-level connection)))
           (make-restart
            :abort "ABORT" "Return to SLIME's top level."
            (fn [connection]
              (trace "restart Aborting to top level")
              (abort-all-levels connection)))
           (when (pos? (connection/sldb-level connection))
             (make-restart
              :quit "QUIT" "Return to previous level."
              (fn [connection]
                (trace "restart Quiting to previous level")
                (quit-level connection))))
           (make-restart
            :ignore-type "IGNORE" "Do not enter debugger for this exception type"
            (fn [connection]
              (trace "restart Ignoring exceptions")
              (ignore-exception-type
               connection (.. exception exception referenceType name))
              (continue-level connection)))
           (when-not (string/blank? (:exception-message condition))
             (make-restart
              :ignore-message "IGNORE-MSG"
              "Do not enter debugger for this exception message"
              (fn [connection]
                (trace "restart Ignoring exceptions")
                (ignore-exception-message
                 connection (:exception-message condition))
                (continue-level connection))))
           (when-let [location (jdi/catch-location exception)]
             (let [location (jdi/location-type-name location)
                   location (re-find #"[^\$]+" location)]
               (make-restart
                :ignore-message "IGNORE-CATCH"
                (str
                 "Do not enter debugger for exceptions with catch location "
                 location ".*")
                (fn [connection]
                  (trace "restart Ignoring exceptions")
                  (ignore-exception-catch-location
                   connection (re-pattern (str location ".*")))
                  (continue-level connection)))))
           (when-let [location (jdi/location exception)]
             (let [location (jdi/location-type-name location)
                   location (re-find #"[^\$]+" location)]
               (make-restart
                :ignore-message "IGNORE-LOC"
                (str
                 "Do not enter debugger for exceptions with throw location "
                 location ".*")
                (fn [connection]
                  (trace "restart Ignoring exceptions")
                  (ignore-exception-location
                   connection (re-pattern (str location ".*")))
                  (continue-level connection)))))]
          (when-let [restarts (seq
                               (swell-impl/available-restarts context thread))]
            (map
             #(make-restart
               :restart (str "RESTART " %)
               (str "Invoke restart " %)
               (fn [connection]
                 (trace "restart Ignoring exceptions")
                 (swell-impl/select-restart context thread %)
                 (continue-level connection)))
             restarts))))
        (filter
         identity
         [(when (pos? (connection/sldb-level connection))
            (make-restart
             :quit "QUIT" "Return to previous level."
             (fn [connection]
               (trace "restart Quiting to previous level")
               (quit-level connection))))])))))

(extend-type BreakpointEvent
  Debugger

  (condition-info
   [breakpoint _]
   {:message "BREAKPOINT"})

  (restarts
   [^BreakpointEvent breakpoint condition connection]
   (trace "calculate-restarts breakpoint")
   (let [thread (.thread breakpoint)]
     (concat
      [(make-restart
        :continue "CONTINUE" "Continue from breakpoint"
        (fn [connection]
          (trace "restart Continuing")
          (continue-level connection)))
       (make-restart
        :continue-clear "CONTINUE-CLEAR"
        "Continue and clear breakpoint"
        (fn [connection]
          (trace "restart Continue clear")
          (remove-breakpoint (connection/vm-context connection) breakpoint)
          (continue-level connection)))]
      (stepping-restarts thread)))))

(extend-type StepEvent
  Debugger

  (condition-info
   [step _]
   {:message "STEPPING"})

  (restarts
   [^StepEvent step-event condition connection]
   (trace "calculate-restarts step-event")
   (let [thread (.thread step-event)]
     (concat
      [(make-restart
        :continue "CONTINUE" "Continue normal execution"
        (fn [connection]
          (trace "restart Continuing")
          (continue-level connection)))]
      (stepping-restarts thread)))))

(defn debugger-event-info
  "Calculate debugger information and invoke"
  [connection event]
  (trace "debugger-event-info")
  (jdi/with-disabled-exception-requests
    [(:vm (connection/vm-context connection))]
    ;; The remote-condition-printer will cause class not found exceptions
    ;; (especially the first time it runs).
    (let [thread (jdi/event-thread event)
          user-threads (conj
                        (set
                         (jdi/threads-in-group
                          (.virtualMachine thread)
                          executor/ritz-executor-group-name))
                        thread)
          _ (trace "user threads %s" user-threads)
          thread-id (.uniqueID thread)
          _ (trace "building condition")
          condition (condition-info event @connection)
          restarts (restarts event condition connection)
          _ (trace "adding sldb level")
          level-info {:restarts restarts :thread thread :event event
                      :user-threads user-threads}
          level (connection/next-sldb-level connection level-info)
          _ (trace "building backtrace")
          backtrace (if (instance? InvocationExceptionEvent event)
                      [{:function "Unavailble"
                        :source "UNKNOWN" :line "UNKNOWN"}]
                      (build-backtrace thread 0 *sldb-initial-frames*))]
      [thread-id level level-info condition restarts backtrace])))

(defn invoke-debugger*
  "Calculate debugger information and invoke"
  [connection thread-id level condition restarts backtrace]
  (trace "invoke-debugger: send-to-emacs")
  (connection/send-to-emacs
   connection
   (messages/debug
    thread-id level condition restarts backtrace
    (connection/pending connection)))
  (connection/send-to-emacs
   connection (messages/debug-activate thread-id level)))

(defn invoke-debugger
  "Calculate debugger information and invoke"
  [connection event]
  (trace "invoke-debugger")

  (let [[thread-id level level-info condition restarts backtrace]
        (debugger-event-info connection event)]
    (invoke-debugger* connection thread-id level condition restarts backtrace)

    ;; The handler resumes threads, so make sure we suspend them again
    ;; first. The restart from the sldb buffer will resume these threads.
    (jdi/suspend-threads (:user-threads level-info))))

(defn debugger-info-for-emacs
  "Calculate debugger information and invoke"
  [connection start end]
  (trace "debugger-info")
  (let [[level-info level] (connection/current-sldb-level-info connection)
        thread (:thread level-info)
        event (:event level-info)]
    (trace "invoke-debugger: send-to-emacs")
    (messages/debug-info
     (condition-info event @connection)
     (:restarts level-info)
     (if (instance? InvocationExceptionEvent event)
       [{:function "Unavailble" :source "UNKNOWN" :line "UNKNOWN"}]
       (build-backtrace thread start end))
     (connection/pending connection))))

(defn invoke-restart
  [connection level n]
  (when-let [level-info (connection/sldb-level-info connection level)]
    (trace "invoke-restart %s of %s" n (count (:restarts level-info)))
    (when-let [f (:f (try (nth (:restarts level-info) n)
                          (catch IndexOutOfBoundsException _)))]
      (inspect/reset-inspector (:inspector @connection))
      (f connection))
    (level-info-thread-id level-info)))

(defn invoke-named-restart
  [connection kw]
  (trace "invoke-named-restart %s" kw)
  (when-let [[level-info level] (connection/current-sldb-level-info connection)]
    (if-let [f (:f (some #(and (= kw (:id %)) %) (:restarts level-info)))]
      (do (inspect/reset-inspector (:inspector @connection))
          (f connection))
      (do (trace "invoke-named-restart %s not found" kw)
          (format "Restart %s not found" kw)))))

(def stm-types #{"clojure.lang.Atom"})

(defn stm-type? [object-reference]
  (stm-types (.. object-reference referenceType name)))

(defn lazy-seq? [object-reference]
  (= "clojure.lang.LazySeq" (.. object-reference referenceType name)))

(defn invoke-option-for [object-reference]
  (trace "invoke-option-for %s" object-reference)
  (if (and (instance? ObjectReference object-reference)
           (stm-type? object-reference))
    jdi/invoke-multi-threaded
    jdi/invoke-single-threaded))

(defmethod inspect/value-as-string com.sun.jdi.PrimitiveValue
  [context obj] (pr-str (.value obj)))

(defmethod inspect/value-as-string com.sun.jdi.StringReference
  [context obj] (pr-str (.value obj)))

(defmethod inspect/value-as-string com.sun.jdi.Value
  [context obj]
  {:pre [(:current-thread context)]}
  (try
    (jdi-clj/pr-str-arg
     context (:current-thread context) jdi/invoke-single-threaded obj)
    (catch com.sun.jdi.InternalException e
      (trace "inspect/value-as-string: exeception %s" e)
      (format "#<%s>" (.. obj referenceType name)))))

(defmethod inspect/object-content-range com.sun.jdi.PrimitiveValue
  [context object start end]
  (inspect/object-content-range context (.value object) start end))

(defmethod inspect/object-content-range com.sun.jdi.Value
  [context object start end]
  (trace
   "inspect/object-content-range com.sun.jdi.Value %s %s" start end)
  (jdi-clj/read-arg
   context
   (:current-thread context)
   (jdi-clj/invoke-clojure-fn
    context (:current-thread context)
    jdi/invoke-multi-threaded
    "ritz.inspect" "object-content-range"
    nil object
    (jdi-clj/eval-to-value
     context (:current-thread context) jdi/invoke-single-threaded start)
    (jdi-clj/eval-to-value
     context (:current-thread context) jdi/invoke-single-threaded end))))

(defmethod inspect/object-nth-part com.sun.jdi.Value
  [context object n max-index]
  (jdi-clj/invoke-clojure-fn
   context (:current-thread context) jdi/invoke-single-threaded
   "ritz.inspect" "object-nth-part"
   nil object
   (jdi-clj/eval-to-value
    context (:current-thread context) jdi/invoke-single-threaded n)
   (jdi-clj/eval-to-value
    context (:current-thread context) jdi/invoke-single-threaded max-index)))

(defmethod inspect/object-call-nth-action :default com.sun.jdi.Value
  [context object n max-index args]
  (jdi-clj/read-arg
   context
   (:current-thread context)
   (jdi-clj/invoke-clojure-fn
    context
    (:current-thread context)
    jdi/invoke-multi-threaded
    "ritz.inspect" "object-call-nth-action"
    object
    (jdi-clj/eval-to-value
     context (:current-thread context) jdi/invoke-single-threaded n)
    (jdi-clj/eval-to-value
     context (:current-thread context) jdi/invoke-single-threaded max-index)
    (jdi-clj/eval-to-value
     context (:current-thread context)
     jdi/invoke-single-threaded
     args))))

(defn frame-locals-with-string-values
  "Return frame locals for slime"
  [context thread n]
  (doall
   (for [{:keys [value] :as map-entry} (jdi/unmangled-frame-locals
                                        (nth (.frames thread) n))]
     (assoc map-entry
       :string-value (inspect/value-as-string
                      (assoc context :current-thread thread) value)))))

(defn nth-frame-var
  "Return the var-index'th var in the frame-index'th frame"
  [context thread frame-index var-index]
  {:pre [(< frame-index (count (.frames thread)))]}
  (trace "debug/nth-frame-var %s %s" frame-index var-index)
  (->
   (seq (frame-locals-with-string-values context thread frame-index))
   (nth var-index)
   :value))

(defn local-bindings
  "Create a lexical environment with the specified values"
  [map-sym locals]
  (mapcat
   (fn [local]
     (let [local (:unmangled-name local)]
       `[~(symbol local) ((var-get (ns-resolve '~'user '~map-sym)) ~local)]))
   locals))

(defn with-local-bindings-form
  "Create a form setting up local bindings around the given expr"
  [map-sym locals expr]
  `(let [~@(local-bindings map-sym locals)] ~expr))

(def ^{:private true
       :doc "A symbol generated on the debuggee, naming a var for our use"}
  remote-map-sym-value (atom nil))

(defn remote-map-sym
  "Return the remote symbol for a var to use in swank"
  [context thread]
  (or @remote-map-sym-value
      (reset! remote-map-sym-value
              (symbol (jdi-clj/eval
                       context thread jdi/invoke-single-threaded
                       `(gensym "swank"))))))

(def ^{:private true
       :doc "An empty map"}
  remote-empty-map-value (atom nil))

(defn remote-empty-map
  "Return a remote empty map for use in resetting the swank remote var"
  [context thread]
  (or @remote-empty-map-value
      (reset! remote-empty-map-value
              (jdi-clj/eval-to-value
               context thread jdi/invoke-single-threaded
               `(hash-map)))))

(defn assoc-local
  "Assoc a local variable into a remote var"
  [context thread map-var local]
  (jdi-clj/remote-call
   context thread (invoke-option-for (:value local))
   `assoc map-var
   (jdi-clj/remote-str context (:unmangled-name local))
   (when-let [value (:value local)]
     (jdi-clj/remote-object value context thread))))

(defn set-remote-values
  "Build a map in map-var of name to value for all the locals"
  [context thread map-var locals]
  (jdi-clj/swap-root
   context thread jdi/invoke-single-threaded
   map-var
   (reduce
    (fn [v local] (assoc-local context thread v local))
    (jdi-clj/var-get context thread jdi/invoke-single-threaded map-var)
    locals)))

(defn clear-remote-values
  [context thread map-var]
  (jdi-clj/swap-root
   context thread jdi/invoke-single-threaded
   map-var (remote-empty-map context thread)))

(defn eval-string-in-frame
  "Eval the string `expr` in the context of the specified `frame-number`."
  [connection context thread expr frame-number]
  (try
    (let [_ (assert (.isSuspended thread))
          locals (jdi/unmangled-frame-locals
                  (nth (.frames thread) frame-number))
          map-sym (remote-map-sym context thread)
          map-var (jdi-clj/eval-to-value
                   context thread jdi/invoke-single-threaded
                   `(intern '~'user '~map-sym {}))
          form (with-local-bindings-form map-sym locals (read-string expr))]
      (locking remote-map-sym
        (try
          (set-remote-values context thread map-var locals)
          (let [v (jdi-clj/eval-to-value
                   context thread jdi/invoke-single-threaded form)]
            (inspect/value-as-string (assoc context :current-thread thread) v))
          (finally
           (clear-remote-values context thread map-var)))))
    (catch com.sun.jdi.InvocationException e
      (if connection
        (let [event (InvocationExceptionEvent. (.exception e) thread)
              [thread-id level level-info condition restarts backtrace]
              (debugger-event-info connection event)]
          (invoke-debugger*
           connection thread-id level condition restarts backtrace))
        (do
          (println (.exception e))
          (println e)
          (.printStackTrace e))))))

(defn pprint-eval-string-in-frame
  "Eval the string `expr` in the context of the specified `frame-number`
   and pretty print the result"
  [connection context thread expr frame-number]
  (try
    (let [_ (assert (.isSuspended thread))
          locals (jdi/unmangled-frame-locals
                  (nth (.frames thread) frame-number))
          map-sym (remote-map-sym context thread)
          map-var (jdi-clj/eval-to-value
                   context thread jdi/invoke-single-threaded
                   `(intern '~'user '~map-sym {}))
          form `(with-out-str
                  (require 'clojure.pprint)
                  ((resolve 'clojure.pprint/pprint)
                   ~(with-local-bindings-form
                      map-sym locals (read-string expr))))]
      (locking remote-map-sym
        (try
          (set-remote-values context thread map-var locals)
          (jdi-clj/eval-to-string
           context thread jdi/invoke-single-threaded form)
          (finally
           (clear-remote-values context thread map-var)))))
    (catch com.sun.jdi.InvocationException e
      (if connection
        (let [event (InvocationExceptionEvent. (.exception e) thread)
              [thread-id level level-info condition restarts backtrace]
              (debugger-event-info connection event)]
          (invoke-debugger*
           connection thread-id level condition restarts backtrace))
        (do
          (println (.exception e))
          (println e)
          (.printStackTrace e))))))

(defn connection-and-id-from-thread
  "Walk the stack frames to find the eval-for-emacs call and extract
   the id argument.  This finds the connection and id in the target
   vm"
  [context thread]
  (trace "connection-and-id-from-thread %s" thread)
  (some (fn [frame]
          (when-let [location (.location frame)]
            (when (and (= "ritz.swank$eval_for_emacs"
                          (jdi/location-type-name location))
                       (= "invoke" (jdi/location-method-name location)))
              ;; (trace "connection-and-id-from-thread found frame")
              (let [connection (first (.getArgumentValues frame))
                    id (last (.getArgumentValues frame))]
                ;; (trace
                ;;  "connection-and-id-from-thread id %s connection %s"
                ;;  (jdi/object-reference id)
                ;;  (jdi/object-reference connection))
                {:connection connection
                 :id (jdi-clj/read-arg context thread id)}))))
        (.frames thread)))

(defmethod jdi/handle-event ExceptionEvent
  [^ExceptionEvent event context]
  (let [exception (.exception event)
        thread (.thread event)
        silent? (jdi/silent-event? event)]
    (when (and
           (:control-thread context)
           (:RT context))
      (if (not silent?)
        ;; (trace "EXCEPTION %s" event)
        ;; assume a single connection for now
        (do
          (trace "EVENT %s" (.toString event))
          (trace "EXCEPTION %s" exception)
          ;; would like to print this, but can cause hangs
          ;;    (jdi-clj/exception-message context event)
          (if-let [connection (ffirst @connections)]
            (if (aborting-level? connection)
              (trace "Not activating sldb (aborting)")
              (when (break-for-exception? event connection)
                (trace "Activating sldb")
                (invoke-debugger connection event)))
            ;; (trace "Not activating sldb (no connection)")
            ))
        (do
          (trace-str "@")
          ;; (trace
          ;;  "jdi/handle-event ExceptionEvent: Silent EXCEPTION %s %s"
          ;;  event
          ;;  (.. exception referenceType name)
          ;;  ;; (jdi-clj/exception-message context event)
          ;;  ;; (jdi/exception-event-string context event)
          ;;  )
          )))))

(defmethod jdi/handle-event BreakpointEvent
  [^BreakpointEvent event context]
  (trace "BREAKPOINT")
  (let [thread (.thread event)]
    (when (and (:control-thread context) (:RT context))
      (if-let [connection (ffirst @connections)]
        (do
          (trace "Activating sldb for breakpoint")
          (invoke-debugger connection event))
        (trace "Not activating sldb (no connection)")))))

(defmethod jdi/handle-event StepEvent
  [^StepEvent event context]
  (trace "STEP")
  (let [thread (.thread event)]
    (when (and (:control-thread context) (:RT context))
      (if-let [connection (ffirst @connections)]
        (do
          (trace "Activating sldb for stepping")
          ;; The step event is completed, so we discard it's request
          (jdi/discard-event-request (:vm context) (.. event request))
          (invoke-debugger connection event))
        (trace "Not activating sldb (no connection)")))))

(defmethod jdi/handle-event VMDeathEvent
  [event context-atom]
  (doseq [[connection proxied-connection] @connections]
    (connection/close proxied-connection)
    (connection/close connection))
  (System/exit 0))

(defn setup-debugee
  "Forward info to the debuggee. This uses request id 0, which will be
   filtered from returning a reply to slime."
  [connection]
  ((forward-command nil) connection
   `(~'swank:interactive-eval
     ~(str
       `(do
          (require '~'ritz.repl-utils.compile)
          (reset!
           (var-get (resolve 'ritz.repl-utils.compile/compile-path))
           ~*compile-path*)))) "user" 0 nil))

(hooks/add core/new-connection-hook setup-debugee)

(defn disassemble-frame
  [context ^ThreadReference thread frame-index]
  (ritz.jpda.debug/disassemble-frame context thread frame-index))

(defn disassemble-symbol
  "Dissasemble a symbol var"
  [context ^ThreadReference thread sym-ns sym-name]
  (ritz.jpda.debug/disassemble-symbol context thread sym-ns sym-name))

(defn add-exception-event-request
  [vm-context]
  (ritz.jpda.debug/add-exception-event-request vm-context))
