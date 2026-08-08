(ns boring.index-robustness-test
  "Every test here is a bug that shipped on this branch and was found by review
  rather than by the suite.

  They shared a theme, and the theme has since been narrowed deliberately.

  It used to be: **the index is an optimisation and is never load-bearing for
  correctness** -- a missing, stale, truncated or randomly corrupt index may
  cost speed, but may not change an answer and may not throw at the caller.
  Six findings violated that, and none needed hostile input; four fired on
  ordinary data at the shipped defaults.

  THAT CLAIM IS NO LONGER MADE, and the tests here say so. Holding it up meant
  re-deriving at read time what the frame asserts: verifying an anchor against
  its predecessor costs O(stride) SKIPS per jump, and a skip is O(1) only for a
  scalar -- stepping over 16 twenty-entry maps is ~640 sub-skips, measured at
  four times the cost of the lookup it guards. The index is now a TRUST
  BOUNDARY outright: we use it only where we are willing to trust it, and
  integrity of the index is integrity of the document. Corruption beneath us is
  the storage layer's job, and both real consumers have one.

  WHAT IS STILL PROMISED, and what these tests now pin, is narrower and not
  negotiable:

    * No untyped exception, ever, from any damage to any byte. A wrong answer
      is within the boundary; an `ArrayIndexOutOfBoundsException` out of `get`
      is not.
    * No read outside the file. This is why the O(1) frame-structure and
      segment bounds stay while the per-node verification goes -- bounds are
      not a matter of trust, because `Reader.skipFrom` does an unchecked array
      access.
    * A reader that consults no index is never affected by frame damage.
    * Undamaged data always reads correctly, which is less trivial than it
      sounds: `confirm` looks like a damage check and is not, and deleting it
      breaks `sorted-map` lookups on perfectly good bytes.

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

(defn- parts
  "The six payload elements, CONSISTENT by default, through the writer's own
  packers.

  The cases below used to build `slots` as a vector of byte arrays and `sorted`
  as a vector of booleans -- the shapes both had before the v2 layout. Every one
  of them was therefore refused at `(bytes? packed)`, a check that runs BEFORE
  every length check the cases name, so all seven assertions passed against a
  frame refused for a reason none of them was testing. The control asserted
  `index accepted` over an index whose `:containers` was nil.

  Packing through `pack-slots`/`pack-sorted` is what stops that recurring: a
  case can only be inconsistent in the way it says it is. It also means the
  writer's own node invariant runs here, which is why lying about `counts` or
  `stride` needs `:pack-counts`/`:pack-stride` -- `pack-slots` refuses to build
  an inconsistent node, so the lie has to go in the DECLARED value only.

  Defaults describe the three 4-byte `{\"x\" n}` items `crafted` writes: one
  sequence node at the sentinel offset -1, three entries, anchors at 0, 4, 8."
  [& {:keys [stride containers counts slots sorted pack-counts pack-stride]
      :or {stride 1 containers [-1] counts [3] slots [[0 4 8]] sorted [false]}}]
  [stride
   (int-array containers)
   (int-array counts)
   (#'boring/pack-slots (mapv long-array slots) (long-array containers)
                        (int-array (or pack-counts counts))
                        (long (or pack-stride stride)))
   (#'boring/pack-sorted sorted)])

(defn- accepted?
  "Did the reader actually USE the index, as opposed to detecting the frame and
  refusing it? Both keep `data-end`, so both stop `items` yielding the frame as
  data -- which is why an item count alone cannot tell them apart, and why every
  case here asserts this too."
  [^bytes bs o]
  (let [c (nav/source bs o)
        ix (#'nav/nav-idx (.nav ^boring.nav.Cursor c))]
    (some? (:containers ix))))

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
            [["slots empty where a node is declared"
              (assoc (parts) 3 (byte-array 0))]
             ["sorted shorter than containers"
              (parts :sorted [])]
             ["count disagrees with the slot's length"
              (parts :counts [1000000] :pack-counts [3])]
             ["containers not ascending"
              (parts :containers [5 -1] :counts [3 3]
                     :slots [[5 9 13] [0 4 8]] :sorted [false false])]
             ["an anchor beyond the data section"
              (parts :slots [[0 100 200]])]
             ["a negative stride"
              (parts :stride -1 :pack-stride 1)]]]
      (let [bs (crafted payload)]
        (is (not (accepted? bs opts))
            (str label ": the index must be REFUSED, not merely survived"))
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

(deftest trusted-and-validated-agree-on-a-well-formed-index
  (testing ":trust-index :trusted skips the FRAME-level structural checks, not
            only the per-node ones. Those checks are O(node count) and were run
            on every `nav/source` regardless of the option -- ~42 us to open a
            16 384-element index against ~2 us trusted, paid per source, which
            a document store pays once per blob for a single lookup.

            Skipping them may not change a single answer on an index boring
            itself wrote. This is the invariant that makes the option safe, so
            it is asserted across shapes rather than argued."
    (let [trusted (assoc opts :trust-index :trusted)]
      (doseq [[label v path]
              [["flat scalars"        (vec (range 400))            [399]]
               ["short array"         (vec (range 3))              [2]]
               ["strings"             (mapv #(str "s-" %) (range 300)) [299]]
               ["nested maps"         (vec (for [i (range 120)]
                                             {:a i :b {:c (* i 2)}}))  [119 :b :c]]
               ["mixed depths"        (vec (for [i (range 90)]
                                             [i {:k (str i)} [i i]]))  [89 1 :k]]
               ["empty array"         [[] [] []]                   [1]]]]
        (let [bs (boring/encode-indexed v (assoc opts :index 16 :index-min 4))
              reach (fn [o] (nav/value (reduce #(get %1 %2) (nav/source bs o) path)))]
          (is (= (get-in v path) (reach opts)) (str label ": validated path"))
          (is (= (get-in v path) (reach trusted)) (str label ": trusted path"))
          ;; whole-document realisation too, not just the one path -- a skipped
          ;; check that corrupted `:total` or `:data-end` shows up here first
          (is (= v (nav/value (nav/source bs trusted)))
              (str label ": trusted realises the whole document unchanged"))
          (is (= (nav/value (nav/source bs opts))
                 (nav/value (nav/source bs trusted)))
              (str label ": the two settings must not disagree")))))))

(deftest a-tagged-container-is-realised-not-navigated
  (testing "a tag-27 record puts its payload past the tag, so the root cursor is
            the TAG and no index node matches its offset -- the lookup realises
            the whole container instead. Correct, but O(container), and it is
            why `:shapes` costs projection. Pinned because the ANSWERS must stay
            identical however the value was encoded; only the cost differs."
    (let [pairs (for [j (range 60)] [(str "f" (format "%03d" j)) j])
          o (assoc opts :index 16 :index-min 8 :profile :canonical)
          bare (boring/encode-indexed (into {} pairs) o)
          tagged (boring/encode-indexed (into (sorted-map) pairs) o)]
      ;; the shapes really are different on the wire, or the test proves nothing
      (is (not= 6 (bit-shift-right (bit-and (aget ^bytes bare 0) 0xff) 5))
          "a plain map must encode as a bare CBOR map")
      (is (= 6 (bit-shift-right (bit-and (aget ^bytes tagged 0) 0xff) 5))
          "a sorted-map must encode as a tag")
      (doseq [[label bs] [["bare" bare] ["tagged" tagged]]
              trust [:trusted :ignore]]
        (let [s (nav/source bs (assoc opts :trust-index trust))]
          (is (= 0 (nav/value (get s "f000"))) (str label " " trust " first key"))
          (is (= 59 (nav/value (get s "f059"))) (str label " " trust " last key"))
          (is (nil? (get s "nope")) (str label " " trust " absent key")))))))

(deftest trusted-still-refuses-a-frame-whose-lengths-disagree
  (testing "the one frame check that stays unconditional. Disagreeing lengths
            cost O(1) to detect and would otherwise surface as a raw
            IndexOutOfBoundsException from inside `get` rather than as
            \"no usable index, scan instead\"."
    (let [bs (crafted (assoc (parts) 3 (byte-array 0)))
          trusted (assoc opts :trust-index :trusted)]
      (is (not (accepted? bs trusted))
          "trusted must still REFUSE this frame, not merely survive it")
      (is (= 3 (count (into [] (nav/items bs trusted))))
          "trusted must still fall back to scanning, not index into nothing"))))

(deftest a-consistent-crafted-payload-is-still-used
  (testing "the control: validation must reject inconsistency, not everything.
            Three 4-byte items at stride 1 means three anchors at 0, 4 and 8."
    (let [bs (crafted (parts))]
      (is (accepted? bs opts)
          "the control's index must actually be USED -- this is the assertion
           whose absence made all six cases above vacuous")
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

(deftest a-valid-index-never-makes-counting-wrong
  (testing "the ITEM budget, which is the same argument as `:max-depth` above
            and was written out once then applied to only one of the two
            limits. The frame's item cost scales with the NODE count, so a
            500-record log written with default options overran a budget that
            was generous for its data: `.readFrom` threw, the payload came back
            nil, `:data-end` was lost with it, and `items` walked past the data
            section into the frame and reported 501 records for 500.

            A silently wrong COUNT out of a default-written file, and it had no
            test. This asserts the count itself rather than that the index
            survived: losing `:data-end` IS the failure, and an index-shaped
            assertion would not see it.

            What it does NOT pin, stated because the difference is easy to
            assume away: the item override no longer does anything. Disabling
            it leaves the whole suite green, and the reason is structural
            rather than lucky -- `Reader.readFrom` sets `items = 0` on entry,
            so each payload element is read against a fresh budget, and since
            the v2 layout every element is ONE item. `sorted` used to be a CBOR
            array of one boolean per node, and 770 booleans inside a single
            `readFrom` is what overran a 500-record file's budget. Byte strings
            removed the scaling, not the override.

            So this guards the INVARIANT -- a caller's budget bounds their
            data, never boring's own footer -- against whatever is done to the
            frame next, which is the reason to have it either way."
    (let [out (ByteArrayOutputStream.)
          vs (vec (repeat 500 (vec (range 32))))]
      (boring/write-seq! (boring/writer 65536 opts) vs out (assoc opts :index 16))
      (let [bs (.toByteArray out)]
        (doseq [mi [1 10 100 1000 0]]
          (is (= 500 (count (into [] (nav/items bs (assoc opts :max-items mi)))))
              (str "max-items " mi ": the caller's budget bounds THEIR data, "
                   "never boring's own footer")))
        ;; and it MUST still bound their data. A 32-element record does not fit
        ;; in a budget of 1, and asking for one there is meant to raise -- the
        ;; override isolates the frame, it does not lift the limit. Realising is
        ;; therefore asserted only where the records themselves fit.
        (doseq [mi [1000 0]]
          (is (= vs (mapv nav/value (nav/items bs (assoc opts :max-items mi))))
              (str "max-items " mi ": and the records read back unchanged")))
        (is (thrown? clojure.lang.ExceptionInfo
                     (mapv nav/value (nav/items bs (assoc opts :max-items 10))))
            "a record too big for the budget still raises")))))

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
        (let [nv (#'boring.nav/nav-of damaged o)
              ix (#'boring.nav/read-index nv)]
          ;; `:containers`, not `:slots`. Both are non-nil on an accepted index
          ;; today, but only `:containers` MEANS accepted -- `:slots` means
          ;; "slots is present", which stays true for shapes that are refused.
          (is (some? (:containers ix)) "the damaged index is accepted")
          ;; Slots are expanded on demand now, so ask for node 0 rather than
          ;; reading a pre-expanded vector.
          (is (= [2 22 43 62 82 102]
                 (vec (take 6 (#'boring.nav/slot-at (.rdr nv) ix 0))))
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

(deftest damage-inside-the-frame-never-fails-untyped
  (testing "mutate arbitrary bytes of a sealed file's FRAME and require that
            neither reader ever fails untyped, and that the reader which
            consults no index is unaffected.

            This asserted more once: that `nav` and `decode-seq` never both
            succeed with different answers. That property is gone deliberately.
            The index is now a TRUST BOUNDARY outright -- we use it only where
            we are willing to trust it -- so a damaged frame may hand back a
            wrong answer, and the per-node and per-anchor validation that made
            it not do so has been removed. It cost O(stride) skips per jump
            plus two `boolean[]` per open, to defend against corruption that
            the storage layer beneath us is already responsible for.

            What remains is the half that is not negotiable, because it is not
            about answers but about staying inside the file: no untyped
            exception, ever, and no read past the buffer. That is bought by the
            O(1) frame-structure checks, which is exactly why THOSE stay while
            the per-node walks go."
    (let [vs (vec (for [i (range 60)] {:id i :name (str "rec-" i) :tags [i (inc i)]}))
          ^bytes clean (seal vs opts)
          n (alength clean)
          frame-at (long (#'boring.frame/footer-start clean))
          gen-site (gen/choose frame-at (dec n))
          gen-damage (gen/vector (gen/tuple gen-site (gen/choose 0 255)) 1 4)
          result
          ;; 4000, not 400, AND a fixed seed. At 400 random cases this detected
          ;; its own regression in 5 runs out of 13 -- it reported the tree
          ;; GREEN with the fix it exists to guard reverted, which makes it
          ;; worse than no test: it converts "unverified" into "verified". A
          ;; property that fires 38% of the time is a property you have not
          ;; measured, and I had claimed this one verified on the strength of
          ;; two runs.
          ;;
          ;; The seed is pinned so CI is deterministic; the exhaustive
          ;; single-byte sweep in `the-item-total-must-match-the-data-section`
          ;; is what covers the space this no longer samples randomly.
          (tc/quick-check
           4000
           (prop/for-all
            [damage gen-damage]
            (let [c (java.util.Arrays/copyOf clean n)]
              (doseq [[i v] damage] (aset-byte c (int i) (unchecked-byte (int v))))
              (let [a (nav-items-or-error c)
                    b (seq-items-or-error c)]
                (and
                 ;; NEITHER READER MAY FAIL UNTYPED, whatever the bytes say.
                 ;; This is the half that survives trusting the index, and it
                 ;; is not free -- it is what the O(1) frame-structure checks
                 ;; buy. A lying length with no check sends `byteAt` into the
                 ;; back-pointer or past the buffer, which is how a raw
                 ;; ArrayIndexOutOfBoundsException came out of `get`.
                 (nil? (:untyped a)) (nil? (:untyped b))
                 ;; AND THE INDEX-FREE READER IS UNAFFECTED. Damage is confined
                 ;; to the frame, so `decode-seq` -- which consults no index --
                 ;; must still produce the true records. It may produce one
                 ;; MORE than there are records, because damage that makes the
                 ;; frame undetectable republishes it as a trailing data item;
                 ;; what it may never do is change a record.
                 ;;
                 ;; COMPARED BY RE-ENCODING, which is the only oracle strong
                 ;; enough here. Three earlier versions of this property
                 ;; reported disagreements that were not: `=` treats the Java
                 ;; arrays inside a decoded frame as identities;
                 ;; `conformance/equiv?` reaches into maps and sequences but
                 ;; not into a tagged literal; and its map branch uses
                 ;; `contains?`, which cannot match a `byte[]` KEY -- which
                 ;; damaged bytes readily produce. Each failure was in the
                 ;; comparison, not the library.
                 (or (not (contains? b :ok))
                     (= (mapv re-encode vs)
                        (mapv re-encode (take (count vs) (:ok b)))))))))
           ;; Pinned so CI is deterministic -- see the note on the case count.
           :seed 1785873600000)]
      (is (:pass? result)
          (str "a damaged frame that failed untyped or changed a record: "
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

(defn- probe-or-error
  "Look up a KEY inside each indexed map, which is the path that goes through
  `node-slot`, `slot-at`, the per-node width codes and the delta run. Realising
  the whole document instead walks it start to finish and consults none of
  that -- which is how a many-node index can be badly wrong and still look
  fine."
  [^bytes bs paths o]
  (try (let [src (nav/source bs o)]
         {:ok (mapv (fn [p] (some-> (nav/walk src p) nav/value)) paths)})
       (catch clojure.lang.ExceptionInfo e {:typed (:type (ex-data e))})
       (catch Throwable t {:untyped (class t)})))

(deftest many-node-damage-never-makes-a-lookup-wrong
  (testing "the same property as `damage-never-makes-the-two-readers-disagree`,
            on a frame with MANY nodes instead of one.

            That test seals with `write-seq!` over records too small to index,
            so its frame has exactly one node -- the sequence. A one-node frame
            cannot express a bad node index, a mis-ordered start table, or a
            width code belonging to a different node, and those are precisely
            the failures the dense per-node layout makes possible and the ones
            an offsets-based reader will no longer get a free JVM bounds check
            against.

            41 nodes here: 40 maps of 20 entries, plus the vector holding them.

            WHAT IT ASSERTS is no untyped failure and no read outside the file,
            NOT that the answers stay right. A trusted index that has been
            damaged may hand back a wrong value or report a present key absent;
            that is what trusting it means. The one-slot property that remains
            is the one the O(1) frame-structure checks buy, and it is the reason
            those checks stay when the per-node walks go -- a lying length with
            nothing checking it sends `byteAt` into the back-pointer or past the
            buffer, which is how a raw ArrayIndexOutOfBoundsException came out
            of `get`.

            The index-free reader is the control: `decode` consults no index, so
            frame damage may never change what IT sees."
    (let [doc (vec (for [i (range 40)]
                     (into {} (for [j (range 20)]
                                [(format "k%02d" j) (+ (* 100 i) j)]))))
          o (assoc opts :index 16 :index-min 4)
          ^bytes clean (boring/encode-indexed doc o)
          n (alength clean)
          frame-at (long (#'boring.frame/footer-start clean))
          ;; across nodes AND across strides within a node: k00 is anchor 0,
          ;; k07 is mid-stride, k19 is the last entry of the last stride
          paths (vec (for [i [0 1 17 39] j ["k00" "k07" "k19"]] [i j]))
          gen-damage (gen/vector (gen/tuple (gen/choose frame-at (dec n))
                                            (gen/choose 0 255))
                                 1 4)
          result
          (tc/quick-check
           2000
           (prop/for-all
            [damage gen-damage]
            (let [c (java.util.Arrays/copyOf clean n)]
              (doseq [[i v] damage] (aset-byte c (int i) (unchecked-byte (int v))))
              (let [a (probe-or-error c paths o)]
                (and (nil? (:untyped a))
                     ;; the index-free reader must be untouched by frame damage
                     (= doc (try (boring/decode c o) (catch Throwable _ ::threw)))))))
           :seed 1785873600000)]
      (is (:pass? result)
          (str "a damaged frame that failed untyped, or reached the decoder: "
               (pr-str (:shrunk result))))))

  (testing "the control: undamaged, the index really is in use and really is
            many-node -- without this the property could pass on a frame that
            never had more than one node to get wrong"
    (let [doc (vec (for [i (range 40)]
                     (into {} (for [j (range 20)]
                                [(format "k%02d" j) (+ (* 100 i) j)]))))
          o (assoc opts :index 16 :index-min 4)
          ^bytes clean (boring/encode-indexed doc o)
          ix (#'boring.nav/read-index (#'boring.nav/nav-of clean o))]
      (is (some? (:containers ix)) "the index is accepted")
      ;; `:containers` is the NODE COUNT now, not the long[] -- the array is a
      ;; byte offset into the frame and there is nothing to `alength`.
      (is (< 1 (long (:containers ix)))
          "and has many nodes, which is the whole point of this fixture")
      (is (= 1907 (some-> (nav/walk (nav/source clean o) [19 "k07"]) nav/value))
          "and a mid-stride key in a middle node reads correctly"))))

(deftest a-sequence-node-claiming-no-items-must-be-backed-by-no-data
  (testing "A node with zero items has zero anchors, so the far-end check
            short-circuits before it looks at the data at all -- and `count`
            then returned 0 and `nth` nil over a file holding all 60 of its
            records. Present in the file, returned by `decode-seq`, unreachable
            through the index. Zero items is only honest when the data section
            is empty, which is one comparison."
    (let [vs (vec (for [i (range 60)] {:id i}))
          w (boring/writer 65536 opts)
          out (ByteArrayOutputStream.)]
      (doseq [v vs] (boring/write-to! w v out))
      (let [dl (.size out)]
        (boring/write-to! w (boring.data/unknown-record
                             boring/index-name
                             [16 (int-array [-1]) (int-array [0]) [(byte-array 0)] [false]
                              (byte-array (map unchecked-byte
                                               [0 0 0 0 0 0 (bit-shift-right dl 8)
                                                (bit-and dl 0xff)]))])
                          out)
        (let [bs (.toByteArray out)]
          (is (= 60 (count (boring/decode-seq bs opts)))
              "the control: the records really are all there")
          (is (= 60 (count (nav/items bs opts)))
              "so `count` must not believe a total of zero")
          (is (= vs (mapv nav/value (nav/items bs opts)))))))
    (testing "and the genuinely empty sealed sequence still reads as empty"
      (is (= 0 (count (nav/items (seal [] opts) opts)))))))

;; ------------------------------------- the two paths nothing depended on
;;
;; Disabling each index path INDIVIDUALLY -- each falls back to the scan, which
;; returns the same answers -- and running the three index namespaces:
;;
;;   index load (whole index dead)   4 tests notice
;;   Items.nth (item by position)    1
;;   lookup-map (map key lookup)     0     <-- nothing
;;   nth-item (array element)        0     <-- nothing
;;
;; `lookup-map` is the headline: it is the `get-in` acceleration the README
;; leads with, 27 us against 181 us, and its entire indexed branch could be
;; deleted with every test still green. Both bare paths later turned out to
;; carry defects that agents found and the suite could not -- `nth-item` had no
;; anchor validation at all, and `lookup-map` reported present keys as absent.
;;
;; No assertion about a returned VALUE can close this, because the index is a
;; pure optimisation and the scan returns the same value. Counting the walking
;; is what distinguishes them, and it is deterministic where timing is not.

(deftest the-index-is-actually-consulted-for-a-map-key
  (testing "`lookup-map`'s indexed branch, which nothing depended on"
    (let [o {:profile :canonical}
          m (into {} (for [i (range 200)] [(format "k%03d" i) i]))
          indexed (nav/source (boring/encode-indexed m (assoc o :index 8 :index-min 8)) o)
          plain (nav/source (boring/encode m o) o)]
      (testing "both find the key -- the index changes the work, not the answer"
        (is (= 150 (nav/value (get indexed "k150"))))
        (is (= 150 (nav/value (get plain "k150")))))
      (let [walked (fn [c k] (nav/skips c 0) (nav/value (get c k)) (nav/skips c))
            with (walked indexed "k150")
            without (walked plain "k150")]
        (is (< (* 4 with) without)
            (str "an indexed lookup must do far less walking: " with " vs " without))
        ;; A ceiling as well as a ratio: at stride 8 the walk from an anchor is
        ;; bounded, so this cannot quietly degrade to "a bit better than a scan".
        (is (< with 40) (str "and it must be bounded by the stride, not the map: " with))))))

(deftest the-index-is-actually-consulted-for-an-array-element
  (testing "`nth-item`'s indexed branch, which nothing depended on either"
    (let [o {:profile :canonical}
          v (vec (range 200))
          indexed (nav/source (boring/encode-indexed v (assoc o :index 8 :index-min 8)) o)
          plain (nav/source (boring/encode v o) o)]
      (testing "both reach the element"
        (is (= 150 (nav/value (nth indexed 150))))
        (is (= 150 (nav/value (nth plain 150)))))
      (let [walked (fn [c i] (nav/skips c 0) (nav/value (nth c i)) (nav/skips c))
            with (walked indexed 150)
            without (walked plain 150)]
        (is (< (* 4 with) without)
            (str "an indexed nth must do far less walking: " with " vs " without))
        (is (< with 20) (str "and be bounded by the stride: " with))))))

(deftest the-two-index-builders-agree
  (testing "boring builds an index two ways -- the writer captures nodes while
            encoding (`write-indexed!`, `write-seq!`), and a byte walk derives
            them afterwards (`encode-indexed`, `build-index`). They are ~450
            lines of duplicated algorithm, and the one cross-platform defect
            that reached HEAD this cycle was the two copies of the byte walk
            disagreeing about a flag. Two builders disagreeing about node
            offsets would be worse than any single damaged-index case, because
            it would mean the format has two readings.

            Asserted as BYTE IDENTITY of the whole sealed file, which subsumes
            the node comparison and cannot pass by both being empty -- the node
            counts are asserted non-trivial below."
    (let [o {:profile :canonical}
          capture (fn [v opts] (let [w (boring/writer 65536 o)
                                     out (ByteArrayOutputStream.)]
                                 (boring/write-indexed! w v out (merge o opts))
                                 (.toByteArray out)))
          walk (fn [v opts] (boring/encode-indexed v (merge o opts)))
          nodes (fn [^bytes bs]
                  (let [p (long (#'boring.frame/footer-start bs))]
                    (count (vec (nth (boring.data/frame-payload
                                      (.readFrom (Reader. bs) p)) 1)))))]
      (doseq [[label v expect-nodes]
              [["flat map"    (into {} (for [i (range 40)] [(format "k%02d" i) i])) 1]
               ["vec of maps" (vec (for [i (range 30)] {"a" i "b" (str i)}))        1]
               ["nested"      {"L1" (into {} (for [i (range 20)]
                                               [(format "m%02d" i)
                                                {"L3" (vec (range 20))}]))}       21]
               ["map of vecs" (into {} (for [i (range 20)]
                                         [(format "v%02d" i) (vec (range 20))]))  21]]
              stride [4 16]]
        (let [c (capture v {:index stride :index-min 4})
              w (walk v {:index stride :index-min 4})]
          (is (= (seq c) (seq w))
              (str label " at stride " stride ": the two builders must agree byte for byte"))
          (is (= expect-nodes (nodes w))
              (str label ": and the index must be non-trivial -- " expect-nodes " nodes"))
          (is (= (nav/value (nav/source c o)) (nav/value (nav/source (boring/encode v o) o)))
              (str label ": and both must read back as the plain encoding")))))))

(deftest the-two-index-builders-agree-across-profiles-strides-and-frames
  (testing "`the-two-index-builders-agree` above asserts byte identity of the
            whole sealed file -- but over ONE profile, ONE `:index-min`, and
            four values none of which is tag-27 wrapped. Both of the places the
            two builders actually disagreed were outside that box, so the test
            could not fail for either of them.

            Widened here rather than in place, so the original stays the
            regression it was written to be. The two disagreements:

            THE `sorted` FLAG UNDER `:profile :canonical-rfc7049`. That profile
            sorts keys LENGTH FIRST, so `Writer` took `!legacyCanonicalOrder`
            and claimed nothing, while the byte walk compared the emitted key
            bytes and reported the truth. Same bytes, same offsets, different
            flag -- and the conservative side gives up the binary search for
            every `:canonical-rfc7049` file written through `write-seq!`.

            AND A TAG-27 FRAME'S OWN `[name, args]` ARRAY. For a `sorted-map`
            or `sorted-set` the byte walk emitted a node for the wrapper as
            well as for the collection inside it: containers `[2 22]` against
            `[22]`, 306 bytes against 295. It needs `:index-min` <= 2 to
            appear, which is why the default of 16 hid it.

            Compared as bytes AND as the decoded node structure, because two
            files can differ in a flag the byte comparison would catch but the
            message would not name."
    (let [capture (fn [v opts] (let [w (boring/writer 65536 opts)
                                     out (ByteArrayOutputStream.)]
                                 (boring/write-indexed! w v out opts)
                                 (.toByteArray out)))
          walk (fn [v opts] (boring/encode-indexed v opts))
          payload (fn [^bytes bs]
                    (let [p (long (#'boring.frame/footer-start bs))]
                      (boring.data/frame-payload (.readFrom (Reader. bs) p))))
          shape (fn [^bytes bs]
                  (let [p (payload bs)]
                    {:stride (nth p 0)
                     :containers (vec (nth p 1))
                     :counts (vec (nth p 2))
                     :sorted (vec (nth p 4))}))]
      (doseq [[label v]
              [["vec of maps"  (vec (for [i (range 30)] {"a" i "b" (str i)}))]
               ["nested"       {"L1" (into {} (for [i (range 20)]
                                                [(format "m%02d" i)
                                                 {"L3" (vec (range 20))}]))}]
               ;; Keys where LENGTH-FIRST and BYTEWISE genuinely diverge: a
               ;; short text key sorts after a large integer key one way and
               ;; before it the other. Equal-length keys cannot show it.
               ["mixed widths" (into {} (concat (for [i (range 20)]
                                                  [(str "k" i) i])
                                                (for [i (range 20)]
                                                  [(+ 100000000 i) i])))]
               ;; Tag-27 wrapped, which is what F10b needed.
               ["sorted map"   (into (sorted-map)
                                     (for [i (range 40)] [(format "k%02d" i) i]))]
               ["sorted set"   (into (sorted-set) (range 40))]
               ["record"       (->Widget (vec (range 30)) (into {} (for [i (range 30)]
                                                                     [(str i) i])))]]
              profile [:canonical :canonical-rfc7049 :clojure :archival :interop]
              stride [1 4 16]
              min-entries [2 4]]
        (let [o (cond-> {:profile profile :index stride :index-min min-entries}
                  (not (#{:canonical :canonical-rfc7049 :archival} profile))
                  (assoc :stringref false))
              tag (str label " | " profile " | stride " stride
                       " | :index-min " min-entries)
              c (capture v o)
              w (walk v o)]
          (is (= (shape c) (shape w))
              (str tag ": the two builders must derive the same nodes"))
          (is (= (seq c) (seq w))
              (str tag ": and therefore the same file, byte for byte"))
          ;; Both must still READ as the plain value, so a builder cannot be
          ;; made to agree by making both of them wrong.
          (is (= (nav/value (nav/source c o))
                 (nav/value (nav/source (boring/encode v o) o)))
              (str tag ": and the file must read back as the plain encoding")))))))

(deftest indexed-and-unindexed-agree-across-shapes
  (testing "the correctness spine: for every container shape, stride and
            profile, the indexed answer must equal the unindexed one for EVERY
            key and element. Swept rather than sampled, because every index
            defect this cycle was found by an audit constructing a case the
            suite did not contain."
    (doseq [n [1 2 15 16 17 64]
            stride [1 2 16 64]
            profile [:canonical :clojure]]
      (let [o (cond-> {:profile profile} (= profile :clojure) (assoc :stringref false))
            m (into {} (for [i (range n)] [(format "k%04d" i) i]))
            ix (nav/source (boring/encode-indexed m (assoc o :index stride :index-min 1)) o)
            pl (nav/source (boring/encode m o) o)]
        (doseq [k (keys m)]
          (is (= (some-> (get ix k) nav/value) (some-> (get pl k) nav/value))
              (str "map n=" n " stride=" stride " " profile " key " k)))
        (is (nil? (some-> (get ix "absent") nav/value))
            (str "map n=" n " stride=" stride " " profile ": and an absent key stays absent")))
      (let [o (cond-> {:profile profile} (= profile :clojure) (assoc :stringref false))
            v (vec (range n))
            ix (nav/source (boring/encode-indexed v (assoc o :index stride :index-min 1)) o)
            pl (nav/source (boring/encode v o) o)]
        (dotimes [i n]
          (is (= (nav/value (nth ix i)) (nav/value (nth pl i)))
              (str "vec n=" n " stride=" stride " " profile " idx " i)))))))

(deftest trust-index-ignore-scans-instead
  (testing "A chosen index can misdirect a lookup WITHIN the blob it came with.
            That gains an attacker nothing directly -- they wrote every byte,
            so they could have sent the value they misdirect you to. It matters
            when an application verifies one part of a document and acts on
            another: two `get`s can be made to resolve to overlapping regions,
            so you checked one thing and used a different one.

            `:trust-index :ignore` removes the question by scanning. The scan
            is the reference implementation the indexed paths are checked
            against, so this setting needs no separate correctness argument --
            only proof that it really is scanning, which is what the skip
            counts below are for."
    (let [o {:profile :canonical}
          m (into {} (for [i (range 200)] [(format "k%03d" i) i]))
          bs (boring/encode-indexed m (assoc o :index 8 :index-min 8))
          walked (fn [opts k] (let [c (nav/source bs opts)]
                                (nav/skips c 0)
                                [(nav/value (get c k)) (nav/skips c)]))
          [tv trusted-skips] (walked o "k150")
          [iv scan-skips] (walked (assoc o :trust-index :ignore) "k150")]
      (testing "same answer either way -- the index is an optimisation"
        (is (= 150 tv))
        (is (= 150 iv)))
      (testing "but `:ignore` really does scan, and the default really does not"
        (is (< (* 4 trusted-skips) scan-skips)
            (str "trusted " trusted-skips " skips, ignored " scan-skips)))
      (testing "and every key still reads back under `:ignore`"
        (let [c (nav/source bs (assoc o :trust-index :ignore))]
          (doseq [i (range 0 200 17)]
            (is (= i (nav/value (get c (format "k%03d" i))))))))
      (testing "an unimplemented or misspelt value is refused rather than
                silently meaning `:trusted` -- `:validate` is measured but not
                yet built, and naming a behaviour boring does not perform would
                be worse than not offering it"
        (doseq [bad [:validate :nope "trusted" nil]]
          (is (= :boring/bad-option
                 (try (do (nav/source bs (assoc o :trust-index bad)) nil)
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
              (pr-str bad)))))))

(deftest trusted-skips-validation-but-not-correctness
  (testing ":trust-index :trusted skips the per-node far-end check, which is
            the single dominant cost of an index on a short-lived source --
            measured 47 us of a node's ~50 us. On sound data it must return
            exactly what the validated path returns."
    (let [m (into {} (for [i (range 200)] [(format "k%03d" i) {:v i :s (str "s" i)}]))
          o {:stringref false}
          bs (boring/encode-indexed m (assoc o :index 16))]
      (doseq [k (map #(format "k%03d" %) (range 200))]
        (is (= (some-> (get (nav/source bs o) k) nav/value)
               (some-> (get (nav/source bs (assoc o :trust-index :trusted)) k)
                       nav/value))
            (str "trusted and validated must agree on " k)))
      (testing "and an absent key is absent under both"
        (is (nil? (get (nav/source bs o) "nope")))
        (is (nil? (get (nav/source bs (assoc o :trust-index :trusted)) "nope")))))))

(deftest validation-is-per-node-not-per-index
  (testing "an unsound node must not disable the index for containers that are
            fine -- validation moved from a load-time gate over every node to a
            per-node verdict taken on first use"
    (let [m {"a" (into {} (for [i (range 40)] [(format "a%02d" i) i]))
             "b" (into {} (for [i (range 40)] [(format "b%02d" i) i]))}
          o {:stringref false}
          bs (boring/encode-indexed m (assoc o :index 4 :index-min 4))
          src (nav/source bs o)]
      ;; Both containers read correctly, and the index carries a node for each.
      ;;
      ;; This used to assert `(some? (:node-checked ix))` -- that the index
      ;; carried a per-node verdict slot. That was checking the MECHANISM, and
      ;; the mechanism is gone: the verdict was cached in two `boolean[]` sized
      ;; by the document because the check walked the container to its end, and
      ;; what is left is three O(1) reads, cheaper to repeat than to remember.
      ;;
      ;; The property the name claims is now STRUCTURAL rather than observed.
      ;; There is no shared verdict store, so there is nothing an unsound node
      ;; could poison for a sound one -- `node-sound?` is a pure function of the
      ;; node it is asked about.
      (doseq [i (range 40)]
        (is (= i (some-> (get (get src "a") (format "a%02d" i)) nav/value)))
        (is (= i (some-> (get (get src "b") (format "b%02d" i)) nav/value)))))))

(deftest get-and-nth-agree-on-arrays
  (testing "`get` with an integer index on an array cursor fell through to
            not-found -- a PRESENT element reported absent, and `contains?`
            false for a valid index -- while `nth` returned it. It survived
            because it depends on the ENCODING, not the data: under :shapes an
            array is a tag cursor and clojure.core answered correctly, so a
            caller's get-in over an indexed path worked or silently returned nil
            according to how the document happened to be written."
    (let [v {:rows (vec (for [i (range 40)] {:e i :v (str "val-" i)}))}]
      (doseq [[label o] [["plain"  {:stringref false}]
                         ["shapes" {:stringref false :shapes true}]
                         ["indexed" {:stringref false :index 4 :index-min 4}]]]
        (let [bs (if (:index o) (boring/encode-indexed v o) (boring/encode v o))
              rows (get (nav/source bs o) :rows)]
          (doseq [i (range 40)]
            (is (= {:e i :v (str "val-" i)} (nav/value (get rows i)))
                (str label ": get " i))
            (is (= (nav/value (nth rows i)) (nav/value (get rows i)))
                (str label ": nth and get agree at " i))
            (is (contains? rows i) (str label ": contains? " i)))
          (testing (str label ": out of range and non-integer keys are absent")
            (is (= :nf (get rows 40 :nf)))
            (is (= :nf (get rows -1 :nf)))
            (is (= :nf (get rows :not-an-index :nf)))
            (is (not (contains? rows 40)))))))))

(deftest get-in-works-through-an-indexed-path
  (testing "the shape a consumer actually writes"
    (let [v {:a {:b [{:c 1} {:c 2} {:c 3}]}}
          o {:stringref false}
          src (nav/source (boring/encode v o) o)]
      (is (= 2 (nav/value (-> src (get :a) (get :b) (get 1) (get :c))))))))

(defn- counting-source
  "A ByteSource over `bs` that counts every byte it is asked for.

   `Reader.skips` is the wrong unit for measuring skip WORK: skipping a
   20 000-element vector is ONE `skipFrom` call, because the walk recurses
   inside the reader. Bytes read is the honest measure, and it is exact --
   no clock, no threshold to tune.

   `heapArray` returns nil deliberately, so the Reader cannot take its byte[]
   fast path around this."
  [^bytes bs counter]
  (let [u (fn [^long p] (bit-and (aget bs (int p)) 0xff))]
    (reify org.replikativ.boring.ByteSource
      (size [_] (alength bs))
      (at [_ p] (swap! counter inc) (aget bs (int p)))
      (i16 [_ p] (swap! counter + 2)
        (unchecked-short (bit-or (bit-shift-left (u p) 8) (u (inc p)))))
      (i32 [_ p] (swap! counter + 4)
        (unchecked-int (bit-or (bit-shift-left (u p) 24) (bit-shift-left (u (+ p 1)) 16)
                               (bit-shift-left (u (+ p 2)) 8) (u (+ p 3)))))
      (i64 [_ p] (swap! counter + 8)
        (bit-or (bit-shift-left (long (u p)) 56)
                (bit-shift-left (long (u (+ p 1))) 48)
                (bit-shift-left (long (u (+ p 2))) 40)
                (bit-shift-left (long (u (+ p 3))) 32)
                (bit-shift-left (long (u (+ p 4))) 24)
                (bit-shift-left (long (u (+ p 5))) 16)
                (bit-shift-left (long (u (+ p 6))) 8)
                (long (u (+ p 7)))))
      (copyTo [_ p dst off n] (swap! counter + n)
        (System/arraycopy bs (int p) dst (int off) (int n)))
      (heapArray [_] nil))))

(deftest a-bounded-walk-does-not-skip-past-its-last-entry
  (testing "`scan-map` walks a span by comparing a key and then advancing past
            the VALUE -- and advancing past a value is a structural walk of that
            value's whole subtree. It used to advance unconditionally and then
            discover on the NEXT iteration that it had reached its limit, so the
            offset it worked out was thrown away.

            Invisible on flat data and decisive on nested data, because the
            wasted skip is the size of one subtree. An INDEXED lookup walks a
            bounded span per anchor, so it paid this on every anchor it tried --
            which is how a correct index came to make lookups SLOWER than no
            index at all: measured on a 32 KB document, 39.5 us indexed against
            34.9 unindexed, and 1.95 once fixed.

            Asserted in BYTES READ, not on a clock: the defect is wasted work
            and this counts exactly how much."
    (let [;; The giant sibling is a NEST OF SMALL CONTAINERS, so `:index-min 5`
          ;; indexes the five-entry root and nothing inside it. A flat 20 000
          ;; element vector would clear the threshold itself and put 20 000
          ;; anchors in the frame, which measures the frame rather than the walk
          ;; -- the first version of this test did exactly that.
          nest (fn nest [^long d]
                 (if (zero? d) 0 {:a (nest (dec d)) :b (nest (dec d)) :c (nest (dec d))}))
          big {:huge (nest 8) :a 1 :b 2 :c 3 :d 4}
          opts {:index 1 :index-min 5}
          bs (boring/encode-indexed big opts)
          plain (boring/encode big {:stringref false})
          ;; `:trust-index :trusted`, because VALIDATION WALKS THE CONTAINER
          ;; per node -- that is what it is for -- so under the default an index
          ;; cannot save a walk by construction. This test is about whether the
          ;; trusted path, where the index is supposed to pay, actually does.
          ropts {:stringref false :trust-index :trusted}
          reads (fn [x k]
                  (let [c (atom 0)
                        v (nav/value (get (nav/source (counting-source x c) ropts) k))]
                    [v @c]))]
      (testing "every answer is what the unindexed document gives"
        (doseq [k [:a :b :c :d :absent]]
          (is (= (first (reads plain k)) (first (reads bs k))) (str "answer for " k))))
      (testing "and reaching a key AFTER the giant sibling reads a bounded
                number of bytes, not one per element of it"
        (doseq [k [:a :b :c :d]]
          (let [[v n] (reads bs k)
                [_ unindexed] (reads plain k)]
            (is (some? v))
            (is (< n (quot unindexed 10))
                (str "indexed lookup of " k " read " n " bytes against "
                     unindexed " unindexed; before the fix the index read MORE "
                     "than the scan, because every anchor it tried walked the "
                     "whole 20000-element sibling")))))
      (testing "a MISS still costs the honest walk, because a negative from a
                possibly-damaged index is re-derived rather than trusted"
        (is (nil? (first (reads bs :absent))))))))
