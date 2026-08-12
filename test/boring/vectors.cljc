;; `appendix-a` was SEEDED from https://github.com/cbor/test-vectors (RFC 8949
;; Appendix A) and has been curated by hand since. It is not machine-generated
;; output: it deviates from that upstream file where the file is wrong, and each
;; deviation carries a comment saying why — see `f818` below, which upstream
;; still lists and RFC 8949 does not have.
;;
;; The header used to say "GENERATED ... regenerate with scratchpad/gen_vectors.py
;; — do not hand-edit", which was misleading twice over: that script has never
;; been in this repository, and the list had already been hand-edited when the
;; instruction was written. It cost a contributor a wrong assumption in both
;; directions at once.
;;
;; So: EDIT THIS BY HAND, and say why in a comment. But keep `appendix-a` to
;; vectors that are actually in Appendix A — a test asserting "every RFC 8949
;; Appendix A vector decodes" should mean it. Vectors of boring's own go in
;; `regression` at the bottom.
;;
;; Fixture markers (realised by boring.conformance/->expected):
;;   [:bytes b...]      byte array          [:tagged n v]  tagged value
;;   [:simple n]        simple value        :undefined     the undefined value
;;   [:bigint "n"]      arbitrary integer
;;   [:f16 x] [:f32 x] [:f64 x]  float of a specific encoded width
;;
;; :roundtrip true means "re-encoding the decoded value reproduces :hex".
;; That holds only under the :shortest float policy — see :float-width.

(ns boring.vectors)

