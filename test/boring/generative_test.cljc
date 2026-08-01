(ns boring.generative-test
  "Property-based tests. The fixture suite can only assert what someone thought
  to write down; these explore the space around it.

  Three properties:
    1. round-trip preserves the value
    2. encoding is deterministic (the signable-dump property)
    3. arbitrary bytes never escape as an untyped failure — a cheap fuzzer,
       since a decoder for untrusted input must fail predictably on garbage"
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring]
            [boring.conformance :as c]
            [boring.data :as data]))

;; ---------------------------------------------------------------- generators

(def gen-ident
  (gen/one-of [gen/keyword gen/keyword-ns gen/symbol gen/symbol-ns]))

(def gen-scalar
  (gen/one-of
   [(gen/return nil)
    gen/boolean
    gen/large-integer
    (gen/double* {:infinite? true :NaN? true})
    gen/string-ascii
     ;; Non-ASCII too, but excluding the surrogate range: a Clojure string can
     ;; hold an unpaired surrogate, which has no valid UTF-8 encoding at all, so
     ;; it is not a round-trip failure so much as an unrepresentable input.
    (gen/fmap #(apply str %)
              (gen/vector (gen/fmap char (gen/one-of [(gen/choose 32 0xD7FF)
                                                      (gen/choose 0xE000 0xFFFD)]))
                          0 20))
    gen-ident
    (gen/fmap #(#?(:clj byte-array :cljs js/Uint8Array.from) %)
              (gen/vector (gen/choose -128 127) 0 12))]))

;; Tag-carrying types. These were absent from the generator entirely — 15 of
;; the 29 types the writer dispatches on were only ever covered by fixed
;; fixtures, which is exactly where subtle codec bugs hide.
;;
;; Excluded deliberately, with reasons:
;;   Instant              decodes as Date under the default :instant-type
;;                        (pinned by boring.fixtures/known-type-collapses)
;;   Integer/Short/Byte   widen to Long, so `=` still holds but they add nothing

(def gen-tagged
  (gen/one-of
   [#?@(:clj
        [(gen/fmap (fn [[a b]] (java.util.UUID. a b))
                   (gen/tuple gen/large-integer gen/large-integer))
          ;; java.util.Date has millisecond resolution; keep well inside the
          ;; range where Date.from cannot overflow.
         (gen/fmap #(java.util.Date. (long %)) (gen/choose -1e12 1e12))
         (gen/fmap #(clojure.lang.BigInt/fromBigInteger
                     (java.math.BigInteger. (str %)))
                   (gen/large-integer* {:min -1e18 :max 1e18}))
          ;; BigDecimal with a VARYING SCALE — 1.50M and 1.5M must stay distinct
         (gen/fmap (fn [[u sc]] (java.math.BigDecimal. (java.math.BigInteger/valueOf u)
                                                       (int sc)))
                   (gen/tuple gen/large-integer (gen/choose -20 20)))
         (gen/fmap (fn [[n d]] (/ (bigint n) (bigint (if (zero? d) 1 d))))
                   (gen/tuple gen/large-integer (gen/choose 1 1000)))
         (gen/fmap long-array (gen/vector gen/large-integer 0 8))
         (gen/fmap double-array (gen/vector (gen/double* {:NaN? false :infinite? false}) 0 8))
         (gen/fmap int-array (gen/vector (gen/choose -2147483648 2147483647) 0 8))
         (gen/fmap float-array (gen/vector (gen/fmap float (gen/choose -1000 1000)) 0 8))
         (gen/fmap short-array (gen/vector (gen/choose -32768 32767) 0 8))]
        :cljs
        [(gen/fmap (fn [_] (random-uuid)) (gen/return nil))
         (gen/fmap #(js/Date. %) (gen/choose -1e12 1e12))
         (gen/fmap #(js/BigInt (str %)) (gen/large-integer* {:min -1e18 :max 1e18}))])
     ;; CBOR types with no Clojure counterpart
    (gen/fmap data/simple-value (gen/one-of [(gen/choose 0 19) (gen/choose 32 255)]))
    (gen/fmap (fn [[t v]] (data/tagged-value t v))
              (gen/tuple (gen/choose 50000 60000) gen/string-ascii))
    (gen/fmap (fn [[t m]] (data/unknown-record t m))
              (gen/tuple (gen/elements ["a.b.C" "x.Y" "some_ns.Rec"])
                         (gen/map gen/keyword gen/large-integer {:max-elements 4})))]))

(def gen-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of
      [(gen/vector inner 0 6)
       (gen/list inner)
       (gen/set (gen/one-of [gen/large-integer gen-ident gen/string-ascii]) {:max-elements 6})
       (gen/map (gen/one-of [gen-ident gen/string-ascii gen/large-integer]) inner
                {:max-elements 6})]))
   (gen/one-of [gen-scalar gen-tagged])))

;; ---------------------------------------------------------------- properties

;; `gen-value` reaches tag types only incidentally — collections dominate it, so
;; in 400 samples UUID, Date, double[] and SimpleValue never appeared at all.
;; These properties generate them densely, on their own and nested one level, so
;; every trial exercises a tag path.

