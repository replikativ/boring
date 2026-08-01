(ns boring.golden
  "The golden corpus: values whose encoding is FROZEN.

  The guarantee this exists to enforce is the one a serialization library lives
  or dies by — **bytes written by an older version must keep decoding, and the
  current version must keep producing the same bytes for the same value.**
  Everything else in the test suite checks that boring agrees with itself today;
  only this checks that it agrees with itself across time.

  `boring.golden-v1` holds the frozen hex, generated once by `dev/gen_golden.clj`
  and thereafter treated as read-only. A diff to that file in a pull request
  means the wire format changed, which is either a deliberate, documented
  break or a bug — there is no third possibility. That is the entire point:
  the format cannot drift silently, because drifting requires editing a file
  whose diff is impossible to miss.

  ## Why the corpus is split

  `portable` holds values that encode identically on the JVM and on
  ClojureScript, so one frozen file pins both platforms and cross-platform
  byte identity comes along for free.

  Deliberately NOT in `portable`:

  - **Integral floats.** ClojureScript has one number type, so 1.0 is the
    integer 1 and encodes as `01` where the JVM emits `f93c00`. Irreconcilable;
    pinned separately in the conformance suite instead.
  - **Values near 2^63.** `Long/MAX_VALUE` has no ClojureScript counterpart.
  - **BigDecimal, Ratio, Instant, Character.** JVM types with no native
    ClojureScript equivalent; they decode there to `boring.data` stand-ins, so
    the byte-level guarantee still holds but the value comparison would not."
  (:require [boring.data :as data]))

(defrecord GoldenPoint [x y])

