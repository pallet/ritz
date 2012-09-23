(ns ritz.jpda.jdi-clj-test
  (:require
   [ritz.logging :as logging]
   [ritz.jpda.jdi :as jdi]
   [ritz.jpda.jdi-clj :as jdi-clj]
   [ritz.jpda.jdi-vm :as jdi-vm]
   [ritz.jpda.debug :as debug]
   [ritz.jpda.jdi-test-handler :as jdi-test-handler]
   [clojure.string :as string])
  (:use
   [bultitude.core :only [classpath-files]]
   [clojure.stacktrace :only [print-cause-trace]])
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
    LongValue ShortValue))
  (:use clojure.test))

;; (logging/set-level :trace)

(deftest eval-test
  (let [context (jdi-vm/launch-vm
                 (jdi-vm/current-classpath)
                 `(do (ns ~'fred)
                      (def ~'x 1)
                      (def ~'y (atom 2)))
                 {})
        context (jdi-clj/vm-rt context)
        thread (:control-thread context)]
    (is thread)
    (.resume (:vm context))
    (testing "eval"
      (is (= 1
             (jdi-clj/eval
              context thread jdi/invoke-multi-threaded `(+ 0 1))))
      (is (= "ab"
             (jdi-clj/eval
              context thread jdi/invoke-multi-threaded `(str "a" "b"))))
      (is (= 'ab
             (jdi-clj/eval
              context thread jdi/invoke-multi-threaded `(symbol "ab"))))
      (is (= [1 "ab" 'ab]
               (jdi-clj/eval
                context thread jdi/invoke-multi-threaded `[1 "ab" '~'ab]))))
    (testing "control-eval"
      (is (= [1 2 3] (jdi-clj/control-eval context `[1 2 3])))
      (is (= "ab" (jdi-clj/control-eval context `(str '~'ab))))
      (is (= 1 (jdi-clj/control-eval context `fred/x)))
      (is (= 2 (jdi-clj/control-eval context `@fred/y))))
    (testing "control-eval-to-value"
      (is (= 1 (jdi-clj/read-arg context thread
                (jdi-clj/control-eval-to-value context `1)))))

    (is (= (clojure-version) (jdi-clj/clojure-version context thread)))

    (is (= "\"a\""
           (jdi/string-value
            (jdi-clj/invoke-clojure-fn
             context thread jdi/invoke-single-threaded "clojure.core" "pr-str"
             (jdi-clj/remote-str context "a")))))
    (jdi/shutdown context)))


