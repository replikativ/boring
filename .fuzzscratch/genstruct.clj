(ns genstruct
  "Structure-aware CBOR generator: tag-27 record frames, stringref namespaces,
  RFC 9581 time maps, RFC 8746 typed arrays, tag 40/1040 multi-dim, shaped
  arrays (39649) and index-frame-shaped footers. Random bytes never reach these."
  (:import (java.io DataOutputStream FileOutputStream BufferedOutputStream ByteArrayOutputStream)))

(def ^:dynamic *r* nil)
(defn ri [n] (.nextInt ^java.util.Random *r* (int n)))
(defn pick [coll] (nth (vec coll) (ri (count coll))))
(defn wpick [pairs]
  (let [total (reduce + (map first pairs)) x (ri total)]
    (loop [acc 0 [[w v] & more] pairs]
      (if (< x (+ acc w)) v (recur (+ acc w) more)))))

;; ---- tiny CBOR emitter -------------------------------------------------
(defn hd [^ByteArrayOutputStream o mt n]
  (let [n (long n)]
    (cond
      (and (>= n 0) (< n 24)) (.write o (int (bit-or (bit-shift-left mt 5) n)))
      (and (>= n 0) (< n 0x100)) (do (.write o (bit-or (bit-shift-left mt 5) 24)) (.write o (int n)))
      (and (>= n 0) (< n 0x10000)) (do (.write o (bit-or (bit-shift-left mt 5) 25))
                                       (.write o (int (bit-shift-right n 8))) (.write o (int (bit-and n 0xff))))
      (and (>= n 0) (< n 0x100000000)) (do (.write o (bit-or (bit-shift-left mt 5) 26))
                                           (dotimes [i 4] (.write o (int (bit-and (bit-shift-right n (* 8 (- 3 i))) 0xff)))))
      :else (do (.write o (bit-or (bit-shift-left mt 5) 27))
                (dotimes [i 8] (.write o (int (bit-and (unsigned-bit-shift-right n (* 8 (- 7 i))) 0xff))))))))

(defn u [o n] (hd o 0 n))
(defn nint [o n] (hd o 1 n))                      ; encodes -1-n
(defn bstr [o ^bytes bs] (hd o 2 (alength bs)) (.write ^ByteArrayOutputStream o bs 0 (alength bs)))
(defn tstr [o ^String s] (let [b (.getBytes s "UTF-8")] (hd o 3 (alength b)) (.write ^ByteArrayOutputStream o b 0 (alength b))))
(defn arr [o n] (hd o 4 n))
(defn mp [o n] (hd o 5 n))
(defn tg [o n] (hd o 6 n))
(defn simple [o n] (if (< n 24) (.write ^ByteArrayOutputStream o (bit-or 0xe0 n))
                       (do (.write ^ByteArrayOutputStream o 0xf8) (.write ^ByteArrayOutputStream o (int n)))))
(defn f64 [o ^double d]
  (.write ^ByteArrayOutputStream o 0xfb)
  (let [b (Double/doubleToRawLongBits d)]
    (dotimes [i 8] (.write ^ByteArrayOutputStream o (int (bit-and (unsigned-bit-shift-right b (* 8 (- 7 i))) 0xff))))))
(defn int! [o ^long v] (if (neg? v) (nint o (- (- v) 1)) (u o v)))

