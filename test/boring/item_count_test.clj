(ns boring.item-count-test
  "The writer counts CBOR items as it emits them; `Reader.skipCountingFrom`
  counts them by walking the bytes afterwards. THE TWO MUST AGREE EXACTLY.

  This is not a curiosity. The `walk` metric -- the mean number of items a scan
  crosses to reach a random entry of a container -- is what decides whether a
  container gets an index node. boring builds an index two ways, the writer
  capturing while encoding and a byte walk deriving afterwards, and both must
  produce the SAME FILE. If they count items differently they disagree about
  which containers are worth a node, and the format has two readings.

  So the count is defined structurally -- ONE ITEM PER HEAD -- and asserted
  here rather than reasoned about. The writer counts at six emit sites and the
  walker counts in one place, and nothing but a test can hold six against one.

  Deliberately NOT compared against `Reader.items`, the decode budget.
  `skipStructural` records three attempts at making a generic walker agree with
  `read`'s accounting, each of which broke a different value, and concludes it
  is not reachable: a tag reader that parses its payload inline charges nothing
  for the containers under it, and the gap is unbounded. Both sides of THIS
  agreement are structural, which is why it is achievable at all."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring])
  (:import (org.replikativ.boring Reader Writer)))

(def ^:private opts {:profile :clojure :stringref false})

(defn- counts-agree?
  "Encode `v`, then count its items both ways. Returns a map when they differ,
  nil when they agree -- so a failing case reports the numbers, not just false."
  ([v] (counts-agree? v opts))
  ([v o]
   (let [^Writer w (boring/writer 4096 o)
         before (.itemsWritten w)
         _ (.writeValue w v)
         written (- (.itemsWritten w) before)
         bs (.toByteArray w)
         ^Reader r (Reader. bs)
         end (.skipCountingFrom r 0)
         walked (.skipItemCount r)]
     (when-not (and (= written walked) (= end (long (alength bs))))
       {:value v :written written :walked walked
        :end end :length (alength bs)}))))

;; ------------------------------------------------------------ the shapes that
;; ------------------------------------------------------------ separate them

