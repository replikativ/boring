(ns boring.conformance-test
  "The conformance spine. Runs identically on JVM and CLJS.

  Written against the *intended* API, so it defines the target rather than
  documenting the current state. Failures here are the completeness backlog."
  (:require [clojure.test :refer [deftest testing is]]
            [boring.core :as boring]
            [boring.data :as data]
            [boring.vectors :as v]
            [boring.fixtures :as f]
            [boring.conformance :as c]
            [boring.wg-bad :as wg]
            [boring.hostile :as hostile]
            [boring.appendix-f :as appf]
            [boring.records :as records]))

;; ---------------------------------------------------------------- helpers

(defn try-decode [hex opts]
  (try {:ok (boring/decode (c/hex->bytes hex) opts)}
       (catch #?(:clj Throwable :cljs :default) e
         {:err (or #?(:clj (.getMessage ^Throwable e) :cljs (.-message e)) (str e))})))

(defn try-encode [value opts]
  (try {:ok (c/bytes->hex (boring/encode value opts))}
       (catch #?(:clj Throwable :cljs :default) e
         {:err (or #?(:clj (.getMessage ^Throwable e) :cljs (.-message e)) (str e))})))

;; Appendix A vectors are plain CBOR: no stringref namespace wrapper, and
;; shortest-form floats (RFC preferred serialisation).
(def interop-opts {:profile :interop :float-policy :shortest :stringref false})

;; ---------------------------------------------------------------- decode

(deftest appendix-a-decode
  (testing "every RFC 8949 Appendix A vector decodes to the expected value"
    (doseq [{:keys [hex value diag]} v/appendix-a]
      (let [expected (c/->expected value)
            r (try-decode hex interop-opts)]
        (is (and (contains? r :ok) (c/equiv? expected (:ok r)))
            (str hex (when diag (str "  " diag))
                 " -> expected " (pr-str expected)
                 " got " (pr-str (or (:ok r) (:err r)))))))))

;; ---------------------------------------------------------------- encode

(defn- js-or-long-floor [x]
  #?(:clj (if (or (Double/isNaN x) (Double/isInfinite x)) (inc x) (Math/floor (double x)))
     :cljs (if (or (js/isNaN x) (not (js/isFinite x))) (inc x) (js/Math.floor x))))

(defn- integral-float-marker?
  "A float fixture whose value happens to have no fractional part. On a platform
  with one number type such a value is indistinguishable from the integer, so it
  re-encodes as an integer and the vector cannot round-trip. Not a defect —
  a documented platform limit, asserted separately below."
  [value]
  (and (vector? value)
       (#{:f16 :f32 :f64} (first value))
       (number? (second value))
       (== (second value) (js-or-long-floor (second value)))))

(deftest appendix-a-encode-shortest
  (testing "roundtrip vectors re-encode to the same bytes under :shortest floats"
    (doseq [{:keys [hex value roundtrip encode-forbidden encoding-differs]} v/appendix-a
            :when (and roundtrip (not encode-forbidden) (not encoding-differs)
                       (or (c/width-checkable?) (not (integral-float-marker? value))))]
      (let [r (try-encode (c/->expected value) interop-opts)]
        (is (= hex (:ok r))
            (str "re-encode " hex " -> " (pr-str (or (:ok r) (:err r)))))))))

(deftest encoding-differs-still-decodes-equal
  (testing "vectors we re-encode differently must still decode to the same
            value, and our own encoding must round-trip"
    (doseq [{:keys [hex value encoding-differs]} v/appendix-a
            :when encoding-differs]
      (let [expected (c/->expected value)
            ours (try-encode expected interop-opts)
            back (when (:ok ours) (try-decode (:ok ours) interop-opts))]
        (is (and back (c/equiv? expected (:ok back)))
            (str hex " re-encoded as " (:ok ours)
                 " decodes to " (pr-str (or (:ok back) (:err back)))))))))

(deftest rfc8949-forbidden-encodings-are-refused
  (testing "vectors that RFC 8949 forbids an encoder from producing must be
            refused, not silently emitted — they remain decodable"
    (doseq [{:keys [hex value encode-forbidden encode-forbidden-reason]} v/appendix-a
            :when encode-forbidden]
      (let [r (try-encode (c/->expected value) interop-opts)]
        (is (contains? r :err)
            (str hex " should be refused on encode (" encode-forbidden-reason
                 ") but produced " (pr-str (:ok r))))))))

(deftest preserve-width-differs-on-narrow-floats
  (testing ":preserve-width must NOT reproduce f16/f32 vectors — it widens by
            design, and that divergence is the point (datahike's dump requirements)"
    (when (c/width-checkable?)
      (doseq [{:keys [hex value float-width roundtrip]} v/appendix-a
              :when (and roundtrip (#{:f16} float-width))]
        (let [r (try-encode (c/->expected value)
                            (assoc interop-opts :float-policy :preserve-width))]
          (is (and (contains? r :ok) (not= hex (:ok r)))
              (str "expected :preserve-width to widen " hex
                   " but got " (pr-str (or (:ok r) (:err r))))))))))

;; ---------------------------------------------------------------- types

(deftest type-identity-roundtrip
  (testing "a round trip preserves TYPE, not merely value"
    (doseq [[label value] f/type-identity]
      (let [enc (try-encode value {})
            r   (when (:ok enc) (try-decode (:ok enc) {}))]
        (is (and r (contains? r :ok) (c/type-identical? value (:ok r)))
            (str label ": " (pr-str value)
                 #?(:clj (str " ^" (some-> value class .getSimpleName)))
                 " -> " (pr-str (or (:ok r) (:err r) (:err enc)))
                 #?(:clj (str " ^" (some-> (:ok r) class .getSimpleName)))))))))

(deftest byte-stability
  (testing "encoding the same value twice yields identical bytes"
    (doseq [[label value] f/byte-stability]
      (let [a (try-encode value {})
            b (try-encode value {})]
        (is (and (:ok a) (= (:ok a) (:ok b)))
            (str label ": " (pr-str (or (:ok a) (:err a)))
                 " vs " (pr-str (or (:ok b) (:err b)))))))))

(deftest canonical-orders-agree-on-homogeneous-keys
  (testing "RFC 8949 bytewise and RFC 7049 length-first orders produce IDENTICAL
            bytes when all keys share a major type.

            CBOR puts a string's length in its leading byte(s), so a bytewise
            comparison encounters the length first — which makes bytewise
            ordering equivalent to length-first ordering for same-major keys.
            The choice between the two rules is therefore moot for keyword- or
            string-keyed maps, i.e. for essentially all Clojure data."
    (doseq [[label m] [["keyword keys" (array-map :z 1 :aaa 2 :m 3)]
                       ["string keys"  (array-map "z" 1 "aaa" 2 "m" 3)]
                       ["len 23 vs 24" (array-map (apply str (repeat 23 \a)) 1
                                                  (apply str (repeat 24 \b)) 2)]]]
      (is (= (:ok (try-encode m {:profile :canonical}))
             (:ok (try-encode m {:profile :canonical-rfc7049})))
          (str label ": the two canonical orders should agree here")))))

(deftest canonical-orders-diverge-on-mixed-key-types
  (testing "they DO diverge when a longer-encoded key has a lower leading byte,
            which requires keys of different major types"
    (let [m (array-map 1000 :x "a" :y)]
      (is (not= (:ok (try-encode m {:profile :canonical}))
                (:ok (try-encode m {:profile :canonical-rfc7049})))
          "int 1000 (3 bytes, leads 0x19) vs \"a\" (2 bytes, leads 0x61)"))))

(deftest canonical-map-ordering-is-stable
  (testing "canonical mode orders map keys deterministically regardless of
            insertion order (datahike's dump requirements)"
    (let [opts {:profile :canonical :stringref false}
          m1 (array-map :b 2 :a 1 :c 3)
          m2 (array-map :c 3 :a 1 :b 2)]
      (is (= (:ok (try-encode m1 opts)) (:ok (try-encode m2 opts)))))))

;; ---------------------------------------------------------------- records

(defrecord ConfPoint [x y])
(defrecord ConfEvent [id ts payload])

(deftest records-carry-their-type-without-write-registration
  (testing "a record always encodes with its type name, so the type is never
            silently flattened to a map — the failure mode incognito works around"
    (let [p (->ConfPoint 3 4)
          enc (try-encode p {})
          back (try-decode (:ok enc) {})]
      (is (data/unknown-record? (:ok back))
          (str "unregistered record should decode to UnknownRecord, got "
               (pr-str (:ok back))))
      (is (= "boring.conformance_test.ConfPoint" (data/record-type (:ok back))))
      (is (= {:x 3 :y 4} (data/record-fields (:ok back)))))))

(deftest unknown-record-behaves-like-the-record-it-stands-for
  (testing "field lookup, count, seq and assoc all work without a read handler,
            so code that only reads fields does not care whether one was
            installed. incognito's IncognitoTaggedLiteral requires reaching
            through :value for this; this does not."
    (let [ur (:ok (try-decode (:ok (try-encode (->ConfPoint 3 4) {})) {}))]
      (is (= 3 (:x ur)))
      (is (= 4 (get ur :y)))
      (is (= 2 (count ur)))
      (is (map? ur))
      (is (= {:x 3 :y 4} (into {} ur)))
      (is (= 9 (:z (assoc ur :z 9))))
      (testing "with defrecord equality semantics, not map equality"
        (is (not= ur {:x 3 :y 4}))
        (is (= ur (data/unknown-record "boring.conformance_test.ConfPoint"
                                       {:x 3 :y 4})))))))

(deftest unknown-record-passthrough-is-byte-identical
  (testing "decode then re-encode of an unregistered record reproduces the bytes"
    (let [enc (try-encode (->ConfPoint 3 4) {})
          back (try-decode (:ok enc) {})
          again (try-encode (:ok back) {})]
      (is (= (:ok enc) (:ok again))))))

(deftest record-wire-names-agree-across-platforms
  (testing "the JVM class name and the name ClojureScript derives must be the
            same string, or a record written on one platform is unreadable on
            the other even with a registration"
    (is (= "boring.conformance_test.ConfPoint"
           (data/record-type-name (->ConfPoint 1 2))))
    (is (= "boring.conformance_test.ConfEvent"
           (data/record-type-name (->ConfEvent 1 2 3))))))

(deftest registered-records-round-trip-as-themselves
  (testing "registration restores the concrete type, portably"
    ;; Registries are immutable values on both platforms, so this cannot leak
    ;; into any other test regardless of the order clojure.test picks — which
    ;; is exactly why the process-global default was removed.
    (let [reg (-> (boring/tag-registry)
                  (boring/register-record "boring.conformance_test.ConfPoint"
                                          map->ConfPoint)
                  (boring/register-record "boring.conformance_test.ConfEvent"
                                          map->ConfEvent))
          opts {:registry reg}
          p (->ConfPoint 3 4)
          back (try-decode (:ok (try-encode p opts)) opts)]
      (is (= p (:ok back)))
      (is (= (type p) (type (:ok back))))
      (testing "including nested inside collections"
        (let [v [(->ConfEvent 1 100 {:a :b}) (->ConfEvent 2 200 [1 2 3])]
              back (try-decode (:ok (try-encode v opts)) opts)]
          (is (= v (:ok back))))))))

#?(:clj
   (deftest register-record-class-derives-name-and-constructor
     (testing "the JVM-only reflective convenience agrees with the portable form"
       (let [reflective (boring/register-record-class (boring/tag-registry) ConfPoint)
             explicit (boring/register-record (boring/tag-registry)
                                              "boring.conformance_test.ConfPoint"
                                              map->ConfPoint)
             p (->ConfPoint 7 8)]
         (doseq [reg [reflective explicit]]
           (let [opts {:registry reg}]
             (is (= p (:ok (try-decode (:ok (try-encode p opts)) opts))))))))))

(deftest records-never-instantiate-arbitrary-classes
  (testing "an unregistered type name must yield an inert value, never a
            constructed object — there is no Class.forName path"
    (let [hostile (data/unknown-record "java.lang.Runtime" {:x 1})
          back (try-decode (:ok (try-encode hostile {})) {})]
      (is (data/unknown-record? (:ok back)))
      (is (= "java.lang.Runtime" (data/record-type (:ok back)))))))

;; ---------------------------------------------------------------- registry
;;
;; The CLJS writer previously ignored its registry entirely, so `register-tag`
;; worked on the JVM and silently did nothing on CLJS. Both directions are
;; asserted here so that asymmetry cannot come back unnoticed.
;;
;; Note there is no reader conditional around the registration itself any more:
;; registries are immutable values with the same signature on both platforms,
;; so this is the exact code a user writes in a .cljc file. Only the platform
;; TYPE being registered differs.

(defrecord HandlerPoint [x y])

(deftest user-handlers-beat-the-record-branch
  (testing "a registered write handler for a defrecord must fire, rather than
            losing to the built-in tag-27 record encoding.

            This was silently broken. A handler for a record type is usually
            registered precisely to TRANSFORM the value before writing --
            stripping live caches, connections or lazy roots that must not go
            on a wire. Being ignored there means shipping exactly the state the
            handler existed to remove, with no error at all."
    (let [reg (boring/register-tag (boring/tag-registry) 40007
                                   #?(:clj HandlerPoint :cljs HandlerPoint)
                                   (fn [_] {:stripped true})
                                   (fn [m] m))
          hex (c/bytes->hex (boring/encode (->HandlerPoint 1 2) {:registry reg}))]
      (testing "the handler's tag is on the wire"
        (is (re-find #"d99c47" hex) (str "expected tag 40007 (d99c47), got " hex)))
      (testing "and NOT the tag-27 record frame"
        (is (not (re-find #"d81b" hex))
            (str "tag 27 means the record branch won and the handler was skipped: " hex)))
      (testing "so the transform actually applied"
        (is (= {:stripped true} (boring/decode (boring/encode (->HandlerPoint 1 2) {:registry reg})
                                               {:registry reg}))))))
  (testing "and a record with NO handler still uses tag 27, unchanged"
    (let [hex (c/bytes->hex (boring/encode (->HandlerPoint 1 2)))]
      (is (re-find #"d81b" hex) hex))))

(deftest user-handlers-beat-structural-inference
  (testing "a registered handler must win over the built-in map/set/vector
            encoding for a type that also satisfies those.

            This was a live silent-corruption path. The registry lookup used to
            be the LAST fallback, below the structural instanceof/predicate
            cascade, so a handler for a type implementing java.util.Set (which
            is exactly what persistent-sorted-set's root does) was ignored: the
            value encoded as tag 258, a plain set of its elements. Structurally
            valid CBOR, entirely the wrong value, and no error anywhere."
    ;; A sorted set stands in for the real case: it satisfies set? on both
    ;; platforms, so without the fix the handler never runs.
    (let [v (sorted-set 3 1 2)
          reg (boring/register-tag (boring/tag-registry) 40002
                                   (type v)
                                   (fn [s] {:sorted (vec s)})
                                   (fn [m] (apply sorted-set (:sorted m))))
          bs (boring/encode v {:registry reg})
          hex (c/bytes->hex bs)]
      (testing "the handler's tag is on the wire, not the set tag"
        (is (re-find #"d99c42" hex)
            (str "expected tag 40002 (d99c42), got " hex))
        (is (not (re-find #"d90102" hex))
            (str "tag 258 means the handler was skipped: " hex)))
      (testing "and it round-trips through the handler"
        (is (= [1 2 3] (vec (boring/decode bs {:registry reg}))))))))

(deftest user-tags-round-trip-on-both-platforms
  (testing "a registered user tag encodes AND decodes"
    (let [reg (boring/register-tag (boring/tag-registry) 40001
                                   #?(:clj java.net.URI :cljs js/URL)
                                   #?(:clj (fn [u] (str u))
                                      :cljs (fn [u] (.-href u)))
                                   #?(:clj (fn [s] (java.net.URI. s))
                                      :cljs (fn [s] (js/URL. s))))
          v #?(:clj (java.net.URI. "https://example.com/a")
               :cljs (js/URL. "https://example.com/a"))
          bs (boring/encode v {:registry reg})]
      (is (= (str v) (str (boring/decode bs {:registry reg}))))
      (testing "and without the reader handler it is an inert TaggedValue"
        (let [t (boring/decode bs)]
          (is (data/tagged-value? t))
          (is (= 40001 (:tag t))))))))

;; ---------------------------------------------------------------- shaped arrays

;; ------------------------------------------------------- awkward text
;;
;; U+FEFF was silently dropped on ClojureScript: TextDecoder defaults to
;; ignoreBOM:false, which does not reject a leading BOM but CONSUMES it, so
;; "﻿" decoded to "" and "﻿A" to "A". Encoding was correct and the
;; JVM decoded correctly, so this was a one-platform silent truncation and a
;; cross-platform differential. A generative case found it; nothing hand-written
;; would have.

(def awkward-strings
  {"leading BOM"          "﻿"
   "BOM then ascii"       "﻿A"
   "BOM in the middle"    "a﻿b"
   "two BOMs"             "﻿﻿"
   "empty"                ""
   "nul"                  " "
   "astral pair"          "💩"
   "combining mark"       "é"
   "lone ascii"           "A"})

(deftest awkward-strings-round-trip-exactly
  (testing "no codepoint may be added or dropped in transit"
    (doseq [[label s] awkward-strings]
      (doseq [opts [{} {:profile :interop} {:profile :canonical}]]
        (let [back (boring/decode (boring/encode s opts) opts)]
          (is (= s back) (str label " under " (or (:profile opts) :clojure)))
          (is (= (count s) (count back))
              (str label ": length changed under "
                   (or (:profile opts) :clojure))))))))

(deftest awkward-strings-survive-as-map-keys-and-in-collections
  (testing "the stringref and ident paths must not drop characters either"
    (let [v {"﻿" 1 "﻿A" 2 "plain" 3}]
      (is (= v (boring/decode (boring/encode v)))))
    (let [v ["﻿" "﻿" "﻿A"]]
      ;; repeated, so the second occurrence goes through stringref
      (is (= v (boring/decode (boring/encode v)))))))

;; ------------------------------------------------------- canonical floats
;;
;; The :canonical profile shipped :float-policy :preserve-width, so the profile
;; whose entire purpose is byte-for-byte agreement with other implementations
;; agreed with none of them — 8 of these 9 vectors mismatched. Deterministic
;; encoding requires the shortest float form that round-trips (RFC 8949 §4.2.2,
;; unchanged in draft-ietf-cbor-serialization).

;; Values whose CBOR type is unambiguous on both platforms. These MUST be
;; byte-identical everywhere — a signed document has to verify on the platform
;; that did not write it.
(def canonical-float-vectors
  [["1.5"                    1.5   "f93e00"]
   ["-0.0 keeps its sign"    -0.0  "f98000"]
   ["Infinity"               ##Inf "f97c00"]
   ["-Infinity"              ##-Inf "f9fc00"]
   ["NaN is always f97e00"   ##NaN "f97e00"]
   ["1.1 needs double"       1.1   "fb3ff199999999999a"]])

;; Values that are INTEGRAL. ClojureScript has one number type, so 1.0 and 1
;; are the same value and boring emits a CBOR integer; the JVM knows it holds a
;; Double and emits a float. Neither is wrong — but the bytes differ, and no
;; option can reconcile them because the distinction does not survive into JS.
;;
;; Consequence, and it is a sharp one: a canonical document containing an
;; integral float is NOT byte-identical across platforms. Sign such a document
;; on the JVM and it will not verify against ClojureScript's re-encoding of the
;; same value. See doc/SECURITY.md.
;;
;; Note the JVM behaviour matches RFC 8949 §4.2 and draft-ietf-cbor-serialization,
;; while ClojureScript's accidentally matches dCBOR's "numeric reduction", which
;; deliberately collapses integral floats to integers. Both are defensible
;; positions in the ecosystem; we simply cannot hold both at once.
(def canonical-integral-float-vectors
  [["1.0"        1.0      "f93c00"   "01"]
   ["0.0"        0.0      "f90000"   "00"]
   ["65504.0"    65504.0  "f97bff"   "19ffe0"]
   ["100000.0"   100000.0 "fa47c35000" "1a000186a0"]])

(deftest canonical-profile-shortens-floats
  (testing "canonical output must use the shortest round-tripping float form"
    (doseq [[label v expected] canonical-float-vectors]
      (let [{:keys [ok err]} (try-encode v {:profile :canonical})]
        (is (nil? err) (str label ": " err))
        (when ok (is (= expected ok) label))))))

(deftest canonical-floats-are-identical-across-platforms
  (testing "unambiguous floats must sign the same on both platforms"
    (doseq [[label v expected] canonical-float-vectors]
      (is (= expected (c/bytes->hex (boring/encode v {:profile :canonical})))
          (str label " on " #?(:clj "JVM" :cljs "ClojureScript"))))))

(deftest integral-floats-diverge-across-platforms-by-necessity
  (testing "pinned so the divergence stays a known, documented quantity rather
            than something discovered by a signature that will not verify"
    (doseq [[label v jvm-hex cljs-hex] canonical-integral-float-vectors]
      (is (= #?(:clj jvm-hex :cljs cljs-hex)
             (c/bytes->hex (boring/encode v {:profile :canonical})))
          label)
      (testing "and both encodings decode to a numerically equal value"
        (is (== v (boring/decode (boring/encode v {:profile :canonical}))) label)))))

(deftest shaped-arrays-round-trip
  (testing "an array whose elements are all maps with the same keys encodes the
            keys ONCE (tag 40000), which is where the measured decode time went"
    (let [datoms (vec (for [i (range 20)]
                        {:e (+ 100 i) :a :user/name :v (str "p-" i) :added true}))
          plain (:ok (try-encode datoms {}))
          shaped (:ok (try-encode datoms {:shapes true}))]
      (is (< (count shaped) (count plain)) "shaped must be smaller")
      (is (= datoms (:ok (try-decode shaped {:shapes true}))))
      (testing "and a shaped document decodes without the writer option set"
        (is (= datoms (:ok (try-decode shaped {}))))))))

(deftest shaped-arrays-fall-back-cleanly
  (testing "anything not uniformly shaped must encode as an ordinary array"
    (doseq [[label v] [["mixed shapes"   [{:a 1} {:b 2}]]
                       ["not all maps"   [{:a 1} 5]]
                       ["single map"     [{:a 1}]]
                       ["empty maps"     [{} {}]]
                       ["empty vector"   []]
                       ["nested vectors" [[{:a 1} {:a 2}] [{:a 3} {:a 4}]]]
                       ["maps of maps"   [{:a {:x 1}} {:a {:x 2}}]]]]
      (is (= v (:ok (try-decode (:ok (try-encode v {:shapes true})) {})))
          (str label " must round-trip")))))

(deftest shaped-arrays-reject-malformed-frames
  (testing "a hostile shaped frame must fail typed, like every other tag"
    ;; d99ae1 is tag 39649, the shaped-array tag. It was d99c40 (40000) until
    ;; the IANA registry showed 40000 already assigned to ur:known-value; see
    ;; doc/COMPATIBILITY.md.
    (doseq [[label hex] [["not an array"      "d99ae101"]
                         ["wrong arity"       "d99ae181820102"]
                         ["keys not an array" "d99ae1820180"]
                         ["empty key list"    "d99ae1828080"]
                         ["row arity mismatch" "d99ae1828163614181820102"]]]
      (is (contains? (try-decode hex {}) :err)
          (str label " should be rejected")))))

;; ------------------------------------------------- cross-platform tag parity
;;
;; The JVM writes BigDecimal as tag 4, Ratio as tag 30, and prim arrays as
;; RFC 8746 typed arrays. CLJS could not read ANY of them — they decoded as
;; opaque TaggedValues, i.e. silent data loss on exactly the JVM-to-browser path
;; a portable dump format promises. These byte strings are what each platform
;; emits; both must decode them to their native equivalent.

(def cross-platform-tag-bytes
  [["decimal 1.50"  "d90100c482211896"]
   ["decimal 1.5"   "d90100c482200f"]
   ["rational 22/7" "d90100d81e821607"]
   ["int array"     "d90100d84e4c0100000002000000fdffffff"]
   ["double array"  "d90100d85650000000000000f83f00000000000002c0"]
   ["short array"   "d90100d84d440100feff"]
   ["float array"   "d90100d855480000c03f00002040"]
   ["long array"    "d90100d84f500100000000000000feffffffffffffff"]])

(deftest cross-platform-tags-decode-natively
  (testing "every tag either platform emits decodes to a native value, not a
            TaggedValue, on BOTH platforms"
    (doseq [[label hex] cross-platform-tag-bytes]
      (let [r (try-decode hex {})]
        (is (contains? r :ok) (str label " must decode"))
        (is (not (data/tagged-value? (:ok r)))
            (str label " decoded as an opaque TaggedValue — that is data loss"))))))

(deftest tag-27-carries-a-positional-argument
  (testing "Tag 27 is \"type name and constructor arguments\", so the argument
            need not be a field map. boring writes defrecords as a map, but a
            positional type -- datahike's Datom is a deftype of five values --
            has no field names and would pay for inventing them: measured over
            512 Datoms, a field map costs 56.1 bytes each against 31.1 for a
            vector.

            The tradeoff is on the fallback path, so it is pinned here too: an
            UNREGISTERED positional type decodes to a `TaggedLiteral`, which
            keeps the values and the type name and does not pretend to answer
            keyword lookup. It used to decode to an UnknownRecord, which
            claimed IPersistentMap over a vector and then threw raw
            ClassCastException from `keys` and IllegalArgumentException from
            `assoc`/`into` -- a broken contract rather than a caller error.
            A record payload still degrades fully."
    (let [reg (boring/register-record (boring/tag-registry) "test.Positional" vec)
          bs  (boring/encode (data/tagged-value 27 ["test.Positional" [1 :a "v" 4 true]]))]
      (testing "registered: the constructor receives the vector"
        (is (= [1 :a "v" 4 true] (boring/decode bs {:registry reg}))))
      (testing "unregistered: values and name survive"
        (let [back (boring/decode bs {})]
          (is (tagged-literal? back))
          (is (= "test.Positional" (data/frame-name back)))
          (is (= [1 :a "v" 4 true] (vec (data/frame-payload back))))
          (is (nil? (:e back))
              "keyword lookup returns nil on a positional payload — the
               documented cost of not paying for field names")
          (testing "and it RE-ENCODES to the identical bytes. This is the whole
                    reason UnknownRecord keeps the payload, and relaxing the
                    read side without relaxing the write side broke it — the
                    writer cast the payload to a map and threw.

                    Compared as hex, not as `(seq bs)`: on ClojureScript `seq`
                    of a Uint8Array does not compare element-wise, so that form
                    reports a difference between two identical byte strings."
            (is (= (c/bytes->hex bs) (c/bytes->hex (boring/encode back {})))))))
      (testing "a map argument is unaffected"
        (let [mb (boring/encode (data/tagged-value 27 ["test.Mapped" {:x 1 :y 2}]))
              back (boring/decode mb {})]
          (is (= 1 (:x back)))
          (is (= "test.Mapped" (data/record-type back)))))
      (testing "the type name is still required to be a string"
        (is (:err (try-decode (c/bytes->hex
                               (boring/encode (data/tagged-value 27 [42 [1]])))
                              {})))))))

(deftest registered-tags-cover-jvm-types-portably
  (testing "URI (tag 32) and regex (tag 35) are REGISTERED, so carrying these
            JVM types costs no private tag and a foreign reader gets a real URI
            or regex rather than an opaque blob. nippy can carry them too, but
            only inside Java serialization, which no non-JVM reader can open."
    (testing "regex is symmetric across platforms -- Pattern and js/RegExp both
              land on tag 35, which makes it one of the few JVM-shaped types
              with no cross-platform caveat at all"
      (let [re #?(:clj (java.util.regex.Pattern/compile "^[a-z]+\\d{2,}$")
                  :cljs #"^[a-z]+\d{2,}$")
            back (boring/decode (boring/encode re))]
        (is (= (str re) (str back)))
        #?(:clj  (is (instance? java.util.regex.Pattern back))
           :cljs (is (regexp? back)))))

    #?(:clj
       (testing "URI round-trips as a URI on the JVM"
         (let [u (java.net.URI. "https://example.com/a?b=c#d")]
           (is (= u (boring/decode (boring/encode u)))))))

    (testing "the bytes are the registered tags, not something we invented"
      ;; d8 20 = tag 32, d8 23 = tag 35
      (is (= "d820" (subs (c/bytes->hex (boring/encode
                                         #?(:clj (java.net.URI. "u")
                                            :cljs (data/tagged-value 32 "u"))
                                         {:stringref false}))
                          0 4)))
      (is (= "d823" (subs (c/bytes->hex (boring/encode
                                         #?(:clj (java.util.regex.Pattern/compile "u")
                                            :cljs #"u")
                                         {:stringref false}))
                          0 4))))

    (testing "malformed tag content is a TYPED error, not a raw platform
              exception escaping the read path"
      ;; Tag 32 is JVM-only to DECODE -- ClojureScript leaves it a TaggedValue
      ;; so it stays re-encodable -- so only the JVM can reject bad content.
      #?(:clj (is (:err (try-decode "d820182a" {})) "tag 32 wrapping an integer"))
      (is (:err (try-decode "d8236128" {})) "tag 35 wrapping an unbalanced ("))))

#?(:clj
   (deftest multi-dimensional-arrays-use-tag-40
     (testing "RFC 8746 tag 40, row-major. Before this a 2D primitive array had
               NO encoding at all -- double[][] threw \"no encoding for [[D\" --
               so this closes a case that previously could not be sent, using
               the registered form rather than a private one."
       (doseq [[label m] [["double[][]" (into-array (map double-array [[1.0 2.0] [3.0 4.0]]))]
                          ["float[][]"  (into-array (map float-array  [[1.0 2.0] [3.0 4.0]]))]
                          ["long[][]"   (into-array (map long-array   [[1 2] [3 4]]))]
                          ["int[][]"    (into-array (map int-array    [[1 2] [3 4]]))]
                          ["short[][]"  (into-array (map short-array  [[1 2] [3 4]]))]]]
         (testing label
           (let [enc (boring/encode m {:stringref false})
                 back (boring/decode enc)]
             (is (= "d828" (subs (c/bytes->hex enc) 0 4)) "tag 40")
             (is (= (class m) (class back)) "the primitive type survives")
             (is (= (mapv vec m) (mapv vec back))))))

       (testing "a RAGGED array is not a multi-dimensional array in RFC 8746's
                 sense, so it falls back to a plain array of rows rather than
                 declaring a shape it does not have"
         (let [r (into-array [(double-array [1.0]) (double-array [2.0 3.0])])
               enc (boring/encode r {:stringref false})]
           (is (not= "d828" (subs (c/bytes->hex enc) 0 4)))
           (is (= [[1.0] [2.0 3.0]] (mapv vec (boring/decode enc))))))

       (testing "dimensions and payload length both come from the wire, so a
                 disagreement must be refused rather than trusted -- otherwise
                 the declared shape is an unchecked allocation"
         ;; 40([[2,3], <4-element f64 array>]) -- claims 6, supplies 4
         (is (:err (try-decode "d82882820203d8564820000000000000003ff0000000000000" {})))))))

#?(:clj
   (deftest foreign-typed-arrays-decode-to-real-arrays
     (testing "RFC 8746 defines 24 typed-array tags. boring WRITES five -- the
               signed little-endian integers plus f32/f64 LE -- because the JVM
               has no unsigned primitives and no float16 outside an incubator
               module. It READS every one with a lossless JVM representation, so
               an array from numpy, Rust or Go arrives as a real primitive array
               rather than an inert TaggedValue.

               The rule is the NARROWEST JVM PRIMITIVE THAT HOLDS EVERY VALUE.
               uint8 becomes short[], not byte[]: byte[] would keep the bits
               while silently reinterpreting 200 as -56."
       (letfn [(tagged [tag payload]
                 (byte-array (concat [(unchecked-byte 0xd8) (unchecked-byte tag)
                                      (unchecked-byte (bit-or 0x40 (count payload)))]
                                     (map unchecked-byte payload))))]
         (doseq [[tag label payload expected]
                 [[64 "uint8"      [0 200 255]             [0 200 255]]
                  [68 "uint8clamp" [0 200 255]             [0 200 255]]
                  [72 "sint8"      [0 0x80 0x7f]           [0 -128 127]]
                  [65 "uint16 BE"  [0xff 0xff 0x00 0x01]   [65535 1]]
                  [69 "uint16 LE"  [0xff 0xff 0x01 0x00]   [65535 1]]
                  [73 "sint16 BE"  [0xff 0xff]             [-1]]
                  [66 "uint32 BE"  [0xff 0xff 0xff 0xff]   [4294967295]]
                  [70 "uint32 LE"  [0xff 0xff 0xff 0xff]   [4294967295]]
                  [74 "sint32 BE"  [0xff 0xff 0xff 0xff]   [-1]]
                  [80 "f16 BE"     [0x3c 0x00 0x40 0x00]   [1.0 2.0]]
                  [84 "f16 LE"     [0x00 0x3c 0x00 0x40]   [1.0 2.0]]
                  [81 "f32 BE"     [0x3f 0x80 0 0]         [1.0]]
                  [82 "f64 BE"     [0x3f 0xf0 0 0 0 0 0 0] [1.0]]]]
           (testing label
             (let [v (boring/decode (tagged tag payload))]
               (is (not (data/tagged-value? v))
                   (str "tag " tag " should decode to a real array"))
               (is (= expected (vec v)) (str "tag " tag))))))

       (testing "a uint64 above Long/MAX_VALUE has no lossless long, so the array
                 widens to a vector of bignums rather than being REFUSED.

                 It used to be refused, which rejected conforming RFC 8746 input
                 over a host-type limit -- and did so DATA-DEPENDENTLY, so a
                 producer's tag 67 worked until the day a value crossed 2^63.
                 The common case still returns a primitive long[]; only an array
                 that needs the width pays for boxing."
         (is (= [18446744073709551615N]
                (:ok (try-decode "d84748ffffffffffffffff" {})))
             "2^64-1 survives")
         ;; tag 67 is uint64 BIG-endian; tag 71 is the little-endian one.
         (is (= [5 18446744073709551615N]
                (:ok (try-decode "d843500000000000000005ffffffffffffffff" {})))
             "mixed widths in one array"))

       (testing "a REGISTERED reader overrides the built-in mapping. The built-in
                 typed-array types are a DEFAULT, not a ceiling: a consumer with
                 its own numeric types -- a Julia-style Float16, a fixed-point, a
                 unit-carrying quantity -- must be able to take tag 84 for itself.

                 Pinned because it was broken until the RFC 8746 tags were added
                 and someone tried exactly this: the registry was consulted only
                 for tags the built-in switch did NOT handle, so the registration
                 was silently ignored and a float[] came back."
         (let [reg (boring/register-tag (boring/tag-registry) 84 nil nil
                                        (fn [bs] {:my-f16 (count bs)}))
               bs (byte-array [(unchecked-byte 0xd8) (unchecked-byte 84)
                               (unchecked-byte 0x42) 0x00 0x3c])]
           (is (= {:my-f16 2} (boring/decode bs {:registry reg})))
           (is (= [1.0] (vec (boring/decode bs))) "and the default still applies without it")))

       (testing "float128 has no JVM type at all and stays inert rather than
                 being approximated as a double"
         (let [v (boring/decode
                  (byte-array (concat [(unchecked-byte 0xd8) (unchecked-byte 83)
                                       (unchecked-byte 0x50)] (repeat 16 (byte 0)))))]
           (is (data/tagged-value? v)))))))

(deftest collection-shapes-collapse-predictably
  (testing "CBOR has arrays, maps and a tagged set, and nothing finer, so the
            Clojure collection zoo collapses. Asserted with an EXPLICIT expected
            value rather than with `=` against the input, because `=` hides
            exactly this: (= (list 1 2) [1 2]) is true, so a value-equality test
            would pass while the type silently changed.

            Runs on both platforms on purpose. This is where a codec that
            dispatches on concrete types instead of protocols fails on one side
            only -- fress cannot write LazySeq, Subvec or Cons in a browser."
    (doseq [[label v expected] f/shape-collapse]
      (let [back (boring/decode (boring/encode v))]
        (is (= expected back) label)
        (is (= (type expected) (type back))
            (str label ": expected " (pr-str (type expected))
                 " got " (pr-str (type back))))))))

(deftest ordered-collections-keep-type-and-order
  (testing "sorted maps, sorted sets and queues survive as themselves, carried
            by reserved tag-27 names -- the same mechanism records use, no
            private tag. Order is asserted separately from equality because a
            wrong reconstruction once produced a PersistentTreeMap whose keys
            were MapEntries: the type check passed while the value was wrong."
    (doseq [[label v] f/ordered-collections]
      (let [back (boring/decode (boring/encode v))]
        (is (= v back) (str label " -- value"))
        (is (= (type v) (type back)) (str label " -- type"))
        (is (= (seq v) (seq back)) (str label " -- order")))))

  (testing "a CUSTOM comparator is refused, not dropped. It is code; no encoding
            can carry it, and rebuilding with `compare` would produce a
            collection that sorts differently from the one that was stored."
    (is (thrown? #?(:clj Exception :cljs :default)
                 (boring/encode (sorted-map-by (fn [a b] (compare b a)) :a 1 :b 2))))
    (is (thrown? #?(:clj Exception :cljs :default)
                 (boring/encode (sorted-set-by (fn [a b] (compare b a)) 1 2)))))

  (testing "and a foreign reader still sees a self-describing name plus the
            entries in order, rather than an opaque blob"
    (let [raw (boring/decode (boring/encode (sorted-map :b 2 :a 1)) {})]
      (is (= [:a :b] (keys raw)) "ordering is on the wire regardless"))))

(deftest metadata-survives
  (testing "carried as 27([\"clojure/with-meta\", [meta value]]), on by default
            to match nippy. `=` ignores metadata, so a value-equality test
            cannot see this either way -- which is exactly why it is asserted
            explicitly."
    (doseq [[label v] f/metadata-preserved]
      (let [back (boring/decode (boring/encode v))]
        (is (= v back) (str label " -- value"))
        (is (= (meta v) (meta back)) (str label " -- metadata")))))

  (testing "a value WITHOUT metadata is byte-identical to before the feature"
    (is (= (c/bytes->hex (boring/encode [1 2]))
           (c/bytes->hex (boring/encode [1 2] {:incl-metadata? false})))))

  (testing ":incl-metadata? false restores the old behaviour"
    (let [back (boring/decode (boring/encode (with-meta [1] {:m 1})
                                             {:incl-metadata? false}))]
      (is (= [1] back))
      (is (nil? (meta back)))))

  #?(:clj
     (testing "metadata must itself be encodable. Attaching a function makes the
               value fail LOUDLY rather than silently discarding the map -- the
               intended trade for turning this on by default."
       (is (thrown? Exception (boring/encode (with-meta [1] {:f (fn [])})))))))

(deftest character-survives-as-a-character
  (testing "CBOR has no char type. Written as a one-character text string, a
            Character came back a String -- and `(= \\a \"a\")` is FALSE, so
            that was silent CORRUPTION, not the value-preserving widening that
            Byte/Short/Integer -> Long is. Carried as 27([\"clojure/char\", \"a\"])
            for the same reason the sorted-collection markers exist."
    #?(:clj
       (do (doseq [ch [\a \ಬ \newline]]
             (let [back (boring/decode (boring/encode ch))]
               (is (= ch back))
               (is (instance? Character back))))
           (let [back (boring/decode (boring/encode {:c \z :s "z"}))]
             (is (= {:c \z :s "z"} back)
                 "a char and the equivalent string stay distinguishable"))
           ;; A char is a UTF-16 code UNIT. Two characters cannot be one, and
           ;; neither can an astral codepoint -- truncating to the high
           ;; surrogate would decode a DIFFERENT character than was written,
           ;; so both are refused.
           (is (contains? (try-decode "d81b826c636c6f6a7572652f63686172626162" {}) :err))
           (is (contains? (try-decode "d81b826c636c6f6a7572652f6368617264f09f92a9" {}) :err)))

       ;; ClojureScript has no character type at all -- the reader turns `\a`
       ;; into the one-character string -- so there is nothing to preserve on
       ;; this side. What matters is that JVM-written data still DECODES here
       ;; rather than surfacing as an UnknownRecord the caller must unwrap.
       :cljs
       (is (= "a" (boring/decode (c/hex->bytes "d81b826c636c6f6a7572652f636861726161")))))))

(deftest stringref-namespaces-are-scoped
  (testing "tag 256 opens a FRESH table and restores the enclosing one.

            Without this the table was global to the message, which produced a
            wrong VALUE -- not an error -- on legal third-party CBOR: two
            sibling namespaces decoded as [[\"abcd\" \"abcd\"] [\"wxyz\" \"abcd\"]]
            because the second namespace's index 0 resolved against the first
            namespace's table.

            boring's own writer emits exactly one namespace at the root, so its
            own output round-tripped correctly and nothing here noticed. The
            reference reader in interop/rust, written against the spec rather
            than against our decoder, had the scoping this one lacked."
    (is (= [["abcd" "abcd"] ["wxyz" "wxyz"]]
           (:ok (try-decode "82d90100826461626364d81900d9010082647778797ad81900" {})))
        "sibling namespaces are independent")
    (is (= ["abcd" ["wxyz" "wxyz"]]
           (:ok (try-decode "d90100826461626364d9010082647778797ad81900" {})))
        "a nested namespace SHADOWS the outer one rather than extending it"))

  (testing "a reference must name an entry that exists, in a namespace that is open"
    (doseq [[label hex] [["outside any namespace" "826461626364d81900"]
                         ["past the end of the table" "d90100826461626364d81905"]
                         ["into an empty namespace" "d9010081d81900"]
                         ;; \"ab\" is 2 bytes; a reference costs 3, so it is not
                         ;; worth referencing and never enters the table.
                         ;; Verified against cbor2, which draws the line in the
                         ;; same place -- 3 bytes IS registered, 2 is not.
                         ["to a below-threshold string" "d9010082626162d81900"]
                         ["outside, via the tag-39 path" "d827d9001900"]]]
      (is (contains? (try-decode hex {}) :err) (str label " must be an error"))))

  (testing "the reference index must be an unsigned integer.

            `arg(u8() & 0x1F)` kept the additional-info bits and DISCARDED the
            major type, so d8 19 20 -- the integer -1 -- was read as index 0.
            Malformed CBOR silently became a valid reference."
    (is (contains? (try-decode "d90100826461626364d81920" {}) :err)))

  (testing "every argument width resolves to the same index. A writer emitting
            preferred serialisation only ever produces the shortest form, so
            these are exactly the encodings our own tests would never generate
            and a foreign encoder legitimately might."
    (doseq [[label hex] [["1-byte"  "d90100826461626364d8191800"]
                         ["2-byte"  "d90100826461626364d819190000"]
                         ["4-byte"  "d90100826461626364d8191a00000000"]]]
      (is (= ["abcd" "abcd"] (:ok (try-decode hex {}))) (str label " argument"))))

  (testing "an alternate-width tag 25 under tag 39 goes through the same checks.
            This path had its own copy of the index logic and skipped them, so
            the JVM threw a raw NullPointerException straight through the
            typed-error contract."
    (is (= ["abcd" (symbol "abcd")]
           (:ok (try-decode "d90100826461626364d827d9001900" {})))))

  (testing "a BYTE string takes a stringref index, exactly as a text string does.

            It did not, and that broke interop in the OUTBOUND direction --
            the one the whole format exists for. For [<5 bytes>, \"abcd\",
            \"abcd\"] boring wrote 25(0) where cbor2 writes 25(1), because
            cbor2 gave index 0 to the byte string and boring gave it to
            \"abcd\". cbor2 read our second \"abcd\" back as the BYTE STRING:
            silently wrong data, no error anywhere.

            Verified byte-for-byte against cbor2's own encoder."
    (is (= "d90100834501020304056461626364d81901"
           (:ok (try-encode [(c/hex->bytes "0102030405") "abcd" "abcd"] {})))
        "the index is 1 -- the byte string consumed index 0")
    (let [back (:ok (try-decode "d90100834501020304056461626364d81901" {}))]
      (is (= 3 (count back)))
      (is (= ["abcd" "abcd"] (vec (rest back)))
          "and reading it back agrees -- the reference resolves to the TEXT")
      (is (= [1 2 3 4 5] (vec (first back))))))

  (testing "boring's own output still round-trips -- the regression this guards"
    (let [v [{:attribute 1} [{:attribute 2}] {:deep {:attribute 3}}]]
      (is (= v (boring/decode (boring/encode v)))))))

(deftest profile-invariants-cannot-be-overridden
  (testing "a profile DEFINES these keys; passing a conflicting value asks for
            two incompatible things and is refused rather than silently
            resolved in the caller's favour.

            It used to be resolved in the caller's favour, so
            {:profile :canonical :stringref true} emitted the stringref
            extension from the profile whose entire purpose is byte agreement
            with other implementations, and {:profile :canonical :canonical
            false} turned determinism off while still calling itself
            canonical."
    (doseq [[label opts v] [["canonical + stringref"  {:profile :canonical :stringref true} {:a 1}]
                            ["canonical + float"      {:profile :canonical :float-policy :preserve-width} 1.5]
                            ["canonical + canonical"  {:profile :canonical :canonical false} {:a 1}]
                            ["interop + stringref"    {:profile :interop :stringref true} {:a 1}]
                            ["interop + shapes"       {:profile :interop :shapes true} [{:a 1}]]
                            ;; :canonical is locked in EVERY profile: it is not
                            ;; a knob, it is what :profile :canonical MEANS.
                            ["clojure + canonical"    {:canonical true} {:a 1}]]]
      (let [r (try-encode v opts)]
        (is (contains? r :err) (str label " must be refused, got " (:ok r))))))

  (testing "the knobs a profile leaves free stay free"
    (doseq [[label opts] [["stringref off under :clojure"  {:stringref false}]
                          ["shortest floats under :interop" {:profile :interop :float-policy :shortest}]
                          ["redundant but consistent"      {:profile :canonical :stringref false}]]]
      (is (contains? (try-encode 1.5 opts) :ok) label))))

(deftest reusable-writer-does-not-retain-a-registry
  (testing "every option is reset on every call. `:registry` was set with
            `when-let`, so a writer reused after one call with a custom
            registry still used it -- a handler meant for one tenant staying
            active for the next, silently changing wire types."
    #?(:clj
       (let [reg (-> (boring/tag-registry)
                     (boring/register-tag 40001 java.net.URI str
                                          #(java.net.URI. %)))
             w (boring/writer 256)
             u (java.net.URI. "http://x")]
         (boring/encode-into! w u {:registry reg})
         (is (= (c/bytes->hex (boring/encode u {}))
                (c/bytes->hex (boring/encode-into! w u {})))
             "the second call must match a FRESH writer, not the first call")))))

(deftest unpaired-surrogates-are-refused
  (testing "a lone UTF-16 surrogate has no UTF-8 encoding, and both platforms
            silently SUBSTITUTED rather than failing: the JVM's
            String.getBytes(UTF_8) writes '?', TextEncoder writes U+FFFD. So a
            legal host-language string encoded to a DIFFERENT string, and the
            two platforms disagreed about which different string."
    (doseq [[label s] [["lone high surrogate"     (str (char 0xD800))]
                       ["lone low surrogate"      (str (char 0xDC00))]
                       ["high surrogate then text" (str (char 0xD800) "abc")]
                       ["reversed pair"           (str (char 0xDC00) (char 0xD800))]
                       ["unpaired inside a longer string"
                        (str "a-reasonably-long-prefix-here" (char 0xD800) "tail")]]]
      (is (contains? (try-encode s {}) :err) (str label " must be refused"))))

  (testing "a WELL-FORMED pair is untouched, including as a map key and inside
            an identifier -- the check must not reject legal astral text"
    (doseq [[label v] [["astral string"  "💩"]
                       ["mixed"          "héllo wörld 💩"]
                       ["as a map key"   {"💩" 1}]
                       ["in a keyword"   (keyword "emoji💩")]
                       ["surrounded"     (str "a💩b")]]]
      (is (= v (boring/decode (boring/encode v))) label))))

(deftest conforming-tag-content-the-specs-allow-and-boring-refused
  (testing "RFC 8746 3.1.1 admits a plain CBOR array, a typed array or a tag-41
            Homogeneous Array as a tag-40 payload, and bounds the dimension
            count nowhere. Tag 258 is registered against \"array\", which 3.2.2
            makes include the indefinite-length form. Tag 39's registered data
            item is \"multiple\", not \"text string\". boring rejected all four,
            which is rejecting conforming input -- identically on both
            platforms, so nothing here is a differential."
    ;; tag 40 [[2,3], [2,4,8,4,16,256]] -- RFC 8746 Figure 2, verbatim.
    ;; A plain CBOR array payload nests into vectors on both platforms, so
    ;; these compare directly with no array-equality helper.
    (is (= [[2 4 8] [4 16 256]]
           (:ok (try-decode "d82882820203860204080410190100" interop-opts)))
        "Figure 2 of the defining RFC")
    ;; tag 40 [[2,2,2], [1..8]]
    (is (= [[[1 2] [3 4]] [[5 6] [7 8]]]
           (:ok (try-decode "d8288283020202880102030405060708" interop-opts)))
        "three dimensions")
    ;; tag 258 around an indefinite-length array
    (is (= #{1 2 3} (:ok (try-decode "d901029f010203ff" interop-opts)))
        "a set over an indefinite-length array")
    ;; tag 39 around an integer -- degrades, does not throw
    (is (contains? (try-decode "d82701" interop-opts) :ok)
        "an identifier tag over non-text content stays inert")
    ;; tag 0 "2016-12-31T23:59:60Z" -- a real leap second, legal RFC 3339 5.6
    (let [leap (try-decode "c074323031362d31322d33315432333a35393a36305a" interop-opts)]
      (is (contains? leap :ok)
          "a leap second is a legal timestamp, not a malformed document")
      (is (= "2016-12-31T23:59:60Z" (:value (:ok leap)))
          "and the STRING survives: Instant.parse collapses :60 to :59 and
           new Date rejects it outright, so neither platform has a lossless
           native value -- preserving the text is the only honest option"))))

(deftest a-truncated-indefinite-string-is-reported-as-truncation
  (testing "`5f 41 00` and `7f 61 00` are indefinite-length strings that run out
            before their break code -- Appendix F.1 lists them under 'too little
            data'. ClojureScript reported them as :boring/bad-indefinite-chunk
            'contains a chunk of major 0', naming a chunk that does not exist:
            `aget` past the end of a Uint8Array is `undefined`, and
            `undefined >> 5` is 0 in JavaScript. Both platforms now agree, and
            agree with the RFC's own classification."
    (doseq [hex ["5f4100" "7f6100"]]
      (let [r (try (do (boring/decode (c/hex->bytes hex)) nil)
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                     (:type (ex-data e))))]
        (is (= :boring/truncated-input r) hex)))))

(deftest tag-40-dimension-count-cannot-blow-the-host-stack
  (testing "tag 40's dimensions are a FLAT array, so :max-depth never charged
            for them -- while the decoded value nests once per dimension. A
            structurally shallow 20 KB item declaring 20 000 dimensions of 1
            therefore recursed 20 000 deep in the reconstructor: StackOverflowError
            on the JVM, RangeError under Node, neither carrying ex-data, both
            contradicting doc/SECURITY.md's typed-failure guarantee.

            Rebuilt iteratively AND charged against :max-depth -- iteration alone
            is not enough, because the result would still be 20 000 deep and
            would overflow in the caller on `=` or `hash`."
    ;; d8 28 82 99 4e 20 <20000 x 01> 81 07
    (let [hex (str "d82882994e20" (apply str (repeat 20000 "01")) "8107")
          r (try {:ok (boring/decode (c/hex->bytes hex))}
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                   (if (:type (ex-data e)) {:typed (:type (ex-data e))} {:untyped (str e)}))
                 #?(:clj (catch Throwable e {:raw (.getSimpleName (class e))})))]
      (is (= {:typed :boring/max-depth-exceeded} r)
          (str "expected a typed depth error, got " (pr-str r)))))
  (testing "and dimensionality the RFC allows still decodes"
    (is (= [[[1 2] [3 4]] [[5 6] [7 8]]]
           (:ok (try-decode "d8288283020202880102030405060708" interop-opts))))))

(deftest max-items-is-enforced-on-both-platforms-and-per-item
  (testing ":max-items is the only heap-amplification control doc/SECURITY.md
            names, and ClojureScript did not implement it at all -- the option
            was accepted and silently ignored, so a browser or Node reader had
            no bound whatsoever. On the JVM the counter existed but carried
            across top-level items, which made acceptance depend on the
            streaming chunk size: the same five items decoded at `:chunk-size 2`
            and failed at 65536. A limit whose meaning changes with an unrelated
            buffering knob cannot be the right one.

            The budget is now PER TOP-LEVEL ITEM on both platforms, which is
            what the streaming API already promises: retained memory is bounded
            by the largest single item, not by the file."
    (let [o {:stringref false}]
      (testing "one oversized item is refused"
        (is (= :boring/max-items-exceeded
               (try (do (boring/decode (c/hex->bytes "8a0102030405060708090a")
                                       (assoc o :max-items 3)) nil)
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                      (:type (ex-data e)))))))
      (testing "an item within budget decodes"
        (is (= [1 2] (boring/decode (c/hex->bytes "820102") (assoc o :max-items 5)))))
      (testing "and the budget does NOT accumulate across a sequence"
        ;; five [1] items: each costs 2, so a budget of 3 admits every one
        (is (= [[1] [1] [1] [1] [1]]
               (vec (boring/decode-seq (c/hex->bytes "81018101810181018101")
                                       (assoc o :max-items 3))))))
      (testing "the option is validated rather than coerced"
        (doseq [bad [-1 1.5 "x"]]
          (is (= :boring/bad-option
                 (try (do (boring/decode (c/hex->bytes "8101") (assoc o :max-items bad)) nil)
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                        (:type (ex-data e)))))
              (str "max-items " (pr-str bad))))))))

(deftest a-caller-supplied-tag-number-is-validated-on-both-platforms
  (testing "ClojureScript's `head!` range-checked its BigInt branch but assumed
            an unsigned integer in the Number branch, so a TaggedValue carrying
            a bad tag reached the arithmetic unchecked. `tag 1.5` emitted `c1 00`
            -- silently BECOMING tag 1 -- and `tag -1` emitted `ff 00`, the break
            code followed by an item, which is not one well-formed CBOR value.
            Neither threw. The JVM has rejected both since `writeTag` gained its
            check; the platforms now agree."
    (doseq [bad [-1 -40 1.5]]
      (is (= :boring/bad-tag
             (try (do (boring/encode (data/tagged-value bad 0) {:stringref false}) nil)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                    (:type (ex-data e)))))
          (str "tag " (pr-str bad) " must be refused, not emitted")))
    ;; UNINTERPRETED tag numbers deliberately: a semantic tag validates its
    ;; content on the way back (tag 0 wants an RFC 3339 string), which would be
    ;; testing the tag handler rather than the number check.
    (testing "and ordinary tags still round-trip, including large ones"
      (doseq [good [60000 1000000 39650 4294967295]]
        (let [v (data/tagged-value good "x")]
          (is (= v (boring/decode (boring/encode v {:stringref false})
                                  {:stringref false}))
              (str "tag " good)))))))

(deftest a-leap-second-is-validated-before-it-is-preserved
  (testing "both readers identified a leap second by finding `:60` after the
            second colon and returned the inert tag BEFORE the real date parser
            ran. So `9999-99-99T99:99:60Z` was accepted and handed back intact.
            Preserving a legal leap second does not make an impossible month,
            day, hour or minute legal -- the non-leap part still has to be a
            real timestamp."
    ;; c0 74 "9999-99-99T99:99:60Z"
    (is (= :boring/bad-tag-content
           (try (do (boring/decode (c/hex->bytes "c074393939392d39392d39395439393a39393a36305a")) nil)
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                  (:type (ex-data e)))))
        "an impossible date is refused even wearing a leap second")
    (testing "and a real one is still preserved, string intact"
      ;; c0 74 "2016-12-31T23:59:60Z"
      (let [v (boring/decode (c/hex->bytes "c074323031362d31322d33315432333a35393a36305a"))]
        (is (= "2016-12-31T23:59:60Z" (:value v)))))))

(deftest a-canonical-set-element-is-encoded-exactly-once
  (testing "ClojureScript staged each element to sort it and then called
            `write-value!` on the ORIGINAL element again, so a registered
            handler ran twice per element. A handler that reads a clock, a
            counter or anything else mutable could then emit bytes different
            from the ones the sort used -- output that is neither canonical nor
            sorted, from the profile whose entire promise is that equal values
            give equal bytes. The JVM was fixed first; the canonical MAP path
            has always copied its staged key bytes."
    ;; `:encode-fallback` rather than a registered tag: it is consulted from the
    ;; canonical scratch writer too (which inherits it), it is portable, and
    ;; String is dispatched before the registry so a handler for it could never
    ;; run anyway.
    (let [calls (atom 0)
          unencodable (fn [] #?(:clj (Object.) :cljs (js/Object.)))
          opts {:profile :canonical
                :encode-fallback (fn [_] (swap! calls inc) (str "v" @calls))}]
      ;; built programmatically: the reader dedups literal set elements
      (boring/encode (into #{} [(unencodable) (unencodable) (unencodable)]) opts)
      (is (= 3 @calls)
          (str "one fallback call per element, got " @calls
               " -- twice per element means the sorted bytes and the emitted"
               " bytes came from different invocations"))))
  (testing "and the bytes are still sorted and decodable"
    (let [opts {:profile :canonical}
          bs (boring/encode #{"bb" "a" "ccc"} opts)]
      (is (= #{"a" "bb" "ccc"} (boring/decode bs opts)))
      (is (= (vec bs) (vec (boring/encode #{"ccc" "a" "bb"} opts)))
          "iteration order must not change the bytes"))))

(deftest a-sealed-sequence-decodes-to-the-same-items-on-both-platforms
  (testing "JVM `write-seq!` appends a tag-27 `boring/index` frame by default,
            and JVM `decode-seq` hides it. ClojureScript had no recognition of
            it at all, so the library's OWN default output decoded to N+1 items
            there and N on the JVM -- the same portable CBOR sequence, two
            different logical results. Writing the index is JVM-only; reading
            past it must not be.

            The bytes below are a real sealed sequence of three `{1: n}` maps at
            stride 1, produced by write-seq!."
    (let [o {:stringref false}
          ;; a1 0101 / a1 0102 / a1 0103, then the tag-27 frame
          ;; produced by (write-seq! w [{1 1} {1 2} {1 3}] out {:index 1 :index-min 1})
          bs (c/hex->bytes
              (str "a10101a10102a10103d81b826c626f72696e672f696e6465788601"
                   "d84e50ffffffff000000000300000006000000"
                   "d84e5003000000010000000100000001000000"
                   "844300030341014101410184f4f5f5f5480000000000000009"))]
      (is (= [{1 1} {1 2} {1 3}] (vec (boring/decode-seq bs o)))
          "three items, not four -- the frame is metadata")))
  (testing "and an impostor sharing the name is NOT erased"
    (let [o {:stringref false}
          ;; a1 0101 then tag 27 ["boring/index", {"not" "an index"}]
          bs (c/hex->bytes (str "a10101"
                                "d81b826c626f72696e672f696e646578"
                                "a1636e6f7468616e20696e646578"))]
      (is (= 2 (count (vec (boring/decode-seq bs o))))
          "a name collision must not delete a logical item"))))

(deftest rfc-8949-appendix-f1-is-rejected-in-full
  (testing "every byte sequence RFC 8949 Appendix F.1 names as not well-formed
            must raise a typed error, on both platforms.

            `boring.wg-bad` is the CBOR working group's corpus and is NOT a
            superset of Appendix F.1 -- it was missing subkind 2 and subkind 5
            entirely, 18 of 24 reserved additional-information bytes, 8 of 10
            chunk cases, and every large-declared-length case. The subkind-2 gap
            is precisely why boring decoded `f8 18` as simple(24) for as long as
            it did. See boring.appendix-f for the full accounting."
    (doseq [[label hex] appf/cases]
      (let [r (try {:ok (boring/decode (c/hex->bytes hex))}
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                     (if (:type (ex-data e))
                       {:typed (:type (ex-data e))}
                       {:untyped (str e)}))
                   #?(:clj (catch Throwable e
                             {:raw (.getSimpleName (class e))})))]
        (is (contains? r :typed)
            (str label " (" hex ") -> "
                 (pr-str (or (:raw r) (:untyped r)
                             (str "decoded to " (pr-str (:ok r)))))))))))

(deftest every-malformed-tag-fails-typed
  (testing "a well-formed CBOR item of the WRONG SHAPE inside a tag boring
            handles specially must raise an ex-info with a :type, on both
            platforms. The byte-level fuzzers do not generate these -- they
            mutate bytes and mostly produce truncation -- so the tag handlers'
            own casts and parsers were unexercised."
    (doseq [[label hex] hostile/cases]
      (let [r (try {:ok (boring/decode (c/hex->bytes hex))}
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                     (if (:type (ex-data e))
                       {:typed (:type (ex-data e))}
                       {:untyped (str e)}))
                   #?(:clj (catch Throwable e
                             {:raw (.getSimpleName (class e))})))]
        (is (contains? r :typed)
            (str label " -> " (pr-str (or (:raw r) (:untyped r)
                                          (str "decoded to " (pr-str (:ok r)))))))))))

(deftest canonical-sets-are-deterministic
  (testing "RFC 8949 defines deterministic ordering for map KEYS and says
            nothing about tag 258, so set elements went out in iteration order
            -- and two sets that are `=` produced DIFFERENT canonical bytes.
            The profile's whole promise is that equal values encode
            identically, which is what a signature over the bytes rests on."
    #?(:clj
       (let [a (java.util.LinkedHashSet. [1 2])
             b (java.util.LinkedHashSet. [2 1])]
         (is (= a b))
         (is (= (c/bytes->hex (boring/encode a {:profile :canonical}))
                (c/bytes->hex (boring/encode b {:profile :canonical})))
             "equal sets, equal bytes")))

    (doseq [[label a b] [["clojure sets" #{:a :b :c} #{:c :b :a}]
                         ["numbers"      #{3 1 2}    #{2 3 1}]
                         ["nested"       {:s #{3 1 2}} {:s #{1 2 3}}]
                         ["mixed"        #{1 "a" :k} #{:k "a" 1}]]]
      (is (= (c/bytes->hex (boring/encode a {:profile :canonical}))
             (c/bytes->hex (boring/encode b {:profile :canonical})))
          label)))

  (testing "sets are NOT reordered outside :canonical -- iteration order is
            cheaper and nothing promises otherwise there"
    (is (some? (boring/encode #{1 2 3})))))

(deftest unknown-tags-round-trip-semantically
  (testing "a tag number is an unsigned 64-bit integer, so the top half of the
            range does not fit in a long. The reader represented 2^64-1
            correctly as a bignum and the writer called .longValue() on it,
            wrapping to -1, which writeTag then rejected as negative -- so a
            TaggedValue the reader had just produced could not be written back."
    (doseq [[label hex] [["2^64-1"     "dbffffffffffffffff01"]
                         ["2^63"       "db800000000000000001"]
                         ["2^32"       "db0000000100000000"]]]
      (let [r (try-decode hex {:stringref false})]
        (when (contains? r :ok)
          (is (= hex (:ok (try-encode (:ok r) {:stringref false})))
              (str label " must re-encode to the identical bytes"))))))

  (testing "semantic, not byte-for-byte: a TaggedValue holds the tag and the
            decoded content, not the original encoding, so a non-preferred
            input normalises. Stated because the docs used to promise
            byte-identity, which was only ever true of input already in
            boring's preferred form."
    (let [overlong "da0000ffff01"]
      (is (= "d9ffff01" (:ok (try-encode (:ok (try-decode overlong {:stringref false}))
                                         {:stringref false})))))))

(deftest platform-contracts-agree
  (testing "encode-side nesting is capped, typed. ClojureScript had no
            encode-side counter, so deeply nested input escaped as a raw JS
            RangeError where the JVM raises :boring/max-depth-exceeded -- and a
            blown stack in a browser tab is not recoverable the way a typed
            error is."
    (let [deep (reduce (fn [acc _] [acc]) [] (range 2000))
          r (try-encode deep {})]
      (is (contains? r :err))
      (is (= [1] (boring/decode (boring/encode [1] {:max-depth 4})))
          "a shallow value is unaffected by the cap")))

  (testing "a simple value is range-checked before it is written. `(< n 24)` is
            true of a negative, so (simple-value -1) emitted 0xFF -- the break
            code -- producing a document this library then rejects."
    (doseq [n [-1 256 1000]]
      (is (contains? (try-encode (data/simple-value n) {}) :err)
          (str "simple value " n " must be refused"))))

  (testing "RFC 8949 3.3 forbids ENCODING simple values 24-31, with an opt-out
            for byte-identical passthrough. The opt-out existed only on the
            JVM, so the same portable option was honoured on one platform and
            silently ignored on the other."
    (is (contains? (try-encode (data/simple-value 24) {}) :err))
    (is (contains? (try-encode (data/simple-value 24)
                               {:permit-reserved-simple-values true}) :ok)))

  (testing "an indefinite-length map rejects a duplicate key, like the definite
            form. Only the definite form checked, so the two encodings of the
            same document had different security semantics."
    ;; bf 01 01 01 02 ff -- {1: 1, 1: 2}, indefinite. The definite form of the
    ;; same document is a2 01 01 01 02.
    (is (contains? (try-decode "bf01010102ff" {}) :err) "indefinite")
    (is (contains? (try-decode "a201010102" {}) :err) "definite, for comparison")
    (is (= {1 1, 2 2} (:ok (try-decode "bf01010202ff" {})))
        "a well-formed indefinite map still decodes"))

  (testing "tag 0 requires RFC 3339, not whatever the platform's date parser
            accepts. `new Date(\"2020\")` and `new Date(\"March 5 2020\")`
            succeed in JS and are rejected on the JVM, so the two platforms
            disagreed about which documents are valid -- and the lenient side
            is the one running in a browser."
    (doseq [[label hex] [["bare year"   "c06432303230"]
                         ["prose date"  "c06c4d617263682035203230"]]]
      (is (contains? (try-decode hex {}) :err) label))
    (is (contains? (try-decode "c07818323030392d30322d31335432333a33313a33302e3132335a" {}) :ok)
        "a well-formed RFC 3339 instant still decodes"))

  (testing "a registered reader overrides a built-in tag. Consulted only in the
            default branch on ClojureScript, registering a reader for a tag
            boring knows natively was silently ignored -- so identical portable
            registry code behaved differently on the two platforms."
    (let [reg (boring/register-tag (boring/tag-registry) 37 nil nil (constantly :overridden))
          bs  (boring/encode #uuid "9682952b-fafa-4b41-8e4a-31ae948d6f08")]
      (is (= :overridden (boring/decode bs {:registry reg})))
      (is (uuid? (boring/decode bs)) "and without the registry, the built-in wins"))))

(deftest reusable-reader-accepts-options
  (testing "`decode-with` had no opts arity and `reader` took only bytes, so
            the advertised reusable-reader path could not be given a registry
            or a date type without reaching into implementation fields."
    (let [reg (boring/register-tag (boring/tag-registry) 37 nil nil (constantly :overridden))
          bs  (boring/encode #uuid "9682952b-fafa-4b41-8e4a-31ae948d6f08")
          r   (boring/reader bs {:registry reg})]
      (is (= :overridden (boring/decode-with r bs)))
      (is (= :overridden (boring/decode-with r bs {:registry reg})))
      (is (uuid? (boring/decode-with r bs {}))
          "and passing opts RESETS what a previous call configured"))))

(deftest optimised-paths-check-what-the-general-path-checks
  (testing "tag 39 has a hand-rolled reader that reads the payload straight
            into the identifier cache to avoid building a String -- and it
            skipped the UTF-8 validation along with it, so malformed UTF-8
            produced an identifier with a replacement character while the
            ordinary text path rejected the same bytes.

            An optimised branch that omits a check is exactly where a suite
            that exercises the general path has its blind spot."
    (is (contains? (try-decode "d82762c328" {}) :err)
        "tag 39 over malformed UTF-8")
    (is (contains? (try-decode "62c328" {}) :err)
        "and the ordinary text path agrees")
    (is (= (symbol "é") (:ok (try-decode "d82762c3a9" {})))
        "valid multi-byte UTF-8 under tag 39 still decodes"))

  (testing "the repeated-keyword fast path recognises D8 27 D8 19 <idx> with a
            32-bit compare, and must still validate the index"
    (is (contains? (try-decode "d827d81905" {}) :err) "index past the table")
    (is (contains? (try-decode "d827d81900" {}) :err) "no namespace open")))

(deftest decimals-convert-to-the-bigdecimal-shape
  (testing "`Decimal` is CBOR's shape (mantissa * 10^exponent); BigDecimal and
            fress's Bigdec are the other one (unscaled * 10^-scale). The sign
            of the power is inverted between them, which is a 10^4 error on an
            ordinary money amount with no symptom."
    (doseq [[hex scale] [["c482211896"                       2]   ; 1.50
                         ["c4822101"                         2]   ; 0.01
                         ["c482213a0001e23f"                 2]   ; -1234.56
                         ["c48221c248ab54a98ceb1f0adb"       2]   ; 18 digits
                         ["c48234c2493635c9adc5dea00001"    21]]] ; 1.000...001
      (let [d (boring/decode (c/hex->bytes hex))]
        (is (= scale (data/decimal-scale d)) hex)
        ;; `=` on BigDecimal ignores scale, so compare the WIRE bytes: that is
        ;; the only thing that distinguishes 1.50M from 1.5M.
        (is (= hex (c/bytes->hex
                    (boring/encode (data/decimal-from-unscaled
                                    (data/decimal-unscaled d)
                                    (data/decimal-scale d))
                                   {:stringref false})))
            (str hex " -- the conversion round-trips, scale intact")))))

  (testing "`decimal-unscaled` is ALWAYS a big integer, whatever the magnitude.

            The raw `:mantissa` is not: on ClojureScript the reader yields an
            ordinary number below 2^53 and a js/BigInt above it. A consumer
            doing exact BigInt arithmetic -- kontor.money, over fress's Bigdec,
            is the live example -- throws \"Cannot mix BigInt and other types\"
            on the SMALL case and works on the large one. A suite with
            interesting numbers passes and 1.50 fails in production."
    (doseq [hex ["c482211896" "c48221c248ab54a98ceb1f0adb"]]
      (let [u (data/decimal-unscaled (boring/decode (c/hex->bytes hex)))]
        #?(:clj  (is (instance? java.math.BigInteger u) hex)
           :cljs (is (= "bigint" (goog/typeOf u)) hex))))

    ;; The arithmetic kontor actually performs, on both magnitudes.
    #?(:cljs
       (doseq [hex ["c482211896" "c48221c248ab54a98ceb1f0adb"]]
         (let [u (data/decimal-unscaled (boring/decode (c/hex->bytes hex)))]
           (is (some? (js* "(~{} * ~{})" u (js/BigInt 2)))
               (str hex " -- exact BigInt multiply must not throw")))))))

(deftest reusable-writer-state-survives-nothing
  (testing "a byte string advances the stringref counter without adding a
            lookup key, so guarding the counter reset on `srCount > 0` left it
            set after a message whose only qualifying string was a byte string.
            The NEXT message then referenced index 1 against a table that
            begins at 0 -- output that does not decode at all, on the advertised
            reuse path."
    #?(:clj
       (let [w (boring/writer 256)]
         (doseq [prev [(byte-array [1 2 3])
                       #uuid "9682952b-fafa-4b41-8e4a-31ae948d6f08"
                       (bigint "18446744073709551616")
                       (long-array [1 2 3])]]
           (boring/encode-into! w prev)
           (is (= (c/bytes->hex (boring/encode ["abc" "abc"]))
                  (c/bytes->hex (boring/encode-into! w ["abc" "abc"])))
               (str "after " (type prev) " the writer must match a fresh one"))))))

  (testing "a typed failure must not poison a reusable writer. `enter!`
            increments depth BEFORE the try/finally that decrements it, so a
            value rejected at the cap left the counter raised -- and every
            later call, however shallow, was rejected too."
    (let [w (boring/writer 256)
          deep (reduce (fn [acc _] [acc]) [] (range 50))]
      (is (contains? (try {:ok (boring/encode-into! w deep {:max-depth 4})}
                          (catch #?(:clj Exception :cljs :default) e {:err (str e)}))
                     :err))
      (is (= (c/bytes->hex (boring/encode [1 2]))
             (c/bytes->hex (boring/encode-into! w [1 2])))
          "the next shallow value must still encode"))))

(deftest canonical-scratch-inherits-every-option
  (testing "canonical map keys are encoded by a scratch writer whose bytes are
            copied into the output. Options it did not inherit became lies
            under :profile :canonical -- and :max-depth is a SECURITY bound, so
            not inheriting it let a caller's nesting cap be bypassed by putting
            the deep value in key position."
    (is (contains? (try-encode {[[[[1]]]] 2} {:profile :canonical :max-depth 3}) :err)
        ":max-depth applies inside a key")
    (is (= (c/bytes->hex (boring/encode {[1] 2} {:profile :canonical}))
           (c/bytes->hex (boring/encode {(with-meta [1] {:m 1}) 2}
                                        {:profile :canonical :incl-metadata? false})))
        ":incl-metadata? false applies inside a key")))

(deftest legacy-canonical-order-is-its-own-profile
  (testing "clj-cbor's length-first key order was a free option, so
            {:profile :canonical-rfc7049} produced
            non-RFC-8949 bytes under the name the README gives the signing
            profile. A signer and a verifier disagreeing about a sub-option
            that does not appear in the profile name produce a mismatch nobody
            can see."
    (let [m (array-map 1000 :x "a" :y)]
      (is (contains? (try-encode m {:profile :canonical :canonical-order :rfc7049}) :err)
          "the knob is locked out of the RFC 8949 profile")
      (is (contains? (try-encode m {:canonical-order :rfc7049}) :err)
          "and out of a profile where it would have no effect, rather than
           being silently ignored")
      (is (not= (:ok (try-encode m {:profile :canonical}))
                (:ok (try-encode m {:profile :canonical-rfc7049})))
          "the two orders genuinely differ on mixed key types")
      (is (contains? (try-encode m {:profile :canonical-rfc7049}) :ok)))))

(deftest tag-39-cannot-name-a-byte-string-slot
  (testing "a byte string legally occupies a stringref slot, so a document can
            point tag 39 at one. The cast was unguarded and threw a raw
            ClassCastException (a JS type error on CLJS) through the
            typed-error contract."
    ;; namespace, 3-byte byte string at index 0, identifier referencing it
    (is (contains? (try-decode "d901008243010203d827d81900" {}) :err))))

(deftest a-registered-writer-beats-every-built-in
  (testing "doc/EXTENDING.md promises a registration wins over boring's own
            encoding for that exact type. On ClojureScript the lookup sat below
            the CONCRETE type tests, so a handler for a date, regex, UUID,
            decimal, rational or typed array was silently ignored -- the same
            defect already fixed on the JVM writer and the CLJS reader, one
            layer further down.

            A handler is registered precisely to override what boring would do
            on its own; being ignored means shipping exactly the representation
            the handler existed to replace."
    (let [reg (boring/register-tag (boring/tag-registry) 40002
                                   #?(:clj java.util.Date :cljs js/Date)
                                   (fn [_] "REPLACED") nil)
          bs  (boring/encode #?(:clj (java.util.Date. 1234567890123)
                                :cljs (js/Date. 1234567890123))
                             {:registry reg :stringref false})]
      (is (= (data/tagged-value 40002 "REPLACED")
             (boring/decode bs))
          "the handler's output is on the wire, not the built-in tag 0"))))

(deftest error-types-are-spelled-the-same-on-both-platforms
  (testing "a portable `catch` dispatches on :type, so the two platforms must
            agree on the keyword. The JVM used ::-qualified forms, which expand
            to :boring.core/..., while ClojureScript spelled the same
            conditions :boring/... -- so the same handler matched on exactly
            one platform."
    (doseq [[label opts v] [["profile conflict" {:profile :canonical :stringref true} {:a 1}]
                            ["unknown profile"  {:profile :nope} {:a 1}]]]
      (let [t (try (do (boring/encode v opts) nil)
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                     (:type (ex-data e))))]
        (is (= "boring" (namespace t)) (str label " -> " t))))))

(deftest both-canonical-orderings-round-trip
  (testing "the RFC 7049 length-first ordering had ZERO test coverage while
            doc/DESIGN.md claimed both orderings were pinned. It is reachable
            only as :profile :canonical-rfc7049 now, so it needs its own
            assertions rather than riding on :canonical's."
    (let [mixed (array-map 1000 :x "a" :y)]
      (doseq [profile [:canonical :canonical-rfc7049]]
        (is (= mixed (boring/decode (boring/encode mixed {:profile profile})))
            (str profile " round-trips"))
        (is (= (c/bytes->hex (boring/encode mixed {:profile profile}))
               (c/bytes->hex (boring/encode (array-map "a" :y 1000 :x)
                                            {:profile profile})))
            (str profile " is insertion-order independent")))
      (is (not= (c/bytes->hex (boring/encode mixed {:profile :canonical}))
                (c/bytes->hex (boring/encode mixed {:profile :canonical-rfc7049})))
          "and the two orderings genuinely differ on mixed major types"))

    (testing "sets are ordered under BOTH canonical profiles, not just the
              RFC 8949 one"
      (doseq [profile [:canonical :canonical-rfc7049]]
        (is (= (c/bytes->hex (boring/encode #{3 1 2} {:profile profile}))
               (c/bytes->hex (boring/encode #{1 2 3} {:profile profile})))
            (str profile " orders set elements"))))))

(deftest unregistered-frames-split-by-payload-shape
  (testing "an unregistered tag-27 frame decodes by the shape of its PAYLOAD,
            not by any claim about the sender. Tag 27 carries no
            record/non-record bit and could not: its content is
            [type-name, *constructor-args], so an extra element would read as
            another argument to every foreign decoder, and a frame written by
            Python carries no origin information at all."
    (let [rec (boring/encode (data/unknown-record "some.Rec" {:z 9}) {:stringref false})
          pos (boring/encode (tagged-literal (symbol "some.Pos") [1 2 3]) {:stringref false})]

      (testing "a field map keeps the map presentation"
        (let [u (boring/decode rec)]
          (is (data/unknown-record? u))
          (is (= 9 (:z u)))
          (is (= {:z 9} (into {} u)) "map ops are sound over a map payload")))

      (testing "anything else becomes a TaggedLiteral, which never promises
                map-ness -- it used to be an UnknownRecord claiming
                IPersistentMap and then throwing from keys/assoc/into"
        (let [t (boring/decode pos)]
          (is (tagged-literal? t))
          (is (= [1 2 3] (:form t)))
          (is (= "some.Pos" (str (:tag t))))))

      (testing "both re-encode to the identical frame -- passthrough must not
                hold for record-shaped payloads only"
        (is (= (c/bytes->hex rec)
               (c/bytes->hex (boring/encode (boring/decode rec) {:stringref false}))))
        (is (= (c/bytes->hex pos)
               (c/bytes->hex (boring/encode (boring/decode pos) {:stringref false})))))

      (testing "and the shared accessors read either without branching"
        (doseq [[label bs nm payload] [["map" rec "some.Rec" {:z 9}]
                                       ["positional" pos "some.Pos" [1 2 3]]]]
          (let [v (boring/decode bs)]
            (is (data/tagged-frame? v) label)
            (is (= nm (data/frame-name v)) label)
            (is (= payload (data/frame-payload v)) label)))))))

#?(:clj
   (defrecord AutoPoint [x y]))

;; Portable records for the compile-time registry. Defined ABOVE the test that
;; uses them: on ClojureScript the macro reads the compiler's analysis cache,
;; so a record defined later in the file is not there yet.
(defrecord RecA [x y])
(defrecord RecB [a b])

(deftest auto-construct-records-is-opt-in
  #?(:clj
     (let [v  {:p (->AutoPoint 1 2) :nested [(->AutoPoint 3 4)]}
           bs (boring/encode v)]
       (testing "OFF by default -- the default IS the security posture"
         (is (data/unknown-record? (:p (boring/decode bs)))))

       (testing "on, a record reconstructs without any registration, nested too"
         (let [back (boring/decode bs {:auto-construct-records? true})]
           (is (= v back))
           (is (instance? AutoPoint (:p back)))
           (is (instance? AutoPoint (first (:nested back))))))

       (testing "resolution is classForNameNonLoading, so naming a class does
                 NOT run its static initialiser -- nippy uses classForName,
                 which passes initialize=true and lets a document trigger the
                 <clinit> of any class on the classpath. The class must also be
                 an IRecord, which is narrower than nippy's create-method check.

                 A name that is not a loaded record stays inert."
         (doseq [nm ["definitely.Not.Loaded" "java.lang.Runtime" "java.lang.String"]]
           (let [back (boring/decode (boring/encode (data/unknown-record nm {:x 1}))
                                     {:auto-construct-records? true})]
             (is (data/unknown-record? back) (str nm " must stay inert")))))

       (testing "a positional payload is untouched by the flag -- there is no
                 create(IPersistentMap) to call, and it is not a record frame"
         (let [pos (boring/encode (tagged-literal (symbol "some.Pos") [1 2]))]
           (is (tagged-literal? (boring/decode pos {:auto-construct-records? true}))))))))

(deftest auto-construct-is-refused-on-cljs-not-ignored
  #?(:cljs
     (testing "the option cannot work on ClojureScript -- advanced compilation
               minifies constructor names and there is no runtime resolve -- so
               it is REFUSED. Accepting it silently would mean a .cljc codebase
               got real records on the JVM and UnknownRecords in the browser: a
               platform divergence in decoded values, which is the worst kind
               to debug and the exact defect class this suite keeps finding."
       (is (contains? (try-decode "d9010001" {:auto-construct-records? true}) :err))
       (is (contains? (try-decode "d9010001" {}) :ok)
           "and the ordinary path is unaffected"))))

(deftest compile-time-record-registry-works-on-both-platforms
  (testing "`auto-registry` resolves records when the code is COMPILED and
            emits a literal map, so nothing is looked up from wire content at
            run time. That is the only mechanism that can work on
            ClojureScript -- advanced compilation minifies constructor names
            and there is no runtime resolve -- and it is the safer one on the
            JVM too, because the constructible set is fixed at build time."
    (let [reg (records/auto-registry "boring.conformance-test")
          v   {:a (->RecA 1 2) :nested [(->RecB "x" "y")]}
          bs  (boring/encode v)]
      (is (data/unknown-record? (:a (boring/decode bs)))
          "precondition: without the registry it is a fallback")
      (let [back (boring/decode bs {:registry reg})]
        (is (= v back))
        (is (instance? RecA (:a back)))
        (is (instance? RecB (first (:nested back)))
            "nested records reconstruct too")))))

(deftest registry-for-names-its-inputs
  (testing "`registry-for` is the deterministic form: it contains exactly the
            records of the namespaces named, regardless of what else is loaded.

            `auto-registry` scans what is LOADED at expansion, and on the JVM
            loading is global -- a namespace pulled in by something unrelated
            is visible to a caller that never required it, so the same source
            can produce different registries in a REPL and in an AOT build."
    (let [reg (records/registry-for boring.conformance-test)
          v   {:a (->RecA 1 2) :b (->RecB "x" "y")}]
      (is (= v (boring/decode (boring/encode v) {:registry reg}))))))

(deftest exceptions-travel-as-data
  (testing "an ex-info in a message or a stored error value is ordinary, and
            boring had no encoding for it at all -- it threw
            :boring/unsupported-type. nippy carries a Throwable through Java
            serialization, which is the mechanism behind the deserialization
            CVE family and produces bytes no non-JVM reader can interpret.
            These carry type, message and cause as ordinary CBOR."
    (let [back (boring/decode (boring/encode (ex-info "boom" {:a 1 :k :v})))]
      (is (= "boom" (ex-message back)))
      (is (= {:a 1 :k :v} (ex-data back))))

    (testing "the cause chain survives"
      (let [back (boring/decode
                  (boring/encode (ex-info "outer" {:o 1} (ex-info "inner" {:i 2}))))]
        (is (= "inner" (ex-message (ex-cause back))))
        (is (= {:i 2} (ex-data (ex-cause back))))))

    #?(:clj
       (testing "a non-ex-info Throwable keeps its class NAME but is rebuilt as
                 an ex-info: instantiating a type named on the wire is the one
                 thing the reader does not do"
         (let [back (boring/decode (boring/encode (Exception. "plain")))]
           (is (instance? clojure.lang.ExceptionInfo back))
           (is (= "plain" (ex-message back)))
           (is (= "java.lang.Exception" (:boring/throwable-class (ex-data back)))))))

    #?(:clj
       (testing "the stack trace is deliberately NOT on the wire -- large,
                 meaningless in the receiving process, and it leaks absolute
                 source paths"
         (is (not (clojure.string/includes?
                   (String. (boring/encode (ex-info "boom" {:a 1})) "ISO-8859-1")
                   "invoke")))))))

#?(:clj
   (deftest arrays-rfc8746-does-not-cover
     (testing "RFC 8746's typed arrays are NUMERIC only, so these four had no
               encoding and threw. A plain CBOR array would lose the type --
               `(= (boolean-array [true]) [true])` is false -- which is the
               silent corruption a Character becoming a String was, not the
               widening Byte -> Long is."
       (doseq [[label v expect]
               [["boolean[]" (boolean-array [true false true]) [true false true]]
                ["char[]"    (char-array [\a \b \ç])          [\a \b \ç]]
                ["String[]"  (into-array String ["a" nil "c"]) ["a" nil "c"]]
                ["Object[]"  (object-array [1 "a" :k])         [1 "a" :k]]]]
         (let [back (boring/decode (boring/encode v))]
           (is (= (class v) (class back)) (str label " keeps its type"))
           (is (= expect (vec back)) (str label " keeps its values")))))

     (testing "char[] rides a text string, not an array of char frames -- same
               information, a fraction of the bytes, and a foreign reader sees
               something usable"
       (is (< (count (boring/encode (char-array [\a \b \c])))
              (count (boring/encode (object-array [\a \b \c]))))))))

(deftest encode-fallback-replaces-rather-than-aborting
  (testing "one unencodable value aborted the whole document. On a wire that
            is usually the wrong trade -- a message that arrives with a
            placeholder beats a message that does not arrive."
    (let [bad #?(:clj (atom 42) :cljs (js-obj "a" 1))
          doc {:ok 1 :bad bad :also-ok "yes"}]
      (is (contains? (try-encode doc {}) :err) "still throws by default")

      (testing ":placeholder keeps the good fields and names the bad type"
        (let [back (boring/decode (boring/encode doc {:encode-fallback :placeholder}))]
          (is (= 1 (:ok back)))
          (is (= "yes" (:also-ok back)))
          (is (= "boring/unencodable" (data/frame-name (:bad back))))))

      (testing "a function gets the offending value and returns a replacement"
        (is (= {:ok 1 :bad :redacted :also-ok "yes"}
               (boring/decode (boring/encode doc {:encode-fallback (fn [_] :redacted)})))))

      (testing "a fallback that returns something ALSO unencodable throws
                rather than looping forever"
        (is (contains? (try-encode doc {:encode-fallback (fn [_] bad)}) :err))))))

(deftest depth-cap-survives-repeated-failures
  (testing "the JVM writer drops the try/finally around the depth counter --
            an exception table on the hottest method in the encoder, 25.5% of
            samples -- because `write-root!` calls reset() before every encode
            and reset() zeroes depth. That makes the invariant depend on
            something a reader of writeValue cannot see, so it is pinned here."
    (let [deep (reduce (fn [acc _] [acc]) [] (range 60))
          w    (boring/writer 256)]
      (is (contains? (try-encode deep {:max-depth 8}) :err) "the cap fires")
      (dotimes [_ 4]
        (try (boring/encode-into! w deep {:max-depth 8})
             (catch #?(:clj Exception :cljs :default) _ nil)))
      (is (= (c/bytes->hex (boring/encode [1 2]))
             (c/bytes->hex (boring/encode-into! w [1 2])))
          "and a writer that failed repeatedly still matches a fresh one"))))

(deftest known-collapses-still-collapse
  (testing "types whose class deliberately does not survive. Asserted rather
            than merely documented, so a change that makes one obsolete fails
            here instead of leaving a document quietly wrong -- which is what
            happened to the Character entry this fixture used to hold."
    #?(:clj
       (doseq [[label v cls _why] f/known-type-collapses]
         (let [back (boring/decode (boring/encode v))]
           (is (= cls (class back)) (str label " -- collapses to " cls))))))

  #?(:clj
     (testing "a widening collapse still preserves the VALUE; a lossy one is
               listed because it does not"
       (doseq [[label v _cls _why] f/known-type-collapses
               :when (number? v)]
         (is (= v (boring/decode (boring/encode v)))
             (str label " -- `=` still holds")))))

  #?(:clj
     (testing ":instant-type is the documented way out of the tag-1 collapse"
       (let [i (java.time.Instant/ofEpochMilli 1234567890123)]
         (is (= i (boring/decode (boring/encode i) {:instant-type :instant})))))))

(deftest decimal-scale-survives-the-platform-boundary
  (testing "1.50M and 1.5M must stay distinct on the WIRE.

            Note this cannot be asserted with `=`: Clojure's `=` on BigDecimal
            ignores scale, so `(= 1.50M 1.5M)` is TRUE — it is `.equals` that
            compares scale. datahike's dump requirements's \"1.50M != 1.5M for us\"
            is therefore a statement about the stored representation, not about
            Clojure equality, and the round-trip property has to be stated in
            bytes."
    (let [a (:ok (try-decode "d90100c482211896" {}))   ; 1.50
          b (:ok (try-decode "d90100c482200f" {}))]    ; 1.5
      (is (= "d90100c482211896" (:ok (try-encode a {})))
          "1.50 must re-encode with scale 2")
      (is (= "d90100c482200f" (:ok (try-encode b {})))
          "1.5 must re-encode with scale 1")
      (is (not= (:ok (try-encode a {})) (:ok (try-encode b {})))
          "and the two must not produce the same bytes"))))

;; ---------------------------------------------------------------- WG corpus

(deftest cbor-wg-not-well-formed-corpus
  (testing "every vector in the CBOR working group's bad.edn must be rejected.

            This is the official corpus (cbor-wg/cbor-test-vectors), not the
            older cbor/test-vectors repo that only carries appendix_a.json."
    (doseq [{:keys [desc hex exempt-reason]} wg/cases
            :when (not exempt-reason)]
      (is (contains? (try-decode hex {}) :err)
          (str desc " (" (subs hex 0 (min 40 (count hex))) ") should be rejected")))))

(deftest cbor-wg-exempt-cases-are-deliberate
  (testing "the entries we accept under default options are a documented policy
            choice, and are still rejected once the policy is tightened"
    (doseq [{:keys [desc hex exempt-reason]} wg/cases
            :when exempt-reason]
      (is (some? exempt-reason) (str desc " must carry a reason"))
      (is (contains? (try-decode hex {:max-depth 256}) :err)
          (str desc " should be rejected at :max-depth 256")))))

(deftest cyclic-reference-tags-are-not-implemented
  (testing "tags 28/29 (shareable / shared-reference) are deliberately absent.
            Implementing them would let a document describe a cyclic value —
            cbor2's self-referential-list class — so their absence is a security
            property worth pinning, not an oversight."
    (let [r (try-decode "d81d05" {})]
      (is (contains? r :ok))
      (is (data/tagged-value? (:ok r)))
      (is (= 29 (:tag (:ok r)))))
    (let [r (try-decode "d81c81d81d00" {})]
      (is (contains? r :ok) "must decode inertly rather than building a cycle")
      (is (data/tagged-value? (:ok r))))))

;; ---------------------------------------------------------------- hostile input

(deftest malformed-counts-are-rejected-before-allocating
  (testing "a wire-supplied count must be validated against the bytes actually
            remaining, or a handful of bytes triggers a multi-gigabyte
            allocation. Found by reading hako, which has this hole in readMap
            and readList despite documenting 'bounded reads'."
    (doseq [[label hex] [["array claiming 2^32-1" "9affffffff"]
                         ["array claiming 2^31-1" "9a7fffffff"]
                         ["array claiming 100M"   "9a05f5e100"]
                         ["map claiming 500M"     "ba1dcd6500"]
                         ["byte string 1GB"       "5a40000000"]
                         ["text string 1GB"       "7a40000000"]
                         ["set claiming 2^31-1"   "d901029a7fffffff"]]]
      (let [r (try-decode hex {})]
        (is (contains? r :err)
            (str label " should be rejected, got " (pr-str (:ok r))))))))

(deftest deep-nesting-is-rejected-before-the-stack-dies
  (testing "the decoder is recursive, so a document of repeated 0x81 (\"array of
            one\") recurses once per byte. 100 KB of them threw
            StackOverflowError — an Error, which a caller's `catch Exception`
            does not stop. QCBOR and TinyCBOR avoid this by never recursing; a
            depth cap is the cheap equivalent."
    (let [nest (fn [n] (apply str (concat (repeat n "81") ["00"])))]
      (is (contains? (try-decode (nest 100000) {}) :err) "100k deep must be rejected")
      (is (contains? (try-decode (nest 2000) {}) :err) "2000 deep must be rejected")
      ;; This assertion is load-bearing and has already earned it once: an
      ;; interim reader that took every load through a MemorySegment used
      ;; ~2.5x the stack per level, dropping the real limit below this cap so
      ;; that the cap stopped being a cap. It failed INTERMITTENTLY -- the
      ;; interpreted path survives deeper than the JIT-compiled one -- so a
      ;; single green run means little here. If this flakes, the decoder's
      ;; frames grew; do not raise the stack, lower the cap or shrink them.
      (is (contains? (try-decode (nest 1023) {}) :ok) "1023 deep is within the cap")
      (testing "and the cap is configurable"
        (is (contains? (try-decode (nest 100) {:max-depth 50}) :err))
        (is (contains? (try-decode (nest 100) {:max-depth 200}) :ok))))))

(deftest truncated-input-is-rejected
  (testing "a count that is plausible but longer than the payload must fail"
    (doseq [[label hex] [["array of 5, only 2 present" "8501"]
                         ["map of 3, only 1 pair"      "a30102"]
                         ["text of 10, only 3 bytes"   "6a616263"]]]
      (let [r (try-decode hex {})]
        (is (contains? r :err)
            (str label " should be rejected, got " (pr-str (:ok r))))))))

#?(:cljs
   (deftest integral-floats-collapse-to-integers-on-cljs
     (testing "documenting the platform limit rather than hiding it: JS has one
               number type, so 1.0 IS 1 and re-encodes as an integer. Values
               with a fractional part, and -0.0, are unaffected."
       (is (= "01" (:ok (try-encode 1.0 interop-opts))))
       (is (= "00" (:ok (try-encode 0.0 interop-opts))))
       (testing "but a fractional value still encodes as a float"
         (is (= "f93e00" (:ok (try-encode 1.5 interop-opts)))))
       (testing "and negative zero keeps its sign rather than becoming 0"
         (is (= "f98000" (:ok (try-encode -0.0 interop-opts))))))))

;; ---------------------------------------------------------------- utf-8

(deftest invalid-utf8-is-rejected
  (testing "RFC 8949 §3.1 requires text strings to be valid UTF-8.
            `new String(bytes, UTF_8)` substitutes U+FFFD instead of failing,
            which silently breaks byte-identical re-export and disagrees with
            implementations that do reject — a parser differential."
    (doseq [[label hex] [["invalid continuation" "62c328"]
                         ["lone surrogate"       "63eda080"]
                         ["overlong"             "62c080"]
                         ["truncated sequence"   "62c3"]]]
      (let [r (try-decode hex {})]
        (is (contains? r :err) (str label " should be rejected, got " (pr-str (:ok r))))))
    (testing "and valid multi-byte UTF-8 still decodes"
      (is (= "héllo wörld 𐅑" (:ok (try-decode (:ok (try-encode "héllo wörld 𐅑" {})) {})))))))

;; ---------------------------------------------------------------- error model

(deftest errors-carry-a-queryable-type
  (testing "datahike's dump requirements ask for a recoverable, identifiable error
            rather than an exception from deep in the decode loop"
    (letfn [(err-type [hex]
              (try (boring/decode (c/hex->bytes hex) {})
                   nil
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                     (:type (ex-data e)))))]
      (is (= :boring/invalid-utf8       (err-type "62c328")))
      (is (= :boring/truncated-input    (err-type "c0")))
      (is (= :boring/reserved-info      (err-type "1c")))
      (is (= :boring/unexpected-break   (err-type "ff")))
      (is (= :boring/bad-count          (err-type "9affffffff")))
      (is (= :boring/duplicate-map-key  (err-type "a201020103")))
      (is (= :boring/bad-tag-content    (err-type "c160"))))
    (testing "and an unregistered tag names the tag, so an importer can say
              'this dump requires tag N'"
      (let [d (try (boring/decode (boring/encode (data/tagged-value 55555 "x"))
                                  {:tolerate-unknown-tags false})
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                     (ex-data e)))]
        (is (= :boring/unregistered-tag (:type d)))
        (is (= 55555 (:tag d)))))))

;; ---------------------------------------------------------------- streaming

#?(:clj
   (deftest streaming-round-trip
     (testing "a chunk is a CBOR sequence: consecutive top-level items, each
               independently decodable (datahike's dump requirements)"
       (let [vals (vec (for [i (range 300)]
                         {:e (+ 100 i) :a :user/name :v (str "person-" i)}))
             out (java.io.ByteArrayOutputStream.)
             w (boring/writer 4096)
             n (boring/write-seq! w vals out)
             bs (.toByteArray out)]
         (is (pos? n))
         (is (= n (alength bs)))
         (is (= vals (vec (boring/decode-seq bs))))
         (testing "and decoding is lazy"
           (is (= (first vals) (first (boring/decode-seq bs)))))))))

#?(:clj
   (deftest truncated-chunk-yields-items-before-the-cut
     (testing "losing the tail of a chunk must not lose the items already written"
       (let [out (java.io.ByteArrayOutputStream.)
             w (boring/writer 4096)]
         (boring/write-seq! w [{:a 1} {:b 2} {:c 3}] out)
         (let [full (.toByteArray out)
               cut (java.util.Arrays/copyOf full (- (alength full) 3))
               s (boring/decode-seq cut)]
           (is (= {:a 1} (first s)))
           (is (= {:b 2} (second s)))
           (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                        (doall s))))))))

;; ---------------------------------------------------------------- report

(defn- tally [pred coll] (count (filter pred coll)))

#?(:clj
 ;; Reporting helper, JVM-only: it uses clojure.core/format, which does not
 ;; exist in ClojureScript. Previously it compiled on CLJS with an
 ;; "undeclared Var format" warning, so if any of these branches had ever
 ;; been reached there it would have thrown instead of printing.
   (defn gap-report
     "Human-readable summary of where the implementation stands against the spine.
   More useful than 82 individual assertion failures while building out."
     []
     (let [dec-results
           (for [{:keys [hex value diag]} v/appendix-a]
             (let [r (try-decode hex interop-opts)]
               {:hex hex :diag diag
                :pass (and (contains? r :ok) (c/equiv? (c/->expected value) (:ok r)))
                :err (:err r)
                :got (:ok r)}))

           enc-results
           (for [{:keys [hex value roundtrip encode-forbidden encoding-differs]} v/appendix-a
                 :when (and roundtrip (not encode-forbidden) (not encoding-differs)
                            (or (c/width-checkable?) (not (integral-float-marker? value))))]
             (let [r (try-encode (c/->expected value) interop-opts)]
               {:hex hex :pass (= hex (:ok r)) :err (:err r) :got (:ok r)}))

           type-results
           (for [[label value] f/type-identity]
             (let [enc (try-encode value {})
                   r (when (:ok enc) (try-decode (:ok enc) {}))]
               {:label label
                :pass (boolean (and r (contains? r :ok) (c/type-identical? value (:ok r))))
                :err (or (:err enc) (:err r))
                :got (:ok r)}))]

       (println)
       (println "=== CONFORMANCE GAP REPORT ===")
       (println (str "Appendix A decode : " (tally :pass dec-results) "/" (count dec-results)))
       (println (str "Appendix A encode : " (tally :pass enc-results) "/" (count enc-results)))
       (println (str "Type identity     : " (tally :pass type-results) "/" (count type-results)))
       (println)
       (println "--- failing decode ---")
       (doseq [{:keys [hex diag err got]} (remove :pass dec-results)]
         (println (format "  %-30s %s" hex (or err (str "got " (pr-str got)) diag))))
       (println)
       (println "--- failing encode ---")
       (doseq [{:keys [hex err got]} (remove :pass enc-results)]
         (println (format "  %-30s %s" hex (or err (str "got " got)))))
       (println)
       (println "--- failing type identity ---")
       (doseq [{:keys [label err got]} (remove :pass type-results)]
         (println (format "  %-18s %s" label (or err (str "got " (pr-str got))))))
       {:decode [(tally :pass dec-results) (count dec-results)]
        :encode [(tally :pass enc-results) (count enc-results)]
        :types  [(tally :pass type-results) (count type-results)]})))

#?(:clj (defn -main [& _] (gap-report)))

;; ---------------------------------------------------------------- registry
;;
;; The registry had no test coverage at all before this, on either platform,
;; despite being the public extension point. These pin the contract that
;; `register-record` and `register-records` are equivalent, that a registry is
;; a VALUE (registering never mutates the one you passed), and that an
;; unregistered record still survives as an inert value.

(defrecord RegPoint [x y])

(def ^:private point-name
  ;; The name a RegPoint carries on the wire, as boring derives it. Asserted
  ;; rather than hard-coded so the test does not encode the JVM's spelling and
  ;; then fail on ClojureScript, where the munging differs.
  (data/record-type-name (->RegPoint 1 2)))

(deftest unregistered-record-survives
  (testing "no registration at all: the value comes back inert but complete,
            carrying its wire name and fields, and re-encodes identically"
    (let [bs   (boring/encode (->RegPoint 1 2))
          back (boring/decode bs)]
      (is (not (instance? RegPoint back)) "not reconstructed without a ctor")
      (is (= point-name (data/record-type back)) "but the name survives")
      (is (= {:x 1 :y 2} (data/record-fields back)) "and so do the fields")
      (is (= (c/bytes->hex bs) (c/bytes->hex (boring/encode back)))
          "and it re-encodes to the same bytes, so passthrough is lossless"))))

(deftest register-record-reconstructs
  (testing "one registration is enough, and only the READ side needs it --
            writing a record never requires registration"
    (let [reg (boring/register-record (boring/tag-registry) point-name map->RegPoint)]
      (is (= (->RegPoint 1 2)
             (boring/decode (boring/encode (->RegPoint 1 2)) {:registry reg}))))))

(deftest register-records-equals-threading-register-record
  (testing "the bulk form is exactly the fold, for one entry and for many"
    (let [ctors {point-name map->RegPoint}
          one   (boring/register-record  (boring/tag-registry) point-name map->RegPoint)
          bulk  (boring/register-records (boring/tag-registry) ctors)
          v     (->RegPoint 3 4)
          bs    (boring/encode v)]
      (is (= v (boring/decode bs {:registry one})))
      (is (= v (boring/decode bs {:registry bulk})) "bulk registers the same thing"))

    (testing "and with several entries, against the same registry threaded"
      (let [names  (mapv #(str "reg.T" %) (range 12))
            ctors  (into {} (map (fn [n] [n map->RegPoint])) names)
            folded (reduce-kv boring/register-record (boring/tag-registry) ctors)
            bulk   (boring/register-records (boring/tag-registry) ctors)]
        (doseq [n names]
          (let [bs (boring/encode (data/unknown-record n {:x 1 :y 2}))]
            (is (= (->RegPoint 1 2) (boring/decode bs {:registry folded})))
            (is (= (->RegPoint 1 2) (boring/decode bs {:registry bulk}))
                (str "bulk and folded agree for " n))))))

    (testing "symbol keys work directly -- incognito's shape"
      (let [reg (boring/register-records (boring/tag-registry)
                                         {(symbol point-name) map->RegPoint})]
        (is (= (->RegPoint 5 6)
               (boring/decode (boring/encode (->RegPoint 5 6)) {:registry reg})))))))

(deftest a-registry-is-a-value
  (testing "registering returns a NEW registry and leaves the old one alone --
            on both platforms, so registration code can be threaded in .cljc"
    (let [empty-reg (boring/tag-registry)
          with-one  (boring/register-records empty-reg {point-name map->RegPoint})
          bs        (boring/encode (->RegPoint 7 8))]
      (is (= (->RegPoint 7 8) (boring/decode bs {:registry with-one})))
      (is (not (instance? RegPoint (boring/decode bs {:registry empty-reg})))
          "the registry we started from is unchanged"))))

;; ---------------------------------------------------------- platform parity
;;
;; Every case below was found by reading the JVM reader against the CLJS one and
;; asking "which documents does exactly one of these accept?". A parser
;; differential is a defect of the FORMAT, not of either implementation: the
;; whole claim boring makes is that a document means one thing, and a reader
;; that admits what the other refuses breaks that claim whichever value the two
;; sides go on to produce.

(defn- err-type
  "The `:type` of the typed error `f` raises, or `[:ok value]` when it returns."
  [f]
  (try [:ok (f)]
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (:type (ex-data e)))))

(defn- dec-hex
  ([hex] (dec-hex hex nil))
  ([hex opts] (boring/decode (c/hex->bytes hex) opts)))

(defn- hex-byte [n]
  (let [h #?(:clj (Integer/toHexString n) :cljs (.toString n 16))]
    (if (== 1 (count h)) (str "0" h) h)))

(deftest byte-strings-are-compared-by-content-not-identity
  (testing "two byte strings with the same bytes are ONE CBOR data item, so a
            map holding both is invalid -- but a host byte array compares by
            IDENTITY on both platforms, so this decoded to a two-entry map
            carrying the very duplicate the check exists to reject"
    ;; {h'01': 1, h'01': 2}
    (is (= :boring/duplicate-map-key (err-type #(dec-hex "a2410101410102")))))

  (testing "and above the array-map threshold, where the small-map scan that
            catches host-equal keys does not run at all"
    ;; nine pairs: seven integer keys, then h'01' twice
    (let [hex (str "a9"
                   (apply str (for [i (range 7)] (str "18" (hex-byte (+ 100 i)) "01")))
                   "410101410102")]
      (is (= :boring/duplicate-map-key (err-type #(dec-hex hex))))))

  (testing "tag 258 the same way: two independently allocated but byte-equal
            elements are one element, and the set silently kept both"
    ;; 258([h'01', h'01'])
    (is (= :boring/duplicate-set-element (err-type #(dec-hex "d901028241014101")))))

  (testing "byte strings that genuinely differ are still two distinct keys"
    (is (= 2 (count (dec-hex "a2410101410202"))))))

(deftest lenient-utf8-is-lenient-on-both-platforms
  (testing ":validate-utf8 false replaces the malformed byte rather than raising
            -- ClojureScript still routed it through a fatal TextDecoder, so an
            option whose entire purpose is to accept this input produced a raw,
            untyped host error there and a replacement character on the JVM"
    (is (= "�" (dec-hex "61ff" {:validate-utf8 false}))))
  (testing "and the default still refuses it, with the typed error"
    (is (= :boring/invalid-utf8 (err-type #(dec-hex "61ff"))))))

(deftest rfc-9581-duration-rules-hold-on-both-platforms
  ;; ClojureScript keeps tag 1002 an inert TaggedValue -- there is no
  ;; java.time.Duration here -- but "we do not convert it" is not a licence to
  ;; accept a shape the JVM rejects.
  (testing "an unsigned key other than the base is CRITICAL (RFC 9581 3)"
    (is (= :boring/bad-tag-content (err-type #(dec-hex "d903eaa201050205")))))
  (testing "at most one decimally scaled fraction key (RFC 9581 3.3)"
    (is (= :boring/bad-tag-content (err-type #(dec-hex "d903eaa3010528012b01")))))
  (testing "a negative fraction is not a duration"
    (is (= :boring/bad-tag-content (err-type #(dec-hex "d903eaa201052820")))))
  (testing "finer than a nanosecond is refused rather than silently truncated"
    (is (= :boring/bad-tag-content (err-type #(dec-hex "d903eaa201052b01")))))
  (testing "keys 4 and 5 -- decimal-fraction and bigfloat bases -- are refused
            by name rather than reported as a missing base value"
    (is (= :boring/bad-tag-content (err-type #(dec-hex "d903eaa104820001")))))
  (testing "but every OTHER negative key is elective and ignored, which the RFC
            requires: {1: 5, -1: 0} is timescale UTC, the default"
    (is (not= :boring/bad-tag-content (err-type #(dec-hex "d903eaa201052000")))))
  (testing "and an ordinary seconds+nanoseconds duration still decodes"
    (is (not= :boring/bad-tag-content (err-type #(dec-hex "d903eaa201052801"))))))

(deftest tag-27-markers-accept-the-same-shapes-on-both-platforms
  ;; The JVM builds a boolean[]/String[]/Object[] and so rejects what cannot go
  ;; in one. ClojureScript reached all of them through `(vec argument)`, which
  ;; turns nil into [] and a string into a vector of characters.
  (let [marker (fn [nm arg-hex]
                 (str "d81b82" (hex-byte (+ 0x60 (count nm)))
                      (apply str (map #(hex-byte #?(:clj (int %) :cljs (.charCodeAt % 0)))
                                      nm))
                      arg-hex))]
    (testing "java/boolean-array holds booleans, not integers"
      (is (= :boring/bad-tag-content
             (err-type #(dec-hex (marker "java/boolean-array" "8101"))))))
    (testing "java/object-array wraps an array, and null is not one"
      (is (= :boring/bad-tag-content
             (err-type #(dec-hex (marker "java/object-array" "f6"))))))
    (testing "java/string-array holds strings"
      (is (= :boring/bad-tag-content
             (err-type #(dec-hex (marker "java/string-array" "8101"))))))
    (testing "java/char-array wraps a text string"
      (is (= :boring/bad-tag-content
             (err-type #(dec-hex (marker "java/char-array" "01"))))))
    (testing "clojure/ex-info data must be a map"
      (is (= :boring/bad-tag-content
             (err-type #(dec-hex (marker "clojure/ex-info" "8361610101"))))))
    (testing "java/throwable names a class, as a string"
      (is (= :boring/bad-tag-content
             (err-type #(dec-hex (marker "java/throwable" "8301f6f6"))))))
    (testing "incomparable sorted-set elements are a TYPED error, not whatever
              the host's compare happens to throw"
      (is (= :boring/bad-tag-content
             (err-type #(dec-hex (marker "clojure/sorted-set" "82016161"))))))
    (testing "a null payload is an EMPTY sorted set on both platforms -- the JVM
              admits it, so this is parity and not an oversight"
      (is (= #{} (dec-hex (marker "clojure/sorted-set" "f6")))))
    (testing "and the valid shapes still decode"
      (is (= [true false] (vec (dec-hex (marker "java/boolean-array" "82f5f4")))))
      (is (= ["a"] (vec (dec-hex (marker "java/string-array" "816161"))))))))

(deftest duplicate-detection-does-not-depend-on-decoder-cache-state
  (testing "whether a repeated key is caught must not depend on how many OTHER
            identifiers were decoded between its two occurrences. ClojureScript
            interns identifiers in a bounded cache that clears wholesale, so a
            shortcut that trusted `identical?` for keywords decoded
            {:a 1, :zzz <5000 keywords>, :a 2} to a THREE-entry array map
            holding :a twice -- a corrupt map, not a missed error"
    (let [filler (into {} (map (fn [i] [(keyword (str "k" i)) i])) (range 5000))
          enc    #(boring/encode % {:stringref false})
          parts  [(c/hex->bytes "a3")                       ; map, 3 pairs
                  (enc :a) (enc 1)
                  (enc :zzz) (enc filler)
                  (enc :a) (enc 2)]
          bs     #?(:clj (let [bos (java.io.ByteArrayOutputStream.)]
                           (doseq [^bytes p parts] (.write bos p))
                           (.toByteArray bos))
                    :cljs (let [total (reduce + (map #(.-length %) parts))
                                out (js/Uint8Array. total)]
                            (loop [ps (seq parts) off 0]
                              (if ps
                                (do (.set out (first ps) off)
                                    (recur (next ps) (+ off (.-length (first ps)))))
                                out))))]
      (is (= :boring/duplicate-map-key
             (err-type #(boring/decode bs {:stringref false})))))))
