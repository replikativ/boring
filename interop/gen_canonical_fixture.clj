(ns gen-canonical-fixture
  "Write the cross-implementation CANONICAL fixture.

  `interop/fixture.cbor` proves other languages can READ what boring writes.
  This proves something stronger and narrower: that boring's deterministic
  encoding agrees, octet for octet, with independent implementations'.
  Reading can be right by accident in the same direction as the writer;
  agreeing on the exact bytes for a canonical profile cannot.

  The corpus is defined ONCE, here, and shipped as CBOR. Nothing is duplicated
  across the checkers, so they cannot drift apart the way two hand-synced case
  lists in two languages do. Two checkers consume it:

    interop/test_canonical_bytes.py   Python, cbor2 (BOTH its C extension and
                                      its pure-Python encoder -- they are two
                                      implementations and they do not agree)
    interop/rust/src/canonical.rs     Rust, ciborium

  Both of those independently implement RFC 8949 4.2.3 length-first map key
  ordering, which is what `:canonical-rfc7049` produces. cbor2's
  `canonical=True` and ciborium's `CanonicalValue` are both length-first --
  see doc/COMPATIBILITY.md.

  ROW FORMAT -- [label value expected-7049 expected-8949 flags]

    label            text, unique
    value            a BYTE STRING holding boring's `:interop` encoding of the
                     case value -- not the value inline. Each checker decodes
                     it with its own decoder, so a value one implementation
                     cannot READ costs that one row instead of the whole gate.
                     That is not hypothetical: ciborium 0.2.2 refuses a tag-3
                     bignum below i128::MIN, and inline it made the entire
                     fixture undecodable in Rust. Under \"rep\" the embedded
                     item is a REP SPEC rather than the value.
    expected-7049    boring's `:canonical-rfc7049` bytes, or a REP-ENC SPEC
    expected-8949    boring's `:canonical` (RFC 8949 4.2.1 bytewise) bytes,
                     or an EMPTY byte string meaning \"identical to
                     expected-7049\". An empty string is unambiguous: no CBOR
                     item encodes to zero bytes.
    flags            array of text markers the checkers act on:
                       \"rep\"         value and expected-7049 are specs
                       \"py-collapse\" Python's dict/set equality merges keys
                                     this corpus deliberately keeps distinct,
                                     so the Python checker must skip it. The
                                     Rust checker still covers it.

  REP SPECS exist so the length-header boundary cases at 65535/65536 do not
  put a megabyte of repeated 'a' in a committed fixture:

    value spec     [\"rep\" kind n unit]      kind = text | bytes | array
                   (itself embedded in the value byte string)
    expected spec  [\"rep-enc\" head unit-enc n]  -> head ++ (unit-enc * n)

  The generator ASSERTS that the expansion reproduces boring's real output
  before writing it, so a wrong spec fails here rather than weakening the gate.

  Run: clojure -Sdeps '{:paths [\"src\" \"interop\" \"target/classes\"]}' \\
         -M -m gen-canonical-fixture"
  (:require [boring.core :as boring])
  (:import (java.util Arrays)))

;; Both canonical profiles, for every case. `:canonical-rfc7049` is what the
;; external encoders produce; `:canonical` is boring's SIGNING profile and had
;; nothing checking it at all. Shipping both lets the checkers assert that the
;; only difference between them is map/set element order -- and, for the cases
;; where the two orders really do diverge, that boring's bytewise output is the
;; bytewise reordering of the very octets cbor2 produced.
(def ^:private p7049 {:profile :canonical-rfc7049})
(def ^:private p8949 {:profile :canonical})

;; ------------------------------------------------------------------ helpers

(defn- txt [n c] (apply str (repeat n c)))
(defn- bs  [^long n ^long b] (byte-array n (unchecked-byte b)))
(defn- big [^String s] (bigint s))

(defn- half->double
  "The IEEE binary16 with these bits, as a double. Java before 20 has no f16
  conversion, and the corpus needs every half-representable value it can get:
  they are exactly the values RFC 8949 4.1's shortest-form rule must narrow."
  [^long bits]
  (let [s (bit-and (bit-shift-right bits 15) 1)
        e (bit-and (bit-shift-right bits 10) 0x1f)
        m (bit-and bits 0x3ff)
        v (cond
            (= e 0x1f)  (if (zero? m) Double/POSITIVE_INFINITY Double/NaN)
            (zero? e)   (* (Math/pow 2.0 -24.0) (double m))
            :else       (* (Math/pow 2.0 (double (- e 15))) (+ 1.0 (/ (double m) 1024.0))))]
    (if (= 1 s) (- v) v)))

;; --------------------------------------------------------------- the corpus
;; Each family answers "where can two conformant encoders legitimately differ?"
;; A case that no plausible encoder could get wrong is padding; a case that
;; DOES differ is the whole point. See the CAN-DIFFER notes.

(def ^:private integers
  "CAN-DIFFER: every additional-information width boundary, in both signs.
  An off-by-one here is the classic canonical defect -- writing 24 as `1817`,
  or 65536 as `19ffff`+1. Both sides of every boundary, so a >= / > slip shows."
  (concat
   (for [n [0 1 22 23 24 25 100 254 255 256 257 1000 65534 65535 65536 65537
            16777215 16777216 4294967294 4294967295 4294967296 4294967297
            9007199254740991 9007199254740992
            9223372036854775806 9223372036854775807]]
     [(str "uint-" n) n])
   (for [n [-1 -22 -23 -24 -25 -26 -100 -255 -256 -257 -258 -1000
            -65535 -65536 -65537 -4294967295 -4294967296 -4294967297
            -9223372036854775807 -9223372036854775808]]
     [(str "nint-" n) n])
   ;; CAN-DIFFER: the u64/bignum seam. RFC 8949 4.2.1 requires the basic
   ;; integer form whenever it fits, so 2^64-1 must be `1bffffffffffffffff`
   ;; and NOT tag 2. One past it must be tag 2 with a MINIMAL-length payload
   ;; (no leading zero byte) -- two places to get it wrong, one on each side.
   (for [s ["18446744073709551614" "18446744073709551615" "18446744073709551616"
            "18446744073709551617" "18446744073709551618"
            "36893488147419103232" "1208925819614629174706176"
            "340282366920938463463374607431768211456"]]
     [(str "bigpos-" s) (big s)])
   (for [s ["-18446744073709551615" "-18446744073709551616" "-18446744073709551617"
            "-18446744073709551618" "-36893488147419103232"
            "-340282366920938463463374607431768211456"
            "-340282366920938463463374607431768211457"]]
     [(str "bigneg-" s) (big s)])
   ;; CAN-DIFFER: the i128 seam, which is not a CBOR boundary at all -- it is
   ;; an artefact of how a Rust reader models bignums, and it is exactly where
   ;; ciborium 0.2.2 stops being able to read valid CBOR. Kept in the corpus
   ;; because a gate that quietly drops the values one peer chokes on is a
   ;; gate that measures the peer, not the encoder.
   (for [s ["170141183460469231731687303715884105727"    ; 2^127-1
            "170141183460469231731687303715884105728"    ; 2^127
            "-170141183460469231731687303715884105728"   ; -2^127
            "-170141183460469231731687303715884105729"]] ; -2^127-1
     [(str "big-i128-" s) (big s)])
   ;; CAN-DIFFER: a bignum that FITS must be reduced, so these must come out
   ;; as one-byte basic integers, not as tag 2 with a one-byte payload.
   (for [s ["0" "1" "23" "24" "255" "256"]]
     [(str "big-reducible-" s) (big s)])))

(def ^:private half-samples
  "CAN-DIFFER: every value that is exactly a binary16. RFC 8949 4.1 requires
  the shortest float encoding that preserves the value, so each of these MUST
  come out three bytes. This is where the two cbor2 implementations part
  company -- its C extension refuses binary16 for the entire top binade.

  Sampled across the whole 16-bit space (a prime stride, so no exponent is
  systematically missed), with the top binade oversampled because that is the
  region an implementation is most likely to get wrong: it abuts overflow."
  (concat
   (for [bits (range 0 0x8000 331)] [(format "half-%04x" bits) (half->double bits)])
   (for [bits (range 0x8000 0x10000 331)] [(format "half-%04x" bits) (half->double bits)])
   ;; the top binade: 32768.0 .. 65504.0 and its mirror
   (for [bits (concat (range 0x7800 0x7c00 149) (range 0xf800 0xfc00 149))]
     [(format "half-top-%04x" bits) (half->double bits)])
   ;; subnormal halves, including the smallest
   (for [bits (concat (range 0x0000 0x0400 37) (range 0x8000 0x8400 37))]
     [(format "half-sub-%04x" bits) (half->double bits)])))

(def ^:private float-cases
  (concat
   half-samples
   [["float-neg-zero" -0.0]
    ["float-pos-zero" 0.0]
    ["float-inf"      Double/POSITIVE_INFINITY]
    ["float-neg-inf"  Double/NEGATIVE_INFINITY]
    ["float-nan"      Double/NaN]
    ;; CAN-DIFFER: RFC 8949 4.1 -- "For NaN values, a shorter encoding is
    ;; preferred if zero-padding the shorter significand towards the right
    ;; reconstitutes the original NaN value." These payloads do NOT survive
    ;; zero-padding from binary16, so preferred serialization keeps them wide.
    ["float-nan-payload-1"  (Double/longBitsToDouble 0x7ff8000000000001)]
    ["float-nan-payload-lo" (Double/longBitsToDouble 0x7ff0000000000001)] ; signalling
    ["float-nan-neg"        (Double/longBitsToDouble 0xfff8000000000000)]
    ["float-nan-f32able"    (Double/longBitsToDouble 0x7ffc000000000000)]
    ;; CAN-DIFFER: exactly representable as binary32 but NOT binary16.
    ["float-100000"     100000.0]
    ["float-f32-max"    3.4028234663852886E38]
    ["float-f32-min-normal" 1.1754943508222875E-38]
    ["float-f32-min-subnormal" 1.401298464324817E-45]
    ["float-f32-subnormal"  5.877471754111438E-39]
    ["float-1.0e-7"     1.1920928955078125E-7]
    ["float-65505"      65505.0]   ; just past binary16's largest finite
    ["float-65519"      65519.0]   ; largest double that rounds to 65504 in f16
    ["float-65520"      65520.0]   ; rounds to binary16 infinity -- must NOT narrow
    ["float-32769"      32769.0]
    ;; CAN-DIFFER: binary64 only.
    ["float-0.1"        0.1]
    ["float-1.1"        1.1]
    ["float-1e300"      1e300]
    ["float-f64-max"    Double/MAX_VALUE]
    ["float-f64-min-normal" 2.2250738585072014E-308]
    ["float-f64-min-subnormal" Double/MIN_VALUE]
    ["float-pi"         Math/PI]
    ["float-2^53"       9007199254740992.0]
    ["float-2^53+2"     9007199254740994.0]
    ["float-2^63"       9.223372036854776E18]
    ["float-2^64"       1.8446744073709552E19]
    ["float-neg-4.1"    -4.1]]))

(def ^:private strings
  "CAN-DIFFER: every string length-header boundary, for both major type 2 and
  major type 3. The 65535/65536 pair is where a 16-bit length silently wraps."
  (concat
   (for [n [0 1 22 23 24 25 254 255 256 257 1000]]
     [(str "bytes-" n) (bs n 0x61)])
   (for [n [0 1 22 23 24 25 254 255 256 257 1000]]
     [(str "text-" n) (txt n \a)])
   [["bytes-high" (byte-array (map unchecked-byte [0x00 0x7f 0x80 0xfd 0xfe 0xff]))]
    ["bytes-all-ff" (bs 24 0xff)]
    ["bytes-all-00" (bs 24 0x00)]]))

(def ^:private unicode
  "CAN-DIFFER: the length header counts UTF-8 BYTES, not characters, and a
  Java String counts UTF-16 units. Every one of these has a character count
  that differs from its byte count, and the last two straddle 23/24 bytes
  while staying under 24 characters.

  The normalisation pair is here to assert that NEITHER implementation
  normalises: NFC and NFD forms of the same text must stay distinct items."
  [["utf8-nfc-e-acute"  "é"]                    ; 2 bytes, 1 char
   ["utf8-nfd-e-acute"  "é"]                   ; 3 bytes, 2 chars
   ["utf8-nfc-string"   "héllo wörld"]
   ["utf8-nfd-string"   "héllo wörld"]
   ["utf8-cjk"          "中文測試"]  ; 3 bytes each
   ["utf8-emoji"        "😀🌍"]  ; surrogate pairs, 4 bytes each
   ["utf8-zwj"          "👩‍💻"]
   ["utf8-rtl"          "שלום"]
   ["utf8-combining"    "á̂̃̄"]
   ["utf8-bom"          "﻿abc"]
   ["utf8-nul"          "a b"]
   ["utf8-max-bmp"      "￿"]
   ["utf8-23-bytes"     (str (txt 21 \a) "é")]  ; 23 bytes, 22 chars
   ["utf8-24-bytes"     (str (txt 22 \a) "é")]  ; 24 bytes, 23 chars
   ["utf8-astral-24"    (str (txt 20 \a) "😀")]])

(def ^:private containers
  (concat
   [["array-empty" []]
    ["map-empty" {}]
    ["set-empty" #{}]]
   ;; CAN-DIFFER: array and map header widths, same boundaries as strings.
   (for [n [1 22 23 24 25 254 255 256 257]]
     [(str "array-" n) (vec (repeat n 0))])
   (for [n [1 22 23 24 25 254 255 256 257]]
     [(str "map-" n) (into (sorted-map) (map (fn [i] [i i])) (range n))])
   (for [n [1 23 24 255 256]]
     [(str "set-" n) (into #{} (range n))])
   [["array-nested-2" [[1] [2 [3]]]]
    ["array-nested-deep" (reduce (fn [a _] [a]) [] (range 50))]
    ["map-nested-deep" (reduce (fn [a _] {"k" a}) {} (range 40))]
    ["array-of-empties" [[] {} #{} "" (byte-array 0)]]
    ["array-heterogeneous" [0 -1 1.5 "x" (byte-array 1) [] {} #{1} true false nil]]
    ["map-values-nested" {"a" {"b" [1 2 {"c" #{3}}]}}]]))

(def ^:private ordering
  "CAN-DIFFER, and this is the family the whole gate exists for: RFC 8949
  4.2.1 (bytewise over the whole encoding) against RFC 8949 4.2.3 (length
  first, then bytewise).

  The two rules diverge exactly when a SHORTER encoding has a LARGER first
  byte, which needs mixed major types -- within one major type a longer
  encoding always has the larger head byte. Cases below are of both kinds
  deliberately: the ones that diverge prove the corpus can tell the orderings
  apart, and the ones that do not prove the two profiles agree where the RFC
  says they must. The checkers count and print both populations, so a corpus
  that quietly stopped being able to distinguish them is visible."
  [;; text \"a\" (6161, 2 B) vs int 1000 (1903e8, 3 B): 0x19 < 0x61
   ["order-text-vs-int"      {1000 "x" "a" "y"}]
   ["order-text-vs-int-many" {1000 0 100000 1 "a" 2 "bb" 3 10 4}]
   ;; int 24 (1818, 2 B) vs bytes h'' (40, 1 B): 0x18 < 0x40
   ["order-int-vs-bytes"     {24 "a" (byte-array 0) "b"}]
   ;; array [] (80, 1 B) vs int 256 (190100, 3 B): 0x19 < 0x80
   ["order-array-vs-int"     {[] "a" 256 "b"}]
   ;; map {} (a0, 1 B) vs int -1000 (3903e7, 3 B): 0x39 < 0xa0
   ["order-map-vs-int"       {{} "a" -1000 "b"}]
   ;; bool/null (f4..f6, 1 B) sort last bytewise but first by length
   ["order-simple-vs-int"    {true "a" false "b" nil "c" 4294967296 "d"}]
   ["order-u64-vs-text"      {4294967296 "a" "abc" "b"}]
   ["order-negatives"        {-1 "a" -1000 "b" -24 "c" -25 "d" 0 "e" 23 "f" 24 "g"}]
   ["order-same-width"       {"bb" 1 "aa" 2 "ab" 3 "ba" 4}]
   ["order-prefix-texts"     {"a" 1 "aa" 2 "aaa" 3 "" 4}]
   ["order-prefix-bytes"     {(byte-array 0) 1 (bs 1 0) 2 (bs 2 0) 3 (bs 1 1) 4}]
   ["order-bytes-vs-text"    {(byte-array (map unchecked-byte [0x61])) 1 "a" 2}]
   ["order-mixed-majors"     {0 "int" "s" "text" (bs 1 0x61) "bytes"
                              [] "array" {} "map" true "bool" nil "null"
                              1.5 "float" 1000 "wide-int"}]
   ["order-nfc-vs-nfd"       {"é" 1 "é" 2}]
   ["order-nested-maps"      {"outer" {1000 "x" "a" "y"}
                              [1000 "z"] {24 "q" (byte-array 0) "r"}}]
   ["order-array-keys"       {[] 1 [1] 2 [1 2] 3 [1000] 4 ["a"] 5}]
   ["order-24-keys"          (into {} (map (fn [i] [(if (even? i) i (str i)) i])) (range 24))]
   ;; CAN-DIFFER: tag 39 identifiers (keywords) against plain text. The tag
   ;; head adds two bytes, so `:a` (d827 6161) outranks "a" (6161) by length
   ;; but loses bytewise -- 0xd8 > 0x61.
   ["order-keyword-vs-text"  {:a 1 "a" 2 'sym 3}]
   ;; CAN-DIFFER: tag 258 element order. No RFC defines it -- boring, cbor2
   ;; and this corpus's Rust checker all apply the map-key rule to elements,
   ;; which is a convention, not a standard. Stated so it is not mistaken for
   ;; conformance.
   ["order-set-mixed"        #{1000 "a" 24 [] nil true}]
   ["order-set-ints"         #{0 23 24 255 256 -1 -24 -25}]])

(def ^:private collisions
  "CAN-DIFFER: values a HOST language merges but CBOR keeps apart. Clojure
  keeps 1 and 1.0 as distinct map keys; Python does not, which is why these
  carry \"py-collapse\" and are checked by the Rust side alone. They are here
  because a canonical encoder that quietly merged them would produce a map
  with the wrong entry count and nothing else would notice."
  [["collide-int-float"    {1 "a" 1.0 "b"} #{"py-collapse"}]
   ["collide-zero-negzero" {0 "a" -0.0 "b"} #{"py-collapse"}]
   ["collide-bool-int"     {true "a" 1 "b"} #{"py-collapse"}]
   ["collide-set-int-float" #{1 1.0} #{"py-collapse"}]
   ;; NOT a collision anywhere: a keyword is tag 39, a string is major type 3.
   ["collide-keyword-text" {:a 1 "a" 2} #{}]
   ["collide-text-bytes"   {"a" 1 (bs 1 0x61) 2} #{}]
   ;; CAN-DIFFER: 0.0 and -0.0 are `=` in Clojure but encode differently.
   ["collide-float-zeroes" [0.0 -0.0 0 (big "0")] #{}]])

(def ^:private tags
  [["tag-keyword"     :hello]
   ["tag-namespaced"  :some.ns/name]
   ["tag-symbol"      'a-symbol]
   ["tag-uuid"        #uuid "12345678-1234-5678-1234-567812345678"]
   ["tag-inst"        #inst "2020-01-02T03:04:05.000-00:00"]
   ["tag-ratio"       (/ 22 7)]
   ["tag-bigdec"      1.5M]
   ["tag-char"        \x]])

(def ^:private rep-cases
  "The length-header boundaries that need a 64 KiB payload. Stored as specs
  (see the namespace docstring) so the fixture stays small enough to read.

  CAN-DIFFER: 65535 vs 65536 is where a 16-bit length header must become a
  32-bit one. `text-utf8-65536` has 32768 CHARACTERS and 65536 BYTES, so an
  implementation that headers by character count trips on it and nothing
  else in the corpus would catch that."
  [["bytes-65535"      "bytes" 65535 0x61]
   ["bytes-65536"      "bytes" 65536 0x61]
   ["bytes-65537"      "bytes" 65537 0x61]
   ["text-65535"       "text"  65535 "a"]
   ["text-65536"       "text"  65536 "a"]
   ["text-utf8-65534"  "text"  32767 "é"]
   ["text-utf8-65536"  "text"  32768 "é"]
   ["array-65535"      "array" 65535 0]
   ["array-65536"      "array" 65536 0]])

(def ^:private scalars
  [["true" true] ["false" false] ["null" nil]])

;; ------------------------------------------------------- generated ordering
;; Hand-picked ordering cases test the divergences somebody thought of. These
;; test the ones nobody did. `java.util.Random`'s algorithm is specified by
;; the JDK, so a fixed seed gives the same corpus on every machine and every
;; JDK -- generated, but not random from run to run, which would make a
;; failure unreproducible.

(def ^:private key-pool
  "Keys spanning every major type and every encoded width, chosen so that no
  two of them are equal under PYTHON's dict rules. That constraint costs the
  pool 0, 1 and 0.0 -- Python merges True with 1, False with 0 and 1 with
  1.0 -- and buys generated cases the Python checker can actually run. The
  hand-written `collisions` family covers what is excluded here."
  [2 3 23 24 25 255 256 257 1000 65535 65536 4294967295 4294967296
   (big "18446744073709551616") (big "340282366920938463463374607431768211456")
   -2 -23 -24 -25 -256 -257 -1000 -65537 -4294967297
   "" "a" "bb" "ccc" "hello" "héllo" "中文" (txt 23 \z) (txt 24 \z)
   (byte-array 0) (bs 1 0x61) (bs 3 0xff) (bs 23 0x00) (bs 24 0x00)
   true false nil 1.5 -2.5 65504.0 1e300
   [] [2] [2 3] ["a"] [[]] {} {"k" 2} {2 "k"}
   :kw :some.ns/kw 'sym])

(def ^:private value-pool
  (into key-pool [#{} #{2 3} #{"a" 1000 []} {"nested" {"deep" [1.5 "x"]}}
                  [[[[[2]]]]] (bs 5 0x7f)]))

(defn- gen-cases
  "n generated containers, drawn from the pools with a fixed seed.

  Keys are sampled by INDEX, never by value: two `(byte-array 1)` objects are
  never `=` in Clojure, so sampling by value would build a map with two
  entries whose canonical encodings are identical, which boring rejects as a
  `canonical-duplicate` -- correctly, but the generator would just crash."
  [n]
  (let [rnd (java.util.Random. 20260803)
        pick (fn [^java.util.List coll] (nth coll (.nextInt rnd (count coll))))
        keys-n (fn [k] (map #(nth key-pool %)
                            (take k (distinct (repeatedly #(.nextInt rnd (count key-pool)))))))]
    (for [i (range n)
          :let [kind (mod i 4)]]
      (case kind
        ;; small maps: the size where a single mis-ordered pair is obvious
        0 [(str "gen-map-" i)
           (zipmap (keys-n (+ 2 (.nextInt rnd 6))) (repeatedly #(pick value-pool)))]
        ;; wide maps: crosses the 23/24 map-header boundary, and gives the
        ;; comparator enough keys that a non-total order shows up as an
        ;; unstable sort rather than a lucky pass
        1 [(str "gen-map-wide-" i)
           (zipmap (keys-n (+ 10 (.nextInt rnd 25))) (repeatedly #(pick value-pool)))]
        ;; sets: same comparator, applied to elements
        2 [(str "gen-set-" i)
           (set (keys-n (+ 2 (.nextInt rnd 12))))]
        ;; nesting: ordering has to hold at every level, not just the top
        3 [(str "gen-nested-" i)
           (let [inner (fn [] (zipmap (keys-n (+ 1 (.nextInt rnd 4)))
                                      (repeatedly (fn [] (pick value-pool)))))]
             (zipmap (keys-n (+ 2 (.nextInt rnd 4))) (repeatedly inner)))]))))

(def cases
  (concat scalars integers float-cases strings unicode containers ordering tags
          (map (fn [[l v]] [l v]) collisions)
          (gen-cases 500)))

;; ------------------------------------------------------------------ writing

(defn- flags-of [label]
  (or (some (fn [[l _ f]] (when (= l label) f)) collisions) #{}))

(def ^:private transport
  "How a case value reaches the checkers: `:interop` is plain conformant CBOR
  with no extensions, and -- the reason it is not one of the canonical
  profiles -- a DIFFERENT code path from the one under test. A canonical-path
  bug therefore cannot corrupt the value and its own expectation in the same
  direction and go unnoticed."
  {:profile :interop})

(defn- row [[label value]]
  (let [b7049 (boring/encode value p7049)
        b8949 (boring/encode value p8949)
        same? (Arrays/equals ^bytes b7049 ^bytes b8949)]
    [label (boring/encode value transport)
     b7049 (if same? (byte-array 0) b8949) (vec (flags-of label))]))

(defn- rep-row [[label kind n unit]]
  (let [value    (case kind
                   "text"  (apply str (repeat n unit))
                   "bytes" (bs n unit)
                   "array" (vec (repeat n unit)))
        b7049    (boring/encode value p7049)
        b8949    (boring/encode value p8949)
        unit-enc (case kind
                   "text"  (.getBytes ^String unit "UTF-8")
                   "bytes" (bs 1 unit)
                   "array" (boring/encode unit p7049))
        head-len (- (alength ^bytes b7049) (* n (alength ^bytes unit-enc)))
        head     (Arrays/copyOf ^bytes b7049 (int head-len))]
    ;; Assert the spec reproduces the real bytes. A silently wrong spec would
    ;; turn these into cases that compare a fiction against a fiction.
    (when-not (Arrays/equals ^bytes b7049 ^bytes b8949)
      (throw (ex-info "rep case must not be order-sensitive" {:label label})))
    (when (neg? head-len)
      (throw (ex-info "rep case: unit encoding does not divide the output"
                      {:label label :head-len head-len})))
    (let [rebuilt (byte-array (alength ^bytes b7049))]
      (System/arraycopy head 0 rebuilt 0 (int head-len))
      (dotimes [i n]
        (System/arraycopy unit-enc 0 rebuilt
                          (+ (int head-len) (* i (alength ^bytes unit-enc)))
                          (alength ^bytes unit-enc)))
      (when-not (Arrays/equals ^bytes rebuilt ^bytes b7049)
        (throw (ex-info "rep spec does not reproduce boring's bytes"
                        {:label label}))))
    [label (boring/encode ["rep" kind n unit] transport)
     ["rep-enc" head unit-enc n] (byte-array 0) ["rep"]]))

(defn -main [& _]
  (let [rows (into (mapv row cases) (mapv rep-row rep-cases))
        labels (mapv first rows)]
    (when-not (= (count labels) (count (set labels)))
      (throw (ex-info "duplicate labels" {:dupes (->> labels frequencies
                                                      (filter #(< 1 (val %)))
                                                      (map key))})))
    (let [^bytes out (boring/encode rows {:profile :interop})
          order-sensitive (count (filter (fn [r] (pos? (alength ^bytes (nth r 3)))) rows))]
      (with-open [o (java.io.FileOutputStream. "interop/canonical_fixture.cbor")]
        (.write o out))
      (println (format "wrote interop/canonical_fixture.cbor -- %d cases, %d bytes"
                       (count rows) (alength out)))
      (println (format "  %d cases where RFC 8949 4.2.1 and 4.2.3 orderings DIFFER"
                       order-sensitive))
      (println (format "  %d cases Python must skip (host-language key collapse)"
                       (count (filter (fn [r] (some #{"py-collapse"} (nth r 4))) rows)))))))
