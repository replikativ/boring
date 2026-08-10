(ns boring.degenerate-frame-test
  "What `encode-indexed` does when the frame it sealed describes NOTHING.

  An indexed write seals a frame whenever it opens a stringref namespace, even
  with no container node to describe, because \"namespace with no pointer
  table\" has to keep meaning exactly one thing for `boring.nav`. On a large
  value that frame is noise. On a small one it is most of the file: the writer
  puts out 50 bytes for `{:a 1}` where 7 say the same thing.

  `encode-indexed` buffers, so it can drop it. It USED TO do that by encoding
  the whole value a second time with `:stringref false` and keeping whichever
  came out shorter -- correct, and a full second pass to discover bytes the
  first pass had already written. When no container was kept and no string was
  referenced, the body is byte-identical either way, because with nothing
  repeating the writer emits literals whether the namespace is open or not. So
  the plain encoding is a SLICE of the buffer, between the envelope and the
  frame.

  These tests pin the three things that made the swap safe: the slice equals a
  plain encode, it equals what the comparison used to return, and it does not
  fire where the frame is doing work."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring]
            [boring.nav :as nav])
  (:import [java.io ByteArrayOutputStream]))

(defn- streamed
  "The same value through the streaming writer, which cannot revisit what it
  has flushed and so always carries the frame."
  ^bytes [v opts]
  (let [out (ByteArrayOutputStream.)]
    (boring/write-indexed! (boring/writer 65536) v out opts)
    (.toByteArray out)))

;; ------------------------------------------------- the frame that describes nothing

(deftest a-frame-describing-nothing-is-not-emitted
  (testing "no container cleared the placement rule and no string was
            referenced, so the whole apparatus is dropped and the result is the
            plain encoding -- byte for byte, not merely the same length"
    (doseq [[label v] [["a one-key map" {:a 1}]
                       ["a short vector" [1 2 3]]
                       ["a scalar" 42]
                       ["a string" "hello"]
                       ["nested but small" {:a {:b [1 2]} :c "x"}]
                       ["40 integers" (vec (range 40))]]]
      (is (= (seq (boring/encode v {:stringref false}))
             (seq (boring/encode-indexed v {})))
          label))))

(deftest the-dropped-frame-is-what-the-streaming-writer-still-carries
  (testing "the streaming writer takes the conservative side, and the gap is
            the frame. This is the measurement the change exists for."
    (let [buffered (boring/encode-indexed {:a 1} {})
          flushed (streamed {:a 1} {})]
      (is (= 7 (alength ^bytes buffered)))
      (is (= 50 (alength ^bytes flushed)))
      (testing "and they mean the same thing"
        (is (= {:a 1} (boring/decode buffered) (boring/decode flushed)))))))

(deftest a-frame-doing-work-is-kept
  (testing "either half is reason enough to seal one: a kept container needs
            its anchors, and an emitted reference needs the table that resolves
            it. The slice must not fire on either."
    (testing "references but no container node -- the case that made the writer
              seal a frame for a small value in the first place. The strings
              have to be long enough that stringref actually WINS: with
              `[{:city \"amsterdam\"} {:city \"amsterdam\"}]` both encodings come
              out at 39 bytes and the comparison takes the plain one, so it
              proves nothing about the frame."
      (let [v (vec (repeat 5 {:some-longer-keyword "a-fairly-long-repeated-string"}))
            bs (boring/encode-indexed v {})]
        (is (< (alength ^bytes bs)
               (alength ^bytes (boring/encode v {:stringref false})))
            "stringref wins here, so the frame is kept -- 139 bytes against 276")
        (is (= 0xd9 (bit-and (aget ^bytes bs 0) 0xff))
            "and the envelope was not sliced off")
        (is (some? (nav/source bs nil))
            "which is what keeps it navigable -- a reference with no table is
             a document nothing can read")
        (is (= v (boring/decode bs)))))
    (testing "container nodes, which are the ordinary reason"
      (let [v (mapv (fn [i] {:e i :a :user/name :v (str "p" i)}) (range 200))
            bs (boring/encode-indexed v {})]
        (is (= v (nav/value (nav/root bs))))
        (is (= "p137" (nav/value (nav/walk (nav/root bs) [137 :v])))
            "and the index is usable, so the frame earned its bytes")))))

(deftest above-the-bound-the-two-writers-still-agree-byte-for-byte
  (testing "the slice is gated on the same bound the old comparison was, and
            that gate is load-bearing rather than leftover. A 400-entry map of
            one-character keywords references nothing -- a reference costs what
            the literal costs, so short strings are never registered -- so it
            is DEGENERATE at 5 KB. Slicing there would be smaller and would
            quietly break the byte-identity `write-indexed-agrees-with-encode-
            indexed` pins. #30, #34 and #43 were all found by that invariant."
    (doseq [[label v] [["wide map" (into {} (for [i (range 400)] [(str "k" i) {:v i}]))]
                       ["wide vec" (mapv (fn [i] {:id i :name (str "n" i)}) (range 400))]]]
      (is (= (seq (boring/encode-indexed v {:index 16}))
             (seq (streamed v {:index 16})))
          label))))

;; ------------------------------------------------------------------ properties

(def ^:private gen-small
  (gen/one-of
   [(gen/map gen/keyword gen/small-integer {:max-elements 4})
    (gen/vector gen/small-integer 0 6)
    gen/small-integer
    gen/string-ascii
    (gen/return nil)]))

(defspec whatever-comes-back-decodes-to-the-value 300
  (prop/for-all [v gen-small]
                (= v (boring/decode (boring/encode-indexed v {})))))

(defspec the-result-is-never-larger-than-the-streaming-writers 300
  (prop/for-all [v gen-small]
                (<= (alength ^bytes (boring/encode-indexed v {}))
                    (alength ^bytes (streamed v {})))))

(defspec every-result-is-still-navigable 300
  ;; The slice removes the envelope along with the frame, so it must not leave
  ;; a document that opens a namespace it cannot resolve -- which `nav/source`
  ;; refuses outright. A value that came back sliced has no references in it by
  ;; construction, and this is the assertion that the construction holds.
  (prop/for-all [v gen-small]
                (some? (nav/source (boring/encode-indexed v {}) nil))))
