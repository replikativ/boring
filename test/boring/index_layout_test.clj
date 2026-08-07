(ns boring.index-layout-test
  "The index frame's `slots` and `sorted` are BYTE STRINGS, and used to be a
  CBOR array of typed arrays and a CBOR array of booleans.

  That change is safe only because of two properties, and neither is implied by
  the round-trip tests: a frame in the OTHER shape must be REFUSED rather than
  misread, and the derived per-node segment lengths must be checked against the
  byte string's actual length rather than assumed. Both are tested here by
  building frames by hand, since no writer in the tree emits them any more."
  (:require [clojure.test :refer [deftest testing is]]
            [boring.core :as boring]
            [boring.data :as data]
            [boring.nav :as nav])
  (:import (java.io ByteArrayOutputStream)))

(def ^:private opts {:stringref false :shapes false})

(def ^:private doc
  (into (sorted-map) (for [i (range 40)] [(format "k%02d" i) i])))

(defn- ptr-bytes ^bytes [^long v]
  (let [b (byte-array 8)]
    (dotimes [i 8] (aset-byte b i (unchecked-byte (bit-shift-right v (* 8 (- 7 i))))))
    b))

(defn- seal-with
  "`body` with a hand-built frame appended carrying `slots` and `sorted` in
  whatever shape the caller supplies."
  ^bytes [^bytes body index slots sorted]
  (let [{:keys [stride ^longs containers counts]} index
        wire-containers (let [a (int-array (alength containers))]
                          (dotimes [i (alength containers)]
                            (aset-int a i (unchecked-int (aget containers i))))
                          a)
        frame (boring/encode
               (data/unknown-record boring/index-name
                                    [(long stride) wire-containers counts slots sorted
                                     (ptr-bytes (alength body))])
               opts)
        out (ByteArrayOutputStream.)]
    (.write out body)
    (.write out ^bytes frame)
    (.toByteArray out)))

(defn- old-shape-slots
  "`slots` the way every writer before this layout emitted them: one typed
  array of deltas PER NODE, narrowest of byte string / sint16 / sint32."
  [index]
  (let [{:keys [slots ^longs containers]} index]
    (vec (map-indexed
          (fn [i ^longs offs]
            (let [n (alength offs)
                  d (long-array n)]
              (loop [k 0 prev (max 0 (aget containers i))]
                (when (< k n)
                  (let [v (aget offs k)]
                    (aset d k (- v prev))
                    (recur (inc k) v))))
              (let [mx (areduce d k m Long/MIN_VALUE (max m (aget d k)))]
                (if (and (pos? n) (> mx 0xFF))
                  (let [a (int-array n)]
                    (dotimes [k n] (aset-int a k (unchecked-int (aget d k))))
                    a)
                  (let [b (byte-array n)]
                    (dotimes [k n] (aset-byte b k (unchecked-byte (aget d k))))
                    b)))))
          slots))))

