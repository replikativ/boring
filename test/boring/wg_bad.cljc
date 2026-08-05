;; GENERATED from cbor-wg/cbor-test-vectors tests/rfc8949/bad.edn
;; Regenerate with dev/gen_wg_bad.py — do not hand-edit.
;;
;; The official CBOR working group's not-well-formed corpus. Every entry
;; must be REJECTED with a typed boring error. `:exempt-reason` marks entries
;; we deliberately accept under default options, with the reason.
;;
;; NOT A SUPERSET of RFC 8949 Appendix F.1 -- see boring.appendix-f, which
;; carries the RFC's own list in full and enumerates what this file lacks.
;; Run both; neither subsumes the other.
;;
;; Two entries here are inherited mislabels: the tag-0 and tag-1 date cases
;; (`c0a1616100`, `c1a1616100`) are VALIDITY errors, not well-formedness
;; ones. boring rejects them either way, so they are left as upstream has
;; them rather than diverging from the corpus this file exists to mirror.

(ns boring.wg-bad)

(def cases
  [{:desc "Missing the next byte for mt0 ai 24" :hex "18"}
   {:desc "Missing the next 2 bytes for mt0 ai 25" :hex "19"}
   {:desc "Missing the next 1 byte for mt0 ai 25" :hex "1900"}
   {:desc "Missing the next 4 bytes for mt0 ai 26" :hex "1a"}
   {:desc "Missing the next 3 bytes for mt0 ai 26" :hex "1a00"}
   {:desc "Missing the next 2 bytes for mt0 ai 26" :hex "1a0000"}
   {:desc "Missing the next byte for mt0 ai 26" :hex "1a000000"}
   {:desc "Missing the next 4 bytes for mt0 ai 27" :hex "1b000000"}
   {:desc "Invalid AI: 28" :hex "1c"}
   {:desc "Invalid AI: 29" :hex "1d"}
   {:desc "Invalid AI: 30" :hex "1e"}
   {:desc "Invalid streaming AI: 28" :hex "fc"}
   {:desc "Invalid streaming AI: 29" :hex "fd"}
   {:desc "Invalid streaming AI: 30" :hex "fe"}
   {:desc "bytes: Only 3 bytes, not 4" :hex "44010203"}
   {:desc "bytes: Indeterminate bytestring with nothing" :hex "5f"}
   {:desc "bytes: Indeterminate bytestring includes a non-bytes chunk" :hex "5f01ff"}
   {:desc "utf8: Only 3 bytes, not 4" :hex "64494554"}
   {:desc "utf8: Length 20 only has 4 bytes" :hex "7432303133"}
   {:desc "utf8: Indeterminate string includes a non-string chunk" :hex "7f01ff"}
   {:desc "utf8: no BREAK" :hex "7f657374726561646d696e"}
   {:desc "utf8: invalid utf8" :hex "62c0ae"}
   {:desc "array: missing item" :hex "81"}
   {:desc "array: missing second item" :hex "8201"}
   {:desc "array: nested missing item" :hex "8181818181"}
   {:desc "array: deeply-nested missing item" :hex "8181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181818181" :exempt-reason "a recursion bomb ~500 deep; boring's default :max-depth is 1024, so this is a depth-policy choice rather than a well-formedness failure. Rejected with :max-depth 256."}
   {:desc "array: invalid item" :hex "81fe"}
   {:desc "array: indeterminate without end" :hex "9f"}
   {:desc "array: indeterminate with an item, without end" :hex "9f01"}
   {:desc "array: streamed containing invalid" :hex "9ffeff"}
   {:desc "array: unexpected BREAK" :hex "91ff"}
   {:desc "map: expected key" :hex "a1"}
   {:desc "map: invalid key" :hex "a1fe01"}
   {:desc "map: missing value" :hex "a16161"}
   {:desc "map: invalid value" :hex "a16161fe"}
   {:desc "map: 1 key expecting 2" :hex "a20102"}
   {:desc "map: streaming no BREAK" :hex "bf"}
   {:desc "map: streaming, odd number of items" :hex "bf000103ff"}
   {:desc "map: streaming missing value" :hex "bf6161"}
   {:desc "map: streaming with item, missing BREAK" :hex "bf616101"}
   {:desc "map: streaming with invalid key" :hex "bffe01"}
   {:desc "map: streaming with invalid value" :hex "bf01fe"}
   {:desc "map: unexpected BREAK in key" :hex "a1ff"}
   {:desc "map: unexpected BREAK in value" :hex "a100ff"}
   {:desc "unexpected BREAK" :hex "ff"}
   {:desc "date: unexpected object instead of offset" :hex "c1a1616100"}
   {:desc "date: unexpected object instead of string" :hex "c0a1616100"}])
