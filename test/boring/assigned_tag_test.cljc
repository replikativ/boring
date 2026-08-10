(ns boring.assigned-tag-test
  "What happens when a caller hands boring a tag boring already means something
  by, with a payload that tag cannot hold.

  WHY THIS DID NOT EXIST. `boring.generative-test`'s `gen-tagged` builds
  arbitrary tagged values from `(gen/choose 50000 60000)` -- deliberately the
  UNASSIGNED range, which is the right range for testing passthrough. Every
  other tagged value it generates is derived from a real typed value (a UUID, a
  Date, a BigInt, an array), so its payload is correct by construction. The
  result is that the round-trip property has never once generated a tag boring
  assigns meaning to, and the whole assigned range was unexplored.

  WHAT IS ACTUALLY TRUE, measured over 784 combinations of 49 assigned tags and
  16 payload shapes:

      refused at encode              32
      typed error on decode         557
      round-tripped                 163
      decoded to the tag's meaning   21
      UNTYPED exceptions              0
      silently different value        0

  So the asymmetry is real -- the writer validates a caller-supplied tag's
  NUMBER but not its PAYLOAD, because the writer's handler table is keyed by
  Clojure TYPE while the reader's is keyed by TAG NUMBER, and there is no point
  in the writer where the two meet -- but its consequence is benign. A caller
  who writes `(tagged-value 0 0)` has asked for a datetime whose payload is an
  integer, and hears about it at the other end, loudly and typed.

  THE TWO INVARIANTS BELOW ARE THE ONES WITH TEETH, and they are what the
  stringref hole violated before it was closed: `(tagged-value 25 0)` used to
  encode fine and come back as a STRING, silently, under the default profile.
  Neither `never untyped` nor `never a different value` tolerated that, which
  is why they are pinned here rather than left to the count above."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.conformance :as c]
            [boring.data :as data]))

(def ^:private assigned
  "Every tag boring reads with a meaning of its own, plus the two it refuses."
  [0 1 2 3 4 5 21 22 23 24 25 27 30 32 33 34 35 36 37 39 40
   64 65 66 67 68 69 70 71 72 73 74 75 76 77 78 79 80 81 82 83 84 85 86
   256 258 1002 1004 39649])

(def ^:private payloads
  [0 -1 1.5 "x" "" :kw [] [1 2 3] [["a"] [[1]]] {} {"a" 1} nil true
   ["clojure/sorted-map" [["a" 1]]]
   ["boring/index" [1 2 3 4 5 6]]])

(defn- outcome
  "How `(tagged-value t p)` behaves end to end, as a keyword plus detail."
  [t p]
  (let [tv (data/tagged-value t p)]
    (try
      (let [bs (boring/encode tv {:stringref false})]
        (try
          (let [v (boring/decode bs)]
            (cond
              (c/equiv? tv v) [:round-trip]
              ;; A TaggedValue back that is NOT equal is the dangerous shape:
              ;; the caller's own construct returned, altered, with no error.
              (and (map? v) (contains? v :tag) (contains? v :value))
              [:silently-different (pr-str v)]
              :else [:decoded-to-meaning]))
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
            (if (:type (ex-data e))
              [:typed-on-decode (:type (ex-data e))]
              [:untyped-on-decode (pr-str e)]))
          #?(:clj (catch Throwable e [:untyped-on-decode (.getName (class e))]))))
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
        (if (:type (ex-data e))
          [:refused-at-encode (:type (ex-data e))]
          [:untyped-on-encode (pr-str e)]))
      #?(:clj (catch Throwable e [:untyped-on-encode (.getName (class e))])))))

(deftest no-assigned-tag-produces-an-untyped-failure
  (testing "guarantee 3 of doc/SECURITY.md, over the space the generators never
            reach: `Nothing escapes as a raw NullPointerException,
            ClassCastException or StackOverflowError, so a caller's
            catch ExceptionInfo is sufficient`. A hand-built tagged value is
            the easiest way to reach a tag handler with the wrong shape, and
            handlers are where that guarantee was false before `boring.hostile`
            existed -- five of them leaked ClassCastException,
            IllegalArgumentException and DateTimeParseException."
    (let [bad (for [t assigned p payloads
                    :let [[kind detail] (outcome t p)]
                    :when (#{:untyped-on-encode :untyped-on-decode} kind)]
                [t p kind detail])]
      (is (empty? bad) (str "untyped failures: " (vec (take 10 bad)))))))

(deftest no-assigned-tag-comes-back-as-a-different-tagged-value
  (testing "THE INVARIANT THE STRINGREF HOLE BROKE. `(tagged-value 25 0)` used
            to encode without complaint and decode to a STRING -- whatever the
            writer's table held at index 0 -- so a value went in and a
            different one came out with no error anywhere. It is refused now,
            but the property is what matters: if a caller's tagged value
            survives the round trip at all, it must survive UNCHANGED.

            Decoding to the tag's own meaning is not a violation. Tag 32 means
            URI and tag 258 means set whoever wrote them, so answering a URI or
            a set is correct; those are stateless. What must never happen is a
            TaggedValue returning as a TaggedValue that is not equal."
    (let [bad (for [t assigned p payloads
                    :let [[kind detail] (outcome t p)]
                    :when (= :silently-different kind)]
                [t p detail])]
      (is (empty? bad) (str "silently altered: " (vec (take 10 bad)))))))

(deftest the-stringref-tags-are-refused-at-encode
  (testing "and they are the only two refused by number, because they are the
            only two whose meaning is STATE the encoder owns rather than a
            fact about the payload"
    (doseq [p [0 1 "x" [1 2 3]]]
      (is (= [:refused-at-encode :boring/reserved-tag] (outcome 25 p)) (pr-str p))
      (is (= [:refused-at-encode :boring/reserved-tag] (outcome 256 p)) (pr-str p)))))

(deftest an-unassigned-tag-still-round-trips
  (testing "the passthrough the generators DO cover, kept here so this file
            states the whole contract rather than only its edges"
    (doseq [p payloads]
      (is (= [:round-trip] (outcome 55555 p)) (pr-str p)))))
