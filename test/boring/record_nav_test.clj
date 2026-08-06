(ns boring.record-nav-test
  "Navigating a tag-27 record without realising it.

   A record writes `27([name, {fields}])`, so the field map begins past the tag
   and the name -- 22 bytes for `clojure/sorted-map`. The cursor stands on the
   TAG, `node-slot` is asked about the tag's offset, and no index node matches,
   so every lookup realised the whole record. 577 us on a 2000-key sorted-map
   against 7.91 us navigated, with the navigated cost flat in size.

   The cursor STAYS on the tag; only the container operations redirect to the
   field map. That is what keeps `value` realising the record rather than the
   bare map, and `value-type` answering `:tag`.

   TWO REFUSALS, both about answering differently from the reader:

   - A REGISTERED name does not descend. `TagRegistry.recordCtor` is an
     arbitrary `IFn` and may rename or drop fields. Unregistered names decode to
     `UnknownRecord`, whose `valAt`/`count`/`seq` delegate straight to the field
     map, so descent is exactly equivalent there.

   - A SORTED-MAP descends only for keyword, symbol and string keys. It looks up
     by `compare`, not `=`, and the two disagree across numeric types:
     `(get (sorted-map 1 :a) 1.0)` is `:a` because `(compare 1 1.0)` is 0, while
     a byte probe for 1.0 matches nothing. A custom comparator cannot reach here
     at all -- the writer refuses to encode one."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring]
            [boring.data]
            [boring.nav :as nav]
            [boring.nav-conformance :as nc]))

(def ^:private opts {:stringref false})
(def ^:private trusted (assoc opts :trust-index :trusted))

(defn- enc ^bytes [v] (boring/encode-indexed v (assoc opts :index 16 :index-min 8)))

(defn- realised-get
  "`get` on the realised value, with the sorted-map comparator's raw
   ClassCastException folded to not-found -- which is `nav/get`'s contract and
   was so before this descent existed."
  [m k nf]
  (try (get m k nf) (catch ClassCastException _ nf)))

