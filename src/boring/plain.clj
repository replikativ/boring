(ns boring.plain
  "Portable reader and writer for definite-length CBOR and Boring's keyword
  and millisecond timestamp tags. Intended for small, unindexed store values."
  (:import [java.io ByteArrayOutputStream]
           [java.time Instant]
           [java.util Date]))

(defn- head! [out major n]
  (let [width (cond (< n 24) 0 (<= n 255) 1 (<= n 65535) 2
                    (<= n 4294967295) 4 :else 8)]
    (.write ^ByteArrayOutputStream out
            (+ (* 32 major) (case width 0 n 1 24 2 25 4 26 8 27)))
    (doseq [i (reverse (range width))]
      (.write ^ByteArrayOutputStream out (bit-and 255 (unsigned-bit-shift-right n (* i 8)))))))

(defn- emit! [out v]
  (cond
    (nil? v) (.write ^ByteArrayOutputStream out 246)
    (boolean? v) (.write ^ByteArrayOutputStream out (if v 245 244))
    (and (integer? v) (<= Long/MIN_VALUE v Long/MAX_VALUE))
    (head! out (if (neg? v) 1 0) (if (neg? v) (bit-not v) v))
    (string? v) (let [bs (.getBytes ^String v "UTF-8")]
                  (head! out 3 (alength bs))
                  (.write ^ByteArrayOutputStream out ^bytes bs))
    (keyword? v) (do (head! out 6 39) (emit! out (str v)))
    (instance? Date v) (do (head! out 6 0)
                           (emit! out (str (Instant/ofEpochMilli (.getTime ^Date v)))))
    (vector? v) (do (head! out 4 (count v)) (doseq [x v] (emit! out x)))
    (and (map? v) (not (record? v))) (do (head! out 5 (count v))
                                          (doseq [[k x] v] (emit! out k) (emit! out x)))
    :else (throw (ex-info "Unsupported plain CBOR value" {:type (type v)}))))

(defn encode [v]
  (let [out (ByteArrayOutputStream.)]
    (emit! out v)
    (.toByteArray out)))

(defn decode [bs]
  (let [pos (volatile! 0)
        size (alength bs)]
    (letfn [(octet []
              (when (>= @pos size) (throw (ex-info "Truncated CBOR" {:offset @pos})))
              (let [v (bit-and 255 (aget bs @pos))] (vswap! pos inc) v))
            (arg [n]
              (if (< n 24) n
                  (let [width (case n 24 1 25 2 26 4 27 8
                                    (throw (ex-info "Unsupported CBOR head" {:n n})))]
                    (loop [left width, value 0]
                      (if (zero? left) value
                          (let [b (octet)
                                _ (when (> value (quot (- Long/MAX_VALUE b) 256))
                                    (throw (ex-info "Unsigned argument exceeds signed long" {:offset @pos})))
                                next (+ (* value 256) b)]
                            (recur (dec left) next)))))))
            (item [depth]
              (when (> depth 128) (throw (ex-info "CBOR nesting limit" {:offset @pos})))
              (let [h (octet) major (quot h 32)
                    n (if (= major 7) (mod h 32) (arg (mod h 32)))
                    child #(item (inc depth))]
                (case major
                  0 n
                  1 (bit-not n)
                  3 (do (when (> n (- size @pos)) (throw (ex-info "Truncated text" {:offset @pos})))
                        (let [raw (byte-array (repeatedly n #(unchecked-byte (octet))))
                              text (String. ^bytes raw "UTF-8")]
                          (when-not (= (seq raw) (seq (.getBytes ^String text "UTF-8")))
                            (throw (ex-info "Invalid UTF-8" {:offset @pos})))
                          text))
                  4 (vec (repeatedly n child))
                  5 (loop [left n, m (array-map)]
                      (if (zero? left) m
                          (let [k (child) v (child)]
                            (when (contains? m k) (throw (ex-info "Duplicate CBOR key" {:key k})))
                            (recur (dec left) (assoc m k v)))))
                  6 (let [v (child)]
                      (case n
                        0 (Date. (.toEpochMilli (Instant/parse v)))
                        39 (if (and (string? v) (.startsWith ^String v ":"))
                             (keyword (subs v 1))
                             (throw (ex-info "Unsupported identifier" {:value v})))
                        (throw (ex-info "Unsupported CBOR tag" {:tag n}))))
                  7 (case n 20 false 21 true 22 nil (throw (ex-info "Unsupported simple value" {:head h})))
                  (throw (ex-info "Unsupported CBOR type" {:head h})))))]
      (let [v (item 0)]
        (when (< @pos size) (throw (ex-info "Trailing CBOR data" {:offset @pos})))
        v))))
