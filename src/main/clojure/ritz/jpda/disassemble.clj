(ns ritz.jpda.disassemble
  (:require
   [ritz.logging :as logging]))

;; http://java.sun.com/docs/books/jvms/second_edition/html/VMSpecTOC.doc.html

(declare opcodes)

(defn ubyte-value
  "convert an unsigned byte value to an int"
  [byte]
  (let [n (int byte)]
    (if (neg? n) (+ 256 n) n)))

(defn byte-for-uvalue
  "convert an int into a byte value with correct bits for an unsigned byte"
  [i]
  (byte (if (> i 127) (- i 256) i)))

        ;; bytes (loop [bytes (take n-bytes (drop 2 bytes))
        ;;              result nil]
        ;;         (if (seq bytes)
        ;;           (let [x (first bytes)]
        ;;             (if (pos? x)
        ;;               (recur (rest bytes) (concat result [(byte 0) x]))))
        ;;           result))

(defn bytes-to-int [bytes]
  (reduce #(+ (bit-shift-left %1 8) (ubyte-value %2)) 0 bytes))

(defn two-bytes-to-short [hi low]
  (+ (bit-shift-left hi 8) low))

(defn int-to-bytes [i]
  (map
   #(byte-for-uvalue (bit-and 255 %))
   [(bit-shift-right i 24) (bit-shift-right i 16) (bit-shift-right i 8) i]))

(defn long-to-bytes [i]
  (map
   #(byte-for-uvalue (bit-and 255 %))
   [(bit-shift-right i 56) (bit-shift-right i 48) (bit-shift-right i 40)
    (bit-shift-right i 32) (bit-shift-right i 24) (bit-shift-right i 16)
    (bit-shift-right i 8) i]))

(def utf-8-charset (java.nio.charset.Charset/forName "UTF-8"))


(defn utf8-pool-entry [bytes]
  (let [n-bytes (+ (* 256 (ubyte-value (first bytes)))
                   (ubyte-value (second bytes)))
        bytes (take n-bytes (drop 2 bytes))]
    [{:value (String. (byte-array bytes) utf-8-charset)
      :type :utf8}
     (+ n-bytes 3)
     1]))

(defn int-pool-entry [bytes]
  [{:value (bytes-to-int (take 4 bytes)) :type :int}
   5 1])

(defn float-pool-entry [bytes]
  [{:value (Float/intBitsToFloat (bytes-to-int (take 4 bytes))) :type :float}
   5 1])

(defn long-pool-entry [bytes]
  [{:value (bytes-to-int (take 8 bytes)) :type :long}
   9 2])

(defn double-pool-entry [bytes]
  [{:value (Double/longBitsToDouble (bytes-to-int (take 8 bytes)))
    :type :double}
   9 2])

(defn class-pool-entry
  ":index is index to class name"
  [bytes]
  [{:name-index (bytes-to-int (take 2 bytes))
    :type :class }
   3 1])

(defn string-pool-entry
  [bytes]
  [{:index (bytes-to-int (take 2 bytes))
    :type :string }
   3 1])

(defn fieldref-pool-entry
  [bytes]
  [{:class-index (bytes-to-int (take 2 bytes))
    :name-and-type-index  (bytes-to-int (take 2 (drop 2 bytes)))
    :type :fieldref }
   5 1])

(defn methodref-pool-entry
  [bytes]
  [{:class-index (bytes-to-int (take 2 bytes))
    :name-and-type-index  (bytes-to-int (take 2 (drop 2 bytes)))
    :type :methodref }
   5 1])

(defn interfacemethodref-pool-entry
  [bytes]
  [{:class-index (bytes-to-int (take 2 bytes))
    :name-and-type-index  (bytes-to-int (take 2 (drop 2 bytes)))
    :type :interfacemethodref }
   5 1])

(defn nameandtype-pool-entry
  [bytes]
  [{:name-index (bytes-to-int (take 2 bytes))
    :descriptor-index  (bytes-to-int (take 2 (drop 2 bytes)))
    :type :nameandtype }
   5 1])

(defn constant-pool-entry [bytes]
  (let [tag (first bytes)
        bytes (rest bytes)]
    (case
     (int tag)
     1 (utf8-pool-entry bytes)
     3 (int-pool-entry bytes)
     4 (float-pool-entry bytes)
     5 (long-pool-entry bytes)
     6 (double-pool-entry bytes)
     7 (class-pool-entry bytes)
     8 (string-pool-entry bytes)
     9 (fieldref-pool-entry bytes)
     10 (methodref-pool-entry bytes)
     11 (interfacemethodref-pool-entry bytes)
     12 (nameandtype-pool-entry bytes))))

(defn index-consts
  "Insert dummy index values for long and double entries"
  [consts]
  (reduce
   #(let [[const size n] %2]
      (if (= n 1)
        (conj %1 const)
        (-> %1 (conj const) (conj nil))))
   [] consts))

(defn resolve-index-values
  [consts m]
  (let [m (if-let [name-index (:name-index m)]
            (let [ni (consts (dec name-index))]
              (if-not (= (:type ni) :utf8)
                (logging/trace "invalid name %s" (pr-str ni)))
              (assoc m :name (-> ni :value)))
            m)
        m (if-let [descriptor-index (:descriptor-index m)]
            (let [d (get consts (dec descriptor-index) nil)]
              (if-not (= (:type d) :utf8)
                (logging/trace "invalid descriptor %s" (pr-str d)))
              (assoc m :descriptor (-> d :value)))
            m)
        m (if-let [name-and-type-index (:name-and-type-index m)]
            (let [nat (consts (dec name-and-type-index))]
              (if-not (= (:type nat) :nameandtype)
                (logging/trace "invalid nameandtype %s" (pr-str nat)))
              (assoc m
                :name (-> nat :name)
                :descriptor (-> nat :descriptor)))
            m)
        m (if-let [class-index (:class-index m)]
            (let [c (consts (dec class-index))]
              (if-not (= (:type c) :class)
                (logging/trace "invalid class %s" (pr-str class)))
              (assoc m :class-name (-> c :name)))
            m)
        m (if-let [index (:index m)]
            (let [s (consts (dec index))]
              (if-not (= (:type s) :utf8)
                (logging/trace "invalid string %s" (pr-str s)))
              (assoc m :value (-> s :value)))
            m)]
    m))

(defn resolve-pool-index-values
  "Lookup the index values in the consts pool."
  [consts]
  (reduce #(conj %1 (resolve-index-values consts %2)) [] consts))

(defn constant-pool [bytes]
  (let [consts (loop [bytes bytes
                      consts []]
                 (let [[const size slots :as constv] (constant-pool-entry bytes)
                       consts (conj consts constv)
                       bytes (drop size bytes)]
                   (if (seq bytes)
                     (recur bytes consts)
                     consts)))]
    (resolve-pool-index-values
     (resolve-pool-index-values
      (resolve-pool-index-values (index-consts consts))))))

(def local-indices
  {:local-0 0
   :local-1 1
   :local-2 2
   :local-3 3})

(defn implicit-arg-values
  "Obtain opcode implicit arguments"
  [const-pool args]
  (map #(hash-map :type :local-index :value (local-indices %)) args))

(defn pad-to-4byte [address]
  (let [al (rem address 4)]
    (if (pos? al) (- 4 al) 0)))

(defn explicit-arg-values
  [const-pool args address bytecode]
  (loop [args args
         address address
         bytecode bytecode
         results []
         result-types []]
    (if (seq args)
      (let [a1 (first args)
            a2 (second args)
            arg-size (if (#{:branch-byte-high :const-index-high} a1) 2 1)
            byte-size (case a1
                            :branch-byte-high 2
                            :const-index-high 2
                            :4byte-align-pad (pad-to-4byte address)
                            :int-value 4
                            :offsets (let [high (last results)
                                           low (last (butlast results))]
                                       (* 4 (- high low -1)))
                            :match-offset-pairs (* 8 (last results))
                            1)
            result (case a1
                         :branch-index-high (const-pool
                                             (dec
                                              (bytes-to-int (take 2 bytecode))))
                         :const-index-high (const-pool
                                            (dec
                                             (bytes-to-int (take 2 bytecode))))
                         :const-index (const-pool
                                       (dec
                                        (ubyte-value (first bytecode))))
                         :int-value (bytes-to-int (take 4 bytecode))
                         :offsets (let [high (last results)
                                        low (last (butlast results))]
                                    (vec
                                     (take
                                      (- high low -1)
                                      (map
                                       bytes-to-int
                                       (partition 4 bytecode)))))
                         :match-offset-pairs (let [n (last results)]
                                               (map
                                                (fn [b]
                                                  [(bytes-to-int (take 4 b))
                                                   (bytes-to-int
                                                    (take 4 (drop 4 b)))]
                                                  b)
                                                (partition 8 bytecode)))
                         :4byte-align-pad (symbol
                                           (str "pad-" (pad-to-4byte address)))
                         (first bytecode))
            result-type (case a1
                              :branch-index-high :branch
                              :const-index-high :const
                              :const-index :const
                              :offsets :tableswitch-offsets
                              :match-offset-pairs :lookupswitch-pairs
                              a1)]
        (recur
         (drop arg-size args)
         (+ address arg-size)
         (drop byte-size bytecode)
         (conj results result)
         (conj result-types result-type)))
      [results result-types])))

(defn tableswitch-size [address bytecode]
  (let [pad (pad-to-4byte (inc address))
        low (bytes-to-int (take 4 (drop (+ pad 5) bytecode)))
        high (bytes-to-int (take 4 (drop (+ pad 9) bytecode)))
        n (inc (- high low))]
    (+ pad 12 (* n 4))))

(defn lookupswitch-size
  "Size of a lookupswitch op.
   See: "
  [address bytecode]
  (let [al (rem (inc address) 4)
        pad (if (zero? al) 0 (- 4 al))
        n (bytes-to-int (take 4 (drop (+ pad 9) bytecode)))]
    (+ pad 8 (* n 8))))

(defn instruction
  "Dissasmble an instruction"
  [const-pool bytecode address]
  (let [opcode-byte (int (first bytecode))
        opcode (int (if (neg? opcode-byte) (+ 256 opcode-byte) opcode-byte))]
    (if (> opcode 202)
      {:mnemonic "impde" :code-index address}
      (let [[_ mnemonic desc args stack-in stack-out implicit-args
             size-fn] (opcodes opcode)
             arg-size (or (and size-fn (size-fn address bytecode))
                          (count args))
             [args arg-types] (explicit-arg-values
                               const-pool args (inc address) (rest bytecode))]
        {:opcode opcode
         :mnemonic mnemonic
         :args args
         :arg-types arg-types
         :implicit-args implicit-args
         :arg-size arg-size
         :code-index address}))))

(defn disassemble [const-pool bytecode]
  (loop [bytecode bytecode
         address 0
         ops []]
    (let [{:keys [arg-size] :as op} (instruction const-pool bytecode address)]
      (if-let [bytecode (seq (drop (inc arg-size) bytecode))]
        (recur bytecode (+ address (inc arg-size)) (conj ops op))
        ops))))

(def
  ^{:doc "Each element is:
             [opcode nemonic description args opstack-pop opstack-push"}
  opcodes
  [[0 :nop "Do nothing"]
   [1 :aconst_null "Push null" [] [] [:null]]
   [2 :iconst_m1 "Push int constant -1" [] [] [:int-const]]
   [3 :iconst_0 "Push int constant 0" []]
   [4 :iconst_1 "Push int constant 1" []]
   [5 :iconst_2 "Push int constant 2" []]
   [6 :iconst_3 "Push int constant 3" []]
   [7 :iconst_4 "Push int constant 4" []]
   [8 :iconst_5 "Push int constant 5" []]
   [9 :lconst_0 "Push long constant 0" []]
   [10 :lconst_1 "Push long constant 1" []]
   [11 :fconst_0 "Push float constant 0" []]
   [12 :fconst_1 "Push float constant 1" []]
   [13 :fconst_2 "Push float constant 2" []]
   [14 :dconst_0 "Push double constant 0.0" []]
   [15 :dconst_1 "Push double constant 1.0" []]
   [16 :bipush "Push byte" [:byte-value] [] [:byte-value]]
   [17 :sipush "Push short"
    [:short-value-hi :short-value-low] [] [:short-value]]
   [18 :ldc "Push item from runtime constant pool" [:const-index] [] [:value]]
   [19 :ldc_w "Push item from runtime constant pool (wide index)"
    [:const-index-high :const-index-low] [] [:value]]
   [20 :ldc2_w "Push long or double from runtime constant pool (wide index)"
    [:const-index-high :const-index-low] [] [:value]]
   [21 :iload "Load int from local variable" [:local-index] [] [:int-value]]
   [22 :lload "Load long from local variable" [:local-index] [] [:long-value]]
   [23 :fload "Load float from local variable" [:local-index] [] [:float-value]]
   [24 :dload "Load double from local variable"
    [:local-index] [] [:double-value]]
   [25 :aload "Load reference from local variable"
    [:local-index] [] [:object-ref]]
   [26 :iload_0 "Load int from local variable 0" [] [] [:int-value]]
   [27 :iload_1 "Load int from local variable 1" [] [] [:int-value]]
   [28 :iload_2 "Load int from local variable 2" [] [] [:int-value]]
   [29 :iload_3 "Load int from local variable 3" [] [] [:int-value]]
   [30 :lload_0 "Load long from local variable 0" [] [] [:long-value]]
   [31 :lload_1 "Load long from local variable 1" [] [] [:long-value]]
   [32 :lload_2 "Load long from local variable 2" [] [] [:long-value]]
   [33 :lload_3 "Load long from local variable 3" [] [] [:long-value]]
   [34 :fload_0 "Load float from local variable 0" [] [] [:float-value]]
   [35 :fload_1 "Load float from local variable 1" [] [] [:float-value]]
   [36 :fload_2 "Load float from local variable 2" [] [] [:float-value]]
   [37 :fload_3 "Load float from local variable 3" [] [] [:float-value]]
   [38 :dload_0 "Load double from local variable 0" [] [] [:double-value]]
   [39 :dload_1 "Load double from local variable 1" [] [] [:double-value]]
   [40 :dload_2 "Load double from local variable 2" [] [] [:double-value]]
   [41 :dload_3 "Load double from 3rd local variable" [] [] [:double-value]]
   [42 :aload_0 "Load reference from local variable 0"
    [] [] [:object-ref] [:local-0]]
   [43 :aload_1 "Load reference from local variable 1"
    [] [] [:object-ref] [:local-1]]
   [44 :aload_2 "Load reference from local variable 2"
    [] [] [:object-ref] [:local-2]]
   [45 :aload_3 "Load reference from local variable 3"
    [] [] [:object-ref] [:local-3]]
   [46 :iaload "Load int from array"
    [] [:array-ref :array-index] [:int-value]]
   [47 :laload "Load long from array"
    [] [:array-ref :array-index] [:long-value]]
   [48 :faload "Load float from array"
    [] [:array-ref :array-index] [:float-value]]
   [49 :daload "Load double from array"
    [] [:array-ref :array-index] [:double-value]]
   [50 :aaload "Load reference from array"
    [] [:array-ref :array-index] [:reference]]
   [51 :baload "Load byte or boolean from array"
    [] [:array-ref :index] [:value]]
   [52 :caload "Load char from array"
    [] [:array-ref :index] [:value]]
   [53 :saload "Load short from array"
    [] [:array-ref :index] [:value]]
   [54 :istore "Store int into local variable"
    [:local-index] [:int-value] []]
   [55 :lstore "Store long into local variable"
    [:local-index] [:long-value] []]
   [56 :fstore "Store float into local variable"
    [:local-index] [:float-value] []]
   [57 :dstore "Store double into local variable"
    [:local-index] [:double-value] []]
   [58 :astore "Store reference into local variable"
    [:local-index] [:object-ref] []]
   [59 :istore_0 "Store int into local variable 0" [] [:int-value] []]
   [60 :istore_1 "Store int into local variable 1" [] [:int-value] []]
   [61 :istore_2 "Store int into local variable 2" [] [:int-value] []]
   [62 :istore_3 "Store int into local variable 3" [] [:int-value] []]
   [63 :lstore_0 "Store long into local variable 0" [] [:long-value] []]
   [64 :lstore_1 "Store long into local variable 1" [] [:long-value] []]
   [65 :lstore_2 "Store long into local variable 2" [] [:long-value] []]
   [66 :lstore_3 "Store long into local variable 3" [] [:long-value] []]
   [67 :fstore_0 "Store float into local variable 0" [] [:float-value] []]
   [68 :fstore_1 "Store float into local variable 1" [] [:float-value] []]
   [69 :fstore_2 "Store float into local variable 2" [] [:float-value] []]
   [70 :fstore_3 "Store float into local variable 3" [] [:float-value] []]
   [71 :dstore_0 "Store double into local variable 0" [] [:double-value] []]
   [72 :dstore_1 "Store double into local variable 1" [] [:double-value] []]
   [73 :dstore_2 "Store double into local variable 2" [] [:double-value] []]
   [74 :dstore_3 "Store double into local variable 3" [] [:double-value] []]
   [75 :astore_0 "Store reference into first local variable"
    [] [:object-ref] [] [:local-0]]
   [76 :astore_1 "Store reference into second local variable"
    [] [:object-ref] [] [:local-1]]
   [77 :astore_2 "Store reference into third local variable"
    [] [:object-ref] [] [:local-2]]
   [78 :astore_3 "Store reference into fourth local variable"
    [] [:object-ref] [] [:local-3]]
   [79 :iastore "Store into int array"
    [] [:array-ref :array-index :value] []]
   [80 :lastore "Store into long array"
    [] [:array-ref :array-index :value] []]
   [81 :fastore "Store into float array"
    [] [:array-ref :array-index :value] []]
   [82 :dastore "Store into double array"
    [] [:array-ref :array-index :value] []]
   [83 :aastore "Store into reference array"
    [] [:array-ref :array-index :value] []]
   [84 :bastore "Store into byte or boolean array"
    [] [:array-ref :array-index :value] []]
   [85 :castore  "Store into char array"
    [] [:array-ref :array-index :value] []]
   [86 :sastore "Store into short array"
    [] [:array-ref :array-index :value] []]
   [87 :pop "Pop the top operand stack value" [] [:value] []]
   [88 :pop2 "Pop the top one or two operand stack values" [] [:value] []]
   [89 :dup "Duplicate the top operand stack value"
    [] [:value] [:value :value]]
   [90 :dup_x1
    "Duplicate the top operand stack value and insert two values down"
    [] [:value] [:value :value]]
   [91 :dup_x2
    "Duplicate the top operand stack value and insert two or three values down"
    [] [:value] [:value :value]]
   [92 :dup2
    "Duplicate the top one or two operand stack values"
    [] [:value] [:value :value]]
   [93 :dup2_x1
    "Duplicate the top 1 or 2 stack values and insert 2 or 3 values down"
    [] [:value] [:value :value]]
   [94 :dup2_x2
    "Duplicate the top one or two operand stack values and insert two, three, or four values down"
    [] [:value] [:value :value]]
   [95 :swap "Swap the top two operand stack values"
    [] [:value :value] [:value :value]]
   [96 :iadd "Add int" [] [:int-value :int-value] [:int-value]]
   [97 :ladd "Add long" [] [:long-value :long-value] [:long-value]]
   [98 :fadd "Add float" [] [:float-value :float-value] [:float-value]]
   [99 :dadd "Add double" [] [:double-value :double-value] [:double-value]]
   [100 :isub "Subtract int" [] [:int-value :int-value] [:int-value]]
   [101 :lsub "Subtract long" [] [:long-value :long-value] [:long-value]]
   [102 :fsub "Subtract float" [] [:float-value :float-value] [:float-value]]
   [103 :dsub "Subtract double"
    [] [:double-value :double-value] [:double-value]]
   [104 :imul "Multiply int" [] [:int-value :int-value] [:int-value]]
   [105 :lmul "Multiply long" [] [:long-value :long-value] [:long-value]]
   [106 :fmul "Multiply float" [] [:float-value :float-value] [:float-value]]
   [107 :dmul "Multiply double"
    [] [:double-value :double-value] [:double-value]]
   [108 :idiv "Divide int" [] [:int-value :int-value] [:int-value]]
   [109 :ldiv "Divide long" [] [:long-value :long-value] [:long-value]]
   [110 :fdiv "Divide float" [] [:float-value :float-value] [:float-value]]
   [111 :ddiv "Divide double" [] [:double-value :double-value] [:double-value]]
   [112 :irem "Int remainder" [] [:int-value] [:int-value]]
   [113 :lrem "Long remainder" [] [:long-value] [:long-value]]
   [114 :frem "Float remainder" [] [:float-value] [:float-value]]
   [115 :drem "Double remainder" [] [:double-value] [:double-value]]
   [116 :ineg "Negate int" [] [:int-value] [:int-value]]
   [117 :lneg "Negate long" [] [:long-value] [:long-value]]
   [118 :fneg "Negate float" [] [:float-value] [:float-value]]
   [119 :dneg "Negate double" [] [:double-value] [:double-value]]
   [120 :ishl "Shift left int" [] [:int-value :int-value] [:int-value]]
   [121 :lshl "Shift left int" [] [:int-value :int-value] [:int-value]]
   [122 :ishr "Shift right long" [] [:int-value :int-value] [:int-value]]
   [123 :lshr "Shift right long" [] [:int-value :int-value] [:int-value]]
   [124 :iushr "Logical shift right int"
    [] [:int-value :int-value] [:int-value]]
   [125 :lushr "Logical shift right long"
    [] [:int-value :int-value] [:int-value]]
   [126 :iand "Boolean AND int" [] [:int-value :int-value] [:int-value]]
   [127 :land "Boolean AND long" [] [:long-value :long-value] [:long-value]]
   [128 :ior "Boolean OR int" [] [:int-value :int-value] [:int-value]]
   [129 :lor "Boolean OR long" [] [:long-value :long-value] [:long-value]]
   [130 :ixor "Boolean XOR int" [] [:int-value :int-value] [:int-value]]
   [131 :lxor "Boolean XOR long" [] [:long-value :long-value] [:long-value]]
   [132 :iinc "Increment local variable by constant"
    [:local-index :int-value] [] []]
   [133 :i2l "Convert int to long" [] [:int-value] [:long-value]]
   [134 :i2f "Convert int to float" [] [:int-value] [:float-value]]
   [135 :i2d "Convert int to double" [] [:int-value] [:double-value]]
   [136 :l2i "Convert long to int" [] [:long-value] [:int-value]]
   [137 :l2f "Convert long to float" [] [:long-value] [:float-value]]
   [138 :l2d "Convert long to double" [] [:long-value] [:double-value]]
   [139 :f2i "Convert float to int" [] [:float-value] [:int-value]]
   [140 :f2l "Convert float to long" [] [:float-value] [:long-value]]
   [141 :f2d "Convert float to double" [] [:float-value] [:double-value]]
   [142 :d2i "Convert double to int" [] [:double-value] [:int-value]]
   [143 :d2l "Convert double to long" [] [:double-value] [:long-value]]
   [144 :d2f "Convert double to float" [] [:double-value] [:float-value]]
   [145 :i2b "Convert int to byte" [] [:int-value] [:byte-value]]
   [146 :i2c "Convert int to char" [] [:int-value] [:char-value]]
   [147 :i2s "Convert int to short" [] [:int-value] [:int-value]]
   [148 :lcmp "Compare long"
    [] [:long-value :long-value] [:int-value]]
   [149 :fcmpl "Compare float (-1 if NaN)"
    [] [:float-value :float-value] [:int-value]]
   [150 :fcmpg "Compare float (1 if NaN)"
    [] [:float-value :float-value] [:int-value]]
   [151 :dcmpl "Compare double (-1 if NaN)"
    [] [:double-value :double-value] [:int-value]]
   [152 :dcmpg "Compare double (1 if NaN)"
    [] [:double-value :double-value] [:int-value]]
   [153 :ifeq "Branch if int comparison with 0 equal"
    [:branch-byte-high :branch-byte-low] [:int-type]]
   [154 :ifne  "Branch if int comparison with 0 not equal"
    [:branch-byte-high :branch-byte-low] [:int-type]]
   [155 :iflt  "Branch if int comparison with 0 less"
    [:branch-byte-high :branch-byte-low] [:int-type]]
   [156 :ifge  "Branch if int comparison with 0 greater equal"
    [:branch-byte-high :branch-byte-low] [:int-type]]
   [157 :ifgt "Branch if int comparison with 0 greater"
    [:branch-byte-high :branch-byte-low] [:int-type]]
   [158 :ifle "Branch if int comparison with 0 less or equal"
    [:branch-byte-high :branch-byte-low] [:int-type]]
   [159 :if_icmpeq "Branch if int comparison equal"
    [:branch-byte-high :branch-byte-low] [:int-type :int-type]]
   [160 :if_icmpne  "Branch if int comparison not equal"
    [:branch-byte-high :branch-byte-low] [:int-type :int-type]]
   [161 :if_icmplt "Branch if int comparison less than"
    [:branch-byte-high :branch-byte-low] [:int-type :int-type]]
   [162 :if_icmpge "Branch if int comparison greater or equal"
    [:branch-byte-high :branch-byte-low] [:int-type :int-type]]
   [163 :if_icmpgt "Branch if int comparison greater than"
    [:branch-byte-high :branch-byte-low] [:int-type :int-type]]
   [164 :if_icmple "Branch if int comparison less or equal"
    [:branch-byte-high :branch-byte-low] [:int-type :int-type]]
   [165 :if_acmpeq "Branch if reference comparison equal"
    [:branch-byte-high :branch-byte-low] [:reference-type :reference-type] []]
   [166 :if_acmpne "Branch if reference comparison not equal"
    [:branch-byte-high :branch-byte-low] [:reference-type :reference-type] []]
   [167 :goto "Branch always" [:branch-byte-high :branch-byte-low] [] []]
   [168 :jsr "Jump subroutine" [:branch-byte-high :branch-byte-low] [] [:address]]
   [169 :ret "Return from subroutine" [] [:return-address] []]
   [170 :tableswitch "Access jump table by index and jump"
    [:4byte-align-pad :int-value :int-value :int-value :offsets] [:int-value] []
    [] tableswitch-size]
   [171 :lookupswitch "Access jump table by key match and jump"
    [:4byte-align-pad :int-value :int-value :match-offset-pairs] [:int-value] []
    [] lookupswitch-size]
   [172 :ireturn "Return int from method" [] [:int-value] []]
   [173 :lreturn "Return long from method" [] [:long-value] []]
   [174 :freturn "Return float from method" [] [:float-value] []]
   [175 :dreturn "Return double from method" [] [:double-value] []]
   [176 :areturn "Return reference from method" [] [:object-ref] []]
   [177 :return "Return void from method" [] [] []]
   [178 :getstatic "Get static field from class"
    [:const-index-high :const-index-low] [] [:value]]
   [179 :putstatic "Set static field in class"
    [:const-index-high :const-index-low] [:value] []]
   [180 :getfield "Fetch field from object"
    [:const-index-high :const-index-low] [:object-ref] [:value]]
   [181 :putfield "Set field in object"
    [:const-index-high :const-index-low] [:object-ref :value] []]
   [182 :invokevirtual "Invoke instance method; dispatch based on class"
    [:const-index-high :const-index-low] [:object-ref :args] [:value]]
   [183 :invokespecial "Invoke instance method; special handling for superclass, private, and instance initialization method invocations"
    [:const-index-high :const-index-low] [:object-ref :args] [:value]]
   [184 :invokestatic "Invoke a class (static) method"
    [:const-index-high :const-index-low] [:object-ref :args] [:value]]
   [185 :invokeinterface "Invoke interface method"
    [:const-index-high :const-index-low :count :zero]
    [:object-ref :args] [:value]]
   [186 :xxxunusedxxx1]
   [187 :new "Create new object"
    [:const-index-high :const-index-low] [] [:object-ref]]
   [188 :newarray "Create new array"
    [:int-value] [] [:array-ref]]
   [189 :anewarray "Create new array of reference"
    [:const-index-high :const-index-low] [:int-value] [:array-ref]]
   [190 :arraylength "Get length of array" [] [:array-ref] [:int-value]]
   [191 :athrow "Throw exception or error" [] [:object-ref] [:object-ref]]
   [192 :checkcast "Check whether object is of given type"
    [:const-index-high :const-index-low] [:object-ref] [:object-ref]]
   [193 :instanceof "Determine if object is of given type"
    [:const-index-high :const-index-low] [:object-ref] [:int-value]]
   [194 :monitorenter "Enter monitor for object" [] [:object-ref] []]
   [195 :monitorexit "Exit monitor for object" [] [:object-ref] []]
   [196 :wide "Extend local variable index by additional bytes"
    [:opcode :local-index-high :local-index-low :const-index-high
     :const-index-low] [] []]
   [197 :multianewarray "Create new multidimensional array"
    [:const-index-high :const-index-low :int-value] [:int-values] [:array-ref]]
   [198 :ifnull "Branch if reference null"
    [:branch-byte-high :branch-byte-low] [:object-ref] []]
   [199 :ifnonnull "Branch if reference not null"
    [:branch-byte-high :branch-byte-low] [:object-ref] []]
   [200 :goto_w "Branch always (wide index)"
    [:branch-byte-high-hi :branch-byte-low
     :branch-byte-low-high :branch-byte-low-low] [] []]
   [201 :jsr_w "Jump subroutine (wide index)"
    [:branch-byte-high-hi :branch-byte-low
     :branch-byte-low-high :branch-byte-low-low] [] []]
   [202 :breakpoint "Breakpoint" [] [] []]
   [254 :impdep1 "Implementation dependent" [] [] []]
   [255 :impdep2 "Implementation dependent" [] [] []]
   ])
