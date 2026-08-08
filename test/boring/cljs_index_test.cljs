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
            random entry -- decides whether a container gets an index node.
            There are THREE index builders: the JVM writer capturing while it
            encodes, the JVM byte walk, and this one. The first two are held
            together by `boring.item-count-test`; nothing held THIS one to
            either, and the namespace docstring above says why -- an index
            written here cannot be read back here.

            So the expected values are pinned from the JVM, the way a golden
            corpus is. If they drift, clj and cljs place nodes differently and
            write different files for the same value -- on a platform pair
            where the whole point is that one writes what the other reads.

            Both branches of the rule are pinned, because they are different
            rules and only one of them mentions `walk`:

              an ARRAY or SORTED MAP is indexed when walk >= 64, at any stride
              an UNSORTED MAP is indexed only at stride 1, at any walk

            The last pair below is the one that separates them: the same map
            keyed in descending order, same walk of 72, earns a node at stride
            1 and NOTHING at stride 16 -- because an anchor loop over an
            unordered map visits every entry exactly as a plain scan does, so
            the reader would refuse it."
    (let [big (vec (range 70))
          nodes (fn [v stride]
                  (let [o {:profile :clojure :stringref false
                           :index stride :index-min 2}
                        i (boring/build-index (boring/encode v o) o)]
                    [(vec (:containers i)) (vec (:walk i)) (vec (:sorted i))]))]
      (doseq [stride [1 16]]
        ;; Arrays: gated on walk alone, so stride does not enter into it.
        (is (= [[0] [99] [false]] (nodes (vec (range 200)) stride))
            (str "stride " stride ": 200 scalars, one item each -- (200-1)/2 = 99"))
        ;; Entries that are themselves containers, so the mean is not the
        ;; uniform formula and an off-by-one anywhere in the accumulation
        ;; moves it: each entry is an array head plus ten elements.
        (is (= [[0] [214] [false]] (nodes (vec (for [_ (range 40)] (vec (range 10)))) stride))
            (str "stride " stride ": 40 vectors of 10"))
        ;; A SORTED map: binary-searchable, so also gated on walk alone.
        (is (= [[0] [72] [true]]
               (nodes (array-map "a" big "b" big "c" big) stride))
            (str "stride " stride ": three ascending keys, walk 72")))
      ;; And the stride-dependent branch.
      (is (= [[0] [72] [false]] (nodes (array-map "c" big "b" big "a" big) 1))
          "an unsorted map IS indexed at stride 1")
      (is (= [[] [] []] (nodes (array-map "c" big "b" big "a" big) 16))
          "and earns nothing above it, at the same walk"))))
