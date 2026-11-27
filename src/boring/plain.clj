(ns boring.plain
  "Portable reader and writer for definite-length CBOR and Boring's keyword
  and millisecond timestamp tags. Supports optional text references and container indexes."
  (:require [boring.data :as data]
            [boring.index-packing :as packing]
            [boring.frame :as frame])
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

(def ^:dynamic ^:private *symbols* nil)
(def ^:dynamic ^:private *nodes* nil)
(def ^:dynamic ^:private *items* nil)

(defn- text! [out v]
  (let [bs (.getBytes ^String v "UTF-8")
        size (alength bs)
        off (.size ^ByteArrayOutputStream out)]
    (if-let [[index _] (when *symbols* (get (:table @*symbols*) v))]
      (do (vswap! *symbols* update :used conj index)
          (head! out 6 25)
          (when *items* (vswap! *items* inc))
          (head! out 0 index))
      (do (when (and *symbols* (>= size (packing/min-len-for-index (count (:table @*symbols*)))))
            (vswap! *symbols* update :table assoc v [(count (:table @*symbols*)) off]))
          (head! out 3 size)
          (.write ^ByteArrayOutputStream out ^bytes bs)))))

(def ^:dynamic *registry* nil)
(def ^:dynamic *seqs* true)
(def ^:dynamic ^:private *tag-depth* 0)

(defn- invoke-handler [kind id f arg]
  (try (f arg)
       (catch Exception e
         (throw (ex-info (str "Boring " kind " failed")
                         {:type :boring/handler-failed :handler kind :id id} e)))))

(declare emit!)

(defn- container! [out v]
  (let [m? (map? v) n (count v) start (.size ^ByteArrayOutputStream out)
        collect? (and *nodes* (>= n (:min @*nodes*)))
        start-items (when collect? @*items*)]
    (head! out (if m? 5 4) n)
    (loop [xs (seq v) slots [] walk 0]
      (if xs
        (let [at (.size ^ByteArrayOutputStream out)
              walk (if collect? (+ walk (- @*items* start-items)) walk)]
          (if m?
            (let [[k x] (first xs)]
              (binding [*nodes* nil] (emit! out k))
              (let [key-end (.size ^ByteArrayOutputStream out)]
                (emit! out x)
                (recur (next xs) (if collect? (conj slots [at key-end]) slots) walk)))
            (do (emit! out (first xs))
                (recur (next xs) (if collect? (conj slots [at at]) slots) walk))))
        (when (and collect? (pos? n))
          (vswap! *nodes* update :candidates conj [start m? slots (quot walk n)]))))))

(defn- emit! [out v]
  (when *items* (vswap! *items* inc))
  (if-let [{:keys [tag writer]} (when (and *registry* (some? v)
                                           (not (or (integer? v) (string? v) (keyword? v) (boolean? v))))
                                  (get-in *registry* [:writers (type v)]))]
    (do (when (>= *tag-depth* 128) (throw (ex-info "Recursive tag writer" {:tag tag})))
        (head! out 6 tag)
        (binding [*tag-depth* (inc *tag-depth*)]
          (emit! out (invoke-handler "tag writer" tag writer v))))
    (cond
      (data/tagged-value? v) (do (head! out 6 (:tag v)) (emit! out (:value v)))
      (or (data/unknown-record? v) (record? v))
      (let [name (if (data/unknown-record? v) (data/record-type v) (data/record-type-name v))
            fields (if (data/unknown-record? v) (data/record-fields v) v)]
        (head! out 6 27)
        (head! out 4 2)
        (when *items* (vswap! *items* inc))
        (emit! out name)
        (when *items* (vswap! *items* inc))
        (container! out fields))
      (nil? v) (.write ^ByteArrayOutputStream out 246)
      (boolean? v) (.write ^ByteArrayOutputStream out (if v 245 244))
      (and (integer? v) (<= Long/MIN_VALUE v Long/MAX_VALUE))
      (head! out (if (neg? v) 1 0) (if (neg? v) (bit-not v) v))
      (string? v) (text! out v)
      (keyword? v) (do (head! out 6 39) (emit! out (str v)))
      (instance? Date v) (do (head! out 6 0)
                             (emit! out (str (Instant/ofEpochMilli (.getTime ^Date v)))))
      (seq? v) (do (when *seqs*
                     (head! out 6 27)
                     (head! out 4 2)
                     (when *items* (vswap! *items* inc))
                     (emit! out "clojure/seq")
                     (when *items* (vswap! *items* inc)))
                   (container! out v))
      (or (vector? v) (and (map? v) (not (record? v)) (not (sorted? v)))) (container! out v)
      :else (throw (ex-info "Unsupported plain CBOR value" {:type (type v)})))))

(defn encode [v]
  (let [out (ByteArrayOutputStream.)]
    (emit! out v)
    (.toByteArray out)))

(defn encode-value [v opts]
  (when-not (and (every? #{:stringref} (keys opts)) (boolean? (get opts :stringref true)))
    (throw (ex-info "Unsupported encoding options" {:options opts})))
  (if (get opts :stringref true)
    (let [out (ByteArrayOutputStream.)]
      (head! out 6 256)
      (binding [*symbols* (volatile! {:table {} :used #{}})] (emit! out v))
      (.toByteArray out))
    (encode v)))

(defn decode [bs]
  (let [pos (volatile! 0)
        footer (frame/footer-start bs)
        size (if (neg? footer) (alength bs) footer)
        refs (volatile! nil)]
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
                          (when (and @refs (>= n (packing/min-len-for-index (count @refs))))
                            (vswap! refs conj text))
                          text))
                  4 (vec (repeatedly n child))
                  5 (loop [left n, m (array-map)]
                      (if (zero? left) m
                          (let [k (child) v (child)]
                            (when (contains? m k) (throw (ex-info "Duplicate CBOR key" {:key k})))
                            (recur (dec left) (assoc m k v)))))
                  6 (if (= n 256)
                      (let [parent @refs]
                        (vreset! refs [])
                        (try (child) (finally (vreset! refs parent))))
                      (let [v (child)]
                        (if-let [reader (get-in *registry* [:readers n])]
                          (invoke-handler "tag reader" n reader v)
                          (case n
                            27 (let [[name fields] v]
                                 (when-not (and (vector? v) (= 2 (count v)) (string? name))
                                   (throw (ex-info "Malformed record frame" {:type :boring/bad-frame})))
                                 (if (= name "clojure/seq")
                                   (if (vector? fields)
                                     (or (seq fields) ())
                                     (throw (ex-info "Expected array for clojure/seq" {:type :boring/bad-frame})))
                                   (if-let [ctor (get-in *registry* [:records name])]
                                     (invoke-handler "record constructor" name ctor fields)
                                     (data/frame-for name fields))))
                            0 (Date. (.toEpochMilli (Instant/parse v)))
                            25 (if (and @refs (integer? v) (<= 0 v) (< v (count @refs)))
                                 (nth @refs v) (throw (ex-info "Invalid string reference" {:index v})))
                            39 (if (and (string? v) (.startsWith ^String v ":"))
                                 (keyword (subs v 1))
                                 (throw (ex-info "Unsupported identifier" {:value v})))
                            (data/tagged-value n v)))))
                  7 (case n 20 false 21 true 22 nil (throw (ex-info "Unsupported simple value" {:head h})))
                  (throw (ex-info "Unsupported CBOR type" {:head h})))))]
      (let [v (item 0)]
        (when (< @pos size) (throw (ex-info "Trailing CBOR data" {:offset @pos})))
        v))))

