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
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest encode-indexed-refuses-stringref-on-both-platforms
  (let [specimen (vec (repeat 20 "aaaaaaaaaa"))]
    (testing "an index and a stringref namespace cannot both be useful"
      (is (= :boring/incompatible-options
             (verdict #(b/encode-indexed specimen {:index 4 :index-min 4
                                                   :stringref true})))))
    (testing "and the option is VALIDATED, not overwritten unread"
      (is (= :boring/bad-option
             (verdict #(b/encode-indexed specimen {:index 2 :index-min 1
                                                   :stringref "yes"})))))
    (testing "an explicit false, and no mention at all, both still work"
      (is (= :ok (verdict #(b/encode-indexed specimen {:index 4 :index-min 4
                                                       :stringref false}))))
      (is (= :ok (verdict #(b/encode-indexed specimen {:index 4 :index-min 4})))))))
