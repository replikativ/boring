(ns boring.index-robustness-test
  "Every test here is a bug that shipped on this branch and was found by review
  rather than by the suite.

  They share a theme, and it is the claim the whole index rests on: **the index
  is an optimisation and is never load-bearing for correctness.** A missing,
  stale, truncated or randomly corrupt index may cost speed; it may not change
  an answer and it may not throw at the caller. Six findings all violated that,
  and none of them needed hostile input -- four fire on ordinary data at the
  shipped defaults.

  That claim is QUALIFIED, and the qualification belongs beside it: it does not
  extend to a CRAFTED index. Checking that every anchor is a real entry boundary
  is O(n) per container, and checking that `sorted` is truthful means reading
  every key -- both exactly the work the index exists to avoid. A deliberately
  lying index can therefore still misdirect a lookup, so the index frame is a
  trust boundary: integrity of the index is integrity of the document. This
  namespace's docstring previously asserted the unqualified version.
  See doc/SHAPES.md.

  The suite missed them for reasons worth recording, because they are reasons a
  suite can miss things again:

    - the differential test compares the two index builders against EACH OTHER,
      so a bug both of them have is invisible to it;
    - the container tests use 300-key maps, and the sortedness bug needs FEW
      anchors, so a wide map essentially never triggers it;
    - the corruption test flipped a byte to 0x7F, which lands out of range,
      rather than to a byte that parses as something dangerous."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring]
            [boring.nav :as nav]
            [boring.frame]
            [boring.conformance :as c]
            [boring.data])
  (:import (java.io ByteArrayOutputStream)
           (org.replikativ.boring Reader)))

(defn- foreign-items
  "Every top-level item INCLUDING any index frame -- what a CBOR reader that
  knows nothing about `boring/index` sees. `decode-seq` hides a trailing frame,
  so it is no longer a stand-in for one."
  [^bytes bs]
  (let [r (Reader. bs)]
    (loop [acc []]
      (if (.atEnd r) acc (recur (conj acc (.readNext r)))))))

(def opts {:stringref false})

(defn- seal ^bytes [vs o]
  (let [w (boring/writer 65536 o)
        out (ByteArrayOutputStream.)]
    (boring/write-seq! w vs out (merge o {:index 16}))
    (.toByteArray out)))

;; ---------------------------------------------------------------- finding 1

