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
            [boring.data :as data]
            #?(:clj [boring.nav])))

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

;; ---------------------------------------------------------------- :archival
;;
;; :archival is the profile a dump uses: sorted map keys AND fixed-width floats.
;; It must satisfy everything :canonical does for determinism, while satisfying
;; the one thing :canonical cannot -- JVM float width survives. Both halves are
;; asserted, because either alone is already provided by an existing profile and
;; would not need a new one.

(defspec archival-encoding-is-order-independent 100
  (prop/for-all [kvs (gen/vector (gen/tuple gen-ident gen/large-integer) 0 8)]
                (let [opts {:profile :archival}
                      m1 (into {} kvs)
                      m2 (into {} (reverse (vec m1)))]
                  (and (= m1 m2)
                       (c/same-bytes? (boring/encode m1 opts) (boring/encode m2 opts))))))

(defspec archival-encoding-reaches-a-fixpoint 200
  ;; The signable-dump guarantee: verify the bytes, re-encode the value, get the
  ;; same bytes. Same property :canonical has, and the reason :archival exists at
  ;; all rather than just using :interop, which preserves insertion order.
  (prop/for-all [v gen-value]
                (let [opts {:profile :archival}
                      bs1 (boring/encode v opts)
                      once (boring/decode bs1 opts)
                      bs2 (boring/encode once opts)]
                  (and (c/same-bytes? bs1 bs2)
                       (c/equiv? once (boring/decode bs2 opts))))))

#?(:clj
   (deftest archival-preserves-float-width
     ;; datahike #633: a :db.type/double came back a Float because the codec
     ;; narrowed on encode. Under :canonical it still would -- RFC 8949 4.2.2
     ;; mandates shortest form -- which is exactly why a dump cannot use that
     ;; profile and why this test names both.
     (let [A {:profile :archival}
           C {:profile :canonical}
           class-of (fn [v opts] (class (boring/decode (boring/encode v opts) opts)))]
       (doseq [v [(double 0.0) (double 1.5) (double 2.0) (double 65504.0)
                  (double ##NaN) (double ##Inf) (double ##-Inf)]]
         (is (= Double (class-of v A))
             (str v " must stay a Double under :archival"))
         (is (= Float (class-of v C))
             (str v " narrows under :canonical — the reason :archival exists")))
       (doseq [v [(float 0.0) (float 1.5)]]
         (is (= Float (class-of v A)) (str v " must stay a Float under :archival"))))))

(deftest archival-emits-no-extensions
  ;; A dump is read by foreign implementations years later; stringref (tags
  ;; 25/256) is a schmorp extension most of them do not implement, so emitting
  ;; it would defeat the point of choosing CBOR.
  (let [bs (boring/encode {:a "repeated" :b "repeated"} {:profile :archival})]
    (is (not= [0xd9 0x01 0x00]
              (mapv #(bit-and % 0xff) (take 3 (seq bs))))
        "no tag 256 stringref namespace")))

(deftest every-profile-exists-on-both-platforms
  ;; `profile-defaults` is written out twice — once in boring/core.clj and once
  ;; in boring/core.cljs — so a profile added to one and forgotten in the other
  ;; compiles fine and fails at runtime, on one platform only. That is precisely
  ;; what happened when :archival was added: the JVM suite was green and the
  ;; ClojureScript suite raised :boring/unknown-profile three times.
  ;;
  ;; This list is the contract. Adding a profile means adding it here, which
  ;; fails on whichever platform has not been updated.
  (doseq [p [:clojure :interop :archival :canonical :canonical-rfc7049]]
    (is (some? (boring/encode {:a 1 :b (double 2.0)} {:profile p}))
        (str p " must be a known profile on this platform")))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (boring/encode {:a 1} {:profile :no-such-profile}))
      "and an unknown one must still be refused"))

(deftest archival-locks-the-bits-that-define-it
  ;; Both bits ARE the profile. Allowing either to be overridden would just be
  ;; :canonical or :interop under another name, which is the ambiguity the
  ;; locking exists to prevent.
  (doseq [conflicting [{:profile :archival :float-policy :shortest}
                       {:profile :archival :canonical false}
                       {:profile :archival :stringref true}]]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (boring/encode {:a 1} conflicting))
        (str (pr-str conflicting) " must be refused"))))

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

