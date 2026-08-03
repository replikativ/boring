(ns boring.hostile
  "Malformed content for every built-in tag, as [label hex].

  The generic fuzzers mutate BYTES and are very good at truncation and bad
  counts. What they almost never produce is a well-formed CBOR item of the
  WRONG SHAPE inside a tag boring handles specially -- a tag 1002 whose seconds
  are a string, a  wrapping an integer. Those reach the tag
  handler's own code, which is where the casts and the java.time parsers are.

  Five of these raised raw JVM exceptions -- ClassCastException,
  IllegalArgumentException, DateTimeParseException -- straight through the
  typed-error contract that doc/SECURITY.md advertises, so a caller catching
  ExceptionInfo was bypassed by attacker-controlled input.

  Every entry must produce an ex-info carrying a :type. Decoding successfully
  is also a failure: each of these is malformed."
  (:require [boring.conformance]))

(def cases
  [;; --- from bench/cljs/cljsbench/sec.cljs -------------------------------
   ;; These are the exact inputs behind two fixed P0 defects (the
   ;; §10z). They lived in a bench file that nothing ran, so the only
   ;; executable check for a closed P0 was outside CI -- a regression would
   ;; have been caught by nobody. `bad-stringref` did not appear anywhere
   ;; under test/ before this.
   ["sr-unregistered-index"   "d81905"]
   ["sr-undefined-map-key"    "a1d8190001"]
   ["sr-tag39-typeerror"      "d827d81900"]
   ["sr-bigint-index"         "d8191b1000000000000000"]
   ["set-bytes-header"        "d90102420102"]
   ["set-duplicate-elements"  "d9010283010101"]
   ["tag2-not-bytes" "c26178"]
   ["tag3-not-bytes" "c301"]
   ["tag4-not-array" "c46178"]
   ["tag4-bad-arity" "c48101"]
   ["tag4-float-exponent" "c482fb3ff800000000000001"]
   ["tag30-not-array" "d81e01"]
   ["tag30-float-num" "d81e82fb3ff800000000000001"]
   ["tag30-zero-denom" "d81e820100"]
   ["tag37-wrong-len" "d8254400000000"]
   ["tag37-not-bytes" "d8256178"]
   ["tag39-not-text" "d82701"]
   ["tag0-not-text" "c001"]
   ["tag0-bad-date" "c06a6e6f742d612d64617465"]
   ["tag1-not-number" "c16178"]
   ["tag32-not-text" "d82001"]
   ["tag35-not-text" "d82301"]
   ["tag35-bad-regex" "d823615b"]
   ["tag40-not-array" "d82801"]
   ["tag40-bad-arity" "d8288101"]
   ["tag40-dims-not-nums" "d828828161784400000000"]
   ["tag40-flat-not-arr" "d828828102627879"]
   ;; DIMS OF ARITY 2, so the payload check is actually reached. The case above
   ;; has a 1-element dims array, so it tripped the dimensionality check and
   ;; never touched the payload -- which is how ClojureScript's `.-length` /
   ;; `.subarray` on a text string escaped as a raw TypeError.
   ["tag40-text-payload" "d8288282010363616263"]
   ["tag40-dim-mismatch" "d828828109d84e480000000000000000"]
   ["tag258-not-array" "d9010201"]
   ["tag1002-not-map" "d903ea01"]
   ["tag1002-str-secs" "d903eaa1016178"]
   ["tag1002-no-base" "d903eaa10201"]
   ["tag1002-str-nano" "d903eaa20101286178"]
   ["tag1004-not-text" "d903ec01"]
   ["tag1004-bad-date" "d903ec6a323032302d39392d3939"]
   ["tag27-not-array" "d81b01"]
   ["tag27-bad-arity" "d81b816178"]
   ["tag27-name-not-text" "d81b820101"]
   ["sorted-map-not-map" "d81b8272636c6f6a7572652f736f727465642d6d617001"]
   ["sorted-set-not-seq" "d81b8272636c6f6a7572652f736f727465642d73657401"]
   ["queue-not-seq" "d81b826d636c6f6a7572652f717565756501"]
   ["with-meta-not-pair" "d81b8271636c6f6a7572652f776974682d6d65746101"]
   ["with-meta-bad-meta" "d81b8271636c6f6a7572652f776974682d6d657461820102"]
   ["char-not-text" "d81b826c636c6f6a7572652f6368617201"]
   ["char-two-chars" "d81b826c636c6f6a7572652f63686172626162"]
   ["period-not-text" "d81b826b6a6176612f706572696f6401"]
   ["period-bad-text" "d81b826b6a6176612f706572696f6463786f78"]
   ["typedarr-not-bytes" "d84f01"]
   ["typedarr-bad-mult" "d84f43000000"]
   ["shaped-not-array" "d99ae101"]
   ["shaped-bad-arity" "d99ae18101"]
   ["shaped-keys-not-arr" "d99ae1820180"]
   ["shaped-row-not-arr" "d99ae18281616b8101"]
   ["shaped-row-arity" "d99ae18281616b81820102"]

   ;; RFC 8949 3.3: "An encoder MUST NOT issue two-byte sequences that start
   ;; with 0xf8 ... and continue with a byte less than 0x20 (32 decimal). Such
   ;; sequences are not well-formed." The final sentence binds the DECODER --
   ;; Appendix C's pseudocode calls fail(), and these four are precisely the
   ;; ones Appendix F.1 enumerates as not-well-formed simple values.
   ;;
   ;; boring accepted all of these until now, on the strength of an Appendix A
   ;; vector that does not exist in RFC 8949 (it is RFC 7049's, removed by
   ;; Erratum 5917). Worth pinning rather than merely fixing: the two
   ;; implementations that accept `f814` -- ciborium and cbor-x -- decode it as
   ;; plain `false`, turning a malformed encoding into a valid-looking value.
   ["simple-2byte-00" "f800"]
   ["simple-2byte-01" "f801"]
   ["simple-2byte-14" "f814"]                 ; would alias onto `false`
   ["simple-2byte-1f" "f81f"]])
