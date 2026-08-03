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
