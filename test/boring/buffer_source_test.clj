(ns boring.buffer-source-test
  "`BufferSource`: reading CBOR out of a `ByteBuffer` without copying it.

  WHY IT MATTERS THAT THIS IS NOT A byte[]. A log engine's whole value
  proposition is that a payload is a read-only slice into an mmap that was
  never copied -- k7's `msg->payload` is exactly this, and so is any NIO
  channel, Netty buffer or `MappedByteBuffer`. Reading one meant
  `payload -> byte[] -> Reader`, which allocates and copies precisely the bytes
  the caller went to trouble not to copy. `SegmentSource` already solves this
  for FFM, but needs JDK 22; this runs on 9 alongside the rest of `src/java`.

  THREE WAYS TO GET IT SILENTLY WRONG, which is what most of this file is:

    the position is not zero -- a slice sitting inside a larger buffer has a
    base, and an accessor that ignores it reads the wrong bytes with no error;

    the byte order is a MUTABLE PROPERTY of the buffer -- a caller who set
    LITTLE_ENDIAN for their own framing would have every multi-byte head
    byte-swapped, so lengths and floats come back wrong and nothing throws;

    `heapArray()` is the Reader's fast path and it assumes index 0 of the array
    is offset 0 of the source, so handing it a view onto part of an array
    aliases the wrong bytes."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring]
            [boring.nav :as nav])
  (:import (java.nio ByteBuffer ByteOrder)
           (org.replikativ.boring BufferSource)))

(def ^:private doc
  {:name "Alice" :n 12345678901 :d 3.14159 :ok true
   :rows (mapv (fn [i] {:e i :a :user/name :v (str "p" i)}) (range 20))})

(defn- blob ^bytes [] (boring/encode-indexed doc {:shapes true :stringref true}))

(defn- embed
  "`bs` placed at `base` inside a larger buffer, handed back as a read-only
  slice -- the shape a log engine produces, where the payload is a window onto
  a segment file that also holds framing, other messages, and free space."
  ^ByteBuffer [^bytes bs ^long base direct?]
  (let [cap (+ base (alength bs) 64)
        b (if direct? (ByteBuffer/allocateDirect cap) (ByteBuffer/allocate cap))]
    (.position b (int base))
    (.put b bs)
    (.position b (int base))
    (.limit b (int (+ base (alength bs))))
    (.asReadOnlyBuffer (.slice b))))

(deftest a-buffer-decodes-to-what-the-bytes-decode-to
  (let [bs (blob)]
    (doseq [[label b] [["heap, whole" (ByteBuffer/wrap bs)]
                       ["heap, offset slice" (embed bs 37 false)]
                       ["direct, offset slice" (embed bs 37 true)]
                       ["direct, base 0" (embed bs 0 true)]
                       ["heap, read-only" (.asReadOnlyBuffer (ByteBuffer/wrap bs))]]]
      (let [src (BufferSource/of b)]
        (is (= (alength bs) (.size src)) label)
        (is (= doc (boring/decode src)) label)))))

(deftest the-callers-buffer-is-not-disturbed
  (testing "a source shares a buffer with the code that produced it. Every read
            here is ABSOLUTE and goes through a duplicate, so a decode cannot
            move a cursor the caller is still using -- and two sources can back
            the same buffer at once."
    (let [bs (blob)
          b (ByteBuffer/wrap bs)
          _ (.position b 0)
          src1 (BufferSource/of b)
          src2 (BufferSource/of b)]
      (is (= doc (boring/decode src1)))
      (is (zero? (.position b)) "the decode moved the caller's position")
      (is (= doc (boring/decode src2)) "the second source saw a moved buffer")
      (is (= doc (boring/decode src1)) "and the first no longer works"))))

(deftest little-endian-is-not-inherited
  (testing "the silent-corruption case. Byte order is a mutable property of a
            ByteBuffer and CBOR is big-endian throughout, so a caller who set
            LITTLE_ENDIAN for their own framing would get every length and
            every float byte-swapped, with no error anywhere."
    (let [bs (blob)
          b (doto (ByteBuffer/wrap bs) (.order ByteOrder/LITTLE_ENDIAN))]
      (is (= doc (boring/decode (BufferSource/of b))))
      (is (= ByteOrder/LITTLE_ENDIAN (.order b))
          "and the caller's own order was changed under them"))))

(deftest the-heap-fast-path-is-taken-only-when-it-is-sound
  (testing "`heapArray` lets the Reader skip this interface entirely, which is
            worth 14-50% -- but it assumes index 0 of the array is offset 0 of
            the source, so anything less than the whole array must decline"
    (let [bs (blob)]
      (is (some? (.heapArray (BufferSource/of (ByteBuffer/wrap bs))))
          "a whole heap buffer at position 0 IS its array")
      (is (nil? (.heapArray (BufferSource/of (embed bs 37 false))))
          "a slice at a non-zero base is not")
      (is (nil? (.heapArray (BufferSource/of (embed bs 0 true))))
          "a direct buffer has no array at all")
      (testing "and a read-only buffer declines by contract -- `hasArray` is
                false for one, which is right twice over: the fast path would
                hand out a MUTABLE reference to memory the caller marked
                read-only"
        (is (nil? (.heapArray (BufferSource/of (.asReadOnlyBuffer
                                               (ByteBuffer/wrap bs))))))))))

(deftest navigation-works-through-a-buffer-including-shapes
  (testing "the operation the integration exists for: one field of one row of a
            payload that was never copied out of the mmap"
    (let [bs (blob)
          src (BufferSource/of (embed bs 37 true))
          s (nav/source src nil)
          rows-tag (nav/field-offset s (nav/root-offset s) :rows)
          sh (nav/shape s rows-tag)]
      (is (= [:e :a :v] (nav/shape-keys sh)))
      (is (= 20 (nav/shape-count sh)))
      (is (= "p7" (nav/value-at
                   s (nav/nth-offset
                      s (nav/nth-offset s (nav/shape-rows sh) 7)
                      (nav/shape-column sh :v)))))
      (testing "and a whole column, which is the scan"
        (is (= (range 20)
               (nav/reduce-at s (nav/shape-rows sh)
                              (fn [acc ro]
                                (conj acc (nav/long-at
                                           s (nav/nth-offset
                                              s ro (nav/shape-column sh :e)))))
                              [])))))))

(defspec any-value-survives-any-base-offset 200
  (prop/for-all [v (gen/recursive-gen
                    (fn [inner]
                      (gen/one-of [(gen/vector inner 0 4)
                                   (gen/map gen/keyword inner {:max-elements 4})]))
                    (gen/one-of [gen/small-integer gen/string-alphanumeric
                                 gen/boolean gen/keyword]))
                 base (gen/choose 0 64)
                 direct? gen/boolean]
    (let [bs (boring/encode v {:stringref false})]
      (= v (boring/decode (BufferSource/of (embed bs base direct?)))))))
