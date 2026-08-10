(ns boring.api-surface-test
  "The public surface answering the same question the same way, and failing in
  its own vocabulary rather than the JVM's.

  Every case here was found by a review that asked one question of the whole
  API at once -- \"what is public that should not be, and what is public but
  undiscoverable\" -- rather than by exercising a feature. They are the kind of
  defect that no feature test reaches, because each one is on the path a
  DIFFERENT feature's user takes by mistake."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.nav :as nav]))

(def ^:private ints (boring/encode-indexed (vec (range 100)) {:stringref false}))
(def ^:private shaped
  (boring/encode (mapv (fn [i] {:a i :b i}) (range 5))
                 {:shapes true :stringref false}))

(deftest the-offset-layer-takes-raw-bytes-because-root-and-cursor-do
  (testing "`root` and `cursor` accept bytes -- `(root bs)` is what a caller
            writes first -- and the offset layer is meant to be the same API
            without the allocation. It routed through `source-of`, whose else
            branch was an unchecked cast, so the documented way IN died with
            `ClassCastException: [B cannot be cast to boring.nav.Items`, naming
            a type the caller has never heard of."
    (is (= 0 (nav/root-offset ints)))
    (is (some? (nav/probe ints :a)))
    (let [at3 (nav/nth-offset ints (nav/root-offset ints) 3)]
      (is (pos? at3))
      (is (= 3 (nav/value-at ints at3)))
      (is (= 3 (nav/long-at ints at3))))
    (is (= 100 (nav/container-count ints (nav/root-offset ints))))
    (is (= (range 100)
           (nav/reduce-at ints (nav/root-offset ints)
                          (fn [acc o] (conj acc (nav/long-at ints o))) [])))
    (testing "and a source, a cursor and an items still work -- this widened,
              it did not move"
      (let [s (nav/source ints nil)]
        (is (= 0 (nav/root-offset s)))
        (is (= 0 (nav/root-offset (nav/root ints))))
        (is (= 0 (nav/root-offset (nav/source-of s))))))))

(deftest an-unsupported-source-is-typed-not-a-class-cast
  (testing "the else branch used to be a cast. Anything that is not a source,
            cursor, items, byte[] or ByteSource now says so in this
            namespace's vocabulary."
    (doseq [bad [42 "a string" :kw {:a 1} nil]]
      (is (= :boring/unsupported-source
             (try (do (nav/root-offset bad) nil)
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          (pr-str bad)))))

(deftest items-accepts-a-source-like-root-and-cursor-do
  (testing "`source` was the universal handle for one half of the namespace and
            rejected by the other: `items` called `nav-of` directly, which
            knows only bytes and a ByteSource."
    (let [out (java.io.ByteArrayOutputStream.)
          _ (boring/write-seq! (boring/writer 64) [1 2 3] out {:stringref false})
          seq-bytes (.toByteArray out)]
      (is (= 3 (count (nav/items seq-bytes))))
      (is (= 3 (count (nav/items (nav/source seq-bytes nil)))))
      (is (= [1 2 3] (mapv nav/value (nav/items (nav/source seq-bytes nil))))))))

(deftest the-shape-accessors-refuse-the-nil-that-shape-documents
  (testing "`shape` returns nil for anything that is not a shaped array and
            says so -- \"asking a question, not asserting an answer\". The four
            accessors are `^Shape` field reads, so that documented nil arrived
            as a NullPointerException naming a private deftype field."
    (let [none (nav/shape (nav/source ints nil) 0)]
      (is (nil? none) "the premise: a plain array has no shape")
      (doseq [[label f] [["shape-rows" #(nav/shape-rows none)]
                         ["shape-count" #(nav/shape-count none)]
                         ["shape-column" #(nav/shape-column none :a)]
                         ["shape-keys" #(nav/shape-keys none)]]]
        (is (= :boring/not-a-shape
               (try (do (f) nil)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
            label))))
  (testing "and a real shape still answers"
    (let [sh (nav/shape (nav/source shaped nil) 0)]
      (is (= [:a :b] (nav/shape-keys sh)))
      (is (= 5 (nav/shape-count sh)))
      (is (= 0 (nav/shape-column sh :a)))
      (is (= -1 (nav/shape-column sh :nope)))
      (is (pos? (nav/shape-rows sh))))))