;; ------------------------------------------------- skipValue vs the decoder
;;
;; `skipValue` is the inner loop of lazy navigation: it walks past a value
;; without building it. That makes it a SECOND implementation of CBOR's
;; structure, and the failure mode of a second implementation is silent -- a
;; skip that lands one byte off does not throw, it returns a plausible wrong
;; value from the wrong offset.
;;
;; So the decoder is the oracle. Whatever `read()` consumes, `skipValue()` must
;; consume exactly, over every shape the generators can produce and under both
;; stringref settings -- stringref being the case where skipping cannot ignore
;; string contents, because a later reference is an index into a table that
;; skipped strings still have to populate.

#?(:clj
   (defn- skip-end
     "Byte offset `skipValue` leaves the cursor at, from a fresh reader."
     [^bytes bs]
     (let [r (org.replikativ.boring.Reader. bs)]
       (.skipValue r)
       (.position r))))

#?(:clj
   (defn- read-end
     "Byte offset `read` leaves the cursor at -- the oracle."
     [^bytes bs]
     (let [r (org.replikativ.boring.Reader. bs)]
       (.read r)
       (.position r))))

#?(:clj
   (defspec skip-consumes-exactly-what-decode-consumes 500
     (prop/for-all [v gen-value]
                   (let [bs (boring/encode v)]
                     (= (skip-end bs) (read-end bs) (alength bs))))))

#?(:clj
   (defspec skip-consumes-exactly-what-decode-consumes-without-stringref 500
     (prop/for-all [v gen-value]
                   (let [bs (boring/encode v {:stringref false})]
                     (= (skip-end bs) (read-end bs) (alength bs))))))

#?(:clj
   (defspec skip-agrees-on-tag-types 500
     (prop/for-all [v gen-tagged-nested]
                   (let [bs (boring/encode v)]
                     (= (skip-end bs) (read-end bs) (alength bs))))))

#?(:clj
   (defspec skip-agrees-on-shaped-encodings 300
     (prop/for-all [v gen-value]
                   (let [bs (boring/encode v {:shapes true})]
                     (= (skip-end bs) (read-end bs) (alength bs))))))

;; Skipping the FIRST of two concatenated items must land exactly on the
;; second, which is the property navigation actually depends on -- landing on
;; the wrong offset is how a wrong-but-plausible value gets returned.
#?(:clj
   (defspec skip-lands-on-the-next-item 300
     (prop/for-all [a gen-value b gen-value]
                   (let [^bytes ba (boring/encode a)
                         ^bytes bb (boring/encode b)
                         both (byte-array (+ (alength ba) (alength bb)))]
                     (System/arraycopy ba 0 both 0 (alength ba))
                     (System/arraycopy bb 0 both (alength ba) (alength bb))
                     (let [r (org.replikativ.boring.Reader. both)]
                       (.skipValue r)
           ;; `c/equiv?`, not `=` -- gen-value produces byte[] and other Java
           ;; arrays, which compare by IDENTITY under `=`, so this property
           ;; failed on a correct skip.
                       (and (= (.position r) (alength ba))
                            (c/equiv? b (.readNext r))))))))

;; ------------------------------------------------- navigation vs the decoder
;;
;; A cursor is a second reading of the wire format, and its failure mode is the
;; same silent one `skipValue` has: land on the wrong offset and you get a
;; plausible value from the wrong place rather than an error. So the decoder is
;; the oracle again -- navigating to a path must equal decoding the whole
;; document and calling `get-in` on it, for every shape the generators reach.

;; Paths are derived from the CURSOR, not from the decoded value. Clojure's
;; `map?` is true for RECORDS, but a record is tag 27 on the wire, not a map --
;; so walking the decoded structure would ask the navigator to descend into
;; things that are not containers. Asking the wire what it holds is both the
;; correct test and the one that documents the distinction.
#?(:clj
   (defn- nav-paths
     "Paths into `v` that are genuinely containers on the wire, one and two deep."
     [c v]
     (when (= :map (boring.nav/value-type c))
       (concat (for [k (keys v)] [k])
               (for [k (keys v)
                     :let [inner (get c k)]
                     :when (and (some? inner) (= :map (boring.nav/value-type inner)))
                     ik (keys (get v k))]
                 [k ik])))))

