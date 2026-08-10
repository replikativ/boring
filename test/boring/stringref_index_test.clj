(ns boring.stringref-index-test
  "Stringref namespaces and an offset index, together.

  A stringref is an index into a table built by decoding every preceding
  qualifying string, so a cursor holding only an offset cannot resolve one --
  which is why the two options were mutually exclusive. The index frame now
  carries `(stringref index -> offset of the defining literal)` for the entries
  something actually references, and a reference resolves by JUMPING.

  EVERY TEST HERE IS A REGRESSION TEST. All five defects below were live on a
  branch whose 422-test suite was green, and were found by a verification pass
  rather than by the suite -- which is the reason this namespace exists."
  (:require [clojure.test :refer [deftest testing is]]
            [boring.core :as b]
            [boring.nav :as nav])
  (:import [org.replikativ.boring Writer Reader]))

;; The public writers still refuse the combination, because `encode-indexed`
;; derives its index by walking bytes and has no symbol table to take pointers
;; from -- so lifting the guard would make the two builders write different
;; files for the same value. These reach the resolved path directly.
(def ^:private write-indexed-resolved! @#'b/write-indexed-resolved!)
(def ^:private write-seq-resolved! @#'b/write-seq-resolved!)
(def ^:private resolve-opts @#'b/resolve-opts)

(defn- doc
  "One value, stringref on, sealed with an index at `stride`."
  ^bytes [v stride]
  (let [w (Writer. 4096)
        bos (java.io.ByteArrayOutputStream.)]
    (write-indexed-resolved! w v bos (resolve-opts {:stringref true}) stride 0)
    (.toByteArray bos)))

(def ^:private records
  "Konserve-shaped: repeated keys, repeated city values, unique names. 56 of the
  256 qualifying strings are referenced, which is the sparse case the pointer
  table is built for."
  (vec (for [i (range 200)]
         {:profile {:city (str "city-" (mod i 50))
                    :name (str "name-" i)
                    :age (mod i 90)}
          :revenue (double i)
          :tags (vec (range 10))})))

;; --------------------------------------------------------------- round trip

(deftest a-stringref-document-is-navigable-when-the-frame-carries-pointers
  (let [d (doc records 16)
        c (nav/root d nil)]
    (is (= 200 (count c)))
    (is (= records (nav/value c)) "the whole document, realised through nav")
    (testing "every row, every field, against the source value"
      (is (every? (fn [i]
                    (let [row (nth c i) m (nth records i)]
                      (and (= (:revenue m) (nav/value (get row :revenue)))
                           (= (get-in m [:profile :city])
                              (nav/value (get (get row :profile) :city)))
                           (= (get-in m [:profile :name])
                              (nav/value (get (get row :profile) :name))))))
                  (range 200))))
    (testing "a path walk and an absent key"
      (is (= "city-0" (nav/value (nav/walk c [150 :profile :city]))))
      (is (nil? (nav/value (get (nth c 1) :nope)))))))

(deftest the-two-resolution-mechanisms-agree
  ;; The reader can resolve a stringref two ways: incrementally, by decoding
  ;; every string in order, which is what `decode` does; and by jumping to the
  ;; recorded offset, which is what a cursor does. Two mechanisms for one fact
  ;; is a parser differential waiting to happen, so they are compared directly.
  (doseq [v [records
             (vec (repeat 40 {:aaaaaa 1 :bbbbbb 2 :cccccc 3}))
             {:outer (vec (repeat 30 {:repeated-key "repeated-value"}))}
             (vec (repeat 30 ["shared-string" "shared-string" "other-string"]))
             ;; non-ASCII, which takes the writeStringSlow path
             (vec (repeat 30 {:kääntäjä "ylläpitäjä" :muu "ylläpitäjä"}))]]
    (let [d (doc v 1)]
      (is (= v (first (b/decode-seq d)))
          "incremental decode")
      (is (= v (nav/value (nav/root d nil)))
          "offset resolution")
      (is (= (first (b/decode-seq d)) (nav/value (nav/root d nil)))
          "and they agree with each other"))))

;; ------------------------------------------------------------ the five bugs

(deftest an-integer-probe-cannot-phantom-match-a-keyword-key
  ;; D1. `stringref-key-matches?` compared the probe past a two-byte tag-39
  ;; head without checking the probe HAD one, and guarded only `length > 2` --
  ;; which admits exactly the three-byte probes where the remaining span is one
  ;; byte. The integer 365 encodes `19 01 6d`, and `6d` is the text head of a
  ;; 13-character keyword, so `(get row 365)` returned the value of
  ;; `:key-number-5`. Sweeping 0..1999 gave 14 phantom hits.
  (let [v (vec (repeat 40 (into {} (map-indexed (fn [i k] [k i])
                                                (map #(keyword (str "key-number-" %))
                                                     (range 40))))))
        c (nav/root (doc v 1) nil)
        row (nth c 5)]
    (is (empty? (filterv #(some? (nav/value (get row %))) (range 2000)))
        "no integer key may match any keyword key")
    (testing "while real lookups still answer"
      (is (= 7 (nav/value (get row :key-number-7))))
      (is (= 0 (nav/value (get row :key-number-0)))))))

(deftest a-sequence-refuses-stringref-with-an-index
  ;; D2. `write-root!` resets per item, so every top-level item numbers its
  ;; namespace from zero, while the frame carries ONE index-keyed table -- which
  ;; described only the last item. Item 1's references then resolved against
  ;; item N's literals, silently: {:alpha 1 :beta 2 :gamma {:alpha 3 :beta 4}}
  ;; read back with :gamma {:zulu 3 :yankee 4}.
  (let [w (Writer. 4096) bos (java.io.ByteArrayOutputStream.)]
    (is (= :boring/incompatible-options
           (try (write-seq-resolved!
                 w [{:alpha 1 :beta 2 :gamma {:alpha 3 :beta 4}}
                    {:zulu 5 :yankee 6 :xray {:zulu 7 :yankee 8}}]
                 bos (resolve-opts {:stringref true}) 1 0)
                :no-throw
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
        "refused at the resolved path, not only at the public arity"))
  (testing "a sequence without stringref is unaffected"
    (let [w (Writer. 4096) bos (java.io.ByteArrayOutputStream.)]
      (write-seq-resolved! w [{:a 1} {:b 2}] bos (resolve-opts {:stringref false}) 1 0)
      (is (= [{:a 1} {:b 2}] (vec (b/decode-seq (.toByteArray bos))))))))

(deftest a-fork-can-resolve-references
  ;; D3. The pointer table is Reader state -- #12 put it there so a fork could
  ;; not read through a stale one -- and a fork inherits the realised Index but
  ;; builds a fresh Reader, so it had no table and every reference raised.
  ;; `fork` is the documented way to read one source from several threads.
  (let [d (doc (vec (repeat 60 {:alphaalpha 1 :betabetaXX 2 :gammagamma 3})) 1)
        parent (nav/root d nil)
        forked (nav/fork parent)]
    (is (= (nav/value parent) (nav/value forked)))
    (is (= 2 (nav/value (get (nth forked 3) :betabetaXX))))
    (testing "and the parent still reads after the fork"
      (is (= 1 (nav/value (get (nth parent 3) :alphaalpha)))))))

(deftest re-pointing-a-root-cursor-lands-on-the-new-root
  ;; D4. A root cursor sits past the envelope on a stringref document and at 0
  ;; on an ordinary one. `re-point!` keeps the cursor's offset, so re-pointing
  ;; across the two read from the middle of the new value -- `[1 2 [7 8 9]]`
  ;; came back as `[7 8 9]`, with no exception. This is the documented
  ;; zero-allocation scan idiom.
  (let [sr (doc (vec (repeat 60 {:alphaalpha 1 :betabetaXX 2 :gammagamma 3})) 1)
        plain (b/encode [1 2 [7 8 9]] {:stringref false})]
    (testing "stringref document -> plain document"
      (is (= [1 2 [7 8 9]] (nav/value (nav/re-point! (nav/root sr nil) plain)))))
    (testing "plain document -> stringref document"
      (let [c (nav/re-point! (nav/root plain nil) sr)]
        (is (= 60 (count c)))
        (is (= 2 (nav/value (get (nth c 0) :betabetaXX))))))))

(deftest a-nested-namespace-keeps-its-meaning
  ;; D5. The envelope skip was applied in `cursor-at`, so it fired at EVERY
  ;; offset -- and a tag 256 in the middle of a document is genuine user data
  ;; whose namespace the reader must enter. Stepping past it made `realize`
  ;; read the inner item without one, turning a valid value into a throw.
  ;; `[1, 256(["hello", 25(0)]), 3]`:
  (let [bs (byte-array (map unchecked-byte
                            [0x83 0x01
                             0xd9 0x01 0x00 0x82 0x65 0x68 0x65 0x6c 0x6c 0x6f
                             0xd8 0x19 0x00
                             0x03]))]
    (is (= [1 ["hello" "hello"] 3] (b/decode bs {})) "decode, for reference")
    (is (= [1 ["hello" "hello"] 3] (nav/value (nav/root bs nil))))
    (is (= ["hello" "hello"] (nav/value (nth (nav/root bs nil) 1)))
        "the nested namespace is entered, not skipped")))

;; ----------------------------------------------------------------- the frame

(deftest the-index-frame-is-never-written-inside-a-namespace
  ;; `seal-index-with!` emitted the frame under the DATA's options, and
  ;; `write-root!` opens a namespace whenever :stringref is set -- so the frame
  ;; gained a leading `d9 01 00`, which shifts the 17-byte prefix `read-index`
  ;; compares against. The frame then stops being recognised and the index is
  ;; SILENTLY DEAD: no error, just scanning. Checked structurally rather than
  ;; through behaviour, because the failure mode is invisible from outside.
  (let [d (doc records 16)
        n (alength d)
        ;; the back-pointer in the trailing 9 bytes names where the frame begins
        ptr (loop [i 0 v 0]
              (if (= i 8) v (recur (inc i) (+ (* v 256) (bit-and (aget d (+ (- n 9) 1 i)) 0xff)))))]
    (is (= 0x48 (bit-and (aget d (- n 9)) 0xff))
        "the locator is an 8-byte byte string, so the trailer is 0x48 plus eight")
    (is (not= [0xd9 0x01 0x00]
              [(bit-and (aget d ptr) 0xff)
               (bit-and (aget d (+ ptr 1)) 0xff)
               (bit-and (aget d (+ ptr 2)) 0xff)])
        "the frame must not begin with a tag-256 head")
    (is (= 0xd8 (bit-and (aget d ptr) 0xff)) "it begins with tag 27")))

(deftest the-pointer-table-is-sparse-and-structurally-gated
  (let [d (doc records 16)
        n (alength d)
        ptr (loop [i 0 v 0]
              (if (= i 8) v (recur (inc i) (+ (* v 256) (bit-and (aget d (+ (- n 9) 1 i)) 0xff)))))
        payload (vec (:form (first (b/decode-seq (java.util.Arrays/copyOfRange d (int ptr) n)))))]
    (is (= 7 (count payload)) "six frozen elements plus the pointer table")
    (is (= 8 (alength ^bytes (peek payload)))
        "data-end is LAST -- read-index finds the frame by the final nine bytes,
         so an element after it would leave the index undiscoverable")
    (let [e5 ^bytes (nth payload 5)
          lay (bit-and (aget e5 0) 0xff)
          iw (bit-shift-left 1 (bit-and (bit-shift-right lay 4) 3))
          ow (bit-shift-left 1 (bit-and (bit-shift-right lay 6) 3))]
      (is (= 1 (bit-and lay 0xF)) "layout version in the low nibble")
      (is (zero? (rem (dec (alength e5)) (+ iw ow)))
          "the count is not stored; the gate is that the division is exact")
      (testing "sparse: only referenced entries, so far fewer than are registered"
        ;; 6 keys + 50 distinct cities are referenced; the 200 unique names are
        ;; registered and never referenced, so they take no space here.
        (is (= 56 (quot (dec (alength e5)) (+ iw ow))))))))

(deftest an-unknown-layout-version-is-refused-rather-than-misread
  ;; THIS IS WHAT MAKES THE FORMAT EXTENSIBLE, so it is asserted rather than
  ;; left as a property of the code.
  ;;
  ;; Sequences cannot carry a pointer table today: the stringref namespace
  ;; restarts at every top-level item and one frame holds one table. The fix is
  ;; designed -- a SECTIONED table keyed by item start offset, binary search
  ;; sections then pairs -- and measured at 0.4-2.9% of the file against a
  ;; 22-28% stringref saving. It is not built, because no workload has asked.
  ;;
  ;; Leaving it unbuilt is only safe if a reader that predates it REFUSES the
  ;; new table instead of reading the old layout's bytes out of it. The low
  ;; nibble of the layout byte is the version, `stringref-table-at` gates on
  ;; it, and a table it does not recognise reads as "no pointer table" -- which
  ;; for a document that opens a namespace is a typed refusal, not a guess.
  ;;
  ;; So: a v2 table in tomorrow's file is unreadable to today's release, which
  ;; is correct, and today's v1 files stay readable forever.
  (let [d (doc records 16)
        n (alength d)
        ptr (loop [i 0 v 0]
              (if (= i 8) v (recur (inc i) (+ (* v 256) (bit-and (aget d (+ (- n 9) 1 i)) 0xff)))))
        payload (vec (:form (first (b/decode-seq (java.util.Arrays/copyOfRange d (int ptr) n)))))
        e5 ^bytes (nth payload 5)
        ;; Where the table's bytes actually sit in the file. The frame is a
        ;; suffix, and the table is a byte string inside it, so scanning from
        ;; `ptr` for its content finds it without hard-coding a header width
        ;; that #20 already widened once.
        at (loop [i (int ptr)]
             (cond (> (+ i (alength e5)) n) nil
                   (java.util.Arrays/equals
                    e5 (java.util.Arrays/copyOfRange d i (+ i (alength e5)))) i
                   :else (recur (inc i))))]
    (is (some? at) "the pointer table must be locatable in the file")
    (is (= 1 (bit-and (aget d (int at)) 0xF)) "the control: it is v1 as written")
    (testing "the same file with the version nibble moved on"
      (let [d2 (java.util.Arrays/copyOf d n)]
        (aset-byte d2 (int at)
                   (unchecked-byte (bit-or (bit-and (aget d2 (int at)) 0xF0) 2)))
        (is (= :boring/stringref-not-navigable
               (try (do (nav/root d2 nil) nil)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
            "refused by type, not read as v1 and not thrown untyped")))))

(deftest a-damaged-pointer-table-costs-time-not-correctness
  ;; The frame is untrusted input. doc/SHAPES.md concedes that a damaged index
  ;; may give a WRONG ANSWER; what it does not allow is an untyped exception or
  ;; a read outside the buffer.
  (let [d (doc (vec (repeat 40 {:alphaalpha 1 :betabetaXX 2 :gammagamma 3})) 1)]
    (doseq [[label f] [["shifted base" #(.setStringrefPointers ^Reader % 3 56 1 2)]
                       ["absurd count" #(.setStringrefPointers ^Reader % 100 1000000 1 2)]
                       ["negative base" #(.setStringrefPointers ^Reader % -1 4 1 2)]
                       ["huge base" #(.setStringrefPointers ^Reader % Long/MAX_VALUE 4 1 2)]]]
      (testing label
        (let [r (Reader. d)]
          (f r)
          (is (contains?
               #{:ok :typed}
               (try (.readFrom r 3) :ok
                    (catch clojure.lang.ExceptionInfo _ :typed)
                    (catch Throwable t (keyword (.getSimpleName (class t))))))
              "typed error or an answer, never an untyped throw"))))))
