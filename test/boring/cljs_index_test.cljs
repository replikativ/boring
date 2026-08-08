(ns boring.cljs-index-test
  "The ClojureScript writer emits an index frame the JVM reads but this platform
  has no reader for, so nothing here is covered by a round trip. These check the
  parts only this side can get wrong."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [boring.core :as boring]
            [boring.data :as data]))

(defn- frame-containers
  "The `containers` element of the frame `seal-index!` emits for a single node
  sitting at byte offset `off`."
  [off]
  (let [bs (boring/seal-index! {:stride 1 :containers [off] :counts [1]
                                :slots [[off]] :sorted [false]}
                               0 {})]
    (nth (data/frame-payload (boring/decode bs {:on-unknown-record :fallback})) 1)))

(deftest container-offsets-promote-rather-than-wrap
  (testing "offsets that fit stay int32, so ordinary files are byte-identical"
    (let [c (frame-containers 1000)]
      (is (instance? js/Int32Array c))
      (is (= 1000 (aget c 0)))))
  ;; `js/Int32Array.from` applies ToInt32, which wraps SILENTLY. Emitting it
  ;; unconditionally turned an offset at or above 2^31 negative -- precisely the
  ;; case 64-bit offsets exist for -- and a reader on `:trust-index :trusted`
  ;; skips the ascending check that would otherwise have caught it.
  (testing "an offset past 2^31 promotes to int64 and keeps its value"
    (let [off (+ 2147483648 7)
          c (frame-containers off)]
      (is (instance? js/BigInt64Array c))
      (is (= off (js/Number (aget c 0)))))))

;; ---------------------------------------------------- walk, across platforms

(deftest walk-matches-the-jvm
  (testing "`walk` -- the mean number of CBOR items a scan crosses to reach a
            random entry -- is what will decide whether a container gets an
            index node. There are THREE index builders: the JVM writer
            capturing while it encodes, the JVM byte walk, and this one. The
            first two are held together by `boring.item-count-test`; nothing
            held THIS one to either, and the namespace docstring above says why
            -- an index written here cannot be read back here.

            So the expected values are pinned from the JVM, the way a golden
            corpus is. If they drift, clj and cljs place nodes differently and
            write different files for the same value -- on a platform pair
            where the whole point is that one writes what the other reads.

            Computed on the JVM at `{:profile :clojure :stringref false
            :index-min 2}`, and stride-independent by construction: `walk`
            describes the SCAN, not the index laid over it."
    (doseq [stride [1 16]]
      (let [o {:profile :clojure :stringref false :index stride :index-min 2}
            ix (fn [v] (let [i (boring/build-index (boring/encode v o) o)]
                         [(vec (:containers i)) (vec (:walk i))]))]
        (is (= [[0] [19]] (ix (vec (range 40))))
            (str "stride " stride ": 40 scalars, one item each -- (40-1)/2 = 19"))
        (is (= [[0] [19]] (ix (into {} (for [i (range 20)] [(str "k" i) (str "v" i)]))))
            (str "stride " stride ": 20 pairs of two items -- 2*(20-1)/2 = 19"))
        ;; Entries of unequal size, so the mean is not the uniform formula and
        ;; an off-by-one anywhere in the accumulation moves it.
        (is (= [[0 8 15 25 38 54 73 95] [28 1 2 4 5 7 8 10]]
               (ix (into {} (for [i (range 8)] [(str "k" i) (vec (range (* i 3)))]))))
            (str "stride " stride ": ragged entries"))
        ;; A large sibling: `walk` on the root must reflect that reaching "c"
        ;; crosses both 30-element vectors, which is the whole case the metric
        ;; exists to catch.
        (is (= [[0 3 43] [32 14 14]]
               (ix {"a" (vec (range 30)) "b" (vec (range 30)) "c" 1}))
            (str "stride " stride ": a root with two large siblings"))))))