(let [test-finished (promise)]
  (defn handler-for-classloader-test [^ExceptionEvent event context]
    (try
      (logging/trace "handler-for-classloader-test %s" event)
      (logging/trace
       "handler-for-classloader-test exception %s" (.exception event))
      (let [thread (jdi/event-thread event)
            frame (first (.frames thread))
            location (.location frame)
            dt (.. location declaringType)
            classloader (.. location declaringType classLoader)
            gp (first (jdi/methods (.referenceType classloader) "getParent"))
            pc #(jdi/invoke-method thread {} % gp nil)
            pcs (take-while identity (iterate pc classloader))
            rt (jdi-clj/clojure-runtime-for-thread
                thread (jdi/thread-classloader thread))
            correct-calc (boolean ((set pcs) (.classLoader (:RT rt))))
            rt2 (jdi-clj/clojure-runtime context thread)
            correct-cache (= (:RT rt) (:RT rt2))
            result (and correct-calc correct-cache)]
        (when-not result
          (println "Couldn't find runtime classloader for" rt "in " (set pcs)))
        (deliver test-finished result))
      (catch Exception e
        (print-cause-trace e)
        (logging/trace e)
        (deliver test-finished nil))
      (finally
       (jdi/resume-event-threads event))))

  (deftest build-classloader-test
    (logging/trace "build-classloader-test")
    (jdi-test-handler/reset-one-shot-error)
    (let [context (debug/launch-vm {:main `(deref (promise))})
          context (assoc context :current-thread (:control-thread context))
          vm (:vm context)]
      (debug/add-exception-event-request context)
      (.resume (:vm context))

      (try
        (let [cp-files (->>
                        (classpath-files)
                        (map #(.getAbsolutePath %))
                        (filter #(re-find #"org/clojure/clojure" %)))
              form `(do
                      (ritz.repl-utils.classloader/set-classpath! [~@cp-files])
                      (ritz.repl-utils.classloader/eval-clojure
                       `(throw
                         (Exception. "go do handler-for-classloader-test"))))]
          (jdi-test-handler/add-one-shot-event-handler
           handler-for-classloader-test "classloader-test")
          (jdi/enable-exception-request-states (:vm context))
          (jdi-clj/control-eval context `(require 'ritz.repl-utils.classloader))
          (jdi-clj/remote-thread
           context (:control-thread context) {}
           form {:name "classloader-test"})
          (is @test-finished)
          (is (not (jdi-test-handler/one-shot-error?)))
          (jdi/disable-exception-request-states vm))
        (finally
         (jdi/shutdown context))))))

(let [test-finished (promise)]
  (defn handler-for-cl2-test [^ExceptionEvent event context]
    (try
      (logging/trace "handler2-for-cl2-test %s" event)
      (let [thread (jdi/event-thread event)
            options {:disable-exception-requests true}
            v-hello (jdi-clj/eval-to-string
                     context thread options `(pr-str "hello"))
            v-str-1 (jdi-clj/eval-to-string context thread options `(pr-str 1))
            v-1 (jdi-clj/eval context thread options 1)
            ver (jdi-clj/pr-str-arg
                 context thread options
                 (jdi-clj/var-get
                  context thread options
                  (jdi-clj/eval-to-value
                   context thread options
                   `(ns-resolve 'clojure.core '~'*clojure-version*))))
            c-opts (jdi-clj/pr-str-arg
                    context thread options
                    (jdi-clj/assoc
                     context thread options
                     (jdi-clj/var-get
                      context thread options
                      (jdi-clj/eval-to-value
                       context thread options
                       `(ns-resolve 'clojure.core '~'*compiler-options*)))
                     (jdi-clj/eval-to-value context thread options `:x)
                     (jdi-clj/eval-to-value context thread options 1)))
            _ (jdi-clj/swap-root
                    context thread options
                    (jdi-clj/eval-to-value
                     context thread options
                     `(ns-resolve 'clojure.core '~'*compiler-options*))
                    (jdi-clj/eval-to-value context thread options `{:y 1}))
            c-opts2 (jdi-clj/pr-str-arg
                    context thread options
                    (jdi-clj/var-get
                      context thread options
                      (jdi-clj/eval-to-value
                       context thread options
                       `(ns-resolve 'clojure.core '~'*compiler-options*))))
            result {:v-1 v-1 :v-hello v-hello :v-str-1 v-str-1 :ver ver
                    :c-opts c-opts :c-opts2 c-opts2}]
        (deliver test-finished result))
      (catch Exception e
        (print-cause-trace e)
        (logging/trace e)
        (deliver test-finished nil))
      (finally
       (jdi/resume-event-threads event))))

  (deftest build-cl2-test
    (logging/trace "build-cl2-test")
    (jdi-test-handler/reset-one-shot-error)
    (let [context (debug/launch-vm {:main `(deref (promise))})
          context (assoc context :current-thread (:control-thread context))
          vm (:vm context)]
      (debug/add-exception-event-request context)
      (.resume (:vm context))

      (try
        (let [cp-files (->>
                        (classpath-files)
                        (map #(.getAbsolutePath %))
                        (filter #(re-find #"org/clojure/clojure" %)))
              form `(do
                      (ritz.repl-utils.classloader/set-classpath! [~@cp-files])
                      (ritz.repl-utils.classloader/eval-clojure
                       `(throw
                         (Exception. "go do handler-for-cl2-test"))))]
          (jdi-test-handler/add-one-shot-event-handler
           handler-for-cl2-test "cl2-test")
          (jdi/enable-exception-request-states (:vm context))
          (jdi-clj/control-eval context `(require 'ritz.repl-utils.classloader))
          (jdi-clj/remote-thread
           context (:control-thread context) {}
           form {:name "cl2-test"})
          (is (= {:v-1 1 :v-hello "\"hello\"" :v-str-1 "1"
                  :ver "{:major 1, :minor 4, :incremental 0, :qualifier nil}"
                  :c-opts "{:x 1}" :c-opts2 "{:y 1}"}
                 @test-finished))
          (is (not (jdi-test-handler/one-shot-error?)))

          (jdi/disable-exception-request-states vm))
        (finally
         (jdi/shutdown context))))))
