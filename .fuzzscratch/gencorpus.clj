(ns gencorpus
  "Byte-level CBOR document generator. Emits a corpus file:
     repeat: [4-byte BE length][bytes]
   Deterministic given a seed."
  (:import (java.io DataOutputStream FileOutputStream BufferedOutputStream ByteArrayOutputStream)))

(def ^:dynamic *r* nil)
(def tags? (atom true))

(defn ri [n] (.nextInt ^java.util.Random *r* (int n)))
(defn rb [] (zero? (ri 2)))
(defn pick [coll] (nth coll (ri (count coll))))

;; weighted choice: [[w v] ...]
(defn wpick [pairs]
  (let [total (reduce + (map first pairs))
        x (ri total)]
    (loop [acc 0 [[w v] & more] pairs]
      (if (< x (+ acc w)) v (recur (+ acc w) more)))))

(defn emit-head!
  "Write major type mt with argument n, choosing an encoding width.
   :min = shortest, :any = random legal, :nonmin = deliberately long."
  [^ByteArrayOutputStream o mt n mode]
  (let [n (long n)
        widths (cond
                 (neg? n) [8]                          ; unsigned interpretation, 8-byte only
                 (= mode :min) [(cond (< n 24) 0 (< n 0x100) 1 (< n 0x10000) 2 (< n 0x100000000) 4 :else 8)]
                 (= mode :nonmin) (cond
                                    (< n 24) [1 2 4 8]
                                    (< n 0x100) [2 4 8]
                                    (< n 0x10000) [4 8]
                                    (< n 0x100000000) [8]
                                    :else [8])
                 :else (cond
                         (< n 24) [0 1 2 4 8]
                         (< n 0x100) [1 2 4 8]
                         (< n 0x10000) [2 4 8]
                         (< n 0x100000000) [4 8]
                         :else [8]))
        w (pick widths)]
    (case (int w)
      0 (.write o (int (bit-or (bit-shift-left mt 5) n)))
      1 (do (.write o (bit-or (bit-shift-left mt 5) 24)) (.write o (int (bit-and n 0xff))))
      2 (do (.write o (bit-or (bit-shift-left mt 5) 25))
            (dotimes [i 2] (.write o (int (bit-and (bit-shift-right n (* 8 (- 1 i))) 0xff)))))
      4 (do (.write o (bit-or (bit-shift-left mt 5) 26))
            (dotimes [i 4] (.write o (int (bit-and (bit-shift-right n (* 8 (- 3 i))) 0xff)))))
      8 (do (.write o (bit-or (bit-shift-left mt 5) 27))
            (dotimes [i 8] (.write o (int (bit-and (unsigned-bit-shift-right n (* 8 (- 7 i))) 0xff))))))))

(def small-tags [0 1 2 3 4 5 16 18 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41
                 63 64 65 66 67 68 69 70 71 72 73 74 75 76 77 78 79 80 81 82 83 84 85 86
                 96 97 98 100 101 103 104 110 111 112 120 258 259 260 261 262 263 264 265 266 267
                 268 269 270 271 272 1001 1002 1003 1004 55799 15309736 4294967295 -1 -2 Long/MIN_VALUE])

(defn rand-utf8-bytes [n]
  ;; mix of ASCII, valid multibyte, and raw random
  (let [o (ByteArrayOutputStream.)]
    (while (< (.size o) n)
      (case (ri 6)
        (0 1 2) (.write o (+ 32 (ri 90)))
        3 (let [cp (+ 0x80 (ri 0x700))
                s (String. (Character/toChars cp))]
            (.write o (.getBytes s "UTF-8")))
        4 (let [cp (+ 0x800 (ri 0xF000))
                s (if (<= 0xD800 cp 0xDFFF) "?" (String. (Character/toChars cp)))]
            (.write o (.getBytes s "UTF-8")))
        5 (.write o (ri 256))))
    (java.util.Arrays/copyOf (.toByteArray o) (int n))))

(declare emit!)

