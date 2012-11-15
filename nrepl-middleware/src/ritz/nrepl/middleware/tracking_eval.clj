(ns ritz.nrepl.middleware.tracking-eval
  "An eval operation with source form tracking."
  (:require
   [clojure.tools.nrepl.transport :as transport]
   ritz.repl-utils.core.defonce
   ritz.repl-utils.core.defprotocol)
  (:use
   [clojure.string :only [join]]
   [clojure.tools.nrepl.middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.middleware.interruptible-eval
    :only [interruptible-eval *msg*]]
   [clojure.tools.nrepl.misc :only [response-for returning]]
   [ritz.repl-utils.compile :only [eval-region with-compiler-options]]
   [ritz.repl-utils.source-forms
    :only [source-form source-forms source-form! clear-source-forms!
           source-form-path]]
   [ritz.logging :only [trace]])
  (:import
   java.io.Writer))

(defn evaluate
  "Evaluates some code within the dynamic context defined by a map of
   `bindings`, as per `clojure.core/get-thread-bindings`.

   Uses `clojure.main/repl` to drive the evaluation of :code in a second map
   argument (a string), which may also optionally specify a :ns (resolved via
   `find-ns`).  The map MUST contain a Transport implementation in :transport;
   expression results and errors will be sent via that Transport.

   Returns the dynamic scope that remains after evaluating all expressions
   in :code.

   It is assumed that `bindings` already contains useful/appropriate entries
   for all vars indicated by `clojure.main/with-bindings`."
  [bindings {:keys [code ns transport file-path id line debug] :as msg}]
  (let [explicit-ns-binding (when-let [ns (and ns (-> ns symbol find-ns))]
                              {#'*ns* ns})
        bindings (atom (merge bindings explicit-ns-binding))
        out (@bindings #'*out*)
        err (@bindings #'*err*)
        file-path (or file-path (source-form-path id))
        line (or line 1)
        debug (when debug (Boolean. debug))]
    (if (and ns (not explicit-ns-binding))
      (transport/send
       transport
       (response-for msg {:status #{:error :namespace-not-found :done} :ns ns}))
      (with-bindings @bindings
        (try
          (let [[v f] (with-compiler-options {:debug debug}
                        (eval-region code file-path line))]
            (.flush ^Writer err)
            (.flush ^Writer out)
            (transport/send
             transport
             (response-for msg {:value v :ns (-> *ns* ns-name str)})))
          (catch Exception e
            (let [root-ex (#'clojure.main/root-cause e)]
              (when-not (instance? ThreadDeath root-ex)
                (reset! bindings (assoc (get-thread-bindings) #'*e e))
                (transport/send
                 transport
                 (response-for
                  msg {:status :eval-error
                       :ex (-> e class str)
                       :root-ex (-> root-ex class str)}))
                (clojure.main/repl-caught e))))
          (finally
            (.flush ^Writer out)
            (.flush ^Writer err)))))
    @bindings))

(def queue-eval
  #'clojure.tools.nrepl.middleware.interruptible-eval/queue-eval)
(def configure-executor
  #'clojure.tools.nrepl.middleware.interruptible-eval/configure-executor)

(defn eval-reply
  [{:keys [op session interrupt-id id transport] :as msg} executor]
  (if (:code msg)
    (queue-eval session executor
                (comp
                 (partial reset! session)
                 (fn []
                   (alter-meta! session assoc
                                :thread (Thread/currentThread)
                                :eval-msg msg)
                   (binding [*msg* msg]
                     (returning (dissoc (evaluate @session msg) #'*msg*)
                       (transport/send
                        transport (response-for msg :status :done))
                       (alter-meta! session dissoc :thread :eval-msg))))))
    (transport/send
     transport (response-for msg :status #{:error :no-code}))))

(defn tracking-eval
  "Evaluation middleware that supports interrupts and tracking of source forms.
   Returns a handler that supports \"eval\" and \"interrupt\" :op-erations that
   delegates to the given handler otherwise."
  [h & {:keys [executor] :or {executor (configure-executor)}}]
  (fn [{:keys [op session interrupt-id id transport] :as msg}]
    (case op
      "eval" (eval-reply msg executor)
      "interrupt" ((interruptible-eval h) msg)
      (h msg))))

(defn source-forms-reply
  [{:keys [source-id clear id transport] :as msg}]
  (if clear
    (clear-source-forms!)
    (transport/send
     transport
     (response-for msg {:value (if source-id
                                 (source-form source-id)
                                 (source-forms))})))
  (transport/send
     transport (response-for msg :status :done)))

(defn wrap-source-forms
  "Middleware that notes and allows query of source forms."
  [h]
  (fn [{:keys [op id transport code file-path line] :as msg}]
    (case op
      "eval" (if (and file-path line)
               (h msg)
               (do (source-form! (read-string id) code) (h msg)))
      "source-forms" (source-forms-reply msg)
      (h msg))))


(set-descriptor!
 #'tracking-eval
 {:requires #{"clone" "close"
              #'clojure.tools.nrepl.middleware.pr-values/pr-values}
  :expects #{}
  :handles
  {"eval"
   {:doc "Evaluates code."
    :requires
    {"code" "The code to be evaluated."
     "session" "The ID of the session within which to evaluate the code."}
    :optional
    {"id" "An opaque message ID that will be included in responses
 related to the evaluation, and which may be used to restrict the scope
 of a later \"interrupt\" operation."
     "file-path" "A file-path to the file defining the code"
     "line" "A line number within the file at which the code starts"}
    :returns {}}
   "interrupt"
   {:doc "Attempts to interrupt some code evaluation."
    :requires
    {"session" "The ID of the session used to start the evaluation to be
interrupted."}
    :optional
    {"interrupt-id" "The opaque message ID sent with the original \"eval\"
request."}
    :returns
    {"status" "'interrupted' if an evaluation was identified and interruption
              will be attempted
'session-idle' if the session is not currently evaluating any code
'interrupt-id-mismatch' if the session is currently evaluating code sent using
                        a different ID than specified by the \"interrupt-id\"
                        value "}}}})

(set-descriptor!
 #'wrap-source-forms
 {:requires #{}
  :expects #{"eval"}
  :handles
  {"source-forms"
   {:doc "Query or reset source forms."
    :optional
    {"source-id" "A message ID identifying the source form to return"
     "reset" "A flag to reset the source forms"}
    :returns {}}}})
