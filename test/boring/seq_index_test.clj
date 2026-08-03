(ns boring.seq-index-test
  "The sequence offset index (tag 39651) is an OPTIMISATION. Every test here
  exists to hold that line: whatever happens to the index, the answer must not
  change, only the speed.

  A sealed sequence ends with an 8-byte byte string -- always `0x48` plus 8 --
  which is where the index lives, because CBOR cannot be parsed backwards. That
  pointer doubles as the data-section length, so a reader that seeks there and
  does not find tag 39651 knows the index is stale and scans instead.

  Nothing here is outside CBOR. The trailing 9 bytes are an ordinary byte
  string, not a magic trailer, so the file stays a valid CBOR sequence."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.nav :as nav])
  (:import (java.io ByteArrayOutputStream)))

(def opts {:stringref false})

(def items
  (vec (for [i (range 500)]
         {"n" i "msg" (str "event " i) "even" (even? i)})))

(defn- build ^bytes [stride]
  (let [w (boring/writer 65536 opts)
        o (ByteArrayOutputStream.)]
    (boring/write-seq! w items o (cond-> opts stride (assoc :index stride)))
    (.toByteArray o)))

(defn- read-items [^bytes bs]
  (mapv nav/value (seq (nav/items bs opts))))

(deftest index-does-not-change-what-is-read
  (testing "with an index, without one, and at several strides, nav/items
            yields exactly the data items -- the index is not one of them"
    (doseq [stride [nil 1 4 16 64 1000]]
      (let [bs (build stride)]
        (is (= items (read-items bs))
            (str "stride " stride))))))

(deftest indexed-and-scanned-nth-agree-everywhere
  (let [plain (build nil)
        idx16 (build 16)]
    (doseq [i [0 1 15 16 17 250 498 499]]
      (is (= (nav/value (nth (nav/items plain opts) i))
             (nav/value (nth (nav/items idx16 opts) i))
             (items i))
          (str "item " i)))
    (testing "out of range is nil on both paths"
      (is (nil? (nth (nav/items plain opts) 500 nil)))
      (is (nil? (nth (nav/items idx16 opts) 500 nil)))
      (is (nil? (nth (nav/items idx16 opts) -1 nil))))))

(deftest a-sealed-sequence-is-still-valid-cbor
  (testing "a reader that knows nothing about tag 39651 consumes the whole
            file: every data item, then one tagged value it can ignore"
    (let [bs (build 16)
          all (vec (boring/decode-seq bs opts))]
      (is (= (count items) (dec (count all))))
      (is (= items (vec (butlast all))))
      (is (= boring/index-tag (:tag (last all)))
          "the trailing item identifies itself by tag"))))

;; ---------------------------------------------------------------- fallbacks

(defn- corrupt-at ^bytes [^bytes bs ^long i ^long v]
  (let [c (java.util.Arrays/copyOf bs (alength bs))]
    (aset-byte c i (unchecked-byte v))
    c))

(deftest a-corrupted-pointer-falls-back-to-scanning
  (testing "flip a byte inside the back-pointer: the offset no longer lands on
            tag 39651, so the index is refused and the scan answers instead"
    (let [bs (build 16)
          ;; the pointer is the last 8 bytes; byte 3 of it is high-order enough
          ;; to move the offset well out of place
          broken (corrupt-at bs (- (alength bs) 6) 0x7F)]
      (is (= (inc (count items)) (count (read-items broken)))
          "falls back to scanning, which sees the index item as data too"))))

