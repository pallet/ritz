(ns ritz.rpc
  "Pass remote calls and responses between lisp systems using the swank-rpc
protocol. Code from Terje Norderhaug <terje@in-progress.com>."
  (:require
   [ritz.logging :as logging]
   [clojure.string :as string])
  (:import
   (java.io Writer Reader PushbackReader StringReader)))

;; ERROR HANDLING
(def swank-protocol-error (Exception. "Swank protocol error."))

;; INPUT
(defn read-chars
  ([rdr n] (read-chars rdr n false))
  ([^Reader rdr n throw-exception]
     (let [sb (StringBuilder.)]
       (dotimes [i n]
         (let [c (.read rdr)]
           (if (not= c -1)
             (.append sb (char c))
             (when throw-exception
               (throw throw-exception)))))
       (str sb))))

(defn- read-form
  "Read a form that conforms to the swank rpc protocol"
  [^Reader rdr]
  (let [c (.read rdr)]
    (condp = (char c)
        \" (let [sb (StringBuilder.)]
             (loop []
               (let [c (.read rdr)]
                 (if (= c -1)
                   (throw
                    (java.io.EOFException.
                     "Incomplete reading of quoted string."))
                   (condp = (char c)
                       \" (str sb)
                       \\ (do (.append sb (char (.read rdr)))
                              (recur))
                       (do (.append sb (char c))
                           (recur)))))))
        \( (loop [result []]
             (let [form (read-form rdr)]
               (let [c (.read rdr)]
                 (if (= c -1)
                   (throw
                    (java.io.EOFException.
                     "Incomplete reading of list."))
                   (condp = (char c)
                       \) (sequence (conj result form))
                       \space (recur (conj result form)))))))
        \' (list 'quote (read-form rdr))
        (let [sb (StringBuilder.)]
          (loop [c c]
            (if (not= c -1)
              (condp = (char c)
                  \\ (do (.append sb (char (.read rdr)))
                         (recur (.read rdr)))
                  \space (.unread rdr c)
                  \) (.unread rdr c)
                  (do (.append sb (char c))
                      (recur (.read rdr))))))
          (let [str (str sb)]
            (cond
             (Character/isDigit c) (read-string str)
             (= "nil" str) nil
             (= "t" str) true
             (.startsWith str ":") (keyword (.substring str 1))
             :else
             (if-let [m (re-matches #"([^:]+)::?(.+)" str)]
               (if (= "swank::%cursor-marker%" str)
                 :ritz/cursor-marker
                 (apply symbol (map #(string/replace % "\\." ".") (rest m))))
               (symbol str))))))))

(defn- read-packet
  [^Reader reader]
  (let [len (read-chars reader 6 swank-protocol-error)
        _ (logging/trace "rpc/read-packet length %s" len)
        len (Integer/parseInt len 16)]
    (logging/trace "rpc/read-packet length %s" len)
    (read-chars reader len swank-protocol-error)))

(defn decode-message
  "Read an rpc message encoded using the swank rpc protocol."
  [^Reader rdr]
  (let [packet (read-packet rdr)]
    (logging/trace "READ: %s\n" packet)
    (try
      (with-open [rdr (PushbackReader. (StringReader. packet))]
        (read-form rdr))
      (catch Exception e
        (list :reader-error packet e)))))

;; OUTPUT

(defmulti print-object (fn [x writer] (type x)))

(defmethod print-object :default [o, ^Writer w]
  (print-method o w))

(defmethod print-object Boolean [o, ^Writer w]
  (.write w (if o "t" "nil")))

(defmethod print-object String [^String s, ^Writer w]
  (let [char-escape-string {\" "\\\""
                            \\  "\\\\"}]
    (do (.append w \")
      (dotimes [n (count s)]
        (let [c (.charAt s n)
              e (char-escape-string c)]
          (if e (.write w e) (.append w c))))
      (.append w \"))
  nil))

(defmethod print-object clojure.lang.ISeq
  [o, ^Writer w]
  (.write w "(")
  (print-object (first o) w)
  (doseq [item (rest o)]
    (.write w " ")
    (print-object item w))
  (.write w ")"))

(defn- write-form
  [^Writer writer message]
  (print-object message writer))

(defn- write-packet
  [^Writer writer str]
  (let [len (.length str)]
    (doto writer
      (.write (format "%06x" len))
      (.write str)
      (.flush))))

(defn encode-message
  "Write an rpc message encoded using the swank rpc protocol."
  [^Writer writer message]
  (let [str (with-out-str
              (write-form *out* message)) ]
    (logging/trace "WRITE: %s\n" str)
    (write-packet writer str)))
