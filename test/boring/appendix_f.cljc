(ns boring.appendix-f
  "RFC 8949 Appendix F.1 in full: every byte sequence the specification itself
  names as NOT WELL-FORMED, as [label hex].

  This exists because `boring.wg-bad` is not a superset of it. That file is a
  faithful copy of the CBOR working group's `cbor-test-vectors` corpus, and the
  working group's corpus and the RFC's own list are different documents --
  comparing them entry by entry, the WG file was missing:

    - subkind 2 entirely (`f8 00`, `f8 01`, `f8 18`, `f8 1f`), which is exactly
      the gap that let boring decode reserved two-byte simple values for as
      long as it did;
    - subkind 5 entirely (`1f`, `3f`, `df` -- major type 0, 1, 6 with additional
      information 31);
    - 18 of the 24 reserved additional-information bytes;
    - 8 of the 10 subkind-3 chunk cases, including both nested-indefinite ones;
    - the large-declared-length cases (`5a ff ff ff ff 00`,
      `5b ff..ff 01 02 03`, and the text-string equivalents), which are the ones
      that specifically test that a decoder does not preallocate on a length it
      read off the wire;
    - `c0`, a tag number with no content.

  boring passes all 94. It passed them before this file existed too -- the
  point is that nothing PINNED them, so a regression would have been caught by
  whichever future reviewer happened to look, which is the same reason the
  simple-value bug survived.

  Every entry must raise an ex-info carrying a `:type`. Decoding successfully
  is a failure, and so is a raw JVM or JS exception.

  Transcribed from the RFC text rather than generated from an upstream file, so
  the provenance is the specification and not somebody's reading of it."
  (:require [boring.conformance]))

