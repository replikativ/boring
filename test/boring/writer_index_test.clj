(ns boring.writer-index-test
  "The writer captures the index WHILE ENCODING, instead of walking the encoded
  bytes afterwards.

  It can, because it already knows everything the index holds: `pos` is the
  offset an entry is about to be written to, and a container's entry count is
  known before a byte of it is emitted. Nothing here needs a subtree's LENGTH,
  only where its entries start -- which is why the same property that makes
  reading expensive (CBOR containers are element-counted, so stepping over a
  subtree means walking it) makes this side free.

  THIS FILE IS THE GATE FOR THAT CLAIM. `boring.core/build-index` walks the
  encoded bytes and is the reference implementation; the writer-captured index
  must agree with it exactly, for every value, at every stride. That is the same
  relationship `boring.nav`'s fast path has to its slow one, and it is the only
  reason a second implementation of the same idea is safe to ship.

  It has already earned its keep. The first version instrumented
  `writeMapValue`, which looked right and indexed nothing: plain Clojure maps
  never reach that method -- the general dispatch carried an inlined copy of its
  loop. Every map came back unindexed while the named method was demonstrably
  correct."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.set]
            [boring.core :as boring]
            [boring.nav :as nav])
  (:import (org.replikativ.boring Writer Reader)))

(defn- norm
  "An index as a comparable value: nodes sorted by container offset."
  [idx]
  (when idx
    (vec (sort-by first
                  (map (fn [c n s so] [c n (vec s) so])
                       (seq ^ints (:containers idx))
                       (seq ^ints (:counts idx))
                       (:slots idx)
                       (:sorted idx))))))

(defn- walked
  "The reference: derived by walking already-encoded bytes."
  [v opts stride mn]
  (norm (boring/build-index (boring/encode v opts)
                            (assoc opts :index stride :index-min mn))))

(defn- hooked
  "The subject: captured by the writer during encoding."
  [v opts stride mn]
  (let [^Writer w (boring/writer 65536 opts)]
    (.setIndex w (int stride) (int mn) 0)
    (.idxReset w)
    (boring/encode-buffered! w v)
    (norm (when (pos? (.idxCount w))
            {:containers (.idxContainers w) :counts (.idxCounts w)
             :slots (vec (.idxSlots w)) :sorted (vec (.idxSorted w))}))))

;; Values built to actually EXERCISE the index: containers wide enough to clear
;; `:index-min`, nested, and of every kind the writer emits differently --
;; plain maps, sorted maps (tag 27), sets (tag 258), vectors, seqs, records.
(def gen-wide-map
  (gen/fmap (fn [n] (into {} (for [i (range (+ 8 n))] [(format "k%03d" i) i])))
            (gen/choose 0 40)))

(def gen-wide-vec
  (gen/fmap (fn [n] (vec (range (+ 8 n)))) (gen/choose 0 40)))