(deftest sortedness-is-decided-by-every-key-not-a-sample
  (testing "`sorted` licenses `lookup-map` to binary-search the anchors and then
            scan ONLY the stride it lands in. That is valid just when the whole
            container is ordered, and ascending ANCHORS do not establish it --
            they are a sample. At the default stride of 16 a map of 17-32
            entries has two anchors, so an unordered map had a coin-flip chance
            of being marked sorted, and then a key that exists returned nil.

            Measured before the fix: 105 of 200 random 20-entry maps had at
            least one such key, 797 wrong lookups in 4000."
    (dotimes [_ 40]
      (let [ks (shuffle (mapv #(format "k%03d" %) (range 20)))
            m (reduce (fn [^java.util.LinkedHashMap a k]
                        (doto a (.put k (subs k 1))))
                      (java.util.LinkedHashMap.) ks)
            c (nav/source (boring/encode-indexed m (assoc opts :index 16 :index-min 16))
                          opts)]
        (doseq [k ks]
          (is (= (subs k 1) (some-> (get c k) nav/value))
              (str "key " k " went missing in insertion order " (pr-str ks))))))))

(deftest both-index-builders-agree-on-sortedness
  (testing "the walk had the identical defect, which is exactly why the
            differential test could not see it: an oracle that shares the bug
            proves nothing. Here the oracle is the DECODED map."
    (dotimes [_ 20]
      (let [ks (shuffle (mapv #(format "k%03d" %) (range 20)))
            m (reduce (fn [^java.util.LinkedHashMap a k] (doto a (.put k (subs k 1))))
                      (java.util.LinkedHashMap.) ks)
            walked (boring/build-index (boring/encode m opts)
                                       (assoc opts :index 16 :index-min 16))
            claimed (boolean (first (:sorted walked)))
            truly (= (vec ks) (vec (sort ks)))]
        (is (or (not claimed) truly)
            (str "index claims sorted for unordered keys " (pr-str ks)))))))

;; ---------------------------------------------------------------- finding 3

(deftest the-legacy-canonical-comparator-is-not-the-readers
  (testing ":profile :canonical-rfc7049 sorts keys LENGTH FIRST, while every
            reader here compares plain bytewise. The writer recorded sorted=true
            because it had just sorted -- without asking which comparator -- and
            a binary search then used an order the file is not in. Short text
            keys against large integer keys is where the two genuinely diverge."
    (let [o {:profile :canonical-rfc7049}
          m (into {} (concat (for [i (range 10)] [(str "s" i) (str "text" i)])
                             (for [i (range 10)] [(+ 1000000 i) (str "int" i)])))
          w (boring/writer 65536 o)
          out (ByteArrayOutputStream.)]
      (boring/write-seq! w [m] out (assoc o :index 4 :index-min 4))
      (let [c (nth (nav/items (.toByteArray out) o) 0)]
        (doseq [[k v] m]
          (is (= v (some-> (get c k) nav/value)) (str "key " (pr-str k))))))))

;; ---------------------------------------------------------------- finding 2

(deftest nth-works-on-an-index-with-no-sequence-node
  (testing "only `write-seq!` emits the sentinel -1 node. `encode-indexed`, and
            `build-index` + `seal-index!` over a file somebody else wrote, both
            produce an index without one -- and `nth` destructured the absent
            total as nil and compared it, throwing NPE. The 3-arity not-found
            form threw too, so there was no safe way to call it, while `seq` and
            `reduce` on the same object worked fine."
    (let [v (into {} (for [i (range 40)] [(format "k%03d" i) i]))
          bs (boring/encode-indexed v (assoc opts :index 16 :index-min 8))
          items (nav/items bs opts)]
      (is (= v (nav/value (nth items 0))))
      (is (= 1 (count (into [] items))) "one top-level value plus the index frame")
      (is (nil? (nth items 5 nil)) "out of range is nil, not an exception"))))

;; ---------------------------------------------------------------- finding 4

(deftest appending-a-second-sealed-batch-does-not-lose-the-first
  (testing "`write-seq!` counts from 0, so its back-pointer is CHUNK-relative.
            Concatenate two sealed batches of equal data length and the trailing
            pointer lands squarely on the FIRST batch's index frame -- a genuine
            tag-27 `boring/index`, so every detection check passed and the
            reader stopped at the first chunk's data-end. Half the log vanished
            with no error at all.

            The fix is that a live index must END AT THE FILE'S END. A stale one
            no longer does, so it is refused and the reader scans -- which sees
            both index frames as data, the honest fallback."
    (let [b (seal (vec (for [i (range 100)] {"n" i})) opts)
          joined (byte-array (concat (seq b) (seq b)))
          seen (into [] (map nav/value) (nav/items joined opts))]
      (is (= 202 (count seen)) "200 data items and two index frames, none lost")
      (is (= 200 (count (filter map? seen))) "every data item survives")
      (is (= 2 (count (filter #(= 0 (get % "n")) (filter map? seen))))
          "and BOTH batches are there -- n=0 appears once per batch, which is
           what distinguishes 'both present' from 'one batch read twice'")
      (is (= (count (foreign-items joined)) (count seen))
          "and nav agrees with a plain CBOR sequence reader -- `decode-seq` is
           NOT one any more, since it hides a trailing index frame, so this
           reads the items off the Java reader directly"))))

;; ---------------------------------------------------------------- finding 5

(deftest a-back-pointer-into-a-reserved-head-byte-scans-rather-than-throws
  (testing "the head parser throws on reserved additional-info 28-30. A
            corrupted pointer can land on such a byte -- about 3 in 256 of
            random corruptions, and binary payloads carry 0xDC routinely -- and
            the probing was OUTSIDE the try, so `nav/source` threw
            `reserved additional-info 28` before returning anything.

            The old corruption test flipped a byte to 0x7F, which lands out of
            range and so never reached the parser."
    (let [bs (seal (vec (for [i (range 20)]
                          {"n" i "blob" (byte-array [(unchecked-byte 0xDC)
                                                     (unchecked-byte 0xDD)
                                                     (unchecked-byte 0xDE)])}))
                   opts)]
      ;; point the trailer at a 0xDC inside one of those byte strings
      (let [target (loop [i 0]
                     (cond (>= i (- (alength bs) 9)) nil
                           (= (unchecked-byte 0xDC) (aget bs i)) i
                           :else (recur (inc i))))]
        (is (some? target) "fixture must actually contain a 0xDC byte")
        (let [broken (java.util.Arrays/copyOf bs (alength bs))]
          (dotimes [k 8]
            (aset-byte broken (+ (- (alength broken) 8) k)
                       (unchecked-byte (bit-and (bit-shift-right (long target)
                                                                 (* 8 (- 7 k)))
                                                0xFF))))
          (is (= 21 (count (into [] (nav/items broken opts))))
              "scans: 20 data items plus the unrecognised index frame")
          (is (some? (nav/source broken opts))
              "and `source` returns rather than throwing"))))))

;; ---------------------------------------------------------------- finding 6

(defn- crafted
  "A sealed sequence whose index frame is hand-built from `payload`."
  ^bytes [payload]
  (let [w (boring/writer 65536 opts)
        out (ByteArrayOutputStream.)
        vs [{"x" 1} {"x" 2} {"x" 3}]]
    (doseq [v vs] (boring/write-to! w v out))
    (let [dl (.size out)]
      (boring/write-to! w (boring.data/unknown-record
                           boring/index-name
                           (conj (vec payload)
                                 (byte-array (map unchecked-byte
                                                  [0 0 0 0 0 0
                                                   (bit-shift-right dl 8) dl]))))
                        out)
      (.toByteArray out))))

(deftest a-payload-whose-parts-disagree-is-refused
  (testing "detection proves something MEANT to be an index; none of it proves
            the payload hangs together. Each of these decoded fine and was
            trusted, producing a raw IndexOutOfBoundsException at the caller of
            `get`, or a wrong subtree. All must now fall back to scanning, which
            sees 4 items -- the 3 data items and the index frame as data."
    (doseq [[label payload]
            [["slots shorter than containers"
              [1 (int-array [-1]) (int-array [3]) [] [false]]]
             ["sorted shorter than containers"
              [1 (int-array [-1]) (int-array [3])
               [(byte-array (map unchecked-byte [0 4 4]))] []]]
             ["count disagrees with the slot's length"
              [1 (int-array [-1]) (int-array [1000000])
               [(byte-array (map unchecked-byte [0 4 4]))] [false]]]
             ["containers not ascending"
              [1 (int-array [5 -1]) (int-array [3 3])
               [(byte-array (map unchecked-byte [0 4 4]))
                (byte-array (map unchecked-byte [0 4 4]))] [false false]]]
             ["an anchor beyond the data section"
              [1 (int-array [-1]) (int-array [3])
               [(byte-array (map unchecked-byte [0 100 100]))] [false]]]
             ["a negative stride"
              [-1 (int-array [-1]) (int-array [3])
               [(byte-array (map unchecked-byte [0 4 4]))] [false]]]]]
      (let [bs (crafted payload)]
        ;; THREE, not four. The frame stays metadata even when its payload is
        ;; unusable: detection and usability are separate questions, and
        ;; detection has already succeeded here -- prefix, pointer, ends at
        ;; EOF. Answering both with one `nil` meant a file with a genuine,
        ;; byte-verified footer lost its `:data-end` along with its anchors,
        ;; so `items` walked past the data section and republished the footer
        ;; as a trailing data item: `nav/items` reporting 41 where `decode-seq`
        ;; reported 40 on the same bytes.
        (is (= 3 (count (into [] (nav/items bs opts))))
            (str label ": must scan the DATA, and stop at the frame"))
        (is (= [1 2 3] (mapv #(get % "x")
                             (filter map? (into [] (map nav/value) (nav/items bs opts)))))
            (str label ": and the data must read back unchanged"))))))

(deftest a-consistent-crafted-payload-is-still-used
  (testing "the control: validation must reject inconsistency, not everything.
            Three 4-byte items at stride 1 means three anchors at 0, 4 and 8."
    (let [bs (crafted [1 (int-array [-1]) (int-array [3])
                       [(byte-array (map unchecked-byte [0 4 4]))] [false]])]
      (is (= 3 (count (into [] (nav/items bs opts))))
          "index accepted, so the frame is not yielded as data")
      (is (= 3 (get (nav/value (nth (nav/items bs opts) 2)) "x"))))))

;; ------------------------------------------------ lifecycle and 2 GiB limit
;;
;; Not reachable through the Clojure API, but `Writer` is public Java and
;; `write-seq!` drives it, so the states below are the ones a reused writer can
;; actually be left in.

(deftest a-failed-indexed-write-does-not-leave-capture-running
  (testing "capture was turned off only on the success path, so an exception
            mid-sequence left the writer capturing with a stale base. Every
            later encode on that writer -- and a writer is meant to be reused --
            then allocated and retained a node per container, invisibly and
            without bound. Nothing cleared it but another indexed write."
    (let [^org.replikativ.boring.Writer w (boring/writer 65536 opts)
          out (ByteArrayOutputStream.)]
      (is (thrown? Exception
                   (boring/write-seq! w [{"a" 1} (Object.)] out
                                      (assoc opts :index 4 :index-min 4))))
      ;; a large UNindexed write on the same writer must capture nothing
      (boring/write-seq! w (repeat 500 (vec (range 32))) (ByteArrayOutputStream.) opts)
      (is (zero? (.idxCount w))
          "capture must be off after a failed indexed write"))))

(deftest starting-a-capture-discards-the-previous-one
  (testing "index state deliberately survives `reset()`, because `write-seq!`
            resets per item while nodes accumulate across the sequence. So the
            fresh start has to be declared somewhere else -- `setIndex` -- or a
            caller who enabled capture once accumulated nodes from documents
            that no longer exist."
    (let [^org.replikativ.boring.Writer w (boring/writer 65536 opts)
          v (vec (range 40))]
      (.setIndex w (int 1) (int 4) 0)
      (boring/encode-buffered! w v)
      (let [after-one (.idxCount w)]
        (is (pos? after-one))
        (.setIndex w (int 1) (int 4) 0)
        (is (zero? (.idxCount w)) "setIndex starts clean")
        (boring/encode-buffered! w v)
        (is (= after-one (.idxCount w))
            "so the second capture is the same size as the first, not double")))))

(deftest resetting-releases-the-anchor-arrays
  (testing "each slot is its own int[], so forgetting the COUNT while leaving
            the references reachable pins one array per indexed container for
            the life of a long-lived writer. Same fix as the stringref table."
    (let [^org.replikativ.boring.Writer w (boring/writer 65536 opts)]
      (.setIndex w (int 1) (int 2) 0)
      (boring/encode-buffered! w (vec (for [i (range 50)] (vec (range 10)))))
      (is (pos? (.idxCount w)))
      (.idxReset w)
      (let [f (doto (.getDeclaredField org.replikativ.boring.Writer "idxSlots")
                (.setAccessible true))]
        (is (every? nil? (seq ^objects (.get f w)))
            "no anchor array may stay reachable after a reset")))))

(deftest an-index-offset-past-2gb-is-carried-not-wrapped
  (testing "index offsets are 64-bit. They used to be int32, which capped an
            indexed artifact at 2 GiB -- historically roomy, and not any more.
            Before the cap was enforced the cast produced NEGATIVE offsets:
            `containers` stopped ascending so the binary search broke, and
            sequence `nth` seeked to a negative position. Then it threw. Now it
            carries them, and the wire promotes to sint64 only when it must.

            `idxBase` is what makes this testable without a 2 GiB file: it is
            added to every recorded offset, so a base past 2^31 sends every
            anchor into 64-bit territory on a document of a few hundred bytes."
    (let [^org.replikativ.boring.Writer w (boring/writer 65536 opts)
          base 3000000000]                       ; Integer/MAX_VALUE is 2147483647
      (.setIndex w (int 1) (int 4) base)
      (is (pos? (boring/encode-buffered! w (vec (range 40))))
          "encodes rather than throwing")
      (let [^longs cs (.idxContainers w)]
        (is (pos? (alength cs)))
        (is (every? #(> % Integer/MAX_VALUE) (seq cs))
            (str "every container offset must be past 2^31, got " (vec cs)))
        (is (apply < (seq cs)) "and they must still ascend, which is what the
                                binary search depends on"))
      (testing "the sequence-offset path carries the range too"
        (.setIndex w (int 1) (int 4) 0)
        (.idxItem w 3000000000)
        (is (= [3000000000] (vec (.idxItemOffsets w)))
            "a 3 GB item offset survives intact rather than wrapping negative")))
    (testing "a negative offset is still refused -- it cannot arise from a walk,
              so it means the arithmetic went wrong"
      (let [^org.replikativ.boring.Writer w (boring/writer 65536 opts)]
        (.setIndex w (int 1) (int 4) 0)
        (is (thrown? clojure.lang.ExceptionInfo (.idxItem w -1)))))))

;; ------------------------------------------- issues outside the index itself
;;
;; Found by the same reviews. Two of these are on `main` and therefore in a
;; released version; none is caused by the index, but all of them ship with it.

(defrecord Widget [a b])

(deftest nav-realises-with-the-decode-options-it-was-given
  (testing "`nav/source` promised that `opts` \"are the decode options
            realisation will use (:registry and friends)\" and that realising
            goes through the ordinary reader, \"same registry, same records\".
            The Reader was left at its defaults, so `opts` reached only the
            ENCODE side used for key probes. A registered record came back as a
            raw tag-27 frame instead of the type, and a caller's `:max-depth` --
            a security bound -- was not enforced on this path at all."
    (let [reg (-> (boring/tag-registry) (boring/register-record-class Widget))
          o {:stringref false :registry reg}
          bs (boring/encode {"w" (->Widget 1 2)} o)]
      (is (= (boring/decode bs o) (nav/value (nav/source bs o)))
          "nav must realise exactly what decode returns")
      (is (instance? Widget (nav/value (get (nav/source bs o) "w")))
          "and a registered record must come back as the record type"))
    (testing "and :max-depth is enforced rather than ignored"
      (let [deep (reduce (fn [acc _] [acc]) [] (range 40))
            bs (boring/encode deep {:stringref false})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (nav/value (nav/source bs {:stringref false :max-depth 4}))))))))

(deftest a-failed-read-does-not-poison-the-readers-depth
  (testing "`enter()` incremented before throwing, and only array/map unwind it
            in a finally -- so a rejected read permanently consumed a level of
            budget on a Reader that `boring.nav` shares across every lookup. A
            later, perfectly shallow read then failed because of an earlier one."
    ;; ONE Reader, reused -- which is the whole point. The first version of
    ;; this test called `nav/source` inside the loop, so every attempt got a
    ;; FRESH Reader and the leak could not show. It passed with the fix
    ;; reverted; a per-fix revert sweep is what caught that.
    ;; BOTH VALUES IN ONE BUFFER, and no `.reset` between them. Two earlier
    ;; versions of this test were vacuous and the suite stayed green with the
    ;; fix removed: the first built a fresh Reader per attempt, and the second
    ;; called `.reset`, which sets `depth = 0` and wipes the very leak the
    ;; assertion is meant to observe. The leak is only visible on a Reader that
    ;; is reused WITHOUT being reset -- which is exactly how `boring.nav` uses
    ;; one, sharing a single Reader across every lookup.
    (let [^bytes deep (boring/encode (reduce (fn [acc _] [acc]) [] (range 20))
                                     {:stringref false})
          ^bytes shallow (boring/encode [1 2 3] {:stringref false})
          both (byte-array (concat (seq deep) (seq shallow)))
          at (alength deep)
          r (org.replikativ.boring.Reader. ^bytes both)]
      (set! (.-maxDepth r) (int 4))
      (dotimes [_ 6]
        (is (thrown? clojure.lang.ExceptionInfo (.readFrom r 0))
            "the deep value must fail every time, for the same reason"))
      (is (= [1 2 3] (.readFrom r at))
          "a shallow value at another offset in the SAME buffer must still read
           after repeated deep failures -- `enter()` incremented before throwing,
           so each failure permanently consumed a level of the budget")
      (is (= (alength both) (.skipFrom r at))
          "and skipping it must land at the end, not short"))))

(deftest nested-tags-are-bounded
  (testing "tag recursion was the one nesting nothing bounded, and enough of it
            blew the stack. It is bounded now -- but by the SKIP BOUND, which is
            max(maxDepth, 1024), not by maxDepth.

            That difference is deliberate and is the fourth attempt at this. Skip
            cannot reproduce read's depth accounting: a tag reader that consumes
            its payload's containers inline charges nothing for them, and a
            generic walker must recurse. Tying skip to maxDepth therefore made
            it STRICTER than read and navigation refused decodable documents --
            once for `#{}`, once for `(sorted-map)`, once for shaped arrays. A
            laxer bound cannot do that, and still cannot overflow."
    (let [chain (fn [n] (byte-array (concat (repeat n (unchecked-byte 0xC0))
                                            [(byte 0)])))]
      (testing "a chain well within the bound skips, even at a low :max-depth --
                the case that used to be refused"
        (let [^bytes bs (chain 40)
              r (org.replikativ.boring.Reader. bs)]
          (set! (.-maxDepth r) (int 4))
          (is (= (alength bs) (.skipFrom r 0)))))
      (testing "and one past the bound is a typed error"
        (let [r (org.replikativ.boring.Reader. ^bytes (chain 2000))]
          (set! (.-maxDepth r) (int 4))
          (is (thrown? clojure.lang.ExceptionInfo (.skipFrom r 0))))))))

(deftest canonical-maps-refuse-keys-that-encode-identically
  (testing "distinct keys can encode to the same bytes -- Long 1 and
            BigInteger.ONE are both `01` -- and a map with two identical CBOR
            keys is output boring's own decoder rejects. Canonical SETS always
            checked this; maps did not, so the same hazard produced an
            unreadable document rather than an error."
    (let [m (doto (java.util.IdentityHashMap.)
              (.put (Long/valueOf 1) "a")
              (.put java.math.BigInteger/ONE "b"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"encode identically"
                            (boring/encode m {:profile :canonical}))))))

(deftest a-collection-that-lies-about-its-size-is-refused
  (testing "the head is written from size() BEFORE the entries, so a mismatch is
            a malformed document regardless. With an index it is worse: the
            anchor array is sized from the same number, so an over-run walks off
            it and an under-fill lets the NEXT item be swallowed as the missing
            element -- which, in an indexed sequence, was the index frame."
    (let [liar (proxy [java.util.AbstractCollection] []
                 (size [] 1)
                 (iterator [] (.iterator (java.util.ArrayList. [1 2]))))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reported 1 entries"
                            (boring/encode liar {:stringref false}))))
    (let [liar (proxy [java.util.AbstractCollection] []
                 (size [] 2)
                 (iterator [] (.iterator (java.util.ArrayList. [1]))))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reported 2 entries"
                            (boring/encode liar {:stringref false}))))))

(deftest write-seq-3-arity-uses-the-writers-options
  (testing "`write-to!` and `encode-into!` fall back to the writer's resolved
            options; `write-seq!` resolved nil instead. So a writer built with
            {:stringref false} -- which is exactly what a navigable file needs --
            silently emitted stringref output through this one entry point, and
            `boring.nav` then refused to read what it had just written."
    ;; EVERY profile, not just {:stringref false}. The first version of this
    ;; test used exactly that one -- the only setting where the round-trip
    ;; through `writer-opts` is a no-op -- and so passed while the fix threw
    ;; `:boring/incompatible-options` on three of the five profiles. Delegating
    ;; the 3-arity to the 4-arity re-resolved options that `writer-opts` had
    ;; already resolved and stripped `:profile` from, so `:canonical` arrived
    ;; without the profile that licenses it.
    (doseq [p [{:stringref false} {:profile :interop} {:profile :archival}
               {:profile :canonical} {:profile :canonical-rfc7049}]]
      (let [vs (vec (for [i (range 5)] {"msg" "repeated" "n" i}))
            a (ByteArrayOutputStream.)
            b (ByteArrayOutputStream.)]
        (boring/write-seq! (boring/writer 65536 p) vs a)
        (boring/write-seq! (boring/writer 65536 p) vs b p)
        (is (= (seq (.toByteArray a)) (seq (.toByteArray b)))
            (str (pr-str p) ": the 3-arity must match passing the writer's own
                  opts explicitly, and must not throw"))
        (is (= vs (mapv nav/value (seq (nav/items (.toByteArray a)
                                                  (assoc p :stringref false)))))
            (str (pr-str p) ": and the result must read back"))))))

(deftest skipping-and-reading-agree-about-depth
  (testing "charging tags to the depth budget in `skipStructural` closed a hole
            -- `read` already charged for them, so skipping was the more
            permissive path and routed around a SECURITY bound. The risk in
            fixing it is the mirror image: skip must not now reject anything
            `read` accepts, because navigation skips constantly.

            Tag-heavy on purpose: a set is tag 258, a sorted-map tag 27, so this
            nests four shapes that exercise both tagged and untagged levels."
    (let [nest (fn nest [d]
                 (reduce (fn [acc i]
                           (case (int (mod i 4))
                             0 #{acc}
                             1 (into (sorted-map) {"k" acc})
                             2 [acc]
                             3 {"m" acc}))
                         :leaf (range d)))]
      (doseq [d [1 2 4 8 16 32 64]
              md [4 16 64 1024]]
        (let [bs (boring/encode (nest d) opts)
              decoded? (try (boring/decode bs (assoc opts :max-depth md)) true
                            (catch Exception _ false))
              r (org.replikativ.boring.Reader. ^bytes bs)
              _ (set! (.-maxDepth r) (int md))
              skipped? (try (.skipFrom r 0) true (catch Exception _ false))]
          ;; ONE-DIRECTIONAL, deliberately. Exact parity was the wrong
          ;; specification and asserting it sent me chasing read's
          ;; idiosyncrasies -- an empty array costs no depth but an empty map
          ;; does, and `readTagged(258)` parses the set's array head inline
          ;; without charging for it. What actually matters is that navigation
          ;; never REFUSES a document that decodes: skip may be laxer, never
          ;; stricter.
          (is (or (not decoded?) skipped?)
              (str "depth " d ", max-depth " md
                   ": decode=" decoded? " skip=" skipped?
                   " -- skip must not reject what read accepts")))))))

(deftest empty-containers-get-no-phantom-anchor
  (testing "`((0 - 1) / stride) + 1` is 1 in Java, so an empty container claimed
            one anchor that the loop never wrote -- leaving a slot whose single
            offset was 0, pointing at the start of the document and marked
            sorted. Only reachable with :index-min 0, which is not a sane
            setting, but it is a wrong answer waiting rather than a missed
            optimisation, and the payload validation would otherwise reject the
            whole index because of it."
    (doseq [stride [1 4 16]]
      (let [v {"empty-map" {} "empty-vec" [] "real" (into {} (for [i (range 20)]
                                                               [(format "k%02d" i) i]))}
            o (assoc opts :index stride :index-min 0)
            idx (boring/build-index (boring/encode v o) o)
            c (nav/source (boring/encode-indexed v o) opts)]
        (is (some? idx) "the index must be BUILT, not silently refused -- a
                         `when` here made every assertion below vanish")
        (doseq [[cnt slot] (map vector (seq ^ints (:counts idx)) (:slots idx))]
          (is (= (if (zero? cnt) 0 (inc (quot (dec (long cnt)) (long stride))))
                 (count slot))
              (str "stride " stride ": a container of " cnt
                   " entries must have exactly ceil(n/stride) anchors")))
        (is (= v (nav/value c)) (str "stride " stride ": and the value still reads back"))
        (is (= {} (nav/value (get c "empty-map"))))
        ;; LOOKING INSIDE is the case the first version missed: it only
        ;; realised the empty map, so it never reached the binary search, which
        ;; assumed at least one anchor and threw straight at the caller.
        (is (nil? (get (get c "empty-map") "missing"))
            (str "stride " stride ": a miss inside an indexed EMPTY map must be
                  nil, not an exception"))
        (is (nil? (nth (get c "empty-vec") 0 nil)))
        (is (= 5 (nav/value (get-in c ["real" "k05"]))))))))

;; ------------------------------------------------- closing the coverage gaps
;;
;; A verification review of the fixes above found that several of those tests
;; were falsely reassuring. Each gap it named is closed here.

(deftest sortedness-holds-through-the-WRITER-capture-too
  (testing "the sortedness tests above go through `encode-indexed`/`build-index`,
            which is the Clojure byte WALK. The Java writer capture is a second
            implementation of the same rule and was only covered transitively.
            `write-seq!` is the path that uses it."
    (dotimes [_ 30]
      (let [ks (shuffle (mapv #(format "k%03d" %) (range 20)))
            m (reduce (fn [^java.util.LinkedHashMap a k] (doto a (.put k (subs k 1))))
                      (java.util.LinkedHashMap.) ks)
            w (boring/writer 65536 opts)
            out (ByteArrayOutputStream.)]
        (boring/write-seq! w [m] out (assoc opts :index 16 :index-min 16))
        (let [c (nth (nav/items (.toByteArray out) opts) 0)]
          (doseq [k ks]
            (is (= (subs k 1) (some-> (get c k) nav/value))
                (str "writer capture lost key " k " in order " (pr-str ks)))))))))

(deftest depth-parity-covers-empty-containers
  (testing "the depth-parity test's generator never produced an EMPTY container,
            which is exactly where the two paths diverged: `read` returns the
            shared empty vector BEFORE charging depth, so charging it in `skip`
            made skipping stricter than decoding. An ordinary #{} is tag 258
            around an empty array -- the smallest value that shows it."
    (doseq [v [#{} [] {} #{#{}} [[]] {"a" {}} (into (sorted-map) {})
               #{[] {}} [#{} {} []]]
            md [1 2 3 4 1024]]
      (let [bs (boring/encode v opts)
            decoded? (try (boring/decode bs (assoc opts :max-depth md)) true
                          (catch Exception _ false))
            r (org.replikativ.boring.Reader. ^bytes bs)
            _ (set! (.-maxDepth r) (int md))
            skipped? (try (.skipFrom r 0) true (catch Exception _ false))]
        (is (or (not decoded?) skipped?)
            (str (pr-str v) " at max-depth " md
                 ": decode=" decoded? " skip=" skipped?
                 " -- skip must not reject what read accepts"))))))

(deftest canonical-encode-fallback-applies-to-keys-and-recurses-safely
  (testing "keys and set elements are pre-encoded in a scratch writer, which did
            not inherit :encode-fallback -- so the option silently did not apply
            exactly there. Inheriting it then opened a second hole: the scratch
            did not inherit the RE-ENTRY GUARD, so a fallback whose result still
            contains the unsupported value recursed until the stack went."
    (let [o {:profile :canonical :encode-fallback (fn [_] "fell-back")}
          m {(Object.) 1 "ok" 2}]
      (is (= {"fell-back" 1 "ok" 2} (boring/decode (boring/encode m o) o))
          "the fallback must apply to a KEY under :canonical"))
    (testing "and a fallback that returns the unsupported value again must raise
              the typed error, not StackOverflowError"
      (let [bad (Object.)
            o {:profile :canonical :encode-fallback (fn [_] {bad 1})}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (boring/encode {bad 1} o)))))))

(deftest size-mismatch-is-caught-on-every-container-path
  (testing "the first version of this check covered AbstractCollection only. The
            ordinary non-indexed map path, the record path, and the canonical
            staging arrays had none -- and canonical staging threw a raw
            ArrayIndexOutOfBoundsException from an array sized by size()."
    (let [over (fn [] (proxy [java.util.AbstractMap] []
                        (size [] 1)
                        (entrySet []
                          (java.util.LinkedHashSet.
                           [(java.util.AbstractMap$SimpleEntry. "a" 1)
                            (java.util.AbstractMap$SimpleEntry. "b" 2)]))))
          under (fn [] (proxy [java.util.AbstractMap] []
                         (size [] 2)
                         (entrySet []
                           (java.util.LinkedHashSet.
                            [(java.util.AbstractMap$SimpleEntry. "a" 1)]))))]
      (doseq [[label o] [["plain" opts] ["canonical" {:profile :canonical}]]
              [what mk] [["over-yield" over] ["under-yield" under]]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reported \d+ entries"
                              (boring/encode (mk) o))
            (str label "/" what " must raise the typed mismatch error")))
      ;; The INDEXED writer path needs `write-seq!`: `encode` never calls
      ;; setIndex, so an `:index` option there goes down the plain path and the
      ;; row that claimed to cover indexing duplicated the plain one.
      (doseq [[what mk] [["over-yield" over] ["under-yield" under]]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reported \d+ entries"
                              (boring/write-seq! (boring/writer 65536 opts) [(mk)]
                                                 (ByteArrayOutputStream.)
                                                 (assoc opts :index 1 :index-min 1)))
            (str "indexed/" what " must raise it too"))))))

(deftest a-semantically-lying-index-is-refused
  (testing "structural validation is not enough on its own: anchors can ascend,
            sit in range and still not be entry boundaries. Three 4-byte items
            live at 0, 4 and 8; anchors of 4, 8, 9 passed every structural check
            and made `nth` return the NEIGHBOURING value -- a silent wrong
            answer, which is the one outcome the index may never produce.

            Closed by two O(1) reads off the container's own head: the node must
            describe a container that is really there with the count it claims,
            and its first anchor must be that container's first entry."
    (let [w (boring/writer 65536 opts)
          out (ByteArrayOutputStream.)
          vs [{"x" 1} {"x" 2} {"x" 3}]]
      (doseq [v vs] (boring/write-to! w v out))
      (let [dl (.size out)]
        (boring/write-to! w (boring.data/unknown-record
                             boring/index-name
                             [1 (int-array [-1]) (int-array [3])
                              ;; deltas -> absolutes 4, 8, 9: ascending, in
                              ;; range, and wrong
                              [(byte-array (map unchecked-byte [4 4 1]))] [false]
                              (byte-array (map unchecked-byte
                                               [0 0 0 0 0 0 (bit-shift-right dl 8) dl]))])
                          out)
        (let [bs (.toByteArray out)
              seen (into [] (map nav/value) (nav/items bs opts))]
          ;; THREE: refused as an INDEX, still recognised as a frame. See the
          ;; note in `a-payload-whose-parts-disagree-is-refused`.
          (is (= 3 (count seen)) "refused, so it scans -- and stops at the frame")
          (is (= [1 2 3] (mapv #(get % "x") (filter map? seen)))
              "and every item reads back as itself, not its neighbour"))))))

(deftest an-unbounded-tag-chain-cannot-overflow-the-stack
  (testing "the reason skipping needed a bound at all. `c0 c0 c0 ... 00` is
            legal CBOR; recursing once per tag blew the stack, which is the DoS
            maxDepth exists to prevent and the one nesting it did not cover.
            Bounded by CHAIN LENGTH and consumed iteratively, so deep chains
            cost no stack at all -- and unlike charging tags to the depth
            budget, this can only make skip laxer than read, never stricter."
    (let [chain (fn [n] (byte-array (concat (repeat n (unchecked-byte 0xC0)) [(byte 0)])))]
      (testing "a chain past the bound is refused, not crashed. 2000 exceeds the
                1024 floor; 40 does not, and is accepted on purpose -- see
                `nested-tags-are-bounded`"
        (let [r (org.replikativ.boring.Reader. ^bytes (chain 2000))]
          (set! (.-maxDepth r) (int 4))
          (is (thrown? clojure.lang.ExceptionInfo (.skipFrom r 0)))))
      (testing "and a chain far deeper than any stack is still a typed error"
        (let [r (org.replikativ.boring.Reader. ^bytes (chain 200000))]
          (set! (.-maxDepth r) (int 1024))
          (is (thrown? clojure.lang.ExceptionInfo (.skipFrom r 0))
              "must be the depth error, never StackOverflowError")))
      (testing "while a chain within the limit still skips correctly"
        (let [^bytes bs (chain 3)
              r (org.replikativ.boring.Reader. bs)]
          (set! (.-maxDepth r) (int 8))
          (is (= (alength bs) (.skipFrom r 0))))))))

(deftest skip-lands-exactly-at-the-end-of-any-value
  (testing "the general property the tag-chain rewrite could have broken. That
            loop reads a head to see whether the chain continues and, when it
            does not, pushes the byte back with `pos--` -- correct only because
            `u8()` is `b(pos++)` and advances exactly one. If it were ever
            wrong, skipping would land mid-item and every later offset in the
            sequence would be garbage, which no round-trip test would notice
            because decoding never uses skip.

            Generated rather than enumerated, over four profiles, because the
            shapes that matter are tag-wrapped: sets (258), sorted maps and sets
            (27), ratios (30), uuids (37), shaped arrays (39649)."
    (let [g (gen/recursive-gen
             (fn [inner]
               (gen/one-of [(gen/map gen/string-ascii inner {:max-elements 6})
                            (gen/vector inner 0 6)
                            (gen/fmap set (gen/vector gen/large-integer 0 6))
                            (gen/fmap #(into (sorted-map)
                                             (map-indexed (fn [i v] [(str i) v]) %))
                                      (gen/vector inner 0 5))
                            (gen/fmap #(into (sorted-set) %)
                                      (gen/vector gen/large-integer 0 5))
                            (gen/fmap #(apply list %) (gen/vector inner 0 5))]))
             (gen/one-of [gen/large-integer gen/string-ascii gen/boolean
                          (gen/return nil) gen/keyword gen/symbol gen/ratio
                          gen/uuid gen/double]))]
      (doseq [prof [{:stringref false} {:profile :archival} {:profile :canonical}
                    {:stringref false :shapes true}]]
        (let [result (tc/quick-check
                      150
                      (prop/for-all [v g]
                                    (let [^bytes bs (boring/encode v prof)
                                          r (org.replikativ.boring.Reader. bs)]
                                      (= (long (alength bs)) (.skipFrom r 0)))))]
          (is (:pass? result)
              (str (or (:profile prof) :clojure) " -- " (pr-str (:shrunk result)))))))))

(deftest the-writer-capture-also-emits-no-phantom-anchor
  (testing "the empty-container test above drives `build-index`/`encode-indexed`
            -- the CLOJURE WALK only. `Writer.anchorCount` is the other half of
            the same fix and is what `write-seq!` uses, which is the primary
            index producer. Deleting its guard left the suite green, so the test
            looked like it covered both and covered one."
    (doseq [stride [1 4 16]]
      (let [v {"empty-map" {} "empty-vec" [] "s" #{}
               "real" (into {} (for [i (range 20)] [(format "k%02d" i) i]))}
            w (boring/writer 65536 opts)
            out (ByteArrayOutputStream.)]
        (boring/write-seq! w [v] out (assoc opts :index stride :index-min 0))
        (let [bs (.toByteArray out)
              c (nth (nav/items bs opts) 0)]
          (is (= v (nav/value c))
              (str "stride " stride ": writer-captured index must round-trip"))
          (is (nil? (get (get c "empty-map") "missing"))
              (str "stride " stride ": a miss inside a captured EMPTY map must be
                    nil, not an exception"))
          ;; and the index must actually be USED, or this proves nothing
          (is (= 1 (count (into [] (nav/items bs opts))))
              (str "stride " stride ": the index frame must be recognised, not
                    yielded as data -- otherwise the assertions above are just
                    testing the scan path")))))))

;; --------------------------------------------- round four: skip's depth bound
;;
;; Three attempts tried to make `skip` agree with `read` about `:max-depth`, and
;; each broke a different value. The fourth stopped trying: skip's bound is a
;; STACK bound now, deliberately laxer than read's, because a tag reader that
;; consumes its payload's containers inline charges nothing for them while a
;; generic walker must recurse -- a gap that grows with nesting and that no
;; constant slack closes.

(deftest navigation-never-refuses-a-decodable-document
  (testing "the failure mode that matters. Shaped arrays were the third value to
            hit it: tag 39649's reader consumes the outer, keys, rows and row
            heads inline and charges only for the tag, while skip charged for
            each -- so a document that decoded at :max-depth 1 could not be
            navigated. `#{}` and `(sorted-map)` were the first two."
    (doseq [v [[{"a" 1} {"a" 2}]                    ; shaped array
               #{} [] {} (into (sorted-map) {})
               #{[] {}} [#{} {} []]
               [{"a" 1 "b" 2} {"a" 3 "b" 4} {"a" 5 "b" 6}]
               {"outer" [{"k" 1} {"k" 2}]}]
            shapes? [true false]
            md [1 2 3 4 1024]]
      (let [prof {:stringref false :shapes shapes?}
            bs (boring/encode v prof)
            o (assoc prof :max-depth md)
            decoded? (try (boring/decode bs o) true (catch Exception _ false))]
        (when decoded?
          (is (some? (try (nav/byte-span (nav/source bs o))
                          (catch Exception _ nil)))
              (str (pr-str v) " shapes=" shapes? " max-depth=" md
                   ": decodes, so it must navigate")))))))

(deftest a-valid-index-never-makes-reading-fail
  (testing "the index frame is a fixed nested shape, so decoding it costs four
            or five levels however shallow the DATA is. Read against the
            caller's `:max-depth`, an indexed `[1]` failed to decode its own
            index, forgot it, walked into the frame as data and raised -- a
            VALID index making a read fail that would have succeeded with no
            index at all. The optimisation had become load-bearing."
    (let [out (ByteArrayOutputStream.)]
      (boring/write-seq! (boring/writer 65536 opts) [1 2 3] out
                         (assoc opts :index 1))
      (let [bs (.toByteArray out)]
        (doseq [md [1 2 3 4 1024]]
          (is (= [1 2 3] (mapv nav/value (nav/items bs (assoc opts :max-depth md))))
              (str "max-depth " md)))))))

(deftest build-index-survives-a-long-tag-chain
  (testing "`index-walk` recursed once per tag, so `c0 c0 ... 00` -- legal CBOR,
            and reachable through the public `build-index` on bytes somebody
            else wrote -- was a StackOverflowError rather than a typed error.
            `Reader.skipStructural` had the same defect and was fixed first;
            this is the Clojure walk catching up."
    (let [chain (byte-array (concat (repeat 20000 (unchecked-byte 0xC0)) [(byte 0)]))]
      (is (nil? (boring/build-index chain {:index 16}))
          "a chain of tags contains no indexable container, and must not overflow"))))

;; ------------------------------------- round four: sub-fixes with no test yet

(deftest canonical-key-nesting-shares-one-depth-budget
  (testing "`canonicalSubWriter` accumulates the parent's depthOffset. Without
            it a scratch writer made by a scratch writer -- a canonical map
            nested inside a canonical map KEY -- reset the budget to the inner
            parent's local depth, so nesting through key position got a fresh
            allowance at every hop and `:max-depth` was not a bound at all."
    (let [deep (reduce (fn [x _] {x 0}) :leaf (range 8))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (boring/encode deep {:profile :canonical :max-depth 2}))
          "depth through map keys must be bounded")
      (is (some? (boring/encode deep {:profile :canonical :max-depth 64}))
          "and a generous budget must still encode it"))))

(deftest canonical-sets-and-records-also-check-their-size
  (testing "the size-mismatch tests covered collections and maps. The canonical
            SET staging array and the record-field loop are sized from size()
            the same way and had no check of their own."
    (let [liar-set (proxy [java.util.AbstractSet] []
                     (size [] 1)
                     (iterator [] (.iterator (java.util.ArrayList. [1 2]))))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reported 1 entries"
                            (boring/encode liar-set {:profile :canonical}))
          "canonical set staging is sized by size() and must check it"))))

;; ------------------------------------------ round five: the footer's options

(deftest the-index-frame-is-written-with-the-calls-options
  (testing "`seal-index!` emitted the footer through `write-to!`'s 2-arity,
            which resolves the WRITER's options -- so `write-seq!`'s 4-arity
            wrote the data with the caller's opts and the frame with the
            writer's. With the documented shape `(write-seq! (writer 4096) vs
            out {:stringref false :index 8})` the frame went out inside a
            stringref namespace, which `nav` refuses to recognise. The index was
            silently dead AND the frame came back as a phantom trailing item in
            every log.

            Every test and doc example missed it by happening to build the
            writer with the same options it passed."
    (let [vs (vec (for [i (range 40)] {"n" i}))]
      (doseq [wopts [nil {:stringref false} {:profile :archival}]]
        (let [out (ByteArrayOutputStream.)
              w (if wopts (boring/writer 4096 wopts) (boring/writer 4096))]
          (boring/write-seq! w vs out (assoc opts :index 8))
          (let [bs (.toByteArray out)
                items (nav/items bs opts)]
            (is (= 40 (count (into [] items)))
                (str "writer opts " (pr-str wopts)
                     ": the frame must be recognised, not yielded as data"))
            (is (= vs (mapv nav/value (seq items))))
            (is (= {"n" 39} (nav/value (nth items 39)))
                "and the index must actually be usable")))))))

(deftest a-mid-item-anchor-does-not-throw-at-the-caller
  (testing "validation proves the FIRST anchor is a container's real first entry;
            proving it of every anchor would cost the walk the index exists to
            avoid. A middle anchor pointing mid-item therefore survives, and
            `scan-map` used to read a garbage head from there and run off the
            buffer -- a raw ArrayIndexOutOfBoundsException out of `get`.

            The answer may be wrong -- that is the documented trust boundary --
            but it may not be an untyped exception."
    (let [m (into {} (for [i (range 20)] [(format "k%02d" i) i]))
          bs (boring/encode-indexed m (assoc opts :index 4 :index-min 4))]
      ;; mutate every single byte of the whole document, and require that no
      ;; lookup ever throws something untyped
      (dotimes [i (alength ^bytes bs)]
        (doseq [v [0x00 0x7F 0xF5 0xFF]]
          (let [c (java.util.Arrays/copyOf ^bytes bs (alength ^bytes bs))]
            (aset-byte c i (unchecked-byte v))
            (doseq [k ["k00" "k07" "k19" "nope"]]
              (is (try (some-> (get (nav/source c opts) k) nav/value) true
                       (catch clojure.lang.ExceptionInfo _ true)
                       (catch Throwable t
                         (println "  untyped" (.getSimpleName (class t))
                                  "at byte" i "=" v "key" k)
                         false))
                  (str "byte " i " -> " v ", key " k)))))))))

;; ------------------------------------- round six: contracts, not corruption

(deftest count-works-on-the-items-of-a-sequence
  (testing "`count` threw AbstractMethodError on ORDINARY, undamaged data:
            `Items` implemented Seqable, Indexed and IReduceInit but not
            Counted, so `clojure.core/count` fell through to an abstract method.
            Every existing test reaches for `seq`, `nth` or `reduce`, so nothing
            touched it. Found by a review harness that applied every entry point
            to every shape rather than the ones each shape was written for."
    (let [vs (vec (for [i (range 20)] {"n" i}))]
      (doseq [ix [nil 1 4 64]]
        (let [out (ByteArrayOutputStream.)]
          (boring/write-seq! (boring/writer 65536 opts) vs out
                             (cond-> opts ix (assoc :index ix)))
          (let [it (nav/items (.toByteArray out) opts)]
            (is (= 20 (count it)) (str "index " ix))
            (is (= (count it) (count (seq it)) (reduce (fn [^long a _] (inc a)) 0 it))
                (str "index " ix ": count, seq and reduce must agree"))))))))

(deftest positional-reads-report-truncation-as-a-typed-error
  (testing "`decode` reported `:boring/truncated-input` where `boring.nav` threw
            a raw ArrayIndexOutOfBoundsException for the SAME corrupted byte --
            415 of 5360 mutations of an UNINDEXED document, so this was the
            navigator's contract with damaged data rather than anything to do
            with the index. `read` gets typed truncation from its own checks;
            `skipStructural` and the positional accessors had none.

            Both accessor families needed it: `b` for single bytes, and s16/s32/
            s64, which read a multi-byte head directly and so ran off the end
            near the buffer's tail even after `b` was bounded."
    (let [m (into {} (for [i (range 20)] [(format "k%02d" i) i]))
          ^bytes bs (boring/encode m opts)]
      (doseq [i (range (alength bs))
              ;; indefinite-length heads and wide-argument heads are the ones
              ;; that walk past the end
              v [0x9F 0xBF 0xFB 0xD9 0x5F]]
        (let [c (java.util.Arrays/copyOf bs (alength bs))]
          (aset-byte c i (unchecked-byte v))
          (is (try (nav/value (nav/source c opts)) true
                   (catch clojure.lang.ExceptionInfo _ true)
                   (catch Throwable _ false))
              (str "byte " i " -> " v " must be typed or fine, never untyped")))))))

(deftest nav-and-decode-seq-agree-on-what-counts-as-a-frame
  (testing "\"is this boring's index footer\" had four implementations, and
            `boring.nav`'s was the weakest: it checked tag 27, then that the
            payload was an array, then the name -- but never the array's
            ELEMENT COUNT. `footer-start` requires the literal byte 0x86 and
            `index-frame?` requires `(= 6 (count payload))`, so widening a
            genuine frame's payload from six elements to seven produced one
            file with two logical contents: `decode-seq` published the frame as
            a trailing data item and reported 2, while `nav/items` used it as
            an index and reported 1. Which answer you got depended on which API
            you called.

            All four now compare the same seventeen-byte constant."
    (let [v (vec (for [i (range 40)] {:e i :a :n/x :v (str "v" i)}))
          good (boring/encode-indexed v {:index 4 :index-min 4})
          ;; Widen the payload header from 0x86 to 0x87 and splice one more
          ;; element (null) in just before the trailing back-pointer, so the
          ;; pointer stays last and every OTHER property of a genuine frame
          ;; still holds.
          p (long (#'boring.frame/footer-start good))
          n (alength ^bytes good)
          ptr-head (- n 9)
          bad (let [out (byte-array (inc n))]
                (System/arraycopy good 0 out 0 ptr-head)
                (aset-byte out (+ p 16) (unchecked-byte 0x87))
                (aset-byte out ptr-head (unchecked-byte 0xf6))
                (System/arraycopy good ptr-head out (inc ptr-head) 9)
                out)]
      (testing "the control: the genuine file is accepted by both, so the
                crafting below is what the disagreement is about and not some
                unrelated breakage"
        (is (not= -1 p) "the genuine file has a locatable footer")
        (is (= 1 (count (boring/decode-seq good))))
        (is (= 1 (count (nav/items good)))))
      (testing "and the crafted one is refused by both"
        (is (= -1 (#'boring.frame/footer-start bad))
            "the byte-level gate refuses it")
        (is (= (count (boring/decode-seq bad)) (count (nav/items bad)))
            "nav and decode-seq report the same number of items")
        (is (= 2 (count (nav/items bad)))
            "namely two -- the data and the thing that is not a frame")))))

;; ---------------------------------------------------------------- audit 9
;;
;; Three findings, all the same class: a plausible WRONG ANSWER out of a
;; damaged-but-consistent index, with no exception anywhere and `decode` of the
;; same bytes returning the truth throughout.

(defn- body-len ^long [^bytes sealed] (long (#'boring.frame/footer-start sealed)))

(deftest a-footer-from-another-file-is-not-trusted
  (testing "Splice one sealed file's data section under another's footer -- same
            byte length, different item boundaries. That is an interrupted
            append and a retry, or a restore taking the body from one snapshot
            and the tail from another. Every gate passed: prefix present,
            pointer in range, frame ends at EOF, anchors ascend and sit in
            range, anchor[0] = 0. `nav` used the index and handed back
            neighbouring records -- `nth 16` returned the string \"gaaaa\",
            `nth 32` returned the record written at 32 minus 9 -- while
            `decode-seq` over the same bytes was correct throughout, so two
            code paths in one application disagreed about the file.

            Nothing pinned the FAR end of a node. Now the last anchor plus the
            items it covers must land exactly on the data section's end."
    (let [a-vs (mapv #(hash-map :id % :name (str "aaaa-" %)) (range 40))
          A (seal a-vs opts)
          want (body-len A)
          short-recs (mapv #(hash-map :id % :n (str "b" %)) (range 40))
          ;; A filler string sized so the two data sections match to the byte.
          k (first (filter #(= want (body-len (seal (conj short-recs
                                                          (apply str (repeat % \x)))
                                                    opts)))
                           (range 1 512)))
          B (seal (conj short-recs (apply str (repeat k \x))) opts)
          spliced (byte-array (concat (take want (seq A)) (drop want (seq B))))]
      (testing "the control: the splice really did produce a file whose footer
                the byte-level gate accepts, so what follows is about the index
                and not about the file being malformed"
        (is (some? k) "a filler length exists that matches the data sections")
        (is (= want (body-len B)) "and both data sections are the same length")
        (is (= want (#'boring.frame/footer-start spliced))
            "the spliced file's footer is located and accepted"))
      (testing "and every reader agrees with what was written"
        (is (= 40 (count (boring/decode-seq spliced))))
        (is (= 40 (count (nav/items spliced))))
        (is (= a-vs (mapv nav/value (nav/items spliced))))))))

(deftest the-item-total-must-match-the-data-section
  (testing "`Items.count` returns the frame's total verbatim and `nth` bounds
            against it, but nothing compared it with the data: the slot-length
            rule `want = 1 + (cnt-1)/stride` cannot see a change anywhere
            inside a whole stride. One flipped bit made `count` report 501 for
            a 500-item file; deflating the total left ten written records
            present in the file, returned by `decode-seq`, and unreachable
            through `count`/`nth`.

            Swept over every byte of the frame rather than the one that was
            found, because the finding is about the rule and not about a byte."
    (let [vs (mapv #(hash-map :id % :name (str "r" %)) (range 200))
          bs (seal vs opts)
          p (long (body-len bs))
          n (alength ^bytes bs)]
      (testing "the control: undamaged, the index loads and is used"
        (is (some? (:offsets (#'boring.nav/read-index (#'boring.nav/nav-of bs {}))))
            "the honest index is still accepted -- the new check is not a veto")
        (is (= 200 (count (nav/items bs)))))
      (testing "and no single-byte change to the frame makes nav and decode-seq
                disagree about how many items the file holds"
        (let [disagreements
              (for [i (range p n)
                    :let [c (aclone ^bytes bs)
                          _ (aset-byte c i (unchecked-byte (bit-xor (aget ^bytes bs i) 0x01)))
                          d (try (count (boring/decode-seq c)) (catch Throwable _ :err))
                          v (try (count (nav/items c)) (catch Throwable _ :err))]
                    :when (and (number? d) (number? v) (not= d v))]
                [i d v])]
          (is (empty? disagreements)
              (str "frame bytes that make nav disagree with decode-seq: "
                   (vec (take 5 disagreements)))))))))

(deftest a-lookup-miss-is-re-derived-by-walking
  (testing "Validation proves the FIRST anchor is a real entry and that the
            anchors ascend and sit in range. It cannot prove a MIDDLE one is an
            entry boundary without walking the container, which is the work the
            index exists to avoid. So a middle anchor off by one byte made the
            bounded walk start mid-item and report a PRESENT key as absent:
            eight of forty, measured, from a single changed byte, while
            `decode` of the same bytes returned the true forty-entry map.

            A negative from the index is now re-derived by the honest walk,
            which costs only on genuine misses and makes this namespace's
            promise -- a stale index falls back to walking and returns the same
            answer -- true for a DAMAGED one too."
    (let [m (into {} (for [i (range 40)] [(format "k%02d" i) i]))
          o {:profile :canonical}
          bs (boring/encode-indexed m (assoc o :index 4 :index-min 4))
          ;; The node's anchors are 2, 22, 42, 62 ... so its deltas are
          ;; 02 14 14 14 ... Move anchor[2] one byte forward and put the next
          ;; delta back, so every OTHER anchor -- including the last, which the
          ;; end check pins -- is untouched.
          needle (byte-array (map unchecked-byte [0x02 0x14 0x14 0x14 0x14 0x14]))
          at (first (for [i (range (body-len bs) (- (alength ^bytes bs) (alength needle)))
                          :when (every? #(= (aget ^bytes bs (+ i %)) (aget needle %))
                                        (range (alength needle)))]
                      i))
          damaged (let [c (aclone ^bytes bs)]
                    (aset-byte c (+ at 2) (unchecked-byte 0x15))
                    (aset-byte c (+ at 3) (unchecked-byte 0x13))
                    c)]
      (testing "the control, and it is the part that matters: the damaged index
                is still ACCEPTED and still USED, with the moved anchor visible
                in the slot the reader loaded. Without this the test could pass
                by the index being rejected, which would prove nothing about
                the lookup path"
        (is (some? at) "the slot's delta bytes were found")
        (let [ix (#'boring.nav/read-index (#'boring.nav/nav-of damaged o))]
          (is (some? (:slots ix)) "the damaged index is accepted")
          (is (= [2 22 43 62 82 102] (vec (take 6 (first (:slots ix)))))
              "and anchor[2] really is one byte off")))
      (testing "yet every present key still reads back correctly"
        (let [src (nav/source damaged o)]
          (doseq [i (range 40)]
            (is (= i (some-> (get src (format "k%02d" i)) nav/value))
                (format "k%02d" i)))))
      (testing "and an absent key is still absent"
        (is (nil? (get (nav/source damaged o) "nope")))))))

;; ------------------------------------------ the property, not another case
;;
;; Three rounds of audits found the same defect three times: the index
;; misdirects, `nav` returns a plausible wrong value, `decode-seq` over the
;; same bytes returns the truth, and nothing raises. Each fix was correct and
;; each time a level underneath was still unguarded -- an anchor that ascends
;; but is not an entry boundary, a total nobody compared with the data, a
;; footer from another generation.
;;
;; So this asserts the INVARIANT rather than the cases:
;;
;;   the index is an optimisation, so for every byte string whatsoever, the
;;   indexed reader and the unindexed reader must agree about every item they
;;   can both produce.
;;
;; Deliberately not "damage is rejected". Rejecting damage is one way to
;; satisfy this and scanning is another, and which one applies is an
;; implementation choice this property has no business pinning. What it forbids
;; is the third outcome: two readers, one file, two different answers.

(defn- reader-loop
  "Every top-level item, read with no index at all -- the reference."
  [^bytes bs]
  (let [r (Reader. bs)]
    (loop [acc []]
      (if (.atEnd r) acc (recur (conj acc (.readNext r)))))))

(defn- re-encode
  "A decoded value as bytes, or a marker. Re-encoding is the comparison -- see
  the property below for why nothing weaker survives damaged input."
  [v]
  (try (vec (boring/encode v opts))
       (catch Throwable _ ::unencodable)))

(defn- nav-items-or-error
  "Read through `count` and `nth` -- the two paths the index actually drives.

  The first version of this used `mapv` over the seq, and the seq WALKS: it
  never consults the item total and never jumps to an anchor. So the property
  built on it passed with the end-anchor check disabled, which is the whole
  class it was written to catch. `count` returns the frame's total verbatim and
  `nth` bounds against it and jumps from an anchor; those are what a lying
  index lies to."
  [^bytes bs]
  (try (let [items (nav/items bs opts)
             n (count items)]
         {:ok (mapv #(nav/value (nth items %)) (range n))})
       (catch clojure.lang.ExceptionInfo e {:typed (:type (ex-data e))})
       (catch Throwable t {:untyped (class t)})))

(defn- seq-items-or-error [^bytes bs]
  (try {:ok (vec (boring/decode-seq bs opts))}
       (catch clojure.lang.ExceptionInfo e {:typed (:type (ex-data e))})
       (catch Throwable t {:untyped (class t)})))

(deftest damage-never-makes-the-two-readers-disagree
  (testing "mutate arbitrary bytes of a sealed file and require that `nav` --
            which consults the index -- and `decode-seq` -- which does not --
            never both succeed with different answers. That is exactly the
            shape of every index defect found so far, and none of them would
            have survived this."
    (let [vs (vec (for [i (range 60)] {:id i :name (str "rec-" i) :tags [i (inc i)]}))
          ^bytes clean (seal vs opts)
          n (alength clean)
          ;; DAMAGE INSIDE THE FRAME, and the boundary is the point.
          ;;
          ;; The index is a claim ABOUT the data section. Validating that claim
          ;; against the data it describes is cheap and complete only at the
          ;; ends: `footer-start` pins the frame, `anchor[0] = 0` pins the
          ;; start, and the end check pins the last stride. Proving a MIDDLE
          ;; anchor is an item boundary means walking to it, which is the work
          ;; the index exists to avoid -- so damage to the DATA that shifts item
          ;; boundaries can leave every anchor after it stale, and measurably
          ;; does: zeroing byte 0 of this fixture makes `nth` disagree with
          ;; `decode-seq` on 44 of 60 items, from anchor 1 onward.
          ;;
          ;; That is the trust boundary doc/SHAPES.md already states, and
          ;; closing it costs a validating walk on every lookup. What must
          ;; hold, and what this asserts, is the half that is affordable: when
          ;; the INDEX is what is damaged, the reader that consults it and the
          ;; reader that ignores it never disagree.
          frame-at (long (#'boring.frame/footer-start clean))
          gen-site (gen/choose frame-at (dec n))
          gen-damage (gen/vector (gen/tuple gen-site (gen/choose 0 255)) 1 4)
          result
          (tc/quick-check
           400
           (prop/for-all
            [damage gen-damage]
            (let [c (java.util.Arrays/copyOf clean n)]
              (doseq [[i v] damage] (aset-byte c (int i) (unchecked-byte (int v))))
              (let [a (nav-items-or-error c)
                    b (seq-items-or-error c)]
                (and
                 ;; Neither reader may fail untyped, whatever the bytes say.
                 (nil? (:untyped a)) (nil? (:untyped b))
                 ;; And where both produce values, the values must match, item
                 ;; for item, for as far as both got.
                 ;; COMPARED BY RE-ENCODING, which is the only oracle strong
                 ;; enough here. Three earlier versions of this property
                 ;; reported disagreements that were not: `=` treats the Java
                 ;; arrays inside a decoded frame as identities;
                 ;; `conformance/equiv?` reaches into maps and sequences but
                 ;; not into a tagged literal; and its map branch uses
                 ;; `contains?`, which cannot match a `byte[]` KEY -- which
                 ;; damaged bytes readily produce. Each failure was in the
                 ;; comparison, not the library.
                 ;;
                 ;; The wire is the ground truth this is about anyway: two
                 ;; values that re-encode to the same bytes are the same value
                 ;; for every purpose boring has.
                 (or (not (and (contains? a :ok) (contains? b :ok)))
                     (let [xs (:ok a) ys (:ok b)]
                       (and (= (count xs) (count ys))
                            (= (mapv re-encode xs) (mapv re-encode ys))))))))))]
      (is (:pass? result)
          (str "a damaged file where the two readers disagree: "
               (pr-str (:shrunk result))))))

  (testing "the control: undamaged, both readers agree AND the index is really
            in use -- without this the property could pass on a corpus where
            nav never consults an index at all"
    (let [vs (vec (for [i (range 60)] {:id i :name (str "rec-" i) :tags [i (inc i)]}))
          ^bytes clean (seal vs opts)]
      (is (some? (:offsets (#'boring.nav/read-index (#'boring.nav/nav-of clean opts))))
          "the sequence node is present, so `nth` goes through the index")
      (is (= vs (mapv nav/value (nav/items clean opts))))
      (is (= vs (vec (boring/decode-seq clean opts))))
      (is (= (inc (count vs)) (count (reader-loop clean)))
          "and a bare Reader sees one MORE item -- the frame -- which is what
           makes `decode-seq` a real second opinion rather than the same code"))))
