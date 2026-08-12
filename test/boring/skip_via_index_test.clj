(ns boring.skip-via-index-test
  "Skipping PAST a container through its index node, rather than walking it.

  THE COST THIS REMOVES. A CBOR array or map carries an ELEMENT COUNT, not a
  byte length, so stepping over one is a structural walk of its whole subtree.
  A field that sits after a big container therefore cost the whole container,
  and the index did nothing about it -- it accelerated lookups INSIDE a
  container and left skipping PAST one linear. Measured on the same indexed
  document, reaching a field after a 3000-element array:

      stride  1    135.13 us -> 17.53      7.7x
      stride 16    133.45 us ->  3.35     39.8x

  A node's anchors are entry boundaries, so the last one is within `stride`
  entries of the end; only that remainder is walked.

  WHAT IT COSTS. The gate is paid on every value a scan steps over: 60 small
  values at stride 1 measured 4.23 us against 3.36 unpatched, about 14 ns per
  entry. Documents with nothing jumpable pay that and gain nothing, which is
  the trade.

  WHY THESE TESTS COMPARE AGAINST THE WALK. A wrong end is a wrong ANSWER, not
  an exception -- the field after the container would be read from the wrong
  offset and come back as whatever those bytes decode to. So every case here
  asserts the jumped result equals what an unindexed document gives, which is
  the walk that cannot use the index at all."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring]
            [boring.nav :as nav]))

(defn- after
  "The value at `path`, read from `v` encoded with `opts`."
  [v path opts]
  (let [bs (if (:index opts) (boring/encode-indexed v opts) (boring/encode v opts))
        src (nav/source bs opts)]
    (nav/value-at src (nav/walk-from src (nav/root-offset src) path))))

(def ^:private plain {:stringref false})

(deftest a-field-after-a-big-container-reads-the-same-with-and-without-a-node
  (testing "the jump must agree with the walk, at every stride and both
            container kinds -- an array's anchors are element positions and a
            map's are key positions, so the remainder walk differs and only one
            of the two would be caught by testing arrays alone"
    (doseq [n [16 17 50 200 1000 3000]
            ;; A CLOJURE `sorted-map` IS TAG 27 WRAPPING A MAP -- major 6, so
            ;; `skip-value`'s gate never fires and it is walked, not jumped.
            ;; Using one here would have left the MAP branch (whose remainder
            ;; walk skips key AND value per entry) completely unexercised. A
            ;; plain map encodes as a bare CBOR map, which is the thing that
            ;; gets a node.
            [label big] [["array" (vec (repeat n {:t 1 :path "/p"}))]
                         ["map" (into {} (for [i (range n)] [(format "k%05d" i) i]))]
                         ["tag-wrapped map (walked, not jumped)"
                          (into (sorted-map) (for [i (range n)] [(format "k%05d" i) i]))]]
            stride [1 4 16 64]]
      (let [v {:big big :tail {:city "Berlin" :n n}}
            want (after v [:tail :city] plain)]
        (is (= "Berlin" want) (str label " n=" n " -- the unindexed baseline"))
        (is (= want (after v [:tail :city]
                           {:index stride :index-min 16 :stringref false}))
            (str label " n=" n " stride " stride))))))

(deftest the-container-itself-still-reads-correctly
  (testing "jumping over a container must not disturb reading INTO it -- the
            same node is used for both, and an off-by-one in the remainder walk
            would show up here as a wrong element rather than a wrong end"
    (let [v {:big (vec (for [i (range 500)] {:i i})) :tail {:city "Berlin"}}]
      (doseq [stride [1 16 64]]
        (let [bs (boring/encode-indexed v {:index stride :index-min 16 :stringref false})
              src (nav/source bs {:stringref false})
              root (nav/root-offset src)]
          (is (= {:i 499} (nav/value-at src (nav/walk-from src root [:big 499])))
              (str "last element, stride " stride))
          (is (= {:i 0} (nav/value-at src (nav/walk-from src root [:big 0]))))
          (is (= "Berlin" (nav/value-at src (nav/walk-from src root [:tail :city])))))))))

(deftest a-container-with-no-node-is-walked-not-jumped
  (testing "below `:index-min` nothing earned a node, so the gate must fall
            through to the honest walk rather than consult an index that cannot
            answer. A 15-entry container is under boring's default of 16."
    (let [v {:big (vec (repeat 15 {:t 1})) :tail {:city "Berlin"}}]
      (is (= "Berlin" (after v [:tail :city] {:index 1 :index-min 16 :stringref false})))
      (is (= "Berlin" (after v [:tail :city] plain))))))

(deftest several-big-containers-in-a-row
  (testing "one jump must land exactly where the next entry begins, or the
            second container is read from the wrong offset. Consecutive
            jumpable values are what makes that failure reachable."
    (let [v {:a (vec (repeat 100 {:x 1}))
             :b (vec (repeat 200 {:y 2}))
             :c (into {} (for [i (range 80)] [(format "k%03d" i) i]))
             :tail {:city "Berlin"}}]
      (doseq [stride [1 16]]
        (let [o {:index stride :index-min 16 :stringref false}]
          (is (= "Berlin" (after v [:tail :city] o)) (str "stride " stride))
          (is (= {:y 2} (after v [:b 199] o)))
          ;; reading INTO the map that was also jumped over
          (is (= 79 (after v [:c "k079"] o))))))))

(def ^:private gen-doc
  (gen/let [n (gen/choose 0 60)
            m (gen/choose 0 40)
            tail gen/string-ascii]
    {:big (vec (repeat n {:t 1 :path "/p"}))
     :mid (into (sorted-map) (for [i (range m)] [(format "k%03d" i) i]))
     :tail {:city tail}}))

(defspec the-jump-never-changes-an-answer 300
  ;; Sizes straddle `:index-min`, so the property covers both the jumped and
  ;; the walked path without knowing which it took.
  (prop/for-all [v gen-doc
                 stride (gen/elements [1 4 16 64])]
                (= (after v [:tail :city] plain)
                   (after v [:tail :city]
                          {:index stride :index-min 16 :stringref false}))))
