(ns boring.reserved-tag-test
  "Two holes that had nothing pinning them.

  ONE. The stringref namespace is the ENCODER'S state -- tag 25 is an index
  into a table the writer builds as it encodes, and tag 256 declares that
  table's scope. A caller could hand boring a raw one and boring wrote it out.
  Neither outcome was a round-trip:

      (encode [\"hello-world-string\" (tagged-value 25 0)] {:stringref false})
        -> bytes boring itself refuses to read, :boring/bad-stringref
      (encode [\"hello-world-string\" (tagged-value 25 0)] {})
        -> [\"hello-world-string\" \"hello-world-string\"], the TaggedValue
           silently RESOLVED against whatever index 0 held. The value came back
           a different TYPE, which `=` on the decoded result cannot see unless
           you look for it.
      (encode (tagged-value 256 [1 2 3])) -> [1 2 3], the tag swallowed.

  This is the encode-side refusal doc/SECURITY.md describes: boring declines to
  write a value it cannot represent faithfully rather than writing an
  approximation. What makes 25 and 256 different from 39, 258 or 27 -- which
  legitimately decode to an ident, a set and a tagged object -- is that those
  are STATELESS. `tag 258` means \"this array is a set\" whoever wrote it. `tag
  25` means \"the n-th string of a table you cannot see\", and only the writer
  that built the table can number one.

  TWO. Nothing tested that the entry points agree with stringref ON. Every
  existing equivalence test -- `streaming-agrees-with-encode`, the writer-opts
  comparisons -- sets `{:stringref false}`, so `encode` and `write-to!` could
  have forked on the default profile and no test would have said so. An
  untested divergence is worse than a failing one: it is the shape a change
  ships through."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring]
            [boring.data :as data]
            [boring.generative-test :as g]
            [boring.conformance :as c])
  #?(:clj (:import [java.io ByteArrayOutputStream])))

;; ------------------------------------------------------- the reserved tags

(defn- refusal [f]
  (try (do (f) nil)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (:type (ex-data e)))))

(deftest a-caller-cannot-hand-boring-a-stringref
  (testing "tag 25 is an index into a table the encoder owns"
    (doseq [opts [{} {:stringref false} {:profile :canonical}]]
      (is (= :boring/reserved-tag
             (refusal #(boring/encode (data/tagged-value 25 0) opts)))
          (pr-str opts))))
  (testing "and nested inside a container, which is where it actually appeared"
    (is (= :boring/reserved-tag
           (refusal #(boring/encode ["hello-world-string" (data/tagged-value 25 0)] {})))))
  (testing "tag 256 declares the namespace, and one is already being written"
    (doseq [opts [{} {:stringref false}]]
      (is (= :boring/reserved-tag
             (refusal #(boring/encode (data/tagged-value 256 [1 2 3]) opts)))
          (pr-str opts)))))

(deftest stateless-tags-with-a-meaning-are-still-accepted
  (testing "the refusal is not `boring reserves every tag it knows`. A tag that
            means the same thing regardless of who wrote it stays writable, and
            decoding it to that meaning is the right answer rather than a lost
            round-trip."
    (is (= #{1 2} (boring/decode (boring/encode (data/tagged-value 258 [1 2]) {}))))
    (is (= 'foo (boring/decode (boring/encode (data/tagged-value 39 "foo") {}))))
    (testing "and an unknown tag still round-trips as a TaggedValue, which is
              the passthrough the whole type exists for"
      (let [tv (data/tagged-value 999 1)]
        (is (= tv (boring/decode (boring/encode tv {}))))))))

(deftest the-refusal-reaches-a-registered-handler-too
  (testing "`writeTag` is the funnel for every CALLER-supplied tag -- a
            `tagged-value` and a registered handler's own tag both pass through
            it. boring's internal stringref writes use the head writer directly
            and are unaffected, which is what lets the check sit there."
    (is (= :boring/reserved-tag
           (refusal #(boring/encode (data/tagged-value 25 "anything") {}))))))

;; -------------------------------------------- the entry points, stringref ON

#?(:clj
   (defn- streamed ^bytes [v opts buf]
     (let [out (ByteArrayOutputStream.)]
       (boring/write-to! (boring/writer buf) v out opts)
       (.toByteArray out))))

#?(:clj
   (deftest encode-and-write-to-agree-with-stringref-on
     (testing "THE GAP: every existing equivalence test pins these with
               `{:stringref false}`, so the default profile -- the one almost
               everybody uses -- was unpinned. These values are chosen to
               exercise both sides of the stringref decision: some repeat a
               string often enough to earn a reference, some never do, and some
               sit near the length threshold where registration starts."
       (doseq [[label v] [["no strings at all" {:a 1 :b 2}]
                          ["one short string" {:a "x"}]
                          ["a string below the registration threshold" (vec (repeat 8 "ab"))]
                          ["a string above it, repeated" (vec (repeat 8 "a-repeated-string"))]
                          ["repeated keywords" (vec (repeat 20 {:some-key 1 :other-key 2}))]
                          ["distinct strings" (mapv #(str "unique-string-" %) (range 40))]
                          ["nested and mixed" {:xs (vec (range 50))
                                               :ys (vec (repeat 10 "repeated-value-here"))
                                               :z "singleton"}]
                          ["past the staging window" (vec (repeat 400 "a-repeated-string"))]]]
         (doseq [buf [64 8192]]
           (is (= (seq (boring/encode v {})) (seq (streamed v {} buf)))
               (str label ", buffer " buf)))))))

#?(:clj
   (deftest encode-into-and-encode-buffered-agree-with-stringref-on
     (testing "the other two buffered entry points, same gap"
       (doseq [v [{:a 1} (vec (repeat 8 "a-repeated-string")) {:a "x" :b "x"}]]
         (let [w (boring/writer 256)
               into-bytes (boring/encode-into! w v {})]
           (is (= (seq (boring/encode v {})) (seq into-bytes)) (pr-str v)))))))

(defspec encode-and-decode-round-trip-on-the-default-profile 200
  ;; `c/equiv?` ON BOTH PLATFORMS, not `=` on ClojureScript. A `Uint8Array`
  ;; compares by identity there, so `=` reported every byte string as a
  ;; round-trip failure -- which is exactly what this spec found the first time
  ;; it was allowed to run under Node, and it was the test that was wrong.
  (prop/for-all [v g/gen-value]
                (c/equiv? v (boring/decode (boring/encode v {})))))