(def gen-tagged-nested
  (gen/one-of
   [gen-tagged
    (gen/vector gen-tagged 1 5)
    (gen/map (gen/one-of [gen/keyword gen/string-ascii]) gen-tagged {:max-elements 4})
    (gen/fmap #(hash-map :wrapped %) gen-tagged)]))

(defspec round-trip-tag-types 1000
  (prop/for-all [v gen-tagged-nested]
                (c/equiv? v (boring/decode (boring/encode v)))))

(defspec round-trip-tag-types-preserves-TYPE 1000
  ;; Stronger than equiv?: for tag types the whole point is that the type
  ;; survives. Scale on a BigDecimal, element type on a typed array, Date vs
  ;; number — all of it must come back.
  (prop/for-all [v gen-tagged]
                (c/type-identical? v (boring/decode (boring/encode v)))))

(defspec round-trip-tag-types-with-shapes 500
  (prop/for-all [v gen-tagged-nested]
                (let [o {:shapes true}]
                  (c/equiv? v (boring/decode (boring/encode v o) o)))))

(defspec tag-types-encode-deterministically 500
  (prop/for-all [v gen-tagged-nested]
                (c/same-bytes? (boring/encode v) (boring/encode v))))

(defspec round-trip-preserves-value 300
  (prop/for-all [v gen-value]
                (c/equiv? v (boring/decode (boring/encode v)))))

(defspec round-trip-preserves-value-without-stringref 200
  (prop/for-all [v gen-value]
                (let [opts {:stringref false}]
                  (c/equiv? v (boring/decode (boring/encode v opts) opts)))))

;; Canonical is a NORMALISATION, and normalisation is lossy on purpose.
;;
;; Asserting `(equiv? v (decode (encode v)))` here was asserting something the
;; profile does not promise and must not: RFC 8949 requires a bignum that fits
;; in a basic integer to be written as one, and a float to be written in its
;; shortest round-tripping form. So a BigInt comes back a number and a Double
;; may come back narrower. On the JVM `(= 0N 0)` hides this; on ClojureScript
;; `(= (js/BigInt 0) 0)` is false and the property failed, correctly.
;;
;; The property that IS true, and is the one worth having, is that canonical
;; encoding reaches a FIXPOINT after one pass: whatever normalisation it
;; applies, applying it again changes nothing. That is exactly the guarantee a
;; signature depends on -- verify the bytes, re-encode the value, get the same
;; bytes -- and it is strictly stronger than a type-preserving round trip would
;; be, because it also pins the bytes.
(defspec canonical-encoding-reaches-a-fixpoint 200
  (prop/for-all [v gen-value]
                (let [opts {:profile :canonical}
                      bs1 (boring/encode v opts)
                      once (boring/decode bs1 opts)
                      bs2 (boring/encode once opts)]
                  (and (c/same-bytes? bs1 bs2)
                       (c/equiv? once (boring/decode bs2 opts))))))

(defspec round-trip-preserves-value-with-shapes 200
  (prop/for-all [v gen-value]
                (let [opts {:shapes true}]
                  (c/equiv? v (boring/decode (boring/encode v opts) opts)))))

(defspec shaped-and-plain-decode-alike 200
  ;; A shaped encoding must decode to exactly what the plain one does — the
  ;; extension is a wire optimisation, not a semantic change.
  (prop/for-all [v gen-value]
                (c/equiv? (boring/decode (boring/encode v {:shapes true}))
                          (boring/decode (boring/encode v)))))

(defspec encoding-is-deterministic 200
  (prop/for-all [v gen-value]
                (c/same-bytes? (boring/encode v) (boring/encode v))))

(defspec canonical-encoding-is-order-independent 100
  (prop/for-all [kvs (gen/vector (gen/tuple gen-ident gen/large-integer) 0 8)]
    ;; Build the map first, THEN reverse its entries. Reversing the raw pairs
    ;; is wrong when a key repeats — last-wins makes the two maps genuinely
    ;; different, which is a flaw in the test rather than in the encoder. The
    ;; JVM run passed only because its seed never generated a duplicate.
                (let [opts {:profile :canonical}
                      m1 (into {} kvs)
                      m2 (into {} (reverse (vec m1)))]
                  (and (= m1 m2)
                       (c/same-bytes? (boring/encode m1 opts) (boring/encode m2 opts))))))

(defn- decode-outcome
  "Decode `bs`, classifying the result. Anything other than a value or a typed
  boring error is a defect — including StackOverflowError and OutOfMemoryError,
  which is why this catches Throwable rather than Exception."
  [bs]
  (try
    {:ok (boring/decode bs)}
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
      (if (some-> (ex-data e) :type namespace (= "boring"))
        {:typed (:type (ex-data e))}
        {:untyped (pr-str (ex-data e))}))
    (catch #?(:clj Throwable :cljs :default) e
      {:untyped (str #?(:clj (.getName (class e)) :cljs (type e)) ": "
                     #?(:clj (.getMessage ^Throwable e) :cljs (.-message e)))})))

(defspec arbitrary-bytes-never-fail-untyped 500
  (prop/for-all [bs (gen/vector (gen/choose -128 127) 0 64)]
                (let [r (decode-outcome #?(:clj (byte-array bs) :cljs (js/Uint8Array.from (clj->js bs))))]
                  (not (contains? r :untyped)))))

(defspec valid-prefixes-never-fail-untyped 300
  (prop/for-all [v gen-value
                 drop-n gen/nat]
    ;; Truncate a legitimately-encoded value at an arbitrary point: this reaches
    ;; deeper into the decoder than random bytes usually do.
                (let [full (boring/encode v)
                      n (max 0 (- #?(:clj (alength full) :cljs (.-length full)) (mod drop-n 32)))
                      cut #?(:clj (java.util.Arrays/copyOf full n)
                             :cljs (.slice full 0 n))]
                  (not (contains? (decode-outcome cut) :untyped)))))

(deftest generative-summary
  (is true "specs above carry the assertions"))
