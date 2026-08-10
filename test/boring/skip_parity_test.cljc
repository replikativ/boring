(ns boring.skip-parity-test
  "The two structural skips, and the two decoders, must accept the SAME
  documents.

  `boring.frame/ends-at` names one rule -- \"where the item at `p` ends, walking
  heads only\" -- and binds it to `Reader.skipFrom` on the JVM and
  `boring.reader/skip-from` on ClojureScript. It is the inner loop of
  `build-index` and of frame recognition, so a document one implementation
  skips clean and the other refuses is a file one platform will INDEX and the
  other cannot OPEN. Every table below carries the JVM's verdict as the
  expected value, because the JVM is the reference implementation.

  Nothing here is derived from either implementation's source: the inputs are
  hand-written CBOR that is malformed in one specific way each, and the
  expected verdict is what RFC 8949 says of it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [boring.conformance :refer [hex->bytes]]
            [boring.core :as b]
            #?(:cljs [boring.reader :as rd]))
  #?(:clj (:import [org.replikativ.boring Reader])))

(defn- verdict
  "`:ok`, or the `:type` of the typed error, or `:untyped` for anything else.

  `:untyped` is a distinct verdict rather than a test failure on the spot,
  because doc/SECURITY.md's guarantee is that it never happens -- so it has to
  be visible in the comparison, not swallowed by a catch."
  [f]
  (try (do (f) :ok)
       (catch #?(:clj Throwable :cljs :default) e
         (or (:type (ex-data e)) :untyped))))

(defn- skip-at
  "The platform's structural skip over the item at offset `p`."
  [bs p]
  #?(:clj (.skipFrom (Reader. ^bytes bs) (long p))
     :cljs (rd/skip-from (rd/reader bs) p)))

