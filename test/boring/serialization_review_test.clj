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

;; ------------------------------------------------------------------- S10

(deftest tag-numbers-are-validated-at-registration
  (testing "the registry accepted NEGATIVE tags and the writer's registered
            branch emitted them through the unchecked head path -- so a handler
            registered as tag -1 wrote `ff`, the CBOR break byte, followed by
            its content. No exception, just output no reader can parse.

            Validated at construction, which is where a caller can still do
            something about it, rather than at emission where the value is
            already half written."
    (doseq [bad [-1 -40 Long/MIN_VALUE]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsigned"
                            (boring/register-tag (boring/tag-registry) bad
                                                 java.io.File (fn [f] (str f)) nil))
          (str "write registration of tag " bad))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsigned"
                            (boring/register-tag (boring/tag-registry) bad
                                                 nil nil (fn [v] v)))
          (str "read registration of tag " bad)))
    (testing "and ordinary tag numbers still register"
      (is (some? (boring/register-tag (boring/tag-registry) 40000 java.io.File
                                      (fn [f] (str f)) (fn [v] (java.io.File. (str v)))))))))

;; ------------------------------------------------------------------- S11

(deftest mmap-closes-its-arena-when-construction-fails
  (testing "the arena owns the mapping and the caller only learns about it
            through the return value -- so anything that throws after it is
            created leaks the mapping with no handle left to close it. Both
            failure modes are reachable: a missing file, and a stringref
            document, which `boring.nav` refuses by design."
    (if-not (try (Class/forName "org.replikativ.boring.ffm.SegmentSource") true
                 (catch Throwable _ false))
      (is true "skipped: this JVM has no java.lang.foreign (JDK < 22)")
      (let [mmap-source (requiring-resolve 'boring.mmap/mmap-source)
            mmap-items (requiring-resolve 'boring.mmap/mmap-items)
            stringref (doto (java.io.File/createTempFile "boring-arena" ".cbor")
                        .deleteOnExit)]
        (with-open [out (java.io.FileOutputStream. stringref)]
          ;; written WITH stringref, which navigation refuses
          (.write out ^bytes (boring/encode {"a" "aaaa" "b" "aaaa"})))
        (doseq [f [mmap-source mmap-items]]
          (is (thrown? clojure.lang.ExceptionInfo (f stringref))
              "a stringref document must be refused")
          (is (thrown? Exception (f (java.io.File. "/nonexistent/nope.cbor")))
              "a missing file must throw"))
        (testing "and a valid file still maps and reads"
          (let [ok (doto (java.io.File/createTempFile "boring-arena-ok" ".cbor")
                     .deleteOnExit)]
            (with-open [out (java.io.FileOutputStream. ok)]
              (.write out ^bytes (boring/encode {"a" 1} o)))
            (let [[c arena] (mmap-source ok o)]
              (try (is (= {"a" 1} ((requiring-resolve 'boring.nav/value) c)))
                   (finally (.close ^java.lang.AutoCloseable arena))))))))))

;; ------------------------------------------------------------------- S7

(deftest duration-decoding-never-truncates-silently
  (testing "both numbers went through longValue(), which TRUNCATED: `{1 1.5}` is
            a valid one-and-a-half second duration and decoded as one second.
            A wrong value is the worst outcome available, and the writer's own
            `{1 seconds, -9 nanos}` subset hid it because round trips never
            produce the other forms.

            Everything boring cannot carry losslessly is refused by RFC 9581's
            own rules, with a typed error naming the key -- refusing a
            conforming form is honest, truncating it is not."
    (letfn [(dur [m] (boring/decode (boring/encode (data/tagged-value 1002 m) o) o))]
      (testing "forms boring represents"
        (is (= (java.time.Duration/ofSeconds 2) (dur {1 2})))
        (is (= (java.time.Duration/ofNanos 1500000000) (dur {1 1.5}))
            "a fractional base must keep its fraction")
        (is (= (java.time.Duration/ofNanos 2500000000) (dur {1 2 -9 500000000}))))
      (testing "forms it does not, each a typed error rather than a wrong value"
        (doseq [[label m] [["negative fraction"        {1 2 -9 -5}]
                           ["fraction out of range"    {1 2 -9 1000000000}]
                           ["fractional base with -9"  {1 1.5 -9 5}]
                           ["decimal-fraction base"    {4 [-1 15]}]
                           ["bigfloat base"            {5 [-1 15]}]
                           ["unknown critical key"     {1 2 99 "x"}]
                           ["other scaled fraction"    {1 2 -3 5}]
                           ["no base"                  {-9 5}]]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tag 1002"
                                (dur m))
              label)))
      (testing "and an ordinary Duration still round-trips"
        (is (= (java.time.Duration/ofNanos 2500000000)
               (boring/decode (boring/encode (java.time.Duration/ofNanos 2500000000) o) o)))))))

;; ------------------------------------------------------------------- S9

(deftest registrations-that-could-never-run-are-refused
  (testing "the hottest scalars are dispatched before the registry is consulted,
            so a handler registered for `String` or `Long` silently did nothing
            -- while the same call for `UUID` or `URI` worked. The same API
            working or not based only on the class, with nothing saying which,
            is worse than not supporting it.

            The lookup cannot move above these without putting a map probe in
            front of every string and every long, so the registration is
            refused instead -- at registration, where a caller can still act."
    (doseq [c [String Long Integer Double Float Boolean
               clojure.lang.Keyword clojure.lang.Symbol (Class/forName "[B")]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could never run"
                            (boring/register-tag (boring/tag-registry) 55555 c
                                                 (fn [_] "x") nil))
          (str (.getName c) " must be refused")))
    (testing "and the classes that DO override still register and take effect"
      (doseq [[c v] [[java.util.UUID (java.util.UUID/randomUUID)]
                     [java.net.URI (java.net.URI. "http://x")]
                     [java.io.File (java.io.File. "/tmp/x")]]]
        (let [reg (boring/register-tag (boring/tag-registry) 55555 c
                                       (fn [_] "OVERRIDDEN") (fn [v] v))
              o' (assoc o :registry reg)]
          (is (= "OVERRIDDEN" (boring/decode (boring/encode v o') o'))
              (str (.getName c) " must be overridden")))))
    (testing "a read-only registration needs no class and stays allowed"
      (is (some? (boring/register-tag (boring/tag-registry) 55555 nil nil (fn [v] v)))))))
