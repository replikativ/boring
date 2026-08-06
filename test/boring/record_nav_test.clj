(ns boring.record-nav-test
  "Navigating a tag-27 record without realising it -- and, mostly, REFUSING to.

   A record writes `27([name, {fields}])`, so the field map begins past the tag
   and the name. Descending means answering from that map instead of building
   what the reader would build, and that is only sound when the two are the same
   thing.

   WHO DECIDES. Not this namespace. `Reader.recordDescendable` does, beside the
   tag-27 dispatch it has to agree with, reading the same fields that dispatch
   reads. The previous gate was `recordCtor == nil`, mirrored here in Clojure
   with a docstring claiming the two could not drift because both read the same
   registry -- true of the registry, false of the DECISION, which also depends on
   the reserved-name table and on two options. It was wrong about all three the
   day it was written; `:on-unknown-record` had been added the day before.

   So the tests below are mostly about refusal, and about refusal being
   INDISTINGUISHABLE FROM THE OUTSIDE: whatever the gate decides, the answers
   must match `decode`, or nav must decline rather than answer."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring]
            [boring.data]
            [boring.nav :as nav]
            [boring.nav-conformance :as nc])
  (:import (org.replikativ.boring Reader)))

(def ^:private opts {:stringref false})

(defrecord Pt [x y])
(def ^:private pt-name (boring.data/record-type-name (->Pt 1 2)))

(defn- outcome [f]
  (try [:ok (f)]
       (catch clojure.lang.ExceptionInfo e [(or (:type (ex-data e)) :untyped)])
       (catch Throwable e [:untyped (class e)])))

