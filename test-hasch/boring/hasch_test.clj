(ns boring.hasch-test
  "The content-address collision, and its fix.

  Without `boring.hasch`, hasch walks an `UnknownRecord` as a bare map --
  because it implements IPersistentMap -- and drops the type name, so three
  distinct values share one address. Worse, a peer WITH the record class
  computes a different address from one without it, so the same logical value
  lands at two addresses depending on the classpath."
  (:require [clojure.test :refer [deftest testing is]]
            [boring.core :as boring]
            [boring.data :as data]
            [boring.hasch]
            [boring.core]
            [hasch.core :as h]))

(defrecord HPoint [x y])

(deftest integration-activates-automatically
  (testing "boring.core requires boring.hasch if hasch is on the classpath, so
            a consumer who already depends on hasch gets correct content
            addresses without knowing this namespace exists. Forgetting it is
            not a loud failure -- hashes just come out wrong -- which is why it
            is not left to the consumer."
    (is (true? boring.core/hasch-integration?)))

  (testing "and boring loads with hasch ABSENT, which the main CI suite proves
            by running the whole conformance suite without it on the classpath"
    (is (some? (boring/encode {:a 1})))))

(deftest every-representation-of-a-value-shares-one-address
  (let [real    (->HPoint 3 4)
        wire    (boring/encode real)
        unknown (boring/decode wire)                 ; no registration
        inc-tl  (incognito.base/map->IncognitoTaggedLiteral
                 {:tag (symbol "boring.hasch_test.HPoint") :value {:x 3 :y 4}})]
    (is (data/unknown-record? unknown) "precondition: the fallback was taken")

    (testing "the record and its unregistered fallback hash identically -- a
              peer without the class must content-address the same as one with"
      (is (= (h/uuid real) (h/uuid unknown))))

    (testing "and both agree with incognito's tagged literal, which hasch
              already knew about"
      (is (= (h/uuid real) (h/uuid inc-tl))))))

(deftest the-type-name-is-part-of-the-address
  (testing "this is what was broken: UnknownRecord hashed as a bare map, so the
            name was dropped and distinct types collided"
    (let [a (data/unknown-record "some.A" {:x 3 :y 4})
          b (data/unknown-record "some.B" {:x 3 :y 4})]
      (is (not= (h/uuid a) (h/uuid b)) "different types, different addresses")
      (is (not= (h/uuid a) (h/uuid {:x 3 :y 4}))
          "and neither collides with the bare map"))))

(deftest positional-frames-hash-by-tag-and-form
  (testing "the split fallback means TaggedLiteral needs the same treatment --
            it is not a record or an IncognitoTaggedLiteral either"
    (let [a (tagged-literal (symbol "some.Pos") [1 2 3])
          b (tagged-literal (symbol "other.Pos") [1 2 3])]
      (is (= (h/uuid a) (h/uuid (tagged-literal (symbol "some.Pos") [1 2 3]))))
      (is (not= (h/uuid a) (h/uuid b)))
      (is (not= (h/uuid a) (h/uuid [1 2 3]))))))