(defn- bytes! [out bs]
  (head! out 2 (alength bs))
  (.write ^ByteArrayOutputStream out ^bytes bs))

(defn- int-table! [out values]
  (let [buffer (ByteArrayOutputStream.)]
    (doseq [x values i (range 4)]
      (.write buffer (bit-and 255 (unsigned-bit-shift-right x (* i 8)))))
    (head! out 6 78)
    (bytes! out (.toByteArray buffer))))

(defn- ordered-keys? [body slots]
  (every? (fn [[[a ae] [b be]]]
            (loop [i a j b]
              (cond (= i ae) (< j be)
                    (= j be) false
                    :else (let [x (bit-and 255 (aget body i)) y (bit-and 255 (aget body j))]
                            (if (= x y) (recur (inc i) (inc j)) (< x y))))))
          (partition 2 1 slots)))

(defn- indexed-bytes [v stride minimum refs?]
  (let [out (ByteArrayOutputStream.)
        nodes (volatile! {:min minimum :candidates []})
        symbols (when refs? (volatile! {:table {} :used #{}}))]
    (when refs? (head! out 6 256))
    (binding [*nodes* nodes *items* (volatile! 0) *symbols* symbols] (emit! out v))
    (let [body (.toByteArray out)
          end (alength body)
          kept (for [[start m? slots walk] (sort-by first (:candidates @nodes))
                     :let [n (count slots)
                           sorted (and m? (ordered-keys? body slots))
                           step (if (and m? (not sorted)) 1 stride)]
                     :when (and (> n step) (>= walk (if (and m? (not sorted)) (* 4 n) 64)))]
                 [start n (long-array (map first (take-nth step slots))) sorted])
          refs (when symbols @symbols)
          pairs (when refs
                  (long-array (mapcat identity (sort-by first (filter #(contains? (:used refs) (first %))
                                                                      (vals (:table refs)))))))]
      (if (and (empty? kept) (or (nil? refs) (and (empty? (:used refs)) (<= end 4096))))
        (if refs (java.util.Arrays/copyOfRange body 3 end) body)
        (let [containers (long-array (map first kept))
              counts (int-array (map second kept))]
          (head! out 6 27)
          (head! out 4 2)
          (emit! out "boring/index")
          (head! out 4 (if refs 7 6))
          (emit! out stride)
          (int-table! out containers)
          (int-table! out counts)
          (bytes! out (packing/pack-slots (mapv #(nth % 2) kept) containers counts stride))
          (bytes! out (packing/pack-sorted (map #(nth % 3) kept)))
          (when refs (bytes! out (packing/pack-stringrefs pairs)))
          (bytes! out (byte-array (map #(unchecked-byte (unsigned-bit-shift-right end %))
                                       [56 48 40 32 24 16 8 0])))
          (.toByteArray out))))))

(defn encode-indexed
  "Encode the supported plain values with Boring container indexes and text references."
  ([v] (encode-indexed v {}))
  ([v opts]
   (let [{:keys [index index-min stringref] :or {index 16 index-min 4 stringref true}} opts]
     (when-not (and (every? #{:index :index-min :stringref} (keys opts))
                    (integer? index) (pos? index) (integer? index-min) (<= 0 index-min)
                    (boolean? stringref))
       (throw (ex-info "Unsupported index options" {:options opts})))
     (let [bs (indexed-bytes v index index-min stringref)]
       (if (and stringref (< (alength bs) 4096))
         (let [alt (indexed-bytes v index index-min false)]
           (if (< (alength alt) (alength bs)) alt bs))
         bs)))))
