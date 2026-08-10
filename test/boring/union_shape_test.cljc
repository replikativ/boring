(ns boring.union-shape-test
  "Shaped arrays over rows that do not all carry the same keys.

  Shapes used to be ALL-OR-NOTHING: the writer took the first row's key set and
  declined the moment any row differed, so one ragged row in a thousand cost the
  whole table its density. Data arriving at a system boundary is exactly the
  ragged case -- optional fields, schema drift, mixed event types.

  The shape's keys are now the UNION of every row's, in first-seen order, and a
  row that lacks one says so in ONE OF TWO WAYS:

    a SHORT row  -- trailing keys it does not reach are absent
    `undefined`  -- simple value 23, byte 0xf7, for a gap in the middle

  `null` (0xf6) is untouched and still means a PRESENT key whose value is nil,
  which is the distinction the whole design rests on: `{:a nil}` and `{}` are
  different maps and must stay different bytes.

  The idea is draft-ietf-cbor-packed's tag-114 `record` function, which uses
  `undefined` for the same purpose. Only the semantics are borrowed -- not the
  packing framework, and not its tag numbers, which are unassigned.

  BOTH PLATFORMS, deliberately. Shapes are per-array, so a JVM/cljs divergence
  here is not a corner case, it is every document -- which is what #38 was
  about. The byte-level assertions below are the part that would catch it."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            #?(:clj [boring.nav])
            [boring.data :as data]))

(def ^:private O {:shapes true :stringref false})
(def ^:private P {:shapes false :stringref false})