(def portable
  "[label value opts]. Encoding these must produce exactly the frozen bytes on
  BOTH platforms, forever."
  [;; --- integers, at every argument-width boundary ------------------------
   ["int-0"            0                          {}]
   ["int-1"            1                          {}]
   ["int-23"           23                         {}]
   ["int-24"           24                         {}]
   ["int-255"          255                        {}]
   ["int-256"          256                        {}]
   ["int-65535"        65535                      {}]
   ["int-65536"        65536                      {}]
   ["int-4294967295"   4294967295                 {}]
   ["int-4294967296"   4294967296                 {}]
   ["int--1"           -1                         {}]
   ["int--24"          -24                        {}]
   ["int--256"         -256                       {}]
   ["int--65536"       -65536                     {}]

   ;; --- non-integral floats (integral ones diverge; see the ns docstring) --
   ["float-1.5"        1.5                        {}]
   ["float-3.14159"    3.14159                    {}]
   ["float-neg"        -2.5                       {}]
   ["float-NaN"        ##NaN                      {}]
   ["float-Inf"        ##Inf                      {}]
   ["float--Inf"       ##-Inf                     {}]

   ;; --- text, including the codepoints that have bitten us ---------------
   ["str-empty"        ""                         {}]
   ["str-ascii"        "hello"                    {}]
   ["str-unicode"      "héllo wörld"              {}]
   ["str-astral"       "💩"                       {}]
   ["str-bom"          "﻿"                        {}]
   ["str-bom-then-a"   "﻿A"                       {}]

   ;; --- identifiers (tag 39) ---------------------------------------------
   ["kw"               :kw                        {}]
   ["kw-ns"            :some.ns/kw                {}]
   ["sym"              'sym                       {}]
   ["sym-ns"           'some.ns/sym               {}]

   ;; --- simple values -----------------------------------------------------
   ["true"             true                       {}]
   ["false"            false                      {}]
   ["nil"              nil                        {}]
   ["undefined"        data/undefined             {}]
   ["simple-200"       (data/simple-value 200)    {}]

   ;; --- collections -------------------------------------------------------
   ["vec-empty"        []                         {}]
   ["vec-ints"         [1 2 3]                    {}]
   ["vec-nested"       [[1] [2 [3]]]              {}]
   ["map-empty"        {}                         {}]
   ["map-kw"           {:a 1 :b "x"}              {}]
   ["set"              #{:a :b :c}                {}]
   ["map-nested"       {:a {:b {:c [1 2]}}}       {}]

   ;; --- stringref: the same keyword twice must use the back-reference -----
   ["stringref-repeat" [{:attribute 1} {:attribute 2}]     {}]
   ;; A byte string CONSUMES an index, so the reference here is 25(1), not
   ;; 25(0). boring skipped byte strings and cbor2 did not, which made cbor2
   ;; read our second "abcd" back as the byte string. Frozen because the index
   ;; arithmetic is the whole of the bug: nothing about the shape shows it.
   ["stringref-after-bytes"
    [#?(:clj (byte-array [1 2 3 4 5]) :cljs (js/Uint8Array. #js [1 2 3 4 5]))
     "abcd" "abcd"]                                        {}]
   ["stringref-threshold" ["abc" "abc" "wxyz" "wxyz"]      {}]

   ;; --- records (tag 27) --------------------------------------------------
   ["record"           (->GoldenPoint 3 4)        {}]
   ["record-nested"    [(->GoldenPoint 1 2)
                        (->GoldenPoint 3 4)]      {}]
   ["unknown-record"   (data/unknown-record "some.Other" {:z 9})  {}]
   ;; A tag-27 frame whose payload is NOT a field map -- what a registered
   ;; write handler emits for a positional type, and what an unregistered
   ;; reader gets back. Frozen because the wire bytes are identical to the
   ;; map case's framing while the decoded VALUE differs, so nothing else
   ;; here would notice a change.
   ["tagged-literal"   (tagged-literal (symbol "some.Positional") [1 2 3]) {}]

   ;; --- reserved tag-27 names ---------------------------------------------
   ;; Markers for types CBOR cannot otherwise distinguish. Frozen because a
   ;; rename here is a silent break: old data decodes to an UnknownRecord,
   ;; which is a VALUE, so nothing throws.
   ["sorted-map"       (sorted-map :b 2 :a 1)      {}]
   ["sorted-set"       (sorted-set 3 1 2)          {}]
   ["queue"            (into #?(:clj clojure.lang.PersistentQueue/EMPTY
                                :cljs cljs.core/PersistentQueue.EMPTY)
                             [:a :b])              {}]
   ["with-meta"        (with-meta [1 2] {:m true}) {}]
   ["with-meta-nested" [(with-meta {:a 1} {:i 1})] {}]

   ;; --- uuid (tag 37) -----------------------------------------------------
   ["uuid"             #uuid "9682952b-fafa-4b41-8e4a-31ae948d6f08" {}]

   ;; --- shaped arrays (provisional tag 39649) -----------------------------
   ["shaped"           [{:e 1 :a :x :v "p"} {:e 2 :a :y :v "q"}] {:shapes true}]

   ;; --- profiles ----------------------------------------------------------
   ["interop-map"      {:a 1 :b "x"}              {:profile :interop}]
   ["canonical-map"    {:b 2 :a 1}                {:profile :canonical}]
   ["canonical-float"  1.5                        {:profile :canonical}]
   ["canonical-nested" {:z [1 2] :a {:b 3}}       {:profile :canonical}]])

#?(:clj
   (def jvm-only
     "JVM types with no native ClojureScript counterpart. The bytes are still
     frozen — a ClojureScript reader decodes these to boring.data stand-ins, so
     the wire guarantee holds even though the value comparison is JVM-only."
     [["bigint-huge"    (bigint "18446744073709551616")    {}]
      ["bigint-neg"     (bigint "-18446744073709551617")   {}]
      ["bigdec-1.50M"   1.50M                              {}]
      ["bigdec-1.5M"    1.5M                               {}]
      ["ratio"          (/ 22 7)                           {}]
      ["date"           (java.util.Date. 1234567890123)    {}]
      ["float-f32"      (float 3.14159)                    {}]
      ["long-max"       Long/MAX_VALUE                     {}]
      ["long-min"       Long/MIN_VALUE                     {}]
      ["char"           \a                                    {}]
      ["char-non-ascii" \ಬ                                   {}]
      ["period"         (java.time.Period/of 1 1 1)           {}]
      ["duration"       (java.time.Duration/ofSeconds 100 100) {}]
      ["duration-whole" (java.time.Duration/ofSeconds 100)     {}]
      ["local-date"     (java.time.LocalDate/of 2020 1 1)      {}]
      ;; Same tag 1 as "date" above; the opt is what decides which JVM class
      ;; comes back, so the corpus pins both readings of identical bytes.
      ["instant"        (java.time.Instant/ofEpochMilli 1234567890123)
       {:instant-type :instant}]
      ["uri"            (java.net.URI. "https://clojure.org")  {}]
      ["regex"          #"^a.*z$"                              {}]
      ;; Types RFC 8746 does not cover, and exceptions as data. Frozen because
      ;; each is a reserved tag-27 name: renaming one is a silent break, since
      ;; an unrecognised name decodes to an ordinary value rather than raising.
      ["boolean-array"  (boolean-array [true false true])   {}]
      ["char-array"     (char-array [\a \b])                {}]
      ["string-array"   (into-array String ["a" "b"])       {}]
      ["object-array"   (object-array [1 "a" :k])           {}]
      ["ex-info"        (ex-info "boom" {:a 1})             {}]
      ["ex-info-nested" (ex-info "outer" {:o 1} (ex-info "inner" {:i 2})) {}]
      ["canonical-bignum" (bigint 255)                     {:profile :canonical}]]))