(defn emit-bytes-payload! [^ByteArrayOutputStream o mt depth mode]
  ;; mt 2 or 3
  (if (and (< depth 4) (= 0 (ri 8)))
    ;; indefinite
    (do (.write o (bit-or (bit-shift-left mt 5) 31))
        (dotimes [_ (ri 4)]
          (let [n (ri 6)
                payload (if (= mt 3) (rand-utf8-bytes n) (byte-array (repeatedly n #(byte (- (ri 256) 128)))))]
            ;; occasionally emit a WRONG chunk type / nested indefinite (invalid)
            (if (= 0 (ri 20))
              (emit! o (inc depth) mode)
              (do (emit-head! o mt (alength payload) mode) (.write o payload 0 (alength payload))))))
        (.write o 0xff))
    (let [n (ri 12)
          payload (if (= mt 3) (rand-utf8-bytes n) (byte-array (repeatedly n #(byte (- (ri 256) 128)))))]
      (emit-head! o mt (alength payload) mode)
      (.write o payload 0 (alength payload)))))

(defn emit! [^ByteArrayOutputStream o depth mode]
  (let [mt (if (>= depth 6)
             (wpick [[3 0] [2 1] [2 2] [2 3] [3 7]])
             (wpick [[3 0] [2 1] [2 2] [3 3] [4 4] [4 5] [(if @tags? 4 0) 6] [3 7]]))]
    (case (int mt)
      0 (emit-head! o 0 (wpick [[4 (ri 24)] [3 (ri 256)] [2 (ri 65536)] [2 (long (ri Integer/MAX_VALUE))]
                                [1 -1] [1 Long/MIN_VALUE] [1 (- (ri 1000))]]) mode)
      1 (emit-head! o 1 (wpick [[4 (ri 24)] [3 (ri 256)] [2 (ri 65536)] [2 (long (ri Integer/MAX_VALUE))]
                                [1 -1] [1 Long/MIN_VALUE]]) mode)
      2 (emit-bytes-payload! o 2 depth mode)
      3 (emit-bytes-payload! o 3 depth mode)
      4 (if (and (< depth 5) (= 0 (ri 6)))
          (do (.write o 0x9f) (dotimes [_ (ri 4)] (emit! o (inc depth) mode)) (.write o 0xff))
          (let [n (ri 4)] (emit-head! o 4 n mode) (dotimes [_ n] (emit! o (inc depth) mode))))
      5 (if (and (< depth 5) (= 0 (ri 6)))
          (do (.write o 0xbf) (dotimes [_ (ri 4)] (emit! o (inc depth) mode) (emit! o (inc depth) mode)) (.write o 0xff))
          (let [n (ri 4)] (emit-head! o 5 n mode) (dotimes [_ n] (emit! o (inc depth) mode) (emit! o (inc depth) mode))))
      6 (do (emit-head! o 6 (pick small-tags) mode) (emit! o (inc depth) mode))
      7 (case (int (wpick [[3 0] [4 1] [3 2] [3 3] [1 4]]))
          0 (.write o (bit-or 0xe0 (+ 20 (ri 4))))                 ; false/true/null/undefined
          1 (case (int (ri 3))                                     ; floats
              0 (do (.write o 0xf9) (dotimes [_ 2] (.write o (ri 256))))
              1 (do (.write o 0xfa) (dotimes [_ 4] (.write o (ri 256))))
              2 (do (.write o 0xfb) (dotimes [_ 8] (.write o (ri 256)))))
          2 (.write o (bit-or 0xe0 (ri 20)))                       ; simple 0..19
          3 (do (.write o 0xf8) (.write o (ri 256)))               ; simple 1-byte (0..31 invalid)
          4 (.write o (bit-or 0xe0 (+ 28 (ri 4))))))))             ; 28,29,30 reserved; 31 break

(defn gen-doc [^java.util.Random r mode]
  (binding [*r* r]
    (let [o (ByteArrayOutputStream.)]
      (emit! o 0 mode)
      (.toByteArray o))))

(defn -main [& [out n-str seed-str mode-str]]
  (let [n (Long/parseLong (or n-str "20000"))
        r (java.util.Random. (Long/parseLong (or seed-str "1")))
        mode (keyword (or mode-str "any"))
        _ (reset! tags? (not (re-find #"notag" (or mode-str ""))))
        mode (if (re-find #"notag" (name mode)) (keyword (clojure.string/replace (name mode) #"-?notag" "")) mode)
        mode (if (= mode (keyword "")) :any mode)]
    (with-open [dos (DataOutputStream. (BufferedOutputStream. (FileOutputStream. ^String out)))]
      (dotimes [_ n]
        (let [bs (gen-doc r mode)]
          (.writeInt dos (alength bs))
          (.write dos bs 0 (alength bs)))))
    (println "wrote" n "docs to" out)))
