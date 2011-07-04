;;; swank_fuzzy.clj --- fuzzy symbol completion, Clojure implementation.

;; Original CL implementation authors (from swank-fuzzy.lisp) below
;; Authors: Brian Downing <bdowning@lavos.net>
;;          Tobias C. Rittweiler <tcr@freebits.de>
;;          and others

;; This progam is based on the swank-fuzzy.lisp.
;; Thanks the CL implementation authors for that useful software.

(ns ritz.commands.contrib.swank-fuzzy
  (:require
   [ritz.repl-utils.fuzzy-completion :as fuzzy-completion]
   [ritz.swank.utils :as utils]
   [ritz.swank.commands :as commands]))

(defn- call-with-timeout [time-limit-in-msec proc]
  "Create a thunk that returns true if given time-limit-in-msec has been
  elapsed and calls proc with the thunk as an argument. Returns a 3 elements
  vec: A proc result, given time-limit-in-msec has been elapsed or not
  elapsed time in millisecond."
  (let [timed-out (atom false)
        start! (fn []
                 (future (do
                           (Thread/sleep time-limit-in-msec)
                           (swap! timed-out (constantly true)))))
        timed-out? (fn [] @timed-out)
        started-at (System/nanoTime)]
    (start!)
    [(proc timed-out?)
     @timed-out
     (/ (double (- (System/nanoTime) started-at)) 1000000.0)]))

(defmacro with-timeout
  "Create a thunk that returns true if given time-limit-in-msec has been
  elapsed and bind it to timed-out?. Then execute body."
  ^{:private true}
  [[timed-out? time-limit-in-msec] & body]
  `(call-with-timeout ~time-limit-in-msec (fn [~timed-out?] ~@body)))

(defn- fuzzy-format-matching [string matching]
  (let [[symbol package] (fuzzy-completion/fuzzy-extract-matching-info
                          matching string)
        result (str package (when package "/") symbol)]
    [result (.indexOf ^String result ^String symbol)]))

(defn- classify-matching [m]
  (let [make-var-meta (fn [m]
                        (fn [key]
                          (when-let [var (:var m)]
                            (when-let [var-meta (meta var)]
                              (get var-meta key)))))
        vm (make-var-meta m)]
    (set
     (filter
      identity
      [(when-not (or (vm :macro) (vm :arglists))
         :boundp)
       (when (vm :arglists) :fboundp)
       ;; (:typespec)
       ;; (:class)
       (when (vm :macro)    :macro)
       (when (special-symbol? (:symbol m)) :special-operator)
       (when (:ns-name m)   :package)
       (when (= clojure.lang.MultiFn (vm :tag))
         :generic-function)]))))
(defn- classification->string [flags]
  (format (apply str (replicate 8 "%s"))
          (if (or (:boundp flags)
                  (:constant flags)) "b" "-")
          (if (:fboundp flags) "f" "-")
          (if (:generic-function flags) "g" "-")
          (if (:class flags) "c" "-")
          (if (:typespec flags) "t" "-")
          (if (:macro flags) "m" "-")
          (if (:special-operator flags) "s" "-")
          (if (:package flags) "p" "-")))

(defn- fuzzy-convert-matching-for-emacs [string matching]
  (let [[name added-length] (fuzzy-format-matching string matching)]
    [name
     (format "%.2f" (:score matching))
     (concat (:ns-chunks matching)
             (map (fn [[offset string]] [(+ added-length offset) string])
                  (:var-chunks matching)))
     (classification->string (classify-matching matching))
     ]))

(defn- fuzzy-completion-set
  [string default-ns limit time-limit-in-msec]
  (let [[matchings interrupted? _]
        (with-timeout [timed-out? time-limit-in-msec]
          (vec (fuzzy-completion/fuzzy-generate-matchings
                string (utils/maybe-ns default-ns) timed-out?)))
        subvec1 (if (and limit
                         (> limit 0)
                         (< limit (count matchings)))
                  (fn [v] (subvec v 0 limit))
                  identity)]
    [(subvec1 (vec (map (partial fuzzy-convert-matching-for-emacs string)
                        matchings)))
     interrupted?]))

(commands/defslimefn fuzzy-completions
  [connection string default-package-name & {:keys [limit time-limit-in-msec]}]
  (let [[xs x] (fuzzy-completion-set string default-package-name
                                     limit time-limit-in-msec)]
    (list
     (seq (map (fn [[symbol score chunks class]]
                 (list symbol score (map (partial apply list) chunks) class))
               xs))
     (when x 't))))

(commands/defslimefn fuzzy-completion-selected [_ _ _] nil)