(def appendix-a
  [{:hex "00" :value 0 :roundtrip true}
   {:hex "01" :value 1 :roundtrip true}
   {:hex "0a" :value 10 :roundtrip true}
   {:hex "17" :value 23 :roundtrip true}
   {:hex "1818" :value 24 :roundtrip true}
   {:hex "1819" :value 25 :roundtrip true}
   {:hex "1864" :value 100 :roundtrip true}
   {:hex "1903e8" :value 1000 :roundtrip true}
   {:hex "1a000f4240" :value 1000000 :roundtrip true}
   {:hex "1b000000e8d4a51000" :value 1000000000000 :roundtrip true}
   {:hex "1bffffffffffffffff" :value [:bigint "18446744073709551615"] :roundtrip true}
   {:hex "c249010000000000000000" :value [:bigint "18446744073709551616"] :roundtrip true}
   {:hex "3bffffffffffffffff" :value [:bigint "-18446744073709551616"] :roundtrip true}
   {:hex "c349010000000000000000" :value [:bigint "-18446744073709551617"] :roundtrip true}
   {:hex "20" :value -1 :roundtrip true}
   {:hex "29" :value -10 :roundtrip true}
   {:hex "3863" :value -100 :roundtrip true}
   {:hex "3903e7" :value -1000 :roundtrip true}
   {:hex "f90000" :value [:f16 0.0] :roundtrip true :float-width :f16}
   {:hex "f98000" :value [:f16 -0.0] :roundtrip true :float-width :f16}
   {:hex "f93c00" :value [:f16 1.0] :roundtrip true :float-width :f16}
   {:hex "fb3ff199999999999a" :value [:f64 1.1] :roundtrip true :float-width :f64}
   {:hex "f93e00" :value [:f16 1.5] :roundtrip true :float-width :f16}
   {:hex "f97bff" :value [:f16 65504.0] :roundtrip true :float-width :f16}
   {:hex "fa47c35000" :value [:f32 100000.0] :roundtrip true :float-width :f32}
   {:hex "fa7f7fffff" :value [:f32 3.4028234663852886e+38] :roundtrip true :float-width :f32}
   {:hex "fb7e37e43c8800759c" :value [:f64 1e+300] :roundtrip true :float-width :f64}
   {:hex "f90001" :value [:f16 5.960464477539063e-08] :roundtrip true :float-width :f16}
   {:hex "f90400" :value [:f16 6.103515625e-05] :roundtrip true :float-width :f16}
   {:hex "f9c400" :value [:f16 -4.0] :roundtrip true :float-width :f16}
   {:hex "fbc010666666666666" :value [:f64 -4.1] :roundtrip true :float-width :f64}
   {:hex "f97c00" :value [:f16 ##Inf] :roundtrip true :diag "Infinity" :float-width :f16}
   {:hex "f97e00" :value [:f16 ##NaN] :roundtrip true :diag "NaN" :float-width :f16}
   {:hex "f9fc00" :value [:f16 ##-Inf] :roundtrip true :diag "-Infinity" :float-width :f16}
   {:hex "fa7f800000" :value [:f32 ##Inf] :roundtrip false :diag "Infinity" :float-width :f32 :decode-only true}
   {:hex "fa7fc00000" :value [:f32 ##NaN] :roundtrip false :diag "NaN" :float-width :f32 :decode-only true}
   {:hex "faff800000" :value [:f32 ##-Inf] :roundtrip false :diag "-Infinity" :float-width :f32 :decode-only true}
   {:hex "fb7ff0000000000000" :value [:f64 ##Inf] :roundtrip false :diag "Infinity" :float-width :f64 :decode-only true}
   {:hex "fb7ff8000000000000" :value [:f64 ##NaN] :roundtrip false :diag "NaN" :float-width :f64 :decode-only true}
   {:hex "fbfff0000000000000" :value [:f64 ##-Inf] :roundtrip false :diag "-Infinity" :float-width :f64 :decode-only true}
   {:hex "f4" :value false :roundtrip true}
   {:hex "f5" :value true :roundtrip true}
   {:hex "f6" :value nil :roundtrip true}
   {:hex "f7" :value :undefined :roundtrip true :diag "undefined"}
   {:hex "f0" :value [:simple 16] :roundtrip true :diag "simple(16)"}
   ;; `f818` / simple(24) DELIBERATELY ABSENT. It was carried here as an
   ;; Appendix A vector that boring could decode but not encode. It is not an
   ;; RFC 8949 Appendix A vector: that row is RFC 7049's, deleted by Erratum
   ;; 5917, and RFC 8949's table jumps from simple(16) straight to simple(255).
   ;; RFC 8949 3.3 makes `f8 00`..`f8 1f` NOT WELL-FORMED, so the correct
   ;; behaviour is to reject it on decode -- see boring.hostile, which pins the
   ;; four values Appendix F.1 enumerates.
   {:hex "f8ff" :value [:simple 255] :roundtrip true :diag "simple(255)"}
   {:hex "c074323031332d30332d32315432303a30343a30305a" :value [:instant "2013-03-21T20:04:00Z"] :roundtrip true :diag "0(\"2013-03-21T20:04:00Z\")"}
   {:hex "c11a514b67b0" :value [:instant "2013-03-21T20:04:00Z"] :roundtrip true :diag "1(1363896240)" :encoding-differs true :encoding-differs-reason "source uses tag 1 (epoch); boring writes instants as tag 0 (RFC 3339), which is lossless where a float epoch is not. Decodes to the same instant."}
   {:hex "c1fb41d452d9ec200000" :value [:instant "2013-03-21T20:04:00.5Z"] :roundtrip true :diag "1(1363896240.5)" :encoding-differs true :encoding-differs-reason "source uses tag 1 (epoch float); boring writes instants as tag 0 (RFC 3339)."}
   ;; A `.678` sibling for the vector above lives in `regression` — it is not an
   ;; Appendix A vector, so it does not belong in this list.
   {:hex "d74401020304" :value [:tagged 23 [:bytes 1 2 3 4]] :roundtrip true :diag "23(h'01020304')"}
   {:hex "d818456449455446" :value [:tagged 24 [:bytes 100 73 69 84 70]] :roundtrip true :diag "24(h'6449455446')"}
   {:hex "d82076687474703a2f2f7777772e6578616d706c652e636f6d" :value [:uri "http://www.example.com"] :roundtrip true :diag "32(\"http://www.example.com\")"}
   {:hex "40" :value [:bytes] :roundtrip true :diag "h''"}
   {:hex "4401020304" :value [:bytes 1 2 3 4] :roundtrip true :diag "h'01020304'"}
   {:hex "60" :value "" :roundtrip true}
   {:hex "6161" :value "a" :roundtrip true}
   {:hex "6449455446" :value "IETF" :roundtrip true}
   {:hex "62225c" :value "\"\\" :roundtrip true}
   {:hex "62c3bc" :value "\u00fc" :roundtrip true}
   {:hex "63e6b0b4" :value "\u6c34" :roundtrip true}
   {:hex "64f0908591" :value "\ud800\udd51" :roundtrip true}
   {:hex "80" :value [] :roundtrip true}
   {:hex "83010203" :value [1 2 3] :roundtrip true}
   {:hex "8301820203820405" :value [1 [2 3] [4 5]] :roundtrip true}
   {:hex "98190102030405060708090a0b0c0d0e0f101112131415161718181819" :value [1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25] :roundtrip true}
   {:hex "a0" :value {} :roundtrip true}
   {:hex "a201020304" :value {1 2, 3 4} :roundtrip true :diag "{1: 2, 3: 4}"}
   {:hex "a26161016162820203" :value {"a" 1, "b" [2 3]} :roundtrip true}
   {:hex "826161a161626163" :value ["a" {"b" "c"}] :roundtrip true}
   {:hex "a56161614161626142616361436164614461656145" :value {"a" "A", "b" "B", "c" "C", "d" "D", "e" "E"} :roundtrip true}
   {:hex "5f42010243030405ff" :value [:bytes 1 2 3 4 5] :roundtrip false :diag "(_ h'0102', h'030405')" :decode-only true}
   {:hex "7f657374726561646d696e67ff" :value "streaming" :roundtrip false :decode-only true}
   {:hex "9fff" :value [] :roundtrip false :decode-only true}
   {:hex "9f018202039f0405ffff" :value [1 [2 3] [4 5]] :roundtrip false :decode-only true}
   {:hex "9f01820203820405ff" :value [1 [2 3] [4 5]] :roundtrip false :decode-only true}
   {:hex "83018202039f0405ff" :value [1 [2 3] [4 5]] :roundtrip false :decode-only true}
   {:hex "83019f0203ff820405" :value [1 [2 3] [4 5]] :roundtrip false :decode-only true}
   {:hex "9f0102030405060708090a0b0c0d0e0f101112131415161718181819ff" :value [1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25] :roundtrip false :decode-only true}
   {:hex "bf61610161629f0203ffff" :value {"a" 1, "b" [2 3]} :roundtrip false :decode-only true}
   {:hex "826161bf61626163ff" :value ["a" {"b" "c"}] :roundtrip false :decode-only true}
   {:hex "bf6346756ef563416d7421ff" :value {"Fun" true, "Amt" -2} :roundtrip false :decode-only true}])

;; Vectors of boring's OWN, in the same shape as `appendix-a` and run through
;; the same machinery — but not from Appendix A, so kept apart from a list whose
;; name is a claim about where its contents came from.
;;
;; These are regressions: each one is a decode that was WRONG, pinned by the
;; bytes that were wrong. Because this file is `.cljc` they also hold the two
;; runtimes to the same answer for the same bytes, which is where a reader
;; defect most easily hides — a divergence needs no failing assertion on either
;; platform alone to be a bug.
(def regression
  [;; A tag-1 float whose value is NOT a dyadic rational.
   ;;
   ;; `1(1363896240.5)` in `appendix-a` was the only tag-1 float vector, and one
   ;; half is exactly representable in binary — it survives any decomposition,
   ;; so it could not fail. Meanwhile the JVM reader rebuilt sub-second
   ;; precision as `(d - secs) * 1e9`, and at epoch scale a double resolves to
   ;; about 100ns: `.678` came back as 677999973ns and truncated to `.677`.
   ;; Roughly half of all millisecond instants read one millisecond early, never
   ;; late, and no test noticed.
   ;;
   ;; ClojureScript was already exact for these bytes, so this vector is also
   ;; the parity pin: the platforms disagreed, and neither was failing.
   {:hex "c1fb41cb46c652d6c8b4" :value [:instant "1999-01-02T03:04:05.678Z"] :roundtrip true :diag "1(915246245.678)" :encoding-differs true :encoding-differs-reason "source uses tag 1 (epoch float); boring writes instants as tag 0 (RFC 3339)."}])