(deftest a-truncated-file-does-not-silently-use-a-stale-index
  (testing "chop the tail: the 9-byte shape is gone, so nothing claims to be an
            index. Reading then walks into the item the cut landed inside, and
            that must be a TYPED error rather than an untyped crash -- the same
            contract the decoder has for any truncated input. What must not
            happen is a stale index being trusted and returning a wrong answer."
    (let [bs (build 16)
          chopped (java.util.Arrays/copyOf bs (- (alength bs) 40))]
      ;; A transducer, not `take` over an eager mapv: `reduced` stops the walk
      ;; before it reaches the cut, which is the whole point of the prefix
      ;; still being readable.
      (is (= (vec (take 100 items))
             (into [] (comp (map nav/value) (take 100)) (nav/items chopped opts)))
          "the prefix before the cut reads unchanged")
      (let [e (try (doall (read-items chopped)) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "walking into the truncated tail must fail, not fabricate")
        (is (#{:boring/bad-count :boring/truncated-input} (:type (ex-data e)))
            (str "must be a typed error, got " (pr-str (ex-data e))))))))

(deftest appending-after-sealing-invalidates-the-index
  (testing "appending moves the tail, so detection fails and the reader scans.
            The index item then shows up AS DATA -- which is the honest
            fallback, but it is a sharp edge: re-seal rather than append."
    (let [sealed (build 16)
          w (boring/writer 65536 opts)
          o (ByteArrayOutputStream.)
          _ (.write o sealed)
          _ (boring/write-to! w {"n" 500 "msg" "appended"} o)
          appended (.toByteArray o)
          read (read-items appended)]
      (is (= (+ (count items) 2) (count read))
          "500 data items, the now-stale index item, and the appended one")
      (is (= boring/index-tag (:tag (nth read (count items))))
          "the stale index is visible, and identifiable, rather than silent"))))

(deftest a-file-that-merely-ends-in-the-right-shape-is-not-an-index
  (testing "the last 9 bytes looking like a byte string is not enough -- the
            pointer must also land on tag 39651. This is the false-positive
            case the three checks exist for."
    (let [w (boring/writer 65536 opts)
          o (ByteArrayOutputStream.)]
      (boring/write-seq! w items o opts)
      ;; a final item that is itself an 8-byte byte string: the file now ends
      ;; with exactly 0x48 + 8 bytes, with no index anywhere
      (boring/write-to! w (byte-array 8) o)
      (let [bs (.toByteArray o)
            read (read-items bs)]
        (is (= (inc (count items)) (count read))
            "read as plain data, not mistaken for a sealed sequence")
        (is (= items (vec (butlast read))))))))

(deftest stride-is-a-parameter-not-a-constant
  (testing "every stride indexes correctly, and larger strides cost less space"
    (let [sizes (into {} (for [s [1 4 16 64]] [s (alength (build s))]))]
      (is (apply > (map sizes [1 4 16 64]))
          (str "index size must fall as stride rises: " sizes))
      (doseq [s [1 4 16 64]]
        (is (= (items 499) (nav/value (nth (nav/items (build s) opts) 499)))
            (str "stride " s " must still reach the last item"))))))

;; ------------------------------------------------- hierarchical descent
;;
;; The sequence index above reaches item n. These reach INTO an item: a node
;; per container, so a map lookup binary-searches and an array indexes
;; positionally. Same guarantee as everything else here -- the index changes
;; how much is walked, never the answer.

(def sorted-opts {:profile :archival})          ; sorts map keys, drops stringref

(def wide-map
  (into {} (for [i (range 300)] [(format "k%04d" i) {"v" i "w" (str "x" i)}])))

(def wide-vec (vec (for [i (range 300)] {"n" i "s" (str "s" i)})))

(deftest indexed-descent-agrees-with-scanning
  (testing "every key of a wide map resolves identically with and without an
            index, under a sorting profile (binary search) and without one
            (anchor-to-anchor walk)"
    (doseq [o [sorted-opts opts]]
      (let [plain (boring/encode wide-map o)
            idxed (boring/encode-indexed wide-map (assoc o :index 16 :index-min 8))
            cp (nav/source plain o)
            ci (nav/source idxed o)]
        (is (< (alength ^bytes plain) (alength ^bytes idxed)) "index costs something")
        (doseq [k (keys wide-map)]
          (is (= (nav/value (get-in cp [k "v"]))
                 (nav/value (get-in ci [k "v"]))
                 (get-in wide-map [k "v"]))
              (str "key " k " under " (:profile o :clojure))))
        (testing "and a missing key is nil on both paths"
          (is (nil? (get cp "nope")))
          (is (nil? (get ci "nope"))))))))

(deftest indexed-arrays-need-no-sorted-keys
  (testing "an array indexes positionally, so it works under ANY profile --
            it is only maps that need canonical order for binary search"
    (let [plain (boring/encode wide-vec opts)
          idxed (boring/encode-indexed wide-vec (assoc opts :index 16 :index-min 8))
          cp (nav/source plain opts)
          ci (nav/source idxed opts)]
      (doseq [i [0 1 15 16 17 150 298 299]]
        (is (= (nav/value (nth cp i)) (nav/value (nth ci i)) (wide-vec i))
            (str "index " i)))
      (is (nil? (nth ci 300 nil)) "out of range still nil"))))

(deftest decode-is-unaffected-by-an-index
  (testing "encode-indexed produces a two-item sequence, so `decode` still
            returns the value and any CBOR reader consumes both items"
    (doseq [v [wide-map wide-vec]]
      (let [bs (boring/encode-indexed v (assoc sorted-opts :index 16))]
        (is (= v (boring/decode bs sorted-opts)))
        (is (= 2 (count (boring/decode-seq bs sorted-opts))))
        (is (= boring/index-tag (:tag (second (vec (boring/decode-seq bs sorted-opts))))))))))

(deftest a-corrupt-container-index-still-answers-correctly
  (testing "flip the back-pointer: no node is found anywhere, so every lookup
            falls back to walking -- slower, identical answers"
    (let [bs (boring/encode-indexed wide-map (assoc sorted-opts :index 16 :index-min 8))
          broken (corrupt-at bs (- (alength bs) 6) 0x7F)
          c (nav/source broken sorted-opts)]
      (doseq [k (take 50 (keys wide-map))]
        (is (= (get-in wide-map [k "v"]) (nav/value (get-in c [k "v"])))
            (str "key " k))))))

(deftest nodes-are-built-inside-tags
  (testing "a set is tag 258 around an array and a record is tag 27 around a
            map -- skipping tags would leave exactly those unindexed"
    (let [v {"s" (into (sorted-set) (range 200))
             "plain" (into {} (for [i (range 200)] [(format "k%03d" i) i]))}
          idx (boring/build-index (boring/encode v sorted-opts)
                                  (assoc sorted-opts :index 16 :index-min 8))]
      (is (some? idx))
      ;; TWO nodes, not three: the outer map has 2 entries and :index-min is 8,
      ;; so it is correctly skipped. What must be present is the set's array
      ;; and the inner map -- both of which live UNDER a tag, and neither of
      ;; which would exist if tags were stepped over.
      (is (= 2 (alength ^ints (:containers idx)))
          (str "expected the set's array and the inner map, got "
               (alength ^ints (:containers idx))))
      (is (every? #(>= % 200) (seq ^ints (:counts idx)))
          "both nodes should be the 200-entry containers"))))

(deftest index-min-controls-size-not-correctness
  (testing "raising the threshold shrinks the index and never changes an answer"
    (let [sizes (vec (for [mn [2 8 64]]
                       [mn (alength ^bytes (boring/encode-indexed
                                            wide-map (assoc sorted-opts :index 16 :index-min mn)))]))]
      ;; Non-increasing, not strictly decreasing: 8 and 64 both exclude the
      ;; 2-entry inner maps and index only the outer one, so they tie. The
      ;; claim is that raising the threshold never GROWS the index.
      (is (apply >= (map second sizes)) (str "size must not rise with threshold: " sizes))
      (is (> (second (first sizes)) (second (last sizes)))
          (str "and 2 must cost more than 64: " sizes))
      (doseq [mn [2 8 64]]
        (let [c (nav/source (boring/encode-indexed
                             wide-map (assoc sorted-opts :index 16 :index-min mn))
                            sorted-opts)]
          (is (= 42 (nav/value (get-in c ["k0042" "v"]))) (str "min " mn)))))))
