(ns gen-canonical-fixture
  "Write the cross-implementation CANONICAL fixture.

  `interop/fixture.cbor` proves other languages can READ what boring writes.
  This proves something stronger and narrower: that boring's deterministic
  encoding agrees, octet for octet, with an independent implementation's.
  Reading can be right by accident in the same direction as the writer;
  agreeing on the exact bytes for a canonical profile cannot.

  The corpus is defined ONCE, here, and shipped as CBOR: each entry is
  [label, value, expected-hex]. The Python side decodes the value with cbor2,
  re-encodes it with cbor2's own canonical writer, and compares to the hex
  boring produced. Nothing is duplicated across the two languages, so the two
  cannot drift apart the way two hand-synced case lists do.

  Run: clojure -M:bench -m gen-canonical-fixture"
  (:require [boring.core :as boring]))

;; :canonical-rfc7049, not :canonical -- cbor2 5.8's `canonical=True` is RFC
;; 7049's canonical form: map keys ordered by encoded LENGTH first, and no
;; preference for the shortest float. boring models both orderings as separate
;; profiles precisely so this comparison can be exact rather than approximate.
;; See doc/COMPATIBILITY.md.
(def profile {:profile :canonical-rfc7049})

(defn- hex [^bytes bs] (apply str (map #(format "%02x" %) bs)))

(def cases
  "Values both implementations can express, chosen for the places encoders
  disagree: integer width boundaries, key ordering, bignum promotion, and the
  types with more than one legal encoding."
  [["int-0" 0] ["int-23" 23] ["int-24" 24] ["int-255" 255] ["int-256" 256]
   ["int-65535" 65535] ["int-65536" 65536] ["int-max-u32" 4294967295]
   ["int-neg-1" -1] ["int-neg-24" -24] ["int-neg-1000" -1000]
   ["float-1.5" 1.5] ["float-0.0" 0.0] ["float-1e300" 1e300]
   ;; INCLUDED BECAUSE IT DIFFERS. 65504.0 is the largest finite float16, so
   ;; RFC 8949 4.2.2's shortest-that-round-trips rule makes it `f9 7bff`;
   ;; RFC 7049 had no such rule and cbor2 writes float32. Listing it, and
   ;; asserting that it still differs, is worth more than leaving it out --
   ;; an omitted case is indistinguishable from a case that passes.
   ["float-65504" 65504.0]
   ["bignum-2^64" (bigint "18446744073709551616")]
   ["str-empty" ""] ["str-ascii" "hello"] ["str-utf8" "héllo wörld"]
   ["bytes" (byte-array (map unchecked-byte [1 2 0xfd]))]
   ["bytes-empty" (byte-array 0)]
   ["vec-empty" []] ["vec" [1 2 3]] ["vec-nested" [[1] [2 [3]]]]
   ["map-empty" {}]
   ;; The ordering cases: length-first and bytewise disagree on both.
   ["map-mixed-width" {1000 "x" "a" "y"}]
   ["map-same-width" {"bb" 1 "aa" 2}]
   ["map-nested" {"a" {"b" [1 2]}}]
   ["true" true] ["false" false] ["null" nil]])

(defn -main [& _]
  (let [rows (mapv (fn [[label v]]
                     [label v (hex (boring/encode v profile))])
                   cases)
        ^bytes out (boring/encode rows {:profile :interop})]
    (with-open [o (java.io.FileOutputStream. "interop/canonical_fixture.cbor")]
      (.write o out))
    (println (format "wrote interop/canonical_fixture.cbor -- %d cases, %d bytes"
                     (count rows) (alength out)))))
