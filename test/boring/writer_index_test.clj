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
            [boring.core :as boring])
  (:import (org.replikativ.boring Writer)))

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
;; is an optimisation and not load-bearing against accidental damage (see
;; doc/SHAPES.md on crafted indexes), and a node for a two-element
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
    (let [v (into {} (for [i (range 40)] [(format "k%03d" i) i]))
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
