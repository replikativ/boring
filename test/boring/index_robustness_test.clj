(ns boring.index-robustness-test
  "Every test here is a bug that shipped on this branch and was found by review
  rather than by the suite.

  They share a theme, and it is the claim the whole index rests on: **the index
  is an optimisation and is never load-bearing for correctness.** A missing,
  stale, corrupt or crafted index may cost speed; it may not change an answer
  and it may not throw at the caller. Six findings all violated that, and none
  of them needed hostile input -- four fire on ordinary data at the shipped
  defaults.

  The suite missed them for reasons worth recording, because they are reasons a
  suite can miss things again:

    - the differential test compares the two index builders against EACH OTHER,
      so a bug both of them have is invisible to it;
    - the container tests use 300-key maps, and the sortedness bug needs FEW
      anchors, so a wide map essentially never triggers it;
    - the corruption test flipped a byte to 0x7F, which lands out of range,
      rather than to a byte that parses as something dangerous."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.nav :as nav]
            [boring.data])
  (:import (java.io ByteArrayOutputStream)))

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
      (is (= (count (vec (boring/decode-seq joined opts))) (count seen))
          "and nav agrees with a plain CBOR sequence reader"))))

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
        (is (= 4 (count (into [] (nav/items bs opts))))
            (str label ": must scan"))
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

(deftest an-index-offset-past-2gb-is-refused-not-wrapped
  (testing "the index stores offsets as int32. Past 2 GiB the cast silently
            produced NEGATIVE offsets: `containers` stopped ascending so the
            binary search broke, and sequence `nth` seeked to a negative
            position. An unindexed file is correct; a wrongly-indexed one is
            not, so this refuses rather than wraps."
    (let [^org.replikativ.boring.Writer w (boring/writer 65536 opts)]
      ;; Integer/MAX_VALUE is 2147483647, so a base this close overflows on the
      ;; first anchor rather than tens of bytes later.
      (.setIndex w (int 1) (int 4) 2147483640)
      (is (thrown? clojure.lang.ExceptionInfo
                   (boring/encode-buffered! w (vec (range 40)))))
      (testing "and the sequence-offset path is checked too"
        (.setIndex w (int 1) (int 4) 0)
        (is (thrown? clojure.lang.ExceptionInfo (.idxItem w 3000000000)))))))

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
    (let [deep (boring/encode (reduce (fn [acc _] [acc]) [] (range 20))
                              {:stringref false})
          shallow (boring/encode [1 2 3] {:stringref false})
          o {:stringref false :max-depth 4}]
      (dotimes [_ 5]
        (is (thrown? clojure.lang.ExceptionInfo (nav/value (nav/source deep o)))))
      (is (= [1 2 3] (nav/value (nav/source shallow o)))
          "a shallow value must still read after repeated deep failures"))))

(deftest nested-tags-are-bounded-by-max-depth
  (testing "arrays and maps charged the depth budget; tag payloads did not, so
            tag recursion was the one nesting maxDepth failed to bound and
            enough of them blew the stack. `skip` must agree with `read` about
            the limit, or the cheap path routes around the expensive path's
            bound -- and skipping is what navigation does."
    ;; c0 = tag 0, repeated, then a small int
    (let [bs (byte-array (concat (repeat 40 (unchecked-byte 0xC0)) [(byte 0)]))
          r (org.replikativ.boring.Reader. bs)]
      (set! (.-maxDepth r) (int 4))
      (is (thrown? clojure.lang.ExceptionInfo (.skipFrom r 0))
          "skipping deeply nested tags must hit the depth limit"))))

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
    (let [w (boring/writer 65536 {:stringref false})
          a (ByteArrayOutputStream.)
          b (ByteArrayOutputStream.)
          vs (vec (for [i (range 5)] {"msg" "repeated" "n" i}))]
      (boring/write-seq! w vs a)
      (boring/write-seq! w vs b {:stringref false})
      (is (= (seq (.toByteArray a)) (seq (.toByteArray b)))
          "the 3-arity must match passing the writer's own opts explicitly")
      (is (= vs (mapv nav/value (seq (nav/items (.toByteArray a) {:stringref false}))))
          "and the result must be navigable"))))

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
          (is (= decoded? skipped?)
              (str "depth " d ", max-depth " md
                   ": decode=" decoded? " skip=" skipped?
                   " -- the two paths must agree on the limit")))))))

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
        (when idx
          (doseq [[cnt slot] (map vector (seq ^ints (:counts idx)) (:slots idx))]
            (is (= (if (zero? cnt) 0 (inc (quot (dec (long cnt)) (long stride))))
                   (count slot))
                (str "stride " stride ": a container of " cnt
                     " entries must have exactly ceil(n/stride) anchors"))))
        (is (= v (nav/value c)) (str "stride " stride ": and the value still reads back"))
        (is (= {} (nav/value (get c "empty-map"))))
        (is (= 5 (nav/value (get-in c ["real" "k05"]))))))))