;; `count` does NOT work on an encode result across both platforms: the JVM
;; returns a byte[] and ClojureScript a js/Uint8Array, which implements no
;; ICounted. Every size assertion below goes through this.
(defn- blen ^long [b] #?(:clj (alength ^bytes b) :cljs (.-length b)))

(defn- bs [v] (mapv #(bit-and % 0xff) (boring/encode v O)))
(defn- rt [v] (boring/decode (boring/encode v O)))
(defn- enc-len ^long [v opts] (blen (boring/encode v opts)))
(defn- shaped? [v] (not= (enc-len v O) (enc-len v P)))

;; ------------------------------------------------------------- round trips

(deftest ragged-rows-round-trip
  (testing "every shape of raggedness survives, which is the whole point"
    (doseq [[label v] [["a trailing extra key"  [{:a 1 :b 2} {:a 3 :b 4 :c 5}]]
                       ["a missing tail"        [{:a 1 :b 2 :c 3} {:a 4}]]
                       ["a missing middle"      [{:a 1 :b 2 :c 3} {:a 4 :c 6}]]
                       ["disjoint keys"         [{:a 1} {:b 2}]]
                       ["an empty row"          [{:a 1 :b 2} {}]]
                       ["growing rows"          [{:a 1} {:a 2 :b 3} {:a 4 :b 5 :c 6}]]
                       ["shrinking rows"        [{:a 1 :b 2 :c 3} {:a 4 :b 5} {:a 6}]]]]
      (is (= v (rt v)) label))))

(deftest nil-is-a-value-and-absence-is-not
  (testing "the distinction the design rests on. `null` is 0xf6 and means the
            key is THERE with a nil value; `undefined` is 0xf7 and means the key
            is not there. Collapsing them would make `{:a nil}` and `{}` the
            same map."
    (let [v [{:a nil :b 1} {:b 2}]]
      (is (= v (rt v)))
      (is (contains? (first (rt v)) :a))
      (is (not (contains? (second (rt v)) :a)))
      (testing "and the two spellings sit in the same column, one byte apart"
        (is (= [0xd9 0x9a 0xe1 0x82
                0x82 0xd8 0x27 0x62 0x3a 0x61 0xd8 0x27 0x62 0x3a 0x62
                0x82
                0x82 0xf6 0x01          ; row 0: null, 1  -- :a present, nil
                0x82 0xf7 0x02]         ; row 1: undef, 2 -- :a absent
               (bs v)))))))

(deftest trailing-absences-are-truncated-not-padded
  (testing "a row stops at its last present key. Padding every row to the union
            would cost a byte per absent slot on every row; truncation costs
            nothing, and it is also what would let a single-pass writer grow the
            shape without rewriting rows already emitted."
    (is (= [0xd9 0x9a 0xe1 0x82
            0x83 0xd8 0x27 0x62 0x3a 0x61 0xd8 0x27 0x62 0x3a 0x62
                 0xd8 0x27 0x62 0x3a 0x63
            0x82
            0x82 0x01 0x02              ; row 0 stops at 2 values, :c absent
            0x83 0x03 0x04 0x05]        ; row 1 carries all three
           (bs [{:a 1 :b 2} {:a 3 :b 4 :c 5}])))))

(deftest an-explicit-undefined-value-declines-the-shape
  (testing "absence is spelled `undefined`, so a row that CARRIES undefined as a
            value cannot be shaped -- the two would be indistinguishable coming
            back. `:shapes` is an optimisation, and an optimisation that
            silently changes the value is not one."
    (let [v [{:a data/undefined :b 1} {:a 2 :b 3}]]
      (is (not (shaped? v)))
      (is (= v (rt v))))
    (testing "and by VALUE, not by identity -- a freshly constructed
              simple-value 23 is equal to the singleton but not the same object"
      (let [v [{:a (data/simple-value 23) :b 1} {:a 2 :b 3}]]
        (is (not (shaped? v)))
        (is (= v (rt v)))))
    (testing "while any OTHER simple value shapes normally"
      (let [v [{:a (data/simple-value 99) :b 1} {:a 2 :b 3}]]
        (is (shaped? v))
        (is (= v (rt v)))))))

;; ------------------------------------------------------------ the density bound

(defn- schemas
  "`n-rows` maps drawn from `n-schemas` DISJOINT key sets of `m` keys each."
  [n-rows n-schemas m]
  (vec (for [i (range n-rows)]
         (let [t (mod i n-schemas)]
           (into {} (for [j (range m)]
                      [(keyword (str "t" t "k" j)) (+ i j)]))))))

(deftest disjoint-key-sets-are-refused-rather-than-shaped
  (testing "THE PATHOLOGICAL CASE. Keys are numbered in first-seen order, so a
            row introducing all-new keys sits at high positions and must be
            padded past everything before it -- the padding is O(rows^2) and the
            document gets BIGGER. Measured, 200 rows of 5 keys, shaped/plain:

              distinct key sets    1     5    10     20     40    200
              ratio             0.21  0.43  0.71   1.19   2.17   9.65

            so the writer declines from 20 upward. An earlier version of this
            work claimed there was no cliff; that was measured on schemas that
            CYCLED, which caps the union and hides the quadratic term."
    (doseq [t [1 2 5 10]]
      (is (shaped? (schemas 200 t 5))
          (str t " key sets should still shape")))
    (doseq [t [20 40 80 200]]
      (is (not (shaped? (schemas 200 t 5)))
          (str t " key sets should be refused")))
    (testing "and whatever it decides, the value survives"
      (doseq [t [1 10 20 200]]
        (let [v (schemas 60 t 5)] (is (= v (rt v))))))))

(deftest the-bound-never-makes-a-document-bigger-than-plain
  (testing "the guard's one job. Whatever the writer decides, shaped output must
            not exceed what it would have written without shapes."
    (doseq [[label v] [["uniform" (schemas 100 1 5)]
                       ["few schemas" (schemas 100 5 5)]
                       ["many schemas" (schemas 100 25 5)]
                       ["fully disjoint" (schemas 100 100 5)]
                       ["one optional field" (mapv #(if (even? %) {:a % :b 1} {:a % :b 1 :c 2})
                                                   (range 100))]]]
      (is (<= (enc-len v O) (enc-len v P)) label))))

;; --------------------------------------------------------------- unchanged

;; ------------------------------------------------------------------ nav
;;
;; JVM only -- `boring.nav` has no ClojureScript port yet.

#?(:clj
   (deftest nav-agrees-with-decode-on-ragged-rows
     (testing "TWO READERS, ONE ANSWER. `boring.nav` presents a shaped row as a
               map without building one, so it re-derives what the decoder
               derives -- and every place it does so had to learn that a key can
               be absent. `nav/value` was the one that did not: it zipped the
               shape's keys against the row's values, which was right while every
               row carried every key and reported absent keys as PRESENT with the
               value `undefined` once the shape became a union."
       (let [v [{:a 1 :b 2 :c 3} {:a 4} {:b 5 :c 6} {:a nil :c 7}]
             bs (boring/encode v O)
             r (boring.nav/root bs)]
         (is (= v (boring/decode bs)))
         (is (= v (boring.nav/value r)) "nav and decode must not disagree")
         (testing "count is the row's, not the shape's"
           (is (= (mapv count v) (mapv #(count (get r %)) (range 4)))))
         (testing "seq yields only the keys the row carries"
           (is (= [[:a :b :c] [:a] [:b :c] [:a :c]]
                  (mapv #(mapv key (seq (get r %))) (range 4)))))
         (testing "get distinguishes absent from present-with-nil"
           ;; row 2 has no :a at all; row 3 has :a with a nil value
           (is (nil? (get (get r 2) :a)))
           (is (some? (get (get r 3) :a)))
           (is (nil? (boring.nav/value (get (get r 3) :a)))))
         (testing "and the offset layer sees the same shape"
           (let [s (boring.nav/source bs nil)
                 sh (boring.nav/shape s (boring.nav/root-offset s))]
             (is (= [:a :b :c] (boring.nav/shape-keys sh)))
             (is (= 4 (boring.nav/shape-count sh)))))))))

(deftest a-uniform-table-is-byte-identical-to-before
  (testing "the union reduces to the old rule when every row agrees, so nothing
            about the dense case may have moved"
    (let [v (mapv (fn [i] {:e i :a :user/name :v (str "p" i)}) (range 20))]
      (is (shaped? v))
      (is (= v (rt v)))
      (testing "no undefined byte appears anywhere in a dense table"
        (is (not (some #{0xf7} (bs v))))))))