(deftest navigating-a-record-agrees-with-realising-it
  (doseq [[label m]
          [["sorted-map keywords" (into (sorted-map) (for [j (range 60)] [(keyword (str "f" j)) j]))]
           ["sorted-map strings"  (into (sorted-map) (for [j (range 60)] [(str "f" j) j]))]
           ["sorted-map longs"    (into (sorted-map) (for [j (range 60)] [j (str "v" j)]))]
           ["sorted-map doubles"  (into (sorted-map) (for [j (range 20)] [(double j) j]))]
           ["two entries"         (sorted-map 1 :a 2 :b)]
           ["empty"               (sorted-map)]]]
    (testing label
      (let [bs (enc m)
            c (nav/source bs trusted)
            realised (boring/decode bs opts)]
        (is (= m realised) "the fixture must round-trip at all")
        (is (= m (nav/value c)) "value realises the RECORD, not the field map")
        (is (= :tag (nav/value-type c)) "and value-type still says tag")
        (is (= (count realised) (count c)))
        (testing "every key, and keys of every other type"
          (doseq [k (concat (keys m) [:absent "absent" 'absent 0 1.0 nil [1] [1.0]])]
            (is (= (realised-get realised k ::nf)
                   (nav/value (get c k ::nf)))
                (str "key " (pr-str k)))))
        (is (= realised (into {} (map (fn [[k v]] [k (nav/value v)])) (seq c))) "seq")
        (is (= realised (into {} (map (fn [[k v]] [k (nav/value v)])) c)) "reduce")
        (is (nil? (nth c 0 nil)) "nth on a map is not-found")))))

(deftest the-numeric-key-divergence-is-not-papered-over
  (testing "this is the case `record-key-ok?` exists for. `compare` says 1 and
            1.0 are the same key and `=` says they are not, so descending on a
            numeric key would answer differently from the reader. It must
            realise instead, and agree."
    (let [m (sorted-map 1 :a 2 :b)
          c (nav/source (enc m) trusted)]
      (is (= :a (get m 1.0)) "the premise: a sorted-map matches across types")
      (is (= :a (nav/value (get c 1.0)))
          "so navigation must give :a too, by realising rather than probing")
      (is (= :a (nav/value (get c 1)))))))

(defrecord Pt [x y])

(deftest a-registered-name-does-not-descend
  (testing "the ctor is an arbitrary IFn, so answering from the field map could
            answer from bytes the reader would never have produced. It must keep
            realising -- and keep giving the same answers."
    (let [reg (-> (boring/tag-registry)
                  (boring/register-record
                   (boring.data/record-type-name (->Pt 1 2)) map->Pt))
          o (assoc opts :registry reg)
          bs (boring/encode (->Pt 1 2) o)
          c (nav/source bs o)]
      (is (= (->Pt 1 2) (nav/value c)))
      (is (= 1 (nav/value (get c :x))))
      (is (= 2 (nav/value (get c :y))))
      (is (= ::nf (nav/value (get c :nope ::nf)))))))

(deftest an-unregistered-record-descends-and-agrees
  (testing "no registration means UnknownRecord, whose lookup IS the field map"
    (let [reg (-> (boring/tag-registry)
                  (boring/register-record
                   (boring.data/record-type-name (->Pt 1 2)) map->Pt))
          bs (boring/encode (->Pt 7 8) (assoc opts :registry reg))
          ;; read back WITHOUT the registration
          c (nav/source bs opts)
          realised (boring/decode bs opts)]
      (is (= 7 (get realised :x)) "UnknownRecord looks up its fields")
      (is (= 7 (nav/value (get c :x))))
      (is (= 2 (count c)))
      (is (= {:x 7 :y 8} (into {} (map (fn [[k v]] [k (nav/value v)])) (seq c)))))))

(def ^:private pt-name (boring.data/record-type-name (->Pt 1 2)))

(deftest a-declared-name-descends
  (testing "`declare-navigable-record` is how a handler's author says the
            constructor preserves structure. `map->Pt` does, so descent must
            engage AND agree -- value still realises the RECORD."
    (let [reg (-> (boring/tag-registry)
                  (boring/register-record pt-name map->Pt)
                  (boring/declare-navigable-record pt-name))
          o (assoc opts :registry reg)
          c (nav/source (boring/encode (->Pt 1 2) o) o)]
      (is (= (->Pt 1 2) (nav/value c)) "value realises the record, not the map")
      (is (= :tag (nav/value-type c)))
      (is (= 2 (count c)) "count descends -- it refused every tag before")
      (is (= 1 (nav/value (get c :x))))
      (is (= ::nf (nav/value (get c :nope ::nf))))
      (is (= {:x 1 :y 2} (into {} (map (fn [[k v]] [k (nav/value v)])) (seq c)))))))

(deftest conformance-catches-a-declaration-that-is-not-true
  (testing "the declaration is a CLAIM, and a wrong one returns wrong values
            silently -- no exception, no slow path. This is the check that
            makes it testable rather than assertable."
    (let [;; a constructor that invents a field: structure NOT preserved
          liar (fn [m] (assoc m :extra 99))
          reg (-> (boring/tag-registry)
                  (boring/register-record pt-name liar)
                  (boring/declare-navigable-record pt-name))
          r (nc/check-record reg pt-name [(->Pt 1 2)])]
      (is (some? r) "a lying declaration must not pass")
      (is (= :count (:check r)))
      (is (= 3 (:expected r)) "the realised value has the invented field")
      (is (= 2 (:actual r)) "the wire has only what was written")))

  (testing "an honest declaration passes"
    (let [reg (-> (boring/tag-registry)
                  (boring/register-record pt-name map->Pt)
                  (boring/declare-navigable-record pt-name))]
      (is (nil? (nc/check-record reg pt-name [(->Pt 1 2) (->Pt 0 0)])))))

  (testing "and a check that would be VACUOUS says so rather than passing --
            an undeclared name never descends, so agreeing proves nothing"
    (let [reg (-> (boring/tag-registry) (boring/register-record pt-name map->Pt))
          r (nc/check-record reg pt-name [(->Pt 1 2)])]
      (is (= :declared (:check r))))))

(deftest conformance-checks-the-built-in-descents
  (testing "check-value is the same property the shaped, typed and record tests
            assert by hand, exposed for handler authors to run on their own data"
    (is (nil? (nc/check-value (into (sorted-map) {:a 1 :b 2}) opts)))
    (is (nil? (nc/check-value (vec (for [i (range 40)] {:id i :n (str i)}))
                              (assoc opts :shapes true))))
    (is (nil? (nc/check-value (long-array (range 50)) opts)))
    (is (nil? (nc/check-value {:a 1 :b [1 2 3]} opts)))
    (is (nil? (nc/check-value #{1 2 3} opts)) "an opaque tag must pass too")))

(defspec record-descent-agrees-with-realising 150
  (prop/for-all
   ;; ONE key type per map. A sorted-map of mixed keywords and strings is not
   ;; mutually comparable, so `(into (sorted-map) ...)` throws while BUILDING
   ;; the fixture -- a broken generator, not a finding.
   [pairs (gen/let [kind (gen/elements [:kw :str :num])
                    n (gen/choose 0 7)
                    vs (gen/vector gen/small-integer n)]
            (map-indexed
             (fn [i v]
               [(case kind
                  :kw (keyword (str "k" i))
                  :str (str "k" i)
                  :num (long i))
                v])
             vs))]
   (let [m (into (sorted-map) pairs)
         bs (enc m)
         c (nav/source bs trusted)
         realised (boring/decode bs opts)]
     (and (= m realised)
          (= (count m) (count c))
          (every? #(= (realised-get realised % ::nf)
                      (nav/value (get c % ::nf)))
                  (concat (keys m) [:absent "absent" 0 1.0]))))))
