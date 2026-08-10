(ns boring.shape-offset-test
  "The offset layer's view of a shaped array (tag 39649), and the guards that
  had to exist before it could have one.

  WHAT WAS WRONG. `nth-item`, `lookup-map` and `head-count` have always
  assumed their caller checked the major type. `walk-from` did; the three
  PUBLIC functions over them did not. On a shaped array -- which is a TAG --
  `nth-offset` and `field-offset` read a tag's argument as though it were a
  container length and walked off the end, and `container-count` returned
  39649, THE TAG NUMBER, silently, as a count. A wrong count out of a public
  function is the one outcome this namespace's trust boundary is supposed to
  make impossible, and it is what these tests pin first.

  WHAT WAS MISSING, which is the larger half. The key-to-column map is the
  entire reason a shaped array is fast: resolve a key ONCE for the table and
  every row afterwards is an array index, where an ordinary array of maps pays
  a key comparison per row per field. That map was computed inside a closure in
  `shaped-view` and reachable only from the cursor layer, so the offset layer --
  the allocation-free one, the one a scan is supposed to use -- could not
  express the operation the encoding exists for. `shape` and its accessors are
  that map, hoisted where a caller can hold it.

  Measured over 5000 rows of five fields, summing one column: 137 us through
  these functions against 367 us for hako and 1414 us for nippy, both of which
  must decode the whole table to reach one field of it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring]
            [boring.nav :as nav]))

(def ^:private opts {:shapes true :stringref false})

(defn- table [n]
  (mapv (fn [i] {:e i :a :user/name :v (str "person-" i)}) (range n)))

(defn- shaped-src
  "A source over a shaped document, plus the offset its root value starts at."
  ([v] (shaped-src v opts))
  ([v o] (let [bs (boring/encode v o)] (nav/source bs nil))))

;; --------------------------------------------------------------- the guards

