(ns boring.register-record-name-test
  "The wire name a registration must use, and what happens when it does not.

   `register-record` takes the name as a STRING, and a wrong one fails SILENTLY
   — by design, and that is the problem. An unregistered record decodes to an
   `UnknownRecord` carrying the same name and fields and re-encodes to identical
   bytes, which is a property worth having; it also means a wrongly-FORMED
   registration is indistinguishable from a type you never registered. Nothing
   raises, and any `instance?` check the caller makes answers false.

   These pin the form, because the docstring got it wrong: it gave
   `\"my.ns.Point\"` — the JVM class name — while the writer emits
   `namespace/Name`. Every registration written from that example silently did
   nothing. See `boring.data/record-type-name` on why the separator is a slash
   (a dot is ambiguous: `a.b.c.D` could split either way)."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.data :as data]))

(defrecord Point [x y])

(defn- round [registry v]
  (let [o {:registry registry}]
    (boring/decode (boring/encode v o) o)))

(deftest the-wire-name-is-namespace-slash-name
  (testing "what the writer emits, which is what a registration has to match"
    (is (= "boring.register-record-name-test/Point"
           (data/record-type-name (->Point 1 2))))
    (is (not= (.getName Point) (data/record-type-name (->Point 1 2)))
        "and it is NOT the class name — that is the mistake the docstring taught")))

(deftest a-derived-name-round-trips
  (testing "the idiom the docstring now recommends: derive, do not write it out"
    (let [reg (-> (boring/tag-registry)
                  (boring/register-record (data/record-type-name (map->Point {}))
                                          map->Point))
          v (round reg (->Point 1 2))]
      (is (instance? Point v))
      (is (= (->Point 1 2) v)))))

(deftest a-class-name-registration-silently-does-nothing
  (testing "the failure this test exists to make visible.

            No exception, no warning — the value simply comes back as something
            else, and an `instance?` check answers false. A caller whose import
            path keys on that check skips the work it meant to do."
    (let [reg (-> (boring/tag-registry)
                  (boring/register-record (.getName Point) map->Point))
          v (round reg (->Point 1 2))]
      (is (not (instance? Point v))
          "registering the CLASS name does not fail — it just never matches")
      (is (= "boring.data.UnknownRecord" (.getName (class v)))
          "it decodes to the unregistered form")
      (testing "and re-encodes to identical bytes, which is why nothing notices"
        (let [o {:registry reg}]
          (is (= (seq (boring/encode (->Point 1 2) o))
                 (seq (boring/encode v o)))))))))
