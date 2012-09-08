(defproject ritz/ritz-nrepl "0.4.2-SNAPSHOT"
  :description "nREPL server using ritz"
  :url "https://github.com/pallet/ritz"
  :scm {:url "git@github.com:pallet/ritz.git"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/tools.nrepl "0.2.0-beta9"
                  :exclusions [org.clojure/clojure]]
                 [ritz/ritz-debugger "0.4.2-SNAPSHOT"]]
  ;; :dummy [~(require 'clojure.tools.nrepl.server)
  ;;         ~(alter-var-root
  ;;           (ns-resolve 'clojure.tools.nrepl.server 'default-middlewares)
  ;;           (constantly
  ;;            [(ns-resolve 'clojure.tools.nrepl.middleware 'wrap-describe)
  ;;             ;; (ns-resolve 'clojure.tools.nrepl.middleware.interruptible-eval
  ;;             ;;  'interruptible-eval)
  ;;             (ns-resolve
  ;;              'clojure.tools.nrepl.middleware.load-file 'wrap-load-file)
  ;;             (ns-resolve 'clojure.tools.nrepl.middleware.session 'add-stdin)
  ;;             (ns-resolve 'clojure.tools.nrepl.middleware.session 'session)]))]
  ;; :profiles
  ;; {:dev {:repl-options
  ;;        {:nrepl-middleware
  ;;         [ritz.nrepl.middleware.tracking-eval/wrap-source-forms
  ;;          ritz.nrepl.middleware.tracking-eval/tracking-eval
  ;;          ritz.nrepl.middleware.simple-complete/wrap-simple-complete]}}}
  )