(deftest container-count-refuses-a-tag-instead-of-answering-its-tag-number
  (testing "the specific regression: 39649 came back as a count, because
            `headArgAt` answers a tag's argument as readily as a container's
            length and nothing looked at the major type"
    (let [s (shaped-src (table 20))]
      (is (= :tag (nav/value-type (nav/root s))))
      (is (= :boring/not-a-container
             (try (do (nav/container-count s (nav/root-offset s)) nil)
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
      (testing "and it is not 39649 by any route -- the count lives on the shape"
        (is (= 20 (nav/shape-count (nav/shape s (nav/root-offset s)))))))))

(deftest container-count-refuses-every-non-container-not-only-tags
  (let [s (nav/source (boring/encode {:n 1 :s "x" :v [1 2 3] :m {:a 1}}
                                     {:stringref false}) nil)
        root (nav/root-offset s)
        at (fn [k] (nav/field-offset s root k))
        typ (fn [off] (try (do (nav/container-count s off) :answered)
                           (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
    (testing "arrays and maps answer"
      (is (= 3 (nav/container-count s (at :v))))
      (is (= 1 (nav/container-count s (at :m)))))
    (testing "scalars refuse rather than reporting their head argument, which
              for `1` would have been 1 and looked entirely plausible"
      (is (= :boring/not-a-container (typ (at :n))))
      (is (= :boring/not-a-container (typ (at :s)))))))

(deftest the-offset-readers-answer--2-for-a-tag-rather-than-throwing
  (testing "-2 is the vocabulary `walk-from` has always used for `it is there,
            but no offset names it`. A caller checking `neg?` gets not-found;
            one checking for -2 knows to use `shape` or `walk`. Neither gets
            `:boring/truncated-input`, which was true only by accident -- the
            walk ran off the end of the document."
    (let [s (shaped-src (table 20))
          root (nav/root-offset s)]
      (is (= -2 (nav/nth-offset s root 3)))
      (is (= -2 (nav/field-offset s root :v))))))

(deftest a-shaped-row-refuses-a-key-because-its-keys-are-not-in-its-bytes
  (testing "a row's bytes are an ARRAY; its value is a MAP. A key comparison
            against those bytes cannot find `:v`, and matching one positionally
            would answer a VALUE where a key was sought."
    (let [s (shaped-src (table 20))
          sh (nav/shape s (nav/root-offset s))
          row (nav/nth-offset s (nav/shape-rows sh) 3)]
      (is (pos? row))
      (is (= -2 (nav/field-offset s row :v)))
      (testing "while the column route answers, and answers correctly"
        (is (= "person-3"
               (nav/value-at s (nav/nth-offset s row (nav/shape-column sh :v)))))))))

;; ------------------------------------------------------------- `shape` itself

(deftest shape-is-nil-for-everything-that-is-not-one
  (testing "a caller asking whether a document is shaped is asking a question,
            not asserting an answer -- so this is an `if-let`, never a throw"
    (doseq [[label v] [["a plain array of maps" (table 20)]
                       ["a map" {:a 1 :b 2}]
                       ["a scalar" 42]
                       ["a string" "hello"]
                       ["an empty array" []]
                       ["a set, which is a tag but not this one" #{1 2 3}]]]
      (let [s (nav/source (boring/encode v {:shapes false :stringref false}) nil)]
        (is (nil? (nav/shape s (nav/root-offset s))) label)))))

(deftest shape-reads-the-header-a-writer-wrote
  (let [s (shaped-src (table 7))
        sh (nav/shape s (nav/root-offset s))]
    (is (some? sh))
    (testing "keys in COLUMN ORDER -- the vector `shape-column` indexes into"
      (is (= [:e :a :v] (nav/shape-keys sh))))
    (is (= 7 (nav/shape-count sh)))
    (testing "every key resolves to its own column, and each column is distinct"
      (is (= [0 1 2] (mapv #(nav/shape-column sh %) (nav/shape-keys sh)))))
    (testing "the rows offset is an ORDINARY ARRAY -- that is the bridge, and
              below it nothing knows about shapes"
      (is (= 7 (nav/container-count s (nav/shape-rows sh)))))))

(deftest a-missing-key-is--1-and--1-composes-to-absent
  (testing "an unchecked `(get pos k)` would be nil, and `nth-offset` on nil
            is a NullPointerException at best and column zero at worst -- which
            is the difference between not-found and the wrong field's value"
    (let [s (shaped-src (table 5))
          sh (nav/shape s (nav/root-offset s))
          row (nav/nth-offset s (nav/shape-rows sh) 0)]
      (is (= -1 (nav/shape-column sh :not-a-key)))
      (is (= -1 (nav/nth-offset s row (nav/shape-column sh :not-a-key))))
      (testing "and `value-at` refuses it rather than reading the document head"
        (is (= :boring/absent
               (try (do (nav/value-at s -1) nil)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest shape-works-away-from-the-root
  (testing "shapes fire at ANY depth -- nested under a key, twice nested, and
            for two sibling tables in one map. The offset layer has to reach
            them all, since the root is rarely the table."
    (let [v {:meta {:n 3} :rows (table 4) :other (mapv (fn [i] {:x i}) (range 6))}
          s (shaped-src v)
          root (nav/root-offset s)
          rows-tag (nav/field-offset s root :rows)
          other-tag (nav/field-offset s root :other)]
      (is (pos? rows-tag))
      (let [a (nav/shape s rows-tag)
            b (nav/shape s other-tag)]
        (is (= [:e :a :v] (nav/shape-keys a)))
        (is (= 4 (nav/shape-count a)))
        (is (= [:x] (nav/shape-keys b)))
        (is (= 6 (nav/shape-count b)))
        (testing "and the two are independent -- one source, two shapes"
          (is (= 3 (nav/value-at s (nav/nth-offset
                                    s (nav/nth-offset s (nav/shape-rows b) 3)
                                    (nav/shape-column b :x)))))))
      (testing "a non-shaped sibling is still nil, not the previous shape"
        (is (nil? (nav/shape s (nav/field-offset s root :meta))))))))

(deftest shape-accepts-a-cursor-as-well-as-a-source-and-offset
  (let [bs (boring/encode (table 5) opts)]
    (is (= (nav/shape-keys (nav/shape (nav/root bs)))
           (nav/shape-keys (nav/shape (nav/source bs nil) 0))))))

;; ------------------------------------------------------------- `root-offset`

(deftest root-offset-agrees-with-the-cursor-it-replaces
  (testing "entering the offset layer used to mean building a cursor purely to
            read its offset back off. Both must name the same byte."
    (doseq [[label v o] [["plain" (table 5) {:shapes false :stringref false}]
                         ["shaped" (table 5) opts]]]
      (let [bs (boring/encode v o)
            s (nav/source bs nil)]
        (is (= (nav/offset (nav/root bs)) (nav/root-offset s)) label))))
  (testing "and the case that is NOT 0: past a tag-256 stringref envelope. It
            has to be an INDEXED encoding, because a stringref document without
            a pointer table is not navigable at all -- `encode` alone turns
            stringref on and produces exactly that."
    (let [bs (boring/encode-indexed (vec (repeat 8 "a-repeated-string"))
                                    {:stringref true})
          s (nav/source bs nil)]
      (is (pos? (nav/root-offset s))
          "the envelope is the whole point of this case")
      (is (= (nav/offset (nav/root bs)) (nav/root-offset s))))))

;; ------------------------------------------------------------------ property

(def ^:private gen-table
  (gen/let [ks (gen/not-empty (gen/vector-distinct
                               (gen/elements [:e :a :v :tx :added :ident])
                               {:min-elements 1 :max-elements 6}))
            n (gen/choose 2 40)
            vals (gen/vector (gen/vector gen/small-integer (count ks)) n)]
    (mapv #(zipmap ks %) vals)))

(defspec the-shaped-scan-answers-what-decode-answers 200
  (prop/for-all [rows gen-table
                 idx (gen/choose 0 5)]
    (let [bs (boring/encode rows opts)
          s (nav/source bs nil)
          sh (nav/shape s (nav/root-offset s))
          ks (vec (keys (first rows)))
          k (nth ks (mod idx (count ks)))]
      ;; The writer only shapes when every row carries the same key set, which
      ;; `zipmap` over one `ks` guarantees -- so a nil shape here is a real
      ;; disagreement between the writer and this reader, not a generator that
      ;; wandered off.
      (and (some? sh)
           (= (count rows) (nav/shape-count sh))
           (= (set ks) (set (nav/shape-keys sh)))
           (= (mapv k rows)
              (let [col (nav/shape-column sh k)
                    rows-off (nav/shape-rows sh)]
                (nav/reduce-at s rows-off
                               (fn [acc ro]
                                 (conj acc (nav/value-at
                                            s (nav/nth-offset s ro col))))
                               [])))))))

(defspec the-offset-route-and-the-cursor-route-never-disagree 200
  (prop/for-all [rows gen-table
                 i (gen/choose 0 39)]
    (let [bs (boring/encode rows opts)
          s (nav/source bs nil)
          sh (nav/shape s (nav/root-offset s))
          row-i (mod i (count rows))
          k (first (keys (first rows)))
          via-offset (nav/value-at
                      s (nav/nth-offset
                         s (nav/nth-offset s (nav/shape-rows sh) row-i)
                         (nav/shape-column sh k)))
          via-cursor (nav/value (nav/walk (nav/root bs) [row-i k]))]
      (= via-offset via-cursor (get (nth rows row-i) k)))))