(defn rbytes [n] (byte-array (repeatedly n #(byte (- (ri 256) 128)))))

;; ---- scalar filler -----------------------------------------------------
(declare emit-any!)
(defn emit-scalar! [o]
  (case (int (ri 10))
    0 (u o (ri 100))
    1 (nint o (ri 100))
    2 (tstr o (apply str (repeatedly (ri 6) #(char (+ 97 (ri 26))))))
    3 (bstr o (rbytes (ri 6)))
    4 (.write ^ByteArrayOutputStream o (+ 0xf4 (ri 4)))
    5 (f64 o (- (.nextDouble ^java.util.Random *r*) 0.5))
    6 (simple o (ri 256))
    7 (do (arr o 0))
    8 (do (mp o 0))
    9 (u o (long (ri Integer/MAX_VALUE)))))

(defn emit-any! [o d]
  (if (>= d 3) (emit-scalar! o)
      (case (int (ri 4))
        0 (emit-scalar! o)
        1 (let [n (ri 3)] (arr o n) (dotimes [_ n] (emit-any! o (inc d))))
        2 (let [n (ri 3)] (mp o n) (dotimes [_ n] (emit-any! o (inc d)) (emit-any! o (inc d))))
        3 (emit-scalar! o))))

;; ---- generators --------------------------------------------------------
(def frame-names
  ["clojure/sorted-map" "clojure/sorted-set" "clojure/with-meta" "clojure/char"
   "clojure/ex-info" "java/throwable" "java/boolean-array" "java/char-array"
   "java/string-array" "java/object-array" "java/period" "clojure/queue"
   "boring/index" "clojure/ratio" "clojure/var" "java/uuid" "unknown/thing"
   "" "clojure/" "/x" "clojure/sorted-map "])

(defn gen-frame27 [o]
  (tg o 27)
  (arr o (wpick [[10 2] [1 1] [1 3] [1 0]]))
  (tstr o (pick frame-names))
  (case (int (ri 6))
    0 (let [n (ri 4)] (arr o n) (dotimes [_ n] (emit-any! o 2)))
    1 (let [n (ri 4)] (mp o n) (dotimes [_ n] (emit-any! o 2) (emit-any! o 2)))
    2 (emit-scalar! o)
    3 (tstr o (pick ["P1Y2M3D" "PT1S" "" "x" "P" "a" "ab" "😀"]))
    4 (do (arr o 2) (emit-any! o 2) (emit-any! o 2))
    5 (do (arr o 3) (emit-any! o 2) (emit-any! o 2) (emit-any! o 2)))
  (dotimes [_ (max 0 (- (ri 2) 1))] (emit-scalar! o)))

(defn gen-stringref [o]
  ;; tag 256 namespace holding an array with literal strings and tag-25 refs
  (when (zero? (ri 4)) (tg o 256))
  (tg o 256)
  (let [n (+ 1 (ri 5))]
    (arr o n)
    (dotimes [_ n]
      (case (int (ri 6))
        0 (tstr o (apply str (repeatedly (+ 1 (ri 12)) #(char (+ 97 (ri 26))))))
        1 (bstr o (rbytes (+ 1 (ri 12))))
        2 (do (tg o 25) (u o (ri 8)))                       ; ref (often out of range)
        3 (do (tg o 39) (tstr o (apply str (repeatedly (+ 1 (ri 8)) #(char (+ 97 (ri 26)))))))
        4 (do (tg o 39) (tg o 25) (u o (ri 8)))              ; ident-by-ref
        5 (do (tg o 256) (arr o 1) (do (tg o 25) (u o (ri 4))))))))

(def ta-tags (vec (concat (range 64 88))))
(defn gen-typed-array [o]
  (let [t (pick ta-tags)]
    (tg o t)
    (case (int (ri 8))
      0 (bstr o (rbytes (ri 18)))                    ; often not a multiple
      1 (bstr o (rbytes (* 8 (ri 3))))
      2 (u o (ri 20))                                ; wrong content type
      3 (do (arr o 1) (u o 1))
      4 (do (.write ^ByteArrayOutputStream o 0x5f)   ; indefinite byte string
            (dotimes [_ (ri 3)] (bstr o (rbytes (ri 5))))
            (.write ^ByteArrayOutputStream o 0xff))
      5 (tstr o "abcd")
      6 (bstr o (rbytes (* 4 (ri 4))))
      7 (bstr o (rbytes (* 2 (ri 5)))))))

(defn gen-time-map [o]
  (tg o (pick [1001 1002 1003]))
  (let [n (+ 1 (ri 4))]
    (mp o n)
    (dotimes [_ n]
      (case (int (ri 8))
        0 (do (u o 1) (int! o (- (ri 2000) 1000)))
        1 (do (u o 1) (f64 o (- (.nextDouble ^java.util.Random *r*) 0.5)))
        2 (do (nint o (dec (pick [3 6 9 12 15 18]))) (u o (ri 1000000000)))
        3 (do (nint o (ri 20)) (emit-scalar! o))
        4 (do (u o (ri 10)) (emit-scalar! o))
        5 (do (tstr o (pick ["a" "tz" ""])) (emit-scalar! o))
        6 (do (u o 1) (do (tg o 2) (bstr o (rbytes (+ 1 (ri 12))))))
        7 (do (int! o (pick [-1 -3 -6 -9 1 4 5 0 2])) (emit-scalar! o))))))

(defn gen-multidim [o]
  (tg o (pick [40 1040]))
  (arr o (wpick [[8 2] [1 1] [1 3]]))
  (let [nd (+ 1 (ri 3))]
    (arr o nd)
    (dotimes [_ nd] (u o (ri 4))))
  (case (int (ri 4))
    0 (let [n (ri 9)] (arr o n) (dotimes [_ n] (u o (ri 10))))
    1 (do (tg o (pick [64 65 70 72 77 78 79 85 86])) (bstr o (rbytes (* 8 (ri 3)))))
    2 (emit-scalar! o)
    3 (let [n (ri 6)] (arr o n) (dotimes [_ n] (emit-any! o 2)))))

(defn gen-shaped [o]
  (tg o 39649)
  (arr o (wpick [[8 2] [1 3] [1 1]]))
  (let [nk (+ 1 (ri 3))]
    (arr o nk)
    (dotimes [_ nk] (case (int (ri 3))
                      0 (tstr o (apply str (repeatedly (+ 1 (ri 4)) #(char (+ 97 (ri 26))))))
                      1 (do (tg o 39) (tstr o (apply str (repeatedly (+ 1 (ri 4)) #(char (+ 97 (ri 26)))))))
                      2 (u o (ri 5)))))
  (let [nrow (ri 4)]
    (arr o nrow)
    (dotimes [_ nrow]
      (let [n (ri 5)] (arr o n) (dotimes [_ n] (emit-scalar! o))))))

(defn gen-tag-misc [o]
  (let [t (pick [0 1 2 3 4 5 30 32 35 37 258 1004 100 55799 24 63])]
    (tg o t)
    (case (int (ri 7))
      0 (emit-scalar! o)
      1 (tstr o (pick ["2020-01-01" "2020-01-01T00:00:00Z" "1970-01-01T00:00:00.5Z"
                       "+10000-01-01T00:00:00Z" "2020-01-01T24:00:00Z" "2020-02-30"
                       "2020-01-01T00:00:60Z" "" "0000-01-01" "9999-12-31"]))
      2 (bstr o (rbytes (pick [0 1 15 16 17])))
      3 (do (arr o 2) (int! o (- (ri 100) 50)) (int! o (- (ri 100) 50)))
      4 (do (arr o 2) (f64 o 1.5) (u o 3))
      5 (do (arr o (ri 4)) (dotimes [_ 2] (emit-scalar! o)))
      6 (do (tg o t) (emit-scalar! o)))))

(defn gen-doc [^java.util.Random r]
  (binding [*r* r]
    (let [o (ByteArrayOutputStream.)]
      ;; sometimes wrap the construct in an outer container
      (let [wrap (ri 4)]
        (when (= wrap 1) (arr o 1))
        (when (= wrap 2) (do (mp o 1) (u o 7)))
        ((wpick [[4 gen-frame27] [3 gen-stringref] [4 gen-typed-array]
                 [3 gen-time-map] [3 gen-multidim] [3 gen-shaped] [4 gen-tag-misc]]) o))
      (.toByteArray o))))

(defn -main [& [out n-str seed-str]]
  (let [n (Long/parseLong (or n-str "20000"))
        r (java.util.Random. (Long/parseLong (or seed-str "7")))]
    (with-open [dos (DataOutputStream. (BufferedOutputStream. (FileOutputStream. ^String out)))]
      (dotimes [_ n]
        (let [bs (gen-doc r)] (.writeInt dos (alength bs)) (.write dos bs 0 (alength bs)))))
    (println "wrote" n "docs to" out)))