(def cases
  [;; ---- kind 2: too little data ---------------------------------------
   ;; "A premature end of the input can occur in a head or within the
   ;; enclosed data, which may be bare strings or enclosed data items that
   ;; are either counted or should have been ended by a break stop code."

   ;; End of input in a head
   ["head-18"                  "18"]
   ["head-19"                  "19"]
   ["head-1a"                  "1a"]
   ["head-1b"                  "1b"]
   ["head-19-01"               "1901"]
   ["head-1a-0102"             "1a0102"]
   ["head-1b-7"                "1b01020304050607"]
   ["head-38"                  "38"]
   ["head-58"                  "58"]
   ["head-78"                  "78"]
   ["head-98"                  "98"]
   ["head-9a-01ff00"           "9a01ff00"]
   ["head-b8"                  "b8"]
   ["head-d8"                  "d8"]
   ["head-f8"                  "f8"]
   ["head-f9-00"               "f900"]
   ["head-fa-0000"             "fa0000"]
   ["head-fb-000000"           "fb000000"]

   ;; Definite-length strings with short data. The 5a/5b/7a/7b entries declare
   ;; lengths up to 2^64-1 with three bytes behind them: the check that a
   ;; declared length is validated against the bytes REMAINING rather than
   ;; allocated on faith.
   ["bstr-41-short"            "41"]
   ["tstr-61-short"            "61"]
   ["bstr-4gb-declared"        "5affffffff00"]
   ["bstr-16eb-declared"       "5bffffffffffffffff010203"]
   ["tstr-4gb-declared"        "7affffffff00"]
   ["tstr-8eb-declared"        "7b7fffffffffffffff010203"]

   ;; Definite-length maps and arrays not closed with enough items
   ["array-81-empty"           "81"]
   ["array-nested-9-deep"      "818181818181818181"]
   ["array-82-one-item"        "8200"]
   ["map-a1-empty"             "a1"]
   ["map-a2-one-pair"          "a20102"]
   ["map-a1-key-only"          "a100"]
   ["map-a2-three-items"       "a2000000"]

   ;; Tag number not followed by tag content
   ["tag-c0-no-content"        "c0"]

   ;; Indefinite-length strings not closed by a break stop code
   ["indef-bstr-unclosed"      "5f4100"]
   ["indef-tstr-unclosed"      "7f6100"]

   ;; Indefinite-length maps and arrays not closed by a break stop code
   ["indef-array-bare"         "9f"]
   ["indef-array-items"        "9f0102"]
   ["indef-map-bare"           "bf"]
   ["indef-map-pairs"          "bf01020102"]
   ["indef-array-in-definite"  "819f"]
   ["indef-array-nested-open"  "9f8000"]
   ["indef-array-5-deep"       "9f9f9f9f9fffffffff"]
   ["indef-array-mixed-open"   "9f819f819f9fffffff"]

   ;; ---- kind 3, subkind 1: reserved additional information ------------
   ["reserved-ai-1c"           "1c"]
   ["reserved-ai-1d"           "1d"]
   ["reserved-ai-1e"           "1e"]
   ["reserved-ai-3c"           "3c"]
   ["reserved-ai-3d"           "3d"]
   ["reserved-ai-3e"           "3e"]
   ["reserved-ai-5c"           "5c"]
   ["reserved-ai-5d"           "5d"]
   ["reserved-ai-5e"           "5e"]
   ["reserved-ai-7c"           "7c"]
   ["reserved-ai-7d"           "7d"]
   ["reserved-ai-7e"           "7e"]
   ["reserved-ai-9c"           "9c"]
   ["reserved-ai-9d"           "9d"]
   ["reserved-ai-9e"           "9e"]
   ["reserved-ai-bc"           "bc"]
   ["reserved-ai-bd"           "bd"]
   ["reserved-ai-be"           "be"]
   ["reserved-ai-dc"           "dc"]
   ["reserved-ai-dd"           "dd"]
   ["reserved-ai-de"           "de"]
   ["reserved-ai-fc"           "fc"]
   ["reserved-ai-fd"           "fd"]
   ["reserved-ai-fe"           "fe"]

   ;; ---- kind 3, subkind 2: reserved two-byte simple values ------------
   ;; RFC 8949 3.3: "An encoder MUST NOT issue two-byte sequences that start
   ;; with 0xf8 ... and continue with a byte less than 0x20 (32 decimal). Such
   ;; sequences are not well-formed." boring accepted all four until recently,
   ;; on the strength of an Appendix A row that RFC 7049 had and RFC 8949
   ;; deleted (Erratum 5917, cited in Appendix G.1). These are why this file
   ;; exists.
   ["two-byte-simple-00"       "f800"]
   ["two-byte-simple-01"       "f801"]
   ["two-byte-simple-18"       "f818"]
   ["two-byte-simple-1f"       "f81f"]

   ;; ---- kind 3, subkind 3: indefinite-length string chunks ------------
   ;; Chunks not of the correct type
   ["chunk-uint-in-bstr"       "5f00ff"]
   ["chunk-nint-in-bstr"       "5f21ff"]
   ["chunk-tstr-in-bstr"       "5f6100ff"]
   ["chunk-array-in-bstr"      "5f80ff"]
   ["chunk-map-in-bstr"        "5fa0ff"]
   ["chunk-tag-in-bstr"        "5fc000ff"]
   ["chunk-simple-in-bstr"     "5fe0ff"]
   ["chunk-bstr-in-tstr"       "7f4100ff"]
   ;; Chunks not definite length
   ["chunk-indef-in-bstr"      "5f5f4100ffff"]
   ["chunk-indef-in-tstr"      "7f7f6100ffff"]

   ;; ---- kind 3, subkind 4: misplaced break -----------------------------
   ["break-alone"              "ff"]
   ["break-in-array-81"        "81ff"]
   ["break-in-array-82"        "8200ff"]
   ["break-in-map-key"         "a1ff"]
   ["break-as-map-key"         "a1ff00"]
   ["break-in-map-value"       "a100ff"]
   ["break-in-map-a2"          "a20000ff"]
   ["break-in-nested-definite" "9f81ff"]
   ["break-deeply-nested"      "9f829f819f9fffffffff"]
   ;; Break in an indefinite-length map's VALUE position, leaving an odd
   ;; number of items
   ["break-odd-map-1"          "bf00ff"]
   ["break-odd-map-2"          "bf00000000ff"]

   ;; ---- kind 3, subkind 5: major type 0, 1, 6 with ai 31 ---------------
   ;; Not reserved for major type 4 or 5, where ai 31 means indefinite length.
   ["uint-ai31"                "1f"]
   ["nint-ai31"                "3f"]
   ["tag-ai31"                 "df"]])