(defn- skip-verdict [bs] (verdict #(skip-at bs 0)))

(defn- decode-verdict [bs] (verdict #(b/decode bs)))

(defn- index-verdict [bs]
  (verdict #(b/build-index bs {:index 4 :index-min 1})))

;; ## Malformed documents the structural skip must refuse
;;
;; Every one of these was skipped CLEAN on ClojureScript and refused on the
;; JVM, so `build-index` in a browser produced an index over a document
;; neither platform can decode -- and that index is then read by `boring.nav`
;; on the JVM.

(def ^:private malformed
  [["83ff01ff0203"   :boring/unexpected-break
    "a break inside a DEFINITE array"]
   ["ff"             :boring/unexpected-break
    "a bare break"]
   ["bf01ff"         :boring/unexpected-break
    "an indefinite map that breaks between a key and its value"]
   ["5f20ff"         :boring/bad-indefinite-chunk
    "an indefinite BYTE string holding a negative integer"]
   ["7f4161ff"       :boring/bad-indefinite-chunk
    "an indefinite TEXT string holding a byte string"]
   ["5f5fffff"       :boring/reserved-info
    "an indefinite byte string holding an indefinite chunk"]
   ["1f"             :boring/reserved-info "additional-info 31 on major 0"]
   ["3f"             :boring/reserved-info "additional-info 31 on major 1"]
   ["df01"           :boring/reserved-info "additional-info 31 on major 6"]])

(deftest structural-skip-refuses-every-malformed-document-the-decoder-refuses
  (doseq [[hex expected what] malformed]
    (testing what
      (let [bs (hex->bytes hex)]
        (is (= expected (skip-verdict bs))
            (str "skip-from " hex " -- " what))
        ;; The skip and the decoder must agree that it is bad. They are allowed
        ;; to differ in HOW they say so only where a head is legal to skip and
        ;; illegal to build, which is not the case for anything here.
        (is (not= :ok (decode-verdict bs))
            (str "decode " hex " -- " what))))))

(deftest build-index-refuses-what-the-decoder-refuses
  ;; The harm F3 measured, stated directly: an index over bytes no reader can
  ;; open. `df01` and a long tag chain are excluded because `index-walk*`
  ;; collapses tag chains without skipping through them on BOTH platforms --
  ;; that is a separate, matched, behaviour.
  (doseq [[hex expected what] malformed
          :when (not= hex "df01")]
    (testing what
      (is (= expected (index-verdict (hex->bytes hex)))
          (str "build-index " hex " -- " what)))))

;; ## Unbounded nesting
;;
;; `Reader.skipLimit()` is `max(maxDepth, 1024)` and both the container nesting
;; and the tag chain are measured against it. ClojureScript had NEITHER bound:
;; nesting was flattened into an item counter that cannot see depth, and 20 000
;; open `9f` recursed once per container for an untyped `RangeError` with empty
;; ex-data -- the exact failure doc/SECURITY.md promises cannot reach a caller,
;; on the one platform browsers run.

(defn- repeat-hex [n s tail] (str (apply str (repeat n s)) tail))

(deftest structural-skip-bounds-nesting-at-the-same-place-on-both-platforms
  (testing "definite arrays"
    (is (= :ok (skip-verdict (hex->bytes (repeat-hex 1024 "81" "00")))))
    (is (= :boring/max-depth-exceeded
           (skip-verdict (hex->bytes (repeat-hex 1025 "81" "00")))))
    (is (= :boring/max-depth-exceeded
           (skip-verdict (hex->bytes (repeat-hex 2000 "81" "00"))))))
  (testing "an empty ARRAY costs no level and an empty MAP costs one, as on the JVM"
    (is (= :ok (skip-verdict (hex->bytes (repeat-hex 1024 "81" "80")))))
    (is (= :ok (skip-verdict (hex->bytes (repeat-hex 1023 "81" "a0")))))
    (is (= :boring/max-depth-exceeded
           (skip-verdict (hex->bytes (repeat-hex 1024 "81" "a0"))))))
  (testing "tag chains, which are consumed iteratively and bounded by length"
    (is (= :ok (skip-verdict (hex->bytes (repeat-hex 1024 "c0" "00")))))
    (is (= :boring/max-depth-exceeded
           (skip-verdict (hex->bytes (repeat-hex 1025 "c0" "00")))))
    (is (= :boring/max-depth-exceeded
           (skip-verdict (hex->bytes (repeat-hex 2000 "c0" "00"))))))
  (testing "indefinite containers -- 20 000 of them, TYPED on both platforms"
    (let [bs (hex->bytes (repeat-hex 20000 "9f" ""))]
      (is (= :boring/max-depth-exceeded (skip-verdict bs)))
      (is (= :boring/max-depth-exceeded (index-verdict bs))))))

;; ## Well-formed documents the structural skip must still accept
;;
;; A skip that is STRICTER than the decoder is the failure that matters: the
;; navigator would then refuse a document that decodes. These are the shapes
;; the refusals above are one byte away from.

(deftest structural-skip-still-accepts-what-the-decoder-accepts
  (doseq [[hex what] [["5f4161ff"   "a well-formed indefinite byte string"]
                      ["7f6161ff"   "a well-formed indefinite text string"]
                      ["9f0102ff"   "a well-formed indefinite array"]
                      ["bf0102ff"   "a well-formed indefinite map"]
                      ["80"         "an empty array"]
                      ["a0"         "an empty map"]
                      ["8180"       "an array holding an empty array"]
                      ["81a0"       "an array holding an empty map"]
                      ["c11a514b67b0" "a tagged value"]
                      ["9f9f01ff02ff" "nested indefinite arrays"]
                      ["bf6161bf616201ffff" "nested indefinite maps"]]]
    (testing what
      (let [bs (hex->bytes hex)]
        (is (= :ok (skip-verdict bs)) (str "skip-from " hex))
        (is (= :ok (decode-verdict bs)) (str "decode " hex))))))

;; ## Positional accessors on damaged data
;;
;; `Reader.b(long)` records this as a fixed JVM defect: a positional read past
;; the end must report `:boring/truncated-input` like every other read. The
;; ClojureScript port used a bare `aget`, which yields `undefined` -- and
;; `(bit-shift-right undefined 5)` is `0`, so offset 99 of a two-byte buffer
;; read back as an unsigned-integer head of length 0 rather than raising.
;; These four are public and `index-walk*` calls them directly.

(deftest positional-accessors-past-the-end-are-typed-truncation
  (let [bs (hex->bytes "8101")
        mk (fn [] #?(:clj (Reader. ^bytes bs) :cljs (rd/reader bs)))]
    (doseq [[nm f] [["major-at"    #(#?(:clj .majorAt :cljs rd/major-at) (mk) 99)]
                    ["head-arg-at" #(#?(:clj .headArgAt :cljs rd/head-arg-at) (mk) 99)]
                    ["head-end-at" #(#?(:clj .headEndAt :cljs rd/head-end-at) (mk) 99)]
                    ["skip-from"   #(skip-at bs 99)]]]
      (is (= :boring/truncated-input (verdict f))
          (str nm " at offset 99 of a two-byte buffer")))))

;; ## `:max-depth` costs the same on both platforms
;;
;; `:max-depth` is a security bound and the setting doc/SECURITY.md tells an
;; operator to tighten, so a pipeline that writes in a browser and reads on the
;; JVM must not reject its own documents. ClojureScript short-circuited an
;; empty map before charging a level, so `[{}]` decoded at `:max-depth 1` here
;; and raised `:boring/max-depth-exceeded` there.

(defn- min-depth
  "The smallest `:max-depth` at which `bs` decodes, or `:never` up to 12."
  [bs]
  (loop [d 1]
    (cond (> d 12) :never
          (= :ok (verdict #(b/decode bs {:max-depth d}))) d
          :else (recur (inc d)))))

(deftest an-empty-container-costs-the-same-depth-on-both-platforms
  (doseq [[hex expected what] [["8180"          1 "[[]]"]
                               ["81a0"          2 "[{}]"]
                               ["a16161a0"      2 "{\"a\" {}}"]
                               ["8201a0"        2 "[1 {}]"]
                               ["d87ba0"        2 "123({})"]
                               ["8181a0"        3 "[[{}]]"]
                               ["d87b82613fa0"  3 "123([\"?\" {}])"]
                               ["81828080"      2 "[[[] []]]"]
                               ["a0"            1 "{}"]
                               ["80"            1 "[]"]]]
    (testing what
      (is (= expected (min-depth (hex->bytes hex)))
          (str "smallest :max-depth that decodes " hex)))))

;; ## `encode-indexed` and `:stringref`

(defn- count-bytes [bs]
  #?(:clj (alength ^bytes bs) :cljs (.-length bs)))

(deftest encode-indexed-composes-with-stringref-on-both-platforms
  (let [specimen (vec (repeat 20 "aaaaaaaaaa"))]
    (testing "an index and a stringref namespace now compose. They were
              mutually exclusive because a cursor holding an offset could not
              resolve a reference into a table built from every preceding
              string; the frame's pointer table gives it the defining offset of
              every slot something references, so it resolves by JUMPING. Both
              platforms lifted the refusal together -- ClojureScript's
              `build-index` re-derives the same numbering by walking the
              finished bytes in document order, which is the order the JVM's
              streaming writer assigns indices in."
      (is (= :ok (verdict #(b/encode-indexed specimen {:index 4 :index-min 4
                                                       :stringref true})))))
    (testing "and the option is still VALIDATED, not overwritten unread"
      (is (= :boring/bad-option
             (verdict #(b/encode-indexed specimen {:index 2 :index-min 1
                                                   :stringref "yes"})))))
    (testing "an explicit false, and no mention at all, both still work"
      (is (= :ok (verdict #(b/encode-indexed specimen {:index 4 :index-min 4
                                                       :stringref false}))))
      (is (= :ok (verdict #(b/encode-indexed specimen {:index 4 :index-min 4})))))
    (testing "and the compression is real: 20 copies of one string cost far
              less with references than without, index frame included"
      (is (< (count-bytes (b/encode-indexed specimen {:index 4 :index-min 4
                                                      :stringref true}))
             (count-bytes (b/encode-indexed specimen {:index 4 :index-min 4
                                                      :stringref false})))))))

;; ## Tag readers that implemented the same tag differently
;;
;; Each of these is one rule with two implementations. The expected value is
;; the JVM's in every case EXCEPT tag 1002 and tag 39649, where the JVM was the
;; wrong side and moved; both are called out below.

(defn- decode-type [bs] (verdict #(b/decode bs)))

(deftest tag-readers-accept-and-refuse-the-same-documents
  (doseq [[hex expected what]
          [;; The JVM range-checks a decimal-fraction exponent against the
           ;; 32-bit scale a BigDecimal can carry; ClojureScript did not, and
           ;; produced a Decimal no JVM peer can construct.
           ["c4821a8000000001" :boring/bad-tag-content
            "tag 4 with exponent 2^31"]
           ["c48221 1896"      :ok
            "tag 4 with an ordinary exponent still decodes"]

           ;; THE JVM MOVED HERE. It used the fraction VALUE as its
           ;; "fraction seen" flag, so a CBOR null degraded the key to absent.
           ["d903eaa2010528f6" :boring/bad-tag-content
            "tag 1002 with a null scaled fraction"]
           ;; And with the key not registered, RFC 9581 3.3's "at most one
           ;; scaled fraction" could never fire.
           ["d903eaa3010528f6 2201" :boring/bad-tag-content
            "tag 1002 with a null fraction AND a real one"]
           ["d903eaa2010528 1a3b9ac9ff" :ok
            "tag 1002 with one real scaled fraction still decodes"]
           ["d903eaa10105" :ok
            "tag 1002 with no fraction at all still decodes"]

           ;; THE JVM MOVED HERE TOO. Shape-key distinctness fell out of
           ;; building each row, so zero rows checked nothing.
           ["d99ae182820101 80" :boring/bad-tag-content
            "a shaped array with duplicate keys and ZERO rows"]
           ["d99ae18282010181820102" :boring/bad-tag-content
            "a shaped array with duplicate keys and one row"]
           ["d99ae182820102 81820304" :ok
            "a shaped array with distinct keys still decodes"]

           ;; `java.net.URI` requires a non-empty scheme-specific part; the
           ;; ClojureScript grammar approximation did not.
           ["d82062613a" :boring/bad-tag-content
            "tag 32 \"a:\" -- a scheme with an EMPTY scheme-specific part"]
           ["d820647572 6e3a" :boring/bad-tag-content
            "tag 32 \"urn:\" -- the same, with a longer scheme"]
           ["d8206461 3a2366" :boring/bad-tag-content
            "tag 32 \"a:#f\" -- the part ends at the fragment, not the string"]
           ["d820 64613a3f71" :ok
            "tag 32 \"a:?q\" -- a query IS a scheme-specific part"]
           ["d82063613a62" :ok
            "tag 32 \"a:b\" still decodes"]]]
    (testing what
      (is (= expected (decode-type (hex->bytes (str/replace hex " " ""))))
          (str hex " -- " what)))))

(deftest a-shaped-arrays-keys-must-be-distinct-whatever-the-map-options-say
  (testing "`:check-duplicate-keys` is an option about MAP CONTENT. Repeated
            SHAPE keys make the shape itself meaningless -- the row values have
            nowhere distinct to land -- so no decode option may turn that check
            off. The JVM gated it and ClojureScript did not, and with the option
            off the same bytes were `[{1 2}]` there and refused here."
    (let [bs (hex->bytes "d99ae18282010181820102")]
      (is (= :boring/bad-tag-content
             (verdict #(b/decode bs {:check-duplicate-keys false}))))
      (is (= :boring/bad-tag-content
             (verdict #(b/decode bs {:check-duplicate-keys true})))))))

;; ## Round trips
;;
;; A verdict can agree while the VALUE does not, so these compare the bytes a
;; decode/re-encode produces. That is the property that matters for a document
;; passing through one platform on its way to the other.

(defn- to-hex [bs]
  (let [n #?(:clj (alength ^bytes bs) :cljs (.-length bs))]
    (apply str (for [i (range n)]
                 (let [b (bit-and (aget bs i) 0xFF)]
                   (str (when (< b 16) "0")
                        #?(:clj (Integer/toString b 16) :cljs (.toString b 16))))))))

(deftest tag-30-round-trips-to-the-same-bytes-on-both-platforms
  (testing "The JVM reads tag 30 through `clojure.lang.Numbers.divide`, which
            reduces to lowest terms, puts the sign on the numerator, and yields
            an INTEGER when the denominator reduces to 1. ClojureScript kept
            whatever was on the wire, so `30([4,2])` re-encoded as itself
            instead of as an integer, and `30([1,-2])` and `30([-1,2])` stayed
            two different values.

            Compared as bytes rather than as values, because the two platforms
            genuinely have different types here -- `clojure.lang.Ratio` against
            `boring.data/Rational` -- and the bytes are what crosses."
    (doseq [[hex expected what]
            [["d81e820402" "c24102"     "30([4,2]) reduces to the integer 2"]
             ["d81e820201" "c24102"     "30([2,1]) is already an integer"]
             ["d81e820121" "d81e822002" "30([1,-2]) puts the sign on top"]
             ["d81e822002" "d81e822002" "30([-1,2]) is already normal"]
             ["d81e82021864" "d81e82011832" "30([2,100]) reduces to 1/50"]
             ["d81e820103" "d81e820103" "30([1,3]) is already lowest terms"]]]
      (testing what
        (is (= expected
               (to-hex (b/encode (b/decode (hex->bytes hex)) {:stringref false})))
            (str hex " -- " what))))))
