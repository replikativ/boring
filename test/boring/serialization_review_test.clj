(ns boring.serialization-review-test
  "Findings from doc/SERIALIZATION-CORRECTNESS-REVIEW.md that are JVM-specific.
  The portable ones live in `boring.canonical-parity-test`, because the whole
  lesson of that review was that a `.clj` test beside the JVM implementation
  does not cover a guarantee the library makes on two runtimes."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.data :as data]))

(def o {:stringref false})
(def canonical {:profile :canonical})

;; ------------------------------------------------------------------- S4

(deftest canonical-nan-has-exactly-one-encoding
  (testing "`toHalf` carried a NaN's payload bits and sign through, so one value
            had three canonical encodings -- f97e00, f97eaa and f9fe00 -- under
            the profile whose entire purpose is that the same value gives the
            same bytes on every platform. ClojureScript already normalised to
            f97e00, so this was also a cross-platform differential.

            Nothing is lost: boring exposes no NaN-payload or signalling-NaN
            type and decoding collapses every half NaN to Float.NaN, so those
            bits were never a value distinction. RFC 8949 says a deterministic
            protocol without intentional NaN-payload support picks one form."
    (let [payloads [0x7ff8000000000000 0x7ff8000000000001 0x7ffaaaa000000000
                    0xfff8000000000000 0x7ff0000000000001]
          encs (for [b payloads]
                 (seq (boring/encode (Double/longBitsToDouble (unchecked-long b)) canonical)))]
      (is (= 1 (count (set encs)))
          (str "every NaN must encode identically, got " (pr-str (set encs))))
      (is (= [(unchecked-byte 0xf9) (unchecked-byte 0x7e) (unchecked-byte 0x00)]
             (first encs))
          "and the form must be f97e00, which is RFC 8949's own example")
      (is (every? #(Double/isNaN (double (boring/decode (byte-array %) canonical))) encs)))
    (testing ":preserve-width still keeps the exact bits -- narrowing is what it
              exists NOT to do"
      (let [d (Double/longBitsToDouble (unchecked-long 0x7ffaaaa000000000))]
        (is (= 9 (count (boring/encode d o))) "an f64 head plus eight bytes")))))

;; ------------------------------------------------------------------- S5

(deftest tag-40-rejects-wrong-shaped-content-with-a-typed-error
  (testing "a WELL-FORMED tag 40 whose content has the wrong shape escaped as a
            raw ClassCastException or IllegalArgumentException, contradicting
            doc/SECURITY.md's typed-failure guarantee. The byte fuzzer rarely
            builds a valid tag around invalid content -- the limitation that
            document already names."
    (doseq [[label content]
            [["non-numeric dimension" [["bad" 1] (byte-array 4)]]
             ["float dimension"       [[1.5 2] (byte-array 4)]]
             ["negative dimension"    [[-1 2] (byte-array 4)]]
             ["payload not an array"  [[2 2] "not-an-array"]]
             ["payload a vector"      [[2 2] [1 2 3 4]]]
             ["payload nil"           [[0 0] nil]]
             ["dimensions overflow"   [[100000000 100000000] (byte-array 4)]]
             ["three dimensions"      [[1 2 3] (byte-array 6)]]]]
      (let [bs (boring/encode (data/tagged-value 40 content) o)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tag 40|dimension|2-dimensional"
                              (boring/decode bs o))
            (str label " must be a typed :boring/bad-tag-content")))))
  (testing "and a valid matrix still decodes"
    (let [m (into-array (Class/forName "[D")
                        [(double-array [1.0 2.0]) (double-array [3.0 4.0])])]
      (is (= "[[D" (.getName (class (boring/decode (boring/encode m o) o))))))))

;; ------------------------------------------------------------------- S6

(deftest matrix-writing-handles-empty-and-null-rows
  (testing "three cases contradicted the method's own comments: a zero-row
            matrix took the non-rectangular fallback and came back a vector,
            losing the source type on a value with no content to disagree
            about; and `rowLen(rows[0])` ran BEFORE the loop's null check, so a
            null FIRST row threw a raw NullPointerException and the documented
            null-row fallback was unreachable for the row most likely to be
            null."
    (testing "zero rows keep their type -- 0x0 is rectangular"
      (is (= "[[D" (.getName (class (boring/decode
                                     (boring/encode (make-array Double/TYPE 0 0) o) o)))))
      (is (= "[[J" (.getName (class (boring/decode
                                     (boring/encode (make-array Long/TYPE 0 0) o) o))))))
    (testing "a null row falls back instead of throwing, in either position"
      (doseq [[label rows] [["first" [nil (double-array [1.0])]]
                            ["later" [(double-array [1.0]) nil]]]]
        (let [m (into-array (Class/forName "[D") rows)]
          (is (some? (boring/decode (boring/encode m o) o))
              (str "null " label " row must not throw")))))
    (testing "ragged rows still widen to a vector of rows -- documented, and the
              values survive even though the 2-D type does not"
      (let [m (into-array (Class/forName "[D")
                          [(double-array [1.0 2.0]) (double-array [3.0])])
            r (boring/decode (boring/encode m o) o)]
        (is (vector? r))
        (is (= [[1.0 2.0] [3.0]] (mapv vec r)))))))