(deftest the-constructs-that-bypass-an-emit-site
  (testing "Each of these reaches the wire by a path that a naive per-emit
            counter misses, and each was found by reading the writer rather
            than by the generator. They are asserted by name so a regression
            says WHICH construct broke."
    (doseq [[label v o]
            [;; The two-byte simple form: not `head`, not `u8`, not a float.
             ["a reserved simple value" (boring.data/simple-value 200) opts]
             ;; Floats bypass `head` entirely -- three separate writers.
             ["f64" 1.5 opts]
             ["f32" (float 1.5) opts]
             ["shortest float" 1.5 (assoc opts :float-policy :shortest)]
             ["bool" true opts]
             ["nil" nil opts]
             ;; `headU64` -- the top of the uint64 range, its own emit site.
             ["uint64 top" (biginteger 18446744073709551615) opts]
             ["bignum" (biginteger "123456789012345678901234567890") opts]
             ["bigdec" 1.50M opts]
             ;; CANONICAL STAGING: keys and elements are encoded into a scratch
             ;; writer and MEMCPY'd in, so they cross no emit site in this
             ;; writer at all. A per-emit counter reads ZERO items for the
             ;; whole staged subtree -- unbounded error, not an off-by-one.
             ["canonical map" {"a" 1 "b" {"c" [1 2 3]}} {:profile :canonical}]
             ["canonical set" #{1 2 3} {:profile :canonical}]
             ["canonical set of colls" #{[1 2] {"k" "v"}} {:profile :canonical}]
             ;; A SHAPED ARRAY consumes four container heads per row that no
             ;; `writeValue` call corresponds to.
             ["shaped array" (vec (for [i (range 5)] {"a" i "b" (str i)}))
              (assoc opts :shapes true)]
             ;; Tag chains: the walker collapses them iteratively, the writer
             ;; emits one head each.
             ["set of sets" #{#{1 2} #{3}} opts]
             ["record" (boring.data/unknown-record "R" {"x" 1}) opts]
             ["typed array" (int-array [1 2 3]) opts]]]
      (is (nil? (counts-agree? v o)) (str label ": " (counts-agree? v o))))))

;; --------------------------------------------------------------- the property

(def ^:private scalar
  (gen/one-of [gen/small-integer gen/large-integer gen/boolean
               (gen/return nil) gen/string-alphanumeric gen/double
               ;; Scaled from a small integer rather than `(fmap float
               ;; gen/double)`, which throws for any double outside float
               ;; range -- and gen/double reaches Infinity.
               (gen/fmap #(float (/ (double %) 1000.0)) gen/small-integer) gen/keyword gen/symbol
               (gen/fmap biginteger gen/large-integer)
               (gen/fmap #(java.util.UUID. % %) gen/large-integer)]))

(def ^:private any-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 6)
                  (gen/map gen/string-alphanumeric inner {:max-elements 6})
                  (gen/set inner {:max-elements 5})
                  (gen/fmap vec (gen/list inner))]))
   scalar))

(defspec writer-and-walker-count-the-same-items 400
  (prop/for-all [v any-value]
    (nil? (counts-agree? v))))

(defspec writer-and-walker-agree-under-canonical 300
  ;; The staging paths only run here, and they are the ones with no emit site.
  (prop/for-all [v any-value]
    (nil? (counts-agree? v {:profile :canonical}))))

(defspec writer-and-walker-agree-under-shapes 300
  ;; `:shapes` is only settable under `:clojure`; the other profiles pin it
  ;; false and refuse the override.
  (prop/for-all [v (gen/vector (gen/map gen/string-alphanumeric scalar
                                        {:num-elements 3})
                               0 8)]
    (nil? (counts-agree? v (assoc opts :shapes true)))))

;; ------------------------------------------------------- what the count MEANS

(deftest the-count-is-items-not-bytes
  (testing "Why `walk` is measured in items. A 70 KB byte string is ONE item
            and a scan steps over it in O(1); 70 KB of small integers is tens
            of thousands of items and stepping over it is O(n). Measuring the
            span in BYTES gets that exactly backwards, which is the error two
            earlier versions of the index policy made -- and it is worth a
            2-3x error in the threshold, so it is pinned rather than trusted."
    (let [blob (byte-array 70000)
          ints (vec (repeat 70000 1))
          n-of (fn [v] (let [^Writer w (boring/writer 4096 opts)]
                         (.writeValue w v)
                         (let [^Reader r (Reader. (.toByteArray w))]
                           (.skipCountingFrom r 0)
                           (.skipItemCount r))))]
      (is (= 1 (n-of blob))
          "a byte string of any size is one item")
      (is (= 70001 (n-of ints))
          "an array of n scalars is n+1: the head, and one per element")
      (is (< (n-of blob) (n-of ints))
          "so the metric ranks them the way a scan actually experiences them"))))

(deftest an-empty-container-still-costs-its-head
  (testing "`walk` divides by the entry count, so a container that costs
            nothing to cross must still be counted as the one head it is --
            otherwise nested empties would look free and a node would be placed
            where there is nothing to skip."
    (let [n-of (fn [v] (let [^Writer w (boring/writer 4096 opts)]
                         (.writeValue w v)
                         (let [^Reader r (Reader. (.toByteArray w))]
                           (.skipCountingFrom r 0)
                           (.skipItemCount r))))]
      (is (= 1 (n-of [])))
      (is (= 1 (n-of {})))
      (is (= 4 (n-of [[] [] []])) "the outer head plus one per empty child"))))

;; ---------------------------------------------------------------- walk itself

(defn- capture-walks
  "Each node's `walk` as the WRITER computed it while encoding, keyed by
  container offset.

  Driven through `setIndex` + `writeValue` rather than `write-indexed!`,
  because `write-indexed!` turns capture off again once it has sealed
  (`setIndex` with a stride of 0, which also resets) -- so reading the node
  arrays after it returns reports an EMPTY capture and every comparison here
  fails identically. `:stringref false` because indexing forces it off, which
  the four public entry points each do for themselves."
  [v o]
  (let [ro (#'boring.core/resolve-opts (assoc o :stringref false))
        ^Writer w (boring/writer 65536 (assoc o :stringref false))]
    ;; `configure!` EXPLICITLY. `boring/writer` only STORES resolved options on
    ;; the writer; the entry points apply them to the fields, and `.writeValue`
    ;; is not an entry point. Without this the writer is not canonical under
    ;; `:profile :canonical` -- same byte COUNT, different key order, so it
    ;; looks like a walk disagreement and is a harness that never turned the
    ;; profile on.
    (#'boring.core/configure! w ro)
    (.setIndex w (int (:index o 16)) (int (:index-min o 16)) 0)
    (.writeValue w v)
    (zipmap (seq (.idxContainers w)) (seq (.idxWalks w)))))

(defn- walked-walks
  "The same, as the BYTE WALK computed it afterwards."
  [v o]
  (let [o (assoc o :stringref false)
        ix (boring/build-index (boring/encode v o) o)]
    (if ix (zipmap (seq (:containers ix)) (:walk ix)) {})))

(deftest the-two-builders-compute-the-same-walk
  (testing "`walk` decides whether a container is worth an index node. The
            writer accumulates it from items it emits; the byte walk
            accumulates it from items it crosses. If they disagree they place
            nodes differently, and two builders that place nodes differently
            produce two different files for one value.

            Asserted per NODE, keyed by container offset, so a failure names
            the container rather than reporting that two files differ.

            This is the property the whole of step 1 was groundwork for, and it
            is checked BEFORE anything reads `walk` -- once the rule is live a
            disagreement shows up as a byte-identity failure, which says that
            something is wrong but not what."
    (doseq [[label v]
            [["flat map"     (into {} (for [i (range 40)] [(format "k%02d" i) i]))]
             ["sorted map"   (into (sorted-map)
                                   (for [i (range 40)] [(format "k%02d" i) i]))]
             ["vec of maps"  (vec (for [i (range 30)] {"a" i "b" (str i)}))]
             ;; Entries of WILDLY different sizes, so the mean is not the
             ;; trivial (n-1)/2 * s and an off-by-one in either accumulator
             ;; moves it.
             ["ragged"       (into {} (for [i (range 20)]
                                        [(format "k%02d" i)
                                         (vec (range (* i i)))]))]
             ["one big sibling" {"a" 1 "b" (vec (range 500)) "c" 3 "d" 4 "e" 5}]
             ["nested"       {"L1" (into {} (for [i (range 20)]
                                              [(format "m%02d" i)
                                               {"L3" (vec (range 20))}]))}]
             ["deep"         (reduce (fn [acc _] {"k" acc, "pad" (vec (range 20))})
                                     {} (range 5))]
             ["set"          (set (range 40))]
             ["record"       (boring.data/unknown-record
                              "R" (into {} (for [i (range 30)] [(str i) i])))]]
            profile [:clojure :canonical :archival]
            stride [1 4 16]
            min-entries [2 16]]
      (let [o (cond-> {:profile profile :index stride :index-min min-entries}
                (= profile :clojure) (assoc :stringref false))
            tag (str label " | " profile " | stride " stride " | min " min-entries)]
        (is (= (capture-walks v o) (walked-walks v o))
            (str tag ": the two builders must agree on every node's walk"))))))

(deftest walk-is-the-mean-prefix-and-nothing-else
  (testing "Pinned against hand-computed values, so the two builders agreeing
            cannot mean they are agreeing on the wrong thing.

            For n uniform entries of s items each, walk = s*(n-1)/2: entry j is
            reached by crossing j entries, and the mean of 0..n-1 is (n-1)/2."
    (let [o {:profile :clojure :stringref false :index 1 :index-min 2}
          walk-of (fn [v] (first (vals (walked-walks v o))))]
      ;; An array of scalars: one item per entry, so walk = (n-1)/2.
      (is (= 4 (walk-of (vec (range 10)))) "10 scalars: (10-1)/2 = 4 (floor)")
      (is (= 49 (walk-of (vec (range 100)))) "100 scalars: (100-1)/2 = 49")
      ;; A byte string is ONE item however large -- the whole reason the metric
      ;; is items. Ten 1 KB blobs must walk exactly like ten integers.
      (is (= (walk-of (vec (range 10)))
             (walk-of (vec (repeat 10 (byte-array 1024)))))
          "a 1 KB blob and an integer cost a scan the same: one item"))))