(def gen-wide-set
  (gen/fmap (fn [n] (into #{} (range (+ 8 n)))) (gen/choose 0 40)))

(def gen-leaf
  (gen/one-of [gen/large-integer gen/string-ascii gen/boolean
               (gen/return nil) gen/keyword]))

(def gen-container
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of
      [(gen/fmap (fn [vs] (into {} (map-indexed (fn [i v] [(format "k%03d" i) v]) vs)))
                 (gen/vector inner 0 25))
       (gen/vector inner 0 25)
       (gen/fmap set (gen/vector gen/large-integer 0 25))
       (gen/fmap #(into (sorted-map) (map-indexed (fn [i v] [(format "s%03d" i) v]) %))
                 (gen/vector inner 0 20))
       (gen/fmap #(apply list %) (gen/vector inner 0 20))]))
   gen-leaf))

(def profiles
  [{:stringref false}
   {:profile :archival}
   {:profile :canonical}])

(deftest writer-index-agrees-with-the-byte-walk
  (testing "on containers built to clear :index-min, across profiles and strides"
    (doseq [opts profiles
            stride [1 4 16]]
      (let [result (tc/quick-check
                    60
                    (prop/for-all [v (gen/one-of [gen-wide-map gen-wide-vec gen-wide-set
                                                  (gen/fmap (fn [m] {"outer" m}) gen-wide-map)
                                                  (gen/vector gen-wide-map 1 4)])]
                                  (= (walked v opts stride 8) (hooked v opts stride 8))))]
        (is (:pass? result)
            (str "profile " (or (:profile opts) :clojure) " stride " stride
                 " -- " (pr-str (:shrunk result))))))))

(deftest writer-index-agrees-on-arbitrary-nested-values
  (testing "recursively generated structures, where the interesting cases are
            the ones nobody would think to write down -- a set inside a sorted
            map inside a list, and every tag wrapper that implies"
    (doseq [opts profiles]
      (let [result (tc/quick-check
                    80
                    (prop/for-all [v gen-container]
                                  (= (walked v opts 16 4) (hooked v opts 16 4))))]
        (is (:pass? result)
            (str "profile " (or (:profile opts) :clojure)
                 " -- " (pr-str (:shrunk result))))))))

;; ------------------------------------------------ the one place they diverge
;;
;; The writer indexes containers a USER wrote. The byte walk indexes containers
;; on the WIRE, and boring puts several there that no user asked for: a
;; sorted-map is tag 27 around a two-element `[name, map]`, a shaped array is
;; `[keys, rows]`, an ex-info is a three-element frame. The walk cannot tell
;; those from a user's own two-element vector -- on the wire they are identical
;; -- so it indexes them; the writer knows the difference and does not.
;;
;; Found by the generative test, shrunk to `(sorted-map)`, whose printed form
;; `{}` is indistinguishable from a plain empty map. Reproducing it by hand from
;; the shrunk output was impossible for exactly that reason.
;;
;; This is a subset, not a disagreement, and a subset is legitimate: the index
;; is an optimisation and not load-bearing against damage that leaves it
;; structurally inconsistent (see doc/SHAPES.md), and a node for a two-element
;; structural frame is pure overhead -- nobody navigates `[name, map]`. So the
;; contract is:
;;
;;   every node the writer emits is byte-identical to the walk's       (always)
;;   the two agree completely once :index-min excludes structural frames
;;
;; Every structural container boring emits has 3 entries or fewer, so
;; `:index-min 4` is the boundary. The default is 16.

(def ^:private structural-max 3)

(deftest every-captured-node-is-one-the-walk-also-found
  (testing "at :index-min 2, below the structural-frame boundary, the captured
            index must still be a strict SUBSET of the walked one -- a node the
            walk did not find would be a node pointing somewhere wrong"
    (doseq [opts profiles]
      (let [result (tc/quick-check
                    80
                    (prop/for-all [v gen-container]
                                  (let [w (set (walked v opts 1 2))
                                        h (set (hooked v opts 1 2))]
                                    (empty? (clojure.set/difference h w)))))]
        (is (:pass? result) (str "profile " (or (:profile opts) :clojure)
                                 " -- " (pr-str (:shrunk result))))))))

(deftest what-the-walk-finds-and-the-writer-skips-is-only-structural
  (testing "and the difference is never a user container: everything the walk
            has that the writer does not must be small enough to be a frame"
    (doseq [opts profiles]
      (let [result (tc/quick-check
                    80
                    (prop/for-all [v gen-container]
                                  (let [w (set (walked v opts 1 2))
                                        h (set (hooked v opts 1 2))]
                                    (every? #(<= (second %) structural-max)
                                            (clojure.set/difference w h)))))]
        (is (:pass? result) (str "profile " (or (:profile opts) :clojure)
                                 " -- " (pr-str (:shrunk result))))))))

(deftest above-the-structural-boundary-they-agree-exactly
  (testing ":index-min 4 excludes every frame boring emits, and from there the
            two implementations must be identical -- which is what catches a
            user container the writer forgot to instrument"
    (doseq [opts profiles
            mn [4 8 16]]
      (let [result (tc/quick-check
                    50
                    (prop/for-all [v gen-container]
                                  (= (walked v opts 1 mn) (hooked v opts 1 mn))))]
        (is (:pass? result) (str "profile " (or (:profile opts) :clojure)
                                 " min " mn " -- " (pr-str (:shrunk result))))))))

(deftest capture-is-off-unless-asked-for
  (testing "a writer that was never given setIndex records nothing, and encodes
            byte-identically to one that does -- the index must not change the
            document"
    ;; A 200-ELEMENT VECTOR, not a 40-entry map. This test needs the indexed
    ;; writer to actually RECORD something, and the old fixture records
    ;; nothing twice over: 40 scalar pairs are crossed in 39 items on average,
    ;; below the threshold, and an UNSORTED map earns no node above stride 1
    ;; at any walk, because the reader would refuse it. An array is gated on
    ;; walk alone, and 200 elements walk 99.
    (let [v (vec (range 200))
          ^Writer plain (boring/writer 65536 {:stringref false})
          ^Writer idxed (boring/writer 65536 {:stringref false})]
      (.setIndex idxed (int 16) (int 8) 0)
      (is (zero? (.idxCount plain)))
      (is (= (seq (boring/encode-into! plain v))
             (seq (boring/encode-into! idxed v)))
          "capturing an index must not alter a single byte of output")
      (is (pos? (.idxCount idxed)) "and the indexed writer must actually capture"))))

(deftest the-canonical-scratch-writer-never-indexes
  (testing "keys are pre-encoded in a scratch writer with its OWN buffer, so a
            node recorded there would carry offsets into bytes that are copied
            elsewhere -- a plausible index pointing at the wrong places. The
            scratch writer is checked, not trusted."
    (let [v (into {} (for [i (range 40)] [(keyword (format "k%03d" i)) {"deep" i}]))]
      (is (= (walked v {:profile :canonical} 16 2)
             (hooked v {:profile :canonical} 16 2))
          "canonical encoding with nested containers inside KEYS-adjacent
           positions must still agree"))))

(deftest an-index-can-be-rooted-at-an-offset
  (testing "`build-index` folded `base` into the CONTAINER offset and not into
            the entry offsets, so a non-zero base produced a node pointing at
            the right container and the wrong entries. The slot-rebasing branch
            had been deleted as dead code on the grounds that base is always 0.

            It is not always 0. An item embedded behind a prefix -- which is
            what `nav/source-at` exists for, and what konserve-lmdb's split blob
            is -- needs its index expressed in the ENCLOSING buffer's
            coordinates. Without that, `source-at` reads a frame whose every
            offset is wrong by the prefix, and silently gets no index at all.

            Asserted as: the embedded form answers identically to the standalone
            one and does the same amount of work."
    (let [o {:stringref false :trust-index :trusted}
          doc (into {:tree (vec (repeatedly 200 #(hash-map :a 1 :b 2)))}
                    (map (fn [i] [(keyword (str "k" i)) i])) (range 12))
          item (boring/encode doc {:stringref false})
          prefix 5
          idx-opts {:index 1 :index-min 13}
          blob (let [idx (boring/build-index item idx-opts prefix)
                     bos (java.io.ByteArrayOutputStream.)]
                 (.write bos (byte-array prefix))
                 (.write bos ^bytes item)
                 (boring/seal-index! (boring/writer) bos idx
                                     (+ prefix (alength ^bytes item))
                                     {:stringref false})
                 (.toByteArray bos))
          ;; The index is forced before counting: opening one walks the frame
          ;; positionally to find each node's slot, and those are `skipFrom`
          ;; calls too. What is being compared here is lookup work.
          probe (fn [^bytes bs ^long off]
                  (let [c (nav/cursor bs off o)
                        nv (.nav ^boring.nav.Cursor c)
                        ^Reader r (.rdr ^boring.nav.Nav nv)
                        _ (#'nav/nav-idx nv)
                        before (.skips r)
                        v (nav/value (nav/walk c [:k11]))]
                    {:value v :skips (- (.skips r) before)}))
          embedded (probe blob prefix)
          ;; `:stringref false` HERE TOO. Everything else in this fixture says
          ;; it explicitly -- `item`, `blob`'s frame, and `o` -- and this one
          ;; call inherited the profile default instead, so once stringref and
          ;; indexing composed the standalone form was a DIFFERENT document
          ;; from the embedded one and did a different amount of work. The
          ;; comparison is about where the index is rooted, not about stringref.
          standalone (probe (boring/encode-indexed
                             doc (assoc idx-opts :stringref false)) 0)
          unindexed (probe item 0)]
      (is (= 11 (:value embedded) (:value standalone) (:value unindexed))
          "every form gives the same answer")
      (is (= (:skips embedded) (:skips standalone))
          "and the embedded index does exactly the work the standalone one does")
      (is (< (:skips embedded) (:skips unindexed))
          "which is less than no index at all -- if these were equal the index
           would be present but unconsulted, which is the state this fixes")
      (testing "every container offset lands inside the blob, not the item"
        (let [idx (boring/build-index item idx-opts prefix)
              ^longs cs (:containers idx)]
          (is (pos? (alength cs)))
          (is (every? #(>= % prefix) (seq cs))
              "a container offset below the prefix is an un-rebased one"))))))