(defn- index-of [^bytes bs]
  (#'boring.nav/read-index (#'boring.nav/nav-of bs opts)))

(defn- usable-index?
  "Whether the frame was ACCEPTED. A refused one is not nil: `read-index` still
  returns `{:data-end ..}`, because where the data section ends is known from
  the back-pointer alone and is needed whether or not the index is usable."
  [^bytes bs]
  (some? (:containers (index-of bs))))

(defn- answers-everything? [^bytes bs]
  (let [src (nav/source bs opts)]
    (every? (fn [i] (= i (some-> (get src (format "k%02d" i)) nav/value)))
            (range 40))))

;; ---------------------------------------------------------------- old shape

(deftest a-frame-in-the-previous-shape-is-refused-not-misread
  (testing "This is the whole compatibility argument for changing the layout
            without changing the frame name or its 17-byte prefix. A reader
            that meets the shape it does not expect must lose the INDEX and
            keep the FILE -- answering by scanning, which is the documented
            behaviour for any index it cannot use. Answering WRONGLY, or
            throwing, would both make the change unshippable."
    (let [body (boring/encode doc opts)
          index (boring/build-index body (assoc opts :index 1 :index-min 4))
          old (seal-with body index
                         (old-shape-slots index)
                         (vec (:sorted index)))]
      (is (not (usable-index? old))
          "the old shape must not be accepted as an index")
      (is (answers-everything? old)
          "and every key must still read back correctly, by scanning")))

  (testing "the control: the SAME body sealed by the current writer is accepted,
            so the refusal above is about the shape and not about the fixture"
    (let [bs (boring/encode-indexed doc (assoc opts :index 1 :index-min 4))]
      (is (usable-index? bs))
      (is (bytes? (:slots (index-of bs))) "slots arrive as one byte string")
      (is (bytes? (:sorted (index-of bs))) "sorted arrives as a bitset")
      (is (answers-everything? bs)))))

;; -------------------------------------------------- the derived-length check

(deftest packed-slots-must-describe-their-own-length
  (testing "No per-node segment length is stored -- it is derived from the entry
            count in `counts` and the stride. The prefix sum over those lengths
            must therefore land EXACTLY on the byte string's length, and that
            equality is the structural check the layout gets in place of the
            per-slot type check it replaced. A frame whose parts disagree is
            refused WHOLE, rather than at whichever node a lookup visits."
    (let [body (boring/encode doc opts)
          index (boring/build-index body (assoc opts :index 1 :index-min 4))
          good (boring/encode-indexed doc (assoc opts :index 1 :index-min 4))
          packed (:slots (index-of good))]
      (is (some? packed))
      (doseq [[label mangled]
              [["one byte short" (java.util.Arrays/copyOf ^bytes packed
                                                          (dec (alength ^bytes packed)))]
               ["one byte long" (java.util.Arrays/copyOf ^bytes packed
                                                         (inc (alength ^bytes packed)))]
               ;; A flipped WIDTH CODE changes every following node's offset,
               ;; so the sum lands somewhere else entirely. The old per-node
               ;; arrays could not express this fault at all -- which is why
               ;; this check is stronger than the one it replaced, not weaker.
               ["a width code raised" (let [c (aclone ^bytes packed)]
                                        (aset-byte c 0 (unchecked-byte
                                                        (bit-or (aget c 0) 0x03)))
                                        c)]]]
        (let [bs (seal-with body index mangled (:sorted (index-of good)))]
          (is (not (usable-index? bs)) (str label ": must be refused"))
          (is (answers-everything? bs) (str label ": and still answer by scanning"))))))

  (testing "`sorted` is one bit per node, so its byte length is an EQUALITY --
            `(quot (+ n 7) 8)` -- not a bound. A bitset sized for a different
            node count is a frame whose parts disagree."
    (let [body (boring/encode doc opts)
          index (boring/build-index body (assoc opts :index 1 :index-min 4))
          good (boring/encode-indexed doc (assoc opts :index 1 :index-min 4))
          ix (index-of good)]
      (doseq [[label n] [["too short" 0] ["too long" 9]]]
        (let [bs (seal-with body index (:slots ix) (byte-array n))]
          (is (not (usable-index? bs)) (str label ": must be refused"))
          (is (answers-everything? bs) (str label ": and still answer by scanning")))))))

;; ------------------------------------------------------------ width per node

(deftest a-wide-node-does-not-widen-its-neighbours
  (testing "The reason `slots` carries a width code per node rather than being
            one flat typed array at a single width. Measured on 767 nodes, a
            single width was 33% SMALLER than the per-node shape when every
            delta was small and 166% LARGER when one node among them was wide
            -- and a map of small values beside one large blob is the ordinary
            shape of scraped data, not a corner case.

            So: the same document, once with a large value and once without,
            must differ in index size by about the one node that needed it."
    (let [small (into (sorted-map)
                      (for [i (range 40)] [(format "k%02d" i) (str "v" i)]))
          wide (assoc small "zzz" (apply str (repeat 70000 \x)))
          idx-bytes (fn [d]
                      (let [sealed (boring/encode-indexed d (assoc opts :index 1 :index-min 4))
                            plain (boring/encode d opts)]
                        (- (alength ^bytes sealed) (alength ^bytes plain))))
          a (idx-bytes small)
          b (idx-bytes wide)]
      ;; One extra entry at i32 costs at most a handful of bytes over the same
      ;; entry at u8. If a wide node promoted the whole array, this would grow
      ;; by ~3 bytes per EXISTING entry -- more than 100 on 41 of them.
      (is (< (- b a) 40)
          (str "a single wide value must not widen the other nodes: "
               a " B -> " b " B")))))