(defn- descends?
  "Whether nav actually descended, observed from the outside: a descended record
   is Counted, an opaque tag is not."
  [bs o]
  (= :ok (first (outcome #(count (nav/source bs o))))))

;; ------------------------------------------------- what the gate must decide

(def ^:private gate-cases
  "[label opts descend?]. `decode` is the oracle for the ANSWERS; this column is
   only about whether the fast path is taken."
  [["unregistered, :fallback" {} true]
   ["unregistered, :on-unknown-record :error" {:on-unknown-record :error} false]
   ["unregistered, :on-unknown-record fn"
    {:on-unknown-record (fn [_nm m] {:renamed (get m :x)})} false]
   ["auto-construct, type exists" {:auto-construct-records? true} false]
   ["registered, undeclared"
    {:registry (-> (boring/tag-registry) (boring/register-record pt-name map->Pt))} false]
   ["registered AND declared"
    {:registry (-> (boring/tag-registry)
                   (boring/register-record pt-name map->Pt)
                   (boring/declare-navigable-record pt-name))} true]])

(deftest the-gate-descends-exactly-where-it-should
  (doseq [[label o descend?] gate-cases]
    (testing label
      (let [oo (merge opts o)
            bs (boring/encode (->Pt 1 2) oo)]
        (is (= descend? (descends? bs oo))
            (str label ": expected descend? " descend?))))))

(deftest answers-agree-with-decode-however-the-gate-decides
  (testing "the point of the gate. Refusing is fine; answering differently is
            not. Where `decode` succeeds, nav must match it; where `decode`
            raises, nav must raise the same type rather than answer."
    (doseq [[label o _] gate-cases]
      (testing label
        (let [oo (merge opts o)
              bs (boring/encode (->Pt 1 2) oo)
              [dk dv] (outcome #(boring/decode bs oo))]
          (doseq [k [:x :y :nope]]
            (let [want (if (= :ok dk) [:ok (get dv k ::nf)] [dk])
                  got (outcome #(nav/value (get (nav/source bs oo) k ::nf)))]
              (is (= want got) (str label " get " k)))))))))

;; ------------------------------------------------------------ reserved names

(def ^:private reserved-names
  "Every name the reader resolves ITSELF, before the registry matters. Each
   builds something the field map is not, so none may be descended into.

   Listed here as well as in `Reader.isReservedRecordName` on purpose: this is
   the check that the Java list has not fallen behind the switch beside it."
  ["clojure/sorted-map" "clojure/sorted-set" "clojure/with-meta" "clojure/char"
   "clojure/ex-info" "clojure/queue" "java/throwable" "java/boolean-array"
   "java/char-array" "java/string-array" "java/object-array" "java/period"])

(deftest no-reserved-name-is-descendable
  (testing "S7. All twelve descended and answered before, while `decode` raised
            `:boring/bad-tag-content` for most of them. `clojure/sorted-set` was
            the sharpest case: `get` on a set is MEMBERSHIP, which the tag
            taxonomy comment already named as the kind that must stay opaque."
    (let [^Reader r (Reader. (byte-array 1))]
      (doseq [nm reserved-names]
        (is (not (.recordDescendable r nm)) (str nm " must not be descendable"))))))

(deftest sorted-map-no-longer-descends-and-still-answers
  (testing "the trade, recorded. `clojure/sorted-map` was descendable and worth a
            measured 73x, and is now refused with the rest. Sound only for
            sorted-maps BORING wrote -- a hand-crafted document can claim the
            name over keys that are not mutually comparable, and then `decode`
            raises while `count` and `seq` answer. Establishing comparability
            costs realising every key at view-build, which is O(K) on the
            operation the descent made O(log K).

            What must NOT change is the answers."
    (let [m (into (sorted-map) {:x 1 :y 2})
          bs (boring/encode m opts)]
      (is (not (descends? bs opts)) "refused")
      (is (= m (nav/value (nav/source bs opts))) "value still realises correctly")
      (is (= 1 (nav/value (get (nav/source bs opts) :x))) "and lookups still answer")
      (is (= ::nf (nav/value (get (nav/source bs opts) :nope ::nf)))))))

;; ------------------------------------------------------------- descent works

(deftest an-unregistered-record-descends-and-agrees
  (testing "the case that survives: no constructor means UnknownRecord, whose
            valAt, count and seq delegate straight to the field map"
    (let [bs (boring/encode (->Pt 7 8) opts)
          c (nav/source bs opts)
          realised (boring/decode bs opts)]
      (is (descends? bs opts))
      (is (= :tag (nav/value-type c)) "value-type still reports the tag")
      (is (= realised (nav/value c)) "value realises the record, not the field map")
      (is (= 2 (count c)))
      (is (= 7 (nav/value (get c :x))))
      (is (= (into {} realised)
             (into {} (map (fn [[k v]] [k (nav/value v)])) (seq c)))))))

(deftest a-declared-name-descends
  (let [reg (-> (boring/tag-registry)
                (boring/register-record pt-name map->Pt)
                (boring/declare-navigable-record pt-name))
        o (assoc opts :registry reg)
        bs (boring/encode (->Pt 1 2) o)
        c (nav/source bs o)]
    (is (descends? bs o))
    (is (= (->Pt 1 2) (nav/value c)) "value realises the RECORD")
    (is (= 1 (nav/value (get c :x))))
    (is (= ::nf (nav/value (get c :nope ::nf))))))

;; ------------------------------------------------------------- conformance

(deftest conformance-catches-a-declaration-that-is-not-true
  (testing "a wrong declaration returns wrong values silently -- no exception,
            no slow path. This is what makes it checkable rather than asserted."
    (let [liar (fn [m] (assoc m :extra 99))
          reg (-> (boring/tag-registry)
                  (boring/register-record pt-name liar)
                  (boring/declare-navigable-record pt-name))
          r (nc/check-record reg pt-name [(->Pt 1 2)])]
      (is (some? r) "a lying declaration must not pass")
      (is (= :count (:check r)))))

  (testing "an honest one passes"
    (let [reg (-> (boring/tag-registry)
                  (boring/register-record pt-name map->Pt)
                  (boring/declare-navigable-record pt-name))]
      (is (nil? (nc/check-record reg pt-name [(->Pt 1 2) (->Pt 0 0)])))))

  (testing "and a VACUOUS check says so rather than passing"
    (let [reg (-> (boring/tag-registry) (boring/register-record pt-name map->Pt))]
      (is (= :declared (:check (nc/check-record reg pt-name [(->Pt 1 2)])))))))

(deftest conformance-checks-the-built-in-descents
  (is (nil? (nc/check-value (into (sorted-map) {:a 1 :b 2}) opts)))
  (is (nil? (nc/check-value (vec (for [i (range 40)] {:id i :n (str i)}))
                            (assoc opts :shapes true))))
  (is (nil? (nc/check-value (long-array (range 50)) opts)))
  (is (nil? (nc/check-value (long-array [-3 -2 -1 0]) opts)) "negatives too")
  (is (nil? (nc/check-value {:a 1 :b [1 2 3]} opts)))
  (is (nil? (nc/check-value #{1 2 3} opts)) "an opaque tag must pass too"))

(defspec record-descent-agrees-with-realising 150
  (prop/for-all
   [xs (gen/vector gen/small-integer 0 6)]
   (let [v (->Pt (vec xs) (count xs))
         bs (boring/encode v opts)
         c (nav/source bs opts)
         realised (boring/decode bs opts)]
     (and (= realised (nav/value c))
          (= 2 (count c))
          (= (vec xs) (nav/value (get c :x)))
          (= ::nf (nav/value (get c :nope ::nf)))))))