#?(:clj
   (defspec navigation-agrees-with-decode-then-get-in 300
     (prop/for-all [v (gen/map (gen/one-of [gen/string-ascii gen/large-integer])
                               gen-value {:max-elements 6})]
                   (let [opts {:stringref false}
                         bs (boring/encode v opts)
                         c (boring.nav/root bs opts)
                         decoded (boring/decode bs opts)]
                     (every? (fn [path]
                               (let [cur (get-in c path)]
                                 (and (some? cur)
                                      (c/equiv? (boring.nav/value cur) (get-in decoded path)))))
                             (nav-paths c v))))))

;; `raw-bytes` must be an independently decodable slice -- that is what makes it
;; safe to hand a subtree somewhere else without materialising it.
#?(:clj
   (defspec raw-bytes-of-a-subtree-decodes-alone 300
     (prop/for-all [v (gen/map gen/string-ascii gen-value {:max-elements 5})]
                   (let [opts {:stringref false}
                         bs (boring/encode v opts)
                         c (boring.nav/root bs opts)]
                     (every? (fn [k]
                               (let [cur (get c k)]
                                 (c/equiv? (boring/decode (boring.nav/raw-bytes cur) opts)
                                           (get v k))))
                             (keys v))))))

;; count must agree with the decoded container, since it is read from the head
;; rather than counted.
#?(:clj
   (defspec cursor-count-agrees 300
     (prop/for-all [v (gen/map gen/string-ascii gen-value {:max-elements 6})]
                   (let [opts {:stringref false}
                         c (boring.nav/root (boring/encode v opts) opts)]
                     (and (= (count c) (count v))
              ;; Only where the WIRE says container -- see nav-paths.
                          (every? (fn [k]
                                    (let [cur (get c k)]
                                      (if (contains? #{:map :array} (boring.nav/value-type cur))
                                        (= (count cur) (count (get v k)))
                                        true)))
                                  (keys v)))))))

;; reduce over a cursor must see the same entries as the decoded map, and must
;; honour `reduced` -- the early-exit is what makes it usable as a source for
;; transducers over a file you do not want to read all of.
#?(:clj
   (defspec cursor-reduce-agrees-and-short-circuits 200
     (prop/for-all [v (gen/map gen/string-ascii gen/large-integer {:max-elements 6})]
                   (let [opts {:stringref false}
                         c (boring.nav/root (boring/encode v opts) opts)
                         via-nav (reduce (fn [acc e] (assoc acc (key e) (boring.nav/value (val e))))
                                         {} c)
                         first-key (reduce (fn [_ e] (reduced (key e))) nil c)]
                     (and (= via-nav v)
                          (or (empty? v) (contains? v first-key)))))))

;; ------------------------------------------------------- CBOR sequences
;;
;; A log is a CBOR sequence (RFC 8742): top-level items concatenated, which is
;; what `write-to!` in a loop produces. `nav/source` addresses only the FIRST
;; of them -- deliberately, since a caller may be navigating a value inside an
;; oversized scratch buffer -- so `nav/items` is what a log needs, and it must
;; agree item-for-item with `decode-seq`.

#?(:clj
   (defspec nav-items-agrees-with-decode-seq 200
     (prop/for-all [vs (gen/vector gen-value 0 8)]
                   (let [opts {:stringref false}
                         baos (java.io.ByteArrayOutputStream.)
                         w (boring/writer 4096)]
                     (doseq [v vs] (boring/write-to! w v baos opts))
                     (let [bs (.toByteArray baos)]
                       (and (= (count vs)
                               (reduce (fn [n _] (inc n)) 0 (boring.nav/items bs opts)))
                            (c/equiv? (vec vs)
                                      (mapv boring.nav/value (seq (boring.nav/items bs opts))))
                            (c/equiv? (vec (boring/decode-seq bs opts))
                                      (mapv boring.nav/value (seq (boring.nav/items bs opts))))))))))

;; `reduced` must stop the walk, which is what makes a transducer over a large
;; log affordable -- otherwise every early-exit query still pays for the tail.
#?(:clj
   (defspec nav-items-short-circuits 200
     (prop/for-all [vs (gen/vector gen/large-integer 1 10)]
                   (let [opts {:stringref false}
                         baos (java.io.ByteArrayOutputStream.)
                         w (boring/writer 4096)]
                     (doseq [v vs] (boring/write-to! w v baos opts))
                     (let [bs (.toByteArray baos)
                           seen (atom 0)]
                       (reduce (fn [_ c] (swap! seen inc) (reduced (boring.nav/value c)))
                               nil (boring.nav/items bs opts))
                       (= 1 @seen))))))
