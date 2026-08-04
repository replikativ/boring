(ns boring.serialization-review-test
  "Findings from an internal serialization-correctness review that are JVM-specific.
  The portable ones live in `boring.canonical-parity-test`, because the whole
  lesson of that review was that a `.clj` test beside the JVM implementation
  does not cover a guarantee the library makes on two runtimes."
  (:require [clojure.test :refer [are deftest is testing]]
            [clojure.zip]
            [boring.core :as boring]
            [boring.data :as data]
            [boring.nav :as nav]))

(def o {:stringref false})
(def canonical {:profile :canonical})

;; ------------------------------------------------------------------- S4

(deftest canonical-nan-has-exactly-one-encoding
  (testing "`toHalf` carried a NaN's payload bits and sign through, so one value
            had three canonical encodings -- f97e00, f97eaa and f9fe00 -- under
            the profile whose entire purpose is that the same value gives the
            same bytes on every platform. ClojureScript already normalised to
            f97e00, so this was also a cross-platform differential.

            Nothing is lost: boring exposes no NaN-payload or signalling-NaN
            type and decoding collapses every half NaN to Float.NaN, so those
            bits were never a value distinction. RFC 8949 says a deterministic
            protocol without intentional NaN-payload support picks one form."
    (let [payloads [0x7ff8000000000000 0x7ff8000000000001 0x7ffaaaa000000000
                    0xfff8000000000000 0x7ff0000000000001]
          encs (for [b payloads]
                 (seq (boring/encode (Double/longBitsToDouble (unchecked-long b)) canonical)))]
      (is (= 1 (count (set encs)))
          (str "every NaN must encode identically, got " (pr-str (set encs))))
      (is (= [(unchecked-byte 0xf9) (unchecked-byte 0x7e) (unchecked-byte 0x00)]
             (first encs))
          "and the form must be f97e00, which is RFC 8949's own example")
      (is (every? #(Double/isNaN (double (boring/decode (byte-array %) canonical))) encs)))
    (testing ":preserve-width still keeps the exact bits -- narrowing is what it
              exists NOT to do"
      (let [d (Double/longBitsToDouble (unchecked-long 0x7ffaaaa000000000))]
        (is (= 9 (count (boring/encode d o))) "an f64 head plus eight bytes")))))

;; ------------------------------------------------------------------- S5

(deftest tag-40-rejects-wrong-shaped-content-with-a-typed-error
  (testing "a WELL-FORMED tag 40 whose content has the wrong shape escaped as a
            raw ClassCastException or IllegalArgumentException, contradicting
            doc/SECURITY.md's typed-failure guarantee. The byte fuzzer rarely
            builds a valid tag around invalid content -- the limitation that
            document already names."
    (doseq [[label content]
            [["non-numeric dimension" [["bad" 1] (byte-array 4)]]
             ["float dimension"       [[1.5 2] (byte-array 4)]]
             ["negative dimension"    [[-1 2] (byte-array 4)]]
             ["payload not an array"  [[2 2] "not-an-array"]]
             ["zero dimension"        [[0 0] nil]]
             ["dimensions overflow"   [[100000000 100000000] (byte-array 4)]]
             ["empty dimensions"      [[] (byte-array 0)]]
             ["dims/payload mismatch" [[2 3] [1 2 3 4 5]]]]]
      (let [bs (boring/encode (data/tagged-value 40 content) o)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tag 40|dimension|payload"
                              (boring/decode bs o))
            (str label " must be a typed :boring/bad-tag-content")))))
  (testing "and a valid matrix still decodes"
    (let [m (into-array (Class/forName "[D")
                        [(double-array [1.0 2.0]) (double-array [3.0 4.0])])]
      (is (= "[[D" (.getName (class (boring/decode (boring/encode m o) o)))))))
  ;; RFC 8746 3.1.1 admits "any one of a CBOR array of major type 4, a Typed
  ;; Array, or a Homogeneous Array" as the payload, and bounds the dimension
  ;; count nowhere. boring demanded a typed array and exactly two dimensions,
  ;; so Figure 2 of the defining RFC did not decode -- conforming input,
  ;; refused, which doc/COMPATIBILITY.md calls the worst kind of interop bug.
  (testing "the shapes RFC 8746 permits all decode"
    (let [d #(boring/decode (boring/encode (data/tagged-value 40 %) o) o)]
      (is (= [[2 4 8] [4 16 256]] (d [[2 3] [2 4 8 4 16 256]]))
          "Figure 2: a plain CBOR array payload")
      (is (= [[2 4 8] [4 16 256]] (d [[2 3] (data/tagged-value 41 [2 4 8 4 16 256])]))
          "a tag-41 Homogeneous Array payload")
      (is (= [[[1 2] [3 4]] [[5 6] [7 8]]] (d [[2 2 2] [1 2 3 4 5 6 7 8]]))
          "three dimensions")
      (is (= [1 2 3] (d [[3] [1 2 3]]))
          "one dimension"))))

;; ------------------------------------------------------------------- S6

(deftest matrix-writing-handles-empty-and-null-rows
  (testing "three cases contradicted the method's own comments: a zero-row
            matrix took the non-rectangular fallback and came back a vector,
            losing the source type on a value with no content to disagree
            about; and `rowLen(rows[0])` ran BEFORE the loop's null check, so a
            null FIRST row threw a raw NullPointerException and the documented
            null-row fallback was unreachable for the row most likely to be
            null."
    (testing "zero rows take the FALLBACK, and must not be tagged"
      ;; I first fixed this by making a zero-row matrix rectangular so its type
      ;; survived -- which emitted tag 40 with dimensions [0,0]. RFC 8746 3.1.1
      ;; requires dimensions distinct from zero, and our own reader accepted it,
      ;; so a round-trip test blessed invalid CBOR. There is no standard tag-40
      ;; encoding of a zero extent, so the TYPE is what has to give: a 0x0
      ;; matrix carries no values to lose.
      (is (= [] (boring/decode (boring/encode (make-array Double/TYPE 0 0) o) o)))
      (is (= [] (boring/decode (boring/encode (make-array Long/TYPE 0 0) o) o)))
      (is (= "80" (apply str (map #(format "%02x" %)
                                  (boring/encode (make-array Double/TYPE 0 0) o))))
          "a bare empty array -- no tag 40, no dimensions"))
    (testing "a null row falls back instead of throwing, in either position"
      (doseq [[label rows] [["first" [nil (double-array [1.0])]]
                            ["later" [(double-array [1.0]) nil]]]]
        (let [m (into-array (Class/forName "[D") rows)]
          (is (some? (boring/decode (boring/encode m o) o))
              (str "null " label " row must not throw")))))
    (testing "ragged rows still widen to a vector of rows -- documented, and the
              values survive even though the 2-D type does not"
      (let [m (into-array (Class/forName "[D")
                          [(double-array [1.0 2.0]) (double-array [3.0])])
            r (boring/decode (boring/encode m o) o)]
        (is (vector? r))
        (is (= [[1.0 2.0] [3.0]] (mapv vec r)))))))

;; ------------------------------------------------------------------- S10

(deftest tag-numbers-are-validated-at-registration
  (testing "the registry accepted NEGATIVE tags and the writer's registered
            branch emitted them through the unchecked head path -- so a handler
            registered as tag -1 wrote `ff`, the CBOR break byte, followed by
            its content. No exception, just output no reader can parse.

            Validated at construction, which is where a caller can still do
            something about it, rather than at emission where the value is
            already half written."
    (doseq [bad [-1 -40 Long/MIN_VALUE]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsigned"
                            (boring/register-tag (boring/tag-registry) bad
                                                 java.io.File (fn [f] (str f)) nil))
          (str "write registration of tag " bad))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsigned"
                            (boring/register-tag (boring/tag-registry) bad
                                                 nil nil (fn [v] v)))
          (str "read registration of tag " bad)))
    (testing "and ordinary tag numbers still register"
      (is (some? (boring/register-tag (boring/tag-registry) 40000 java.io.File
                                      (fn [f] (str f)) (fn [v] (java.io.File. (str v)))))))))

;; ------------------------------------------------------------------- S11

(deftest mmap-closes-its-arena-when-construction-fails
  (testing "the arena owns the mapping and the caller only learns about it
            through the return value -- so anything that throws after it is
            created leaks the mapping with no handle left to close it. Both
            failure modes are reachable: a missing file, and a stringref
            document, which `boring.nav` refuses by design."
    (if-not (try (Class/forName "org.replikativ.boring.ffm.SegmentSource") true
                 (catch Throwable _ false))
      (is true "skipped: this JVM has no java.lang.foreign (JDK < 22)")
      (let [mmap-source (requiring-resolve 'boring.mmap/mmap-source)
            mmap-items (requiring-resolve 'boring.mmap/mmap-items)
            stringref (doto (java.io.File/createTempFile "boring-arena" ".cbor")
                        .deleteOnExit)]
        (with-open [out (java.io.FileOutputStream. stringref)]
          ;; written WITH stringref, which navigation refuses
          (.write out ^bytes (boring/encode {"a" "aaaa" "b" "aaaa"})))
        (doseq [f [mmap-source mmap-items]]
          (is (thrown? clojure.lang.ExceptionInfo (f stringref))
              "a stringref document must be refused")
          (is (thrown? Exception (f (java.io.File. "/nonexistent/nope.cbor")))
              "a missing file must throw"))
        (testing "and a valid file still maps and reads"
          (let [ok (doto (java.io.File/createTempFile "boring-arena-ok" ".cbor")
                     .deleteOnExit)]
            (with-open [out (java.io.FileOutputStream. ok)]
              (.write out ^bytes (boring/encode {"a" 1} o)))
            (let [[c arena] (mmap-source ok o)]
              (try (is (= {"a" 1} ((requiring-resolve 'boring.nav/value) c)))
                   (finally (.close ^java.lang.AutoCloseable arena))))))))))

;; ------------------------------------------------------------------- S7

(deftest duration-decoding-never-truncates-silently
  (testing "both numbers went through longValue(), which TRUNCATED: `{1 1.5}` is
            a valid one-and-a-half second duration and decoded as one second.
            A wrong value is the worst outcome available, and the writer's own
            `{1 seconds, -9 nanos}` subset hid it because round trips never
            produce the other forms.

            Everything boring cannot carry losslessly is refused by RFC 9581's
            own rules, with a typed error naming the key -- refusing a
            conforming form is honest, truncating it is not."
    (letfn [(dur [m] (boring/decode (boring/encode (data/tagged-value 1002 m) o) o))]
      (testing "forms boring represents"
        (is (= (java.time.Duration/ofSeconds 2) (dur {1 2})))
        (is (= (java.time.Duration/ofNanos 1500000000) (dur {1 1.5}))
            "a fractional base must keep its fraction")
        (is (= (java.time.Duration/ofNanos 2500000000) (dur {1 2 -9 500000000}))))
      (testing "forms it does not, each a typed error rather than a wrong value"
        (doseq [[label m] [["negative fraction"        {1 2 -9 -5}]
                           ["fraction out of range"    {1 2 -9 1000000000}]
                           ["fractional base with -9"  {1 1.5 -9 5}]
                           ["decimal-fraction base"    {4 [-1 15]}]
                           ["bigfloat base"            {5 [-1 15]}]
                           ["unknown critical key"     {1 2 99 "x"}]
                           ;; `{1 2, -3 5}` USED TO BE HERE. It is a conforming
                           ;; duration -- -3 is milliseconds -- and RFC 9581 3
                           ;; makes negative keys elective, so refusing it
                           ;; inverted a MUST. See
                           ;; `tag-1002-ignores-elective-keys-and-scales-every-fraction`.
                           ["sub-nanosecond fraction"  {1 2 -12 1}]
                           ["two scaled fractions"     {1 2 -3 5 -6 5}]
                           ["no base"                  {-9 5}]]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tag 1002"
                                (dur m))
              label)))
      (testing "and an ordinary Duration still round-trips"
        (is (= (java.time.Duration/ofNanos 2500000000)
               (boring/decode (boring/encode (java.time.Duration/ofNanos 2500000000) o) o)))))))

;; ------------------------------------------------------------------- S9

(deftest registrations-that-could-never-run-are-refused
  (testing "the hottest scalars are dispatched before the registry is consulted,
            so a handler registered for `String` or `Long` silently did nothing
            -- while the same call for `UUID` or `URI` worked. The same API
            working or not based only on the class, with nothing saying which,
            is worse than not supporting it.

            The lookup cannot move above these without putting a map probe in
            front of every string and every long, so the registration is
            refused instead -- at registration, where a caller can still act."
    (doseq [c [String Long Integer Double Float Boolean
               clojure.lang.Keyword clojure.lang.Symbol (Class/forName "[B")]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could never run"
                            (boring/register-tag (boring/tag-registry) 55555 c
                                                 (fn [_] "x") nil))
          (str (.getName c) " must be refused")))
    (testing "and the classes that DO override still register and take effect"
      (doseq [[c v] [[java.util.UUID (java.util.UUID/randomUUID)]
                     [java.net.URI (java.net.URI. "http://x")]
                     [java.io.File (java.io.File. "/tmp/x")]]]
        (let [reg (boring/register-tag (boring/tag-registry) 55555 c
                                       (fn [_] "OVERRIDDEN") (fn [v] v))
              o' (assoc o :registry reg)]
          (is (= "OVERRIDDEN" (boring/decode (boring/encode v o') o'))
              (str (.getName c) " must be overridden")))))
    (testing "a read-only registration needs no class and stays allowed"
      (is (some? (boring/register-tag (boring/tag-registry) 55555 nil nil (fn [v] v)))))))

;; ---------------------------------------------------- nav collection contracts

(deftest nav-honours-the-collection-interfaces-it-advertises
  (testing "each of these threw an UNTYPED exception on undamaged data, because
            every test happened to use the arity or operation the type does
            implement. Same family as the `count`/AbstractMethodError gap: the
            docstring advertises `reduce` and `get`, and the call people
            actually write was the one that failed."
    (let [v (boring/encode [1 2 3] o)
          m (boring/encode {"a" 1 "b" 2} o)
          seqbs (let [out (java.io.ByteArrayOutputStream.)]
                  (boring/write-seq! (boring/writer 4096 o) [1 2 3] out o)
                  (.toByteArray out))]
      (testing "reduce WITHOUT an init -- IReduce, not only IReduceInit"
        (is (= 3 (count (reduce (fn [a x] (conj (if (vector? a) a [a]) x))
                                (nav/source v o)))))
        (is (= 3 (count (reduce (fn [a x] (conj (if (vector? a) a [a]) x))
                                (nav/items seqbs o))))))
      (testing "contains? and find -- Associative, not only ILookup"
        (let [c (nav/source m o)]
          (is (true? (contains? c "a")))
          (is (false? (contains? c "zz")))
          (is (= 1 (nav/value (val (find c "a")))))
          (is (nil? (find c "zz")))
          (is (thrown? clojure.lang.ExceptionInfo (assoc c "c" 3))
              "and assoc is refused, because a cursor is a view over bytes")))
      (testing "nth's two arities differ, as Indexed specifies"
        (let [c (nav/source v o)]
          (is (= 2 (nav/value (nth c 1))))
          (is (thrown? IndexOutOfBoundsException (nth c 99))
              "the 2-arity throws out of range, like every other Indexed")
          (is (= :nf (nth c 99 :nf)) "the 3-arity answers with not-found")))
      (testing "and a not-found argument is honoured even through a tag, where
                clojure.core/nth throws for a realised keyword or set"
        (is (= :nf (nth (nav/source (boring/encode :foo/bar o) o) 0 :nf)))
        (is (= :nf (nth (nav/source (boring/encode #{1 2 3} o) o) 0 :nf)))))))

(deftest nav-reports-an-impossible-count-rather-than-believing-it
  (testing "`count` was the one entry point that never checked the head against
            the data. A head declaring 2^31 entries threw an untyped
            ArithmeticException; below that it returned an impossible number --
            1048576 entries from a five-byte document -- while `decode`, `seq`,
            `reduce` and `nth` on the same bytes all said :boring/bad-count.
            `seq` and `zipper` had the mirror bug: `(* 2 n)` overflowed to a
            negative long on a map head."
    (let [ba (fn [& xs] (byte-array (map unchecked-byte xs)))]
      (doseq [[label bs op]
              [["array 2^31 head" (ba 0x9b 0 0 0 0 0x80 0 0 0 0x01) count]
               ["array 1M head"   (ba 0x9a 0x00 0x10 0x00 0x00) count]
               ["map 2^62 head"   (ba 0xbb 0x40 0 0 0 0 0 0 0 0x01 0x02) #(doall (seq %))]
               ["map 2^62 zipper" (ba 0xbb 0x40 0 0 0 0 0 0 0 0x01 0x02)
                #(clojure.zip/next (nav/zipper %))]]]
        (is (thrown? clojure.lang.ExceptionInfo (op (nav/source bs o)))
            (str label " must be typed, not untyped or impossible"))))
    (testing "and a real count still answers"
      (is (= 3 (count (nav/source (boring/encode [1 2 3] o) o)))))))

(deftest sorted-collections-refuse-incomparable-content
  (testing "a corrupt `clojure/sorted-map` frame fed the default comparator keys
            it cannot order, and the raw ClassCastException escaped `decode`,
            `decode-seq` and `nav/value` alike. A SINGLE byte flip reaches it --
            change one key's head to 0xF8 and an ordinary document becomes a
            sorted-map of simple values. It was 8502 of 8872 untyped throwables
            in an exhaustive single-byte sweep; random-byte fuzzing never builds
            a tag-27 frame carrying a valid name, which is why it survived."
    (let [ba (fn [& xs] (byte-array (map unchecked-byte (flatten xs))))
          txt (fn [s] (let [a (.getBytes ^String s "UTF-8")]
                        (cons (+ 0x60 (count a)) (seq a))))]
      (doseq [[label bs]
              [["sorted-map, simple-value key"
                (ba 0xd8 0x1b 0x82 (txt "clojure/sorted-map") 0xa2 0xf8 0x30 0x00 0x01 0x02)]
               ["sorted-map, mixed key types"
                (ba 0xd8 0x1b 0x82 (txt "clojure/sorted-map") 0xa2 0x61 0x61 0x00 0x01 0x02)]
               ["sorted-set, simple value"
                (ba 0xd8 0x1b 0x82 (txt "clojure/sorted-set") 0x82 0xf8 0x30 0x01)]]]
        (is (thrown? clojure.lang.ExceptionInfo (boring/decode bs))
            (str label " must be typed"))))
    (testing "and real sorted collections still round-trip"
      (is (= (sorted-map "a" 1 "b" 2)
             (boring/decode (boring/encode (sorted-map "a" 1 "b" 2) o) o)))
      (is (= (sorted-set 1 2 3) (boring/decode (boring/encode (sorted-set 1 2 3) o) o))))))

(deftest build-index-refuses-nesting-it-cannot-walk
  (testing "`index-walk` recursed per CONTAINER without a bound -- the tag chain
            was made iterative and this was left. ~1.2 KB of `81 81 81 ...`
            through the public `build-index` was a StackOverflowError where
            `decode` on the same bytes gives a typed error.

            Bounded at 200, not the decoder's 1024 and not the 512 I first
            chose: an isolated measurement put the stack limit between 600 and
            800, but the SUITE has already spent stack, so 512 was flaky about
            one run in three. A bound calibrated against the best case is not a
            bound, and catching StackOverflowError is only a backstop -- the
            handler needs stack to build the exception and can overflow again."
    (let [deep (fn [n] (byte-array (concat (repeat n (unchecked-byte 0x81)) [(byte 1)])))]
      (doseq [n [10 100 200]]
        (is (some? (boring/build-index (deep n) {:index 4 :index-min 0}))
            (str n " levels is ordinary nesting and must still index")))
      ;; The exact cutoff is a safety MARGIN, not a contract -- it is set where
      ;; the deterministic check reliably beats the stack, which depends on how
      ;; much stack the caller has already spent. So this asserts comfortably
      ;; past it rather than pinning an off-by-one.
      (doseq [n [250 1200 20000]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (boring/build-index (deep n) {:index 4 :index-min 0}))
            (str n " nested containers must be a typed error, not an Error"))))))

(deftest a-forked-source-is-safe-to-use-in-parallel
  (testing "`boring.nav` shares one Reader across every cursor from a source, so
            parallel use returns PLAUSIBLE BUT WRONG documents -- 6 of 200
            passes, with no exception. `fork` shares the decoded index, which is
            the expensive part, and replaces only the Reader."
    (let [oi (assoc o :index 8 :index-min 4)
          vals (mapv (fn [i] {"id" i "pad" (apply str (repeat 20 (char (+ 97 (mod i 26)))))})
                     (range 200))
          out (java.io.ByteArrayOutputStream.)]
      (boring/write-seq! (boring/writer 8192 oi) vals out oi)
      (let [bs (.toByteArray out)
            its (nav/items bs o)
            want (mapv nav/value its)]
        (is (= want (mapv nav/value (nav/fork its))) "a fork sees the same data")
        (is (every? #(= want %)
                    (doall (pmap (fn [_] (mapv nav/value (nav/fork its))) (range 60))))
            "and 60 parallel forked passes all agree")
        (testing "a forked CURSOR works too, and keeps its position"
          (let [c (nav/source (boring/encode {"a" {"b" 7}} o) o)]
            (is (= 7 (nav/value (get-in (nav/fork c) ["a" "b"]))))))))))

(deftest value-is-total-so-lookups-behave-the-same-through-tags
  (testing "`get` returns a CURSOR when it descends a map or array and the
            REALISED VALUE when it descends a tag -- documented, because a tag's
            reader is arbitrary. But it meant `(value (get c k))` threw a
            ClassCastException or not depending on the WIRE REPRESENTATION of
            what you asked for: a sorted-map is a tag, a plain map is not, and
            the caller cannot tell from the outside. Found by writing that
            expression in a probe and having it fail on the sorted case only."
    (let [c (nav/source (boring/encode {"plain"  {"y" 2}
                                        "sorted" (into (sorted-map) {"x" 1 "y" 2})
                                        "set"    #{1 2}
                                        "vec"    [1 2]} o) o)]
      (is (= 2 (nav/value (get (get c "plain") "y"))))
      (is (= 2 (nav/value (get (get c "sorted") "y")))
          "the same expression must work through a tag")
      (is (= 2 (nav/value (nth (get c "vec") 1))))
      (is (= #{1 2} (nav/value (get c "set"))))
      (is (nil? (nav/value (get (get c "plain") "zz")))
          "and a miss stays nil rather than becoming an exception"))))

(deftest the-concurrency-detector-does-not-fire-on-single-threaded-use
  (testing "a false positive would break correct code, which is worse than the
            bug the detector exists for. Every nesting shape a caller can
            reasonably build: realising children inside a reduce, a full zipper
            traversal, binary search through a sorted map, count inside a
            reduce, and `compareItemsAt`, which calls skipFrom twice."
    (let [nested {"a" {"b" [1 2 {"c" #{1 2 3}}]}
                  "d" (into (sorted-map) {"x" 1 "y" 2})
                  "e" (vec (for [i (range 30)] {"n" i "s" (str i)}))}
          bs (boring/encode nested o)
          out (java.io.ByteArrayOutputStream.)]
      (boring/write-seq! (boring/writer 8192 o)
                         (vec (for [i (range 50)] {"n" i "v" [i i]})) out
                         (assoc o :index 4 :index-min 2))
      (let [seqbs (.toByteArray out)
            c (nav/source bs o)
            it (nav/items seqbs o)]
        (are [x] (some? x)
          (nav/value (get-in c ["a" "b"]))
          (doall (map nav/value (seq (get-in c ["a" "b"]))))
          (reduce (fn [a x] (conj a (nav/value x))) [] (get c "e"))
          (reduce (fn [a x] (conj a (nav/value (get x "n")))) [] it)
          (reduce (fn [a x] (conj a (nav/value (nth (get x "v") 1)))) [] it)
          (loop [z (nav/zipper c) n 0]
            (if (or (clojure.zip/end? z) (> n 400)) n (recur (clojure.zip/next z) (inc n))))
          (nav/value (get (get c "d") "y"))
          (reduce (fn [a x] (+ (long a) (long (count x)))) 0 it)
          (doall (map (fn [i] (nav/value (nth it i))) (range 50)))
          (doall (map nav/byte-span (seq (get c "e")))))))))

;; ---------------------------------------------- decode budget and amplification

(deftest max-items-caps-what-a-decode-may-produce
  (testing "`:max-depth` bounds how DEEP a document is and `checkCount` bounds
            each container against the bytes that remain, but nothing bounded
            the TOTAL -- so a document inside both limits could still amplify
            past anything doc/SECURITY.md claimed. Measured, that page's
            'roughly 5x' was wrong by a factor of five: many tiny containers
            reach 23x, while a megabyte byte string is 1.0x.

            Items rather than bytes, because heap tracks OBJECT COUNT: a
            one-byte container head that becomes a vector is the worst per-byte
            case there is, and bulk payloads do not amplify at all."
    (let [bs (boring/encode (vec (repeat 5000 [1 2])) o)]
      (is (= 5000 (count (boring/decode bs o)))
          "unlimited by default")
      (is (= 5000 (count (boring/decode bs (assoc o :max-items 0))))
          "0 means unlimited, explicitly")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"more than 100 items"
                            (boring/decode bs (assoc o :max-items 100))))
      (is (= 5000 (count (boring/decode bs (assoc o :max-items 20000))))
          "a generous budget still decodes"))
    (testing "and the budget resets per decode rather than accumulating across
              calls on a reused reader"
      (let [bs (boring/encode (vec (repeat 500 [1 2])) o)
            r (org.replikativ.boring.Reader. bs)]
        (dotimes [_ 5]
          (is (= 500 (count (boring/decode-with r bs (assoc o :max-items 5000))))))))
    (testing "bulk payloads are what the budget must NOT punish -- one item,
              whatever its size"
      (is (= 100000 (alength ^bytes (boring/decode (boring/encode (byte-array 100000) o)
                                                   (assoc o :max-items 4))))))))

;; ------------------------------------------------- F6 F7 F8 F9: tag semantics

(deftest a-canonical-set-emits-the-bytes-it-sorted-by
  (testing "both writers pre-encoded each element to get its sort key and then
            called `writeValue` AGAIN to emit it. A registered handler may be
            stateful or read the clock, so the bytes that decided the order need
            not be the bytes emitted -- a canonical set could go out DESCENDING
            under the profile whose entire point is determinism. Canonical maps
            already copied their staged keys."
    (let [calls (atom 0)
          reg (boring/register-tag (boring/tag-registry) 40001 java.io.File
                                   (fn [_] (swap! calls inc) (if (odd? @calls) 1 2)) nil)
          bs (boring/encode #{(java.io.File. "/a")} {:profile :canonical :registry reg})]
      (is (= 1 @calls) "one handler call per value, not two")
      (is (= "d9010281d99c4101" (apply str (map #(format "%02x" %) bs)))
          "and the emitted element is the one that was sorted"))
    (testing "ordinary canonical sets are still sorted"
      (is (= "d9010283010203"
             (apply str (map #(format "%02x" %)
                             (boring/encode #{3 1 2} {:profile :canonical}))))))))

(deftest a-fractional-tag-number-is-refused-not-truncated
  (testing "`longValue()` turned tag 1.5 into tag 1, so `(tagged-value 1.5 0)`
            and `(tagged-value 1 0)` emitted the same bytes. The registry
            validates its own numbers, but the public `tagged-value` constructor
            bypasses it."
    (doseq [bad [1.5 -1 (/ 3 2) 2.0M]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tag"
                            (boring/encode (data/tagged-value bad 0) o))
          (str (pr-str bad) " must be refused")))
    (testing "and the whole unsigned 64-bit range still encodes"
      (doseq [good [0 1 40000 (biginteger "18446744073709551615")]]
        (is (some? (boring/encode (data/tagged-value good 0) o))
            (str (pr-str good)))))))

(deftest a-leap-second-is-preserved-rather-than-normalised
  (testing "RFC 3339 permits `time-second = 60`. `Instant.parse` accepts the
            spelling and silently rewrites it to :59, so two DISTINCT valid
            tag-0 strings decoded to the same instant -- data lost with no
            error. Neither host type can hold a leap second, so it comes back as
            an inert TaggedValue, the same treatment f128 gets: boring does not
            discard what it cannot represent."
    (let [leap "2016-12-31T23:59:60Z"
          ordinary "2016-12-31T23:59:59Z"]
      (is (= (data/tagged-value 0 leap)
             (boring/decode (boring/encode (data/tagged-value 0 leap) o) o))
          "the leap second survives untouched")
      (is (not= (boring/decode (boring/encode (data/tagged-value 0 leap) o) o)
                (boring/decode (boring/encode (data/tagged-value 0 ordinary) o) o))
          "and the two no longer collapse to one value")
      (is (inst? (boring/decode (boring/encode (data/tagged-value 0 ordinary) o) o))
          "while an ordinary timestamp is still an instant")
      (is (inst? (boring/decode (boring/encode (java.util.Date. 0) o) o))
          "and ordinary Date round-trips are unaffected"))))

(deftest tag-27-markers-validate-their-payload
  (testing "`seqableContent` admits nil, because nil IS seqable in Clojure --
            right for callers that seq it, wrong for the ones that cast to List
            and call size()/toArray(). A well-formed frame with a null payload
            was a raw NullPointerException, against the typed-only guarantee."
    (doseq [nm ["java/boolean-array" "java/string-array" "java/object-array"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must wrap a list"
                            (boring/decode (boring/encode (data/unknown-record nm nil) o) o))
          (str nm " with nil"))
      (is (thrown? clojure.lang.ExceptionInfo
                   (boring/decode (boring/encode (data/unknown-record nm 7) o) o))
          (str nm " with a scalar")))
    (testing "and the real arrays still round-trip"
      (is (= [true false] (vec (boring/decode (boring/encode (boolean-array [true false]) o) o))))
      (is (= ["a" "b"] (vec (boring/decode (boring/encode (into-array String ["a" "b"]) o) o)))))))

;; ---------------------------------------------------- M2 M5 M6: range checks

(deftest options-and-ranges-fail-typed-rather-than-raw
  (testing ":chunk-size 0 silently returned an EMPTY sequence for non-empty
            input -- data loss with no error, the worst way an option can be
            wrong -- and a negative one was a raw NegativeArraySizeException.
            `bytesBetween` narrowed `(int)(end - start)` with no range check,
            and `bytesEqualAt` used overflow-prone addition. All are public
            entry points, so all must fail the way the rest of the reader does."
    (let [bs (boring/encode [1 2 3] o)]
      (testing ":chunk-size"
        (doseq [bad [0 -1 1.5 "big"]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"chunk-size"
                                (doall (boring/decode-seq-from
                                        (java.io.ByteArrayInputStream. bs)
                                        (assoc o :chunk-size bad))))
              (str (pr-str bad))))
        (is (= 1 (count (vec (boring/decode-seq-from
                              (java.io.ByteArrayInputStream. bs)
                              (assoc o :chunk-size 4096)))))
            "and a sane size still streams")
        (is (= 1 (count (vec (boring/decode-seq-from
                              (java.io.ByteArrayInputStream. bs) o))))
            "as does the default"))
      (testing "bytesBetween ranges"
        (let [r (org.replikativ.boring.Reader. ^bytes bs)]
          (is (= 3 (alength ^bytes (.bytesBetween r 0 3))) "a valid range works")
          (doseq [[st e] [[3 0] [-1 3] [0 999999] [0 -1]]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"byte range"
                                  (.bytesBetween r st e))
                (str "[" st " " e ")"))))))))

;; ------------------------------------------------------------ RFC 3339 years

(deftest a-year-outside-0000-9999-is-refused-rather-than-emitted-malformed
  (testing "RFC 3339's date-fullyear is 4DIGIT, so there is no conforming tag-0
            or tag-1004 text for a year outside 0000-9999. java.time renders
            those in ISO-8601's EXPANDED form -- `+12013-03-21T20:04:00Z` --
            which boring emitted and then read back happily, so every
            round-trip test passed while a foreign reader received a string no
            conforming parser accepts. Refusing is the honest outcome: silently
            rerouting to tag 1 would trade the interop bug for a precision one,
            since tag 1 as a float cannot carry nanoseconds at these epochs."
    (are [v] (thrown-with-msg? clojure.lang.ExceptionInfo #"outside 0000-9999"
                               (boring/encode v o))
      (java.time.Instant/parse "+12013-03-21T20:04:00Z")
      (java.time.Instant/parse "-0001-03-21T20:04:00Z")
      (java.time.LocalDate/parse "+12013-03-21")
      (java.time.LocalDate/parse "-0001-03-21")))
  (testing "and the ordinary range is untouched"
    (are [v] (= v (boring/decode (boring/encode v o) (assoc o :instant-type :instant)))
      (java.time.Instant/parse "2013-03-21T20:04:00Z")
      (java.time.Instant/parse "0001-01-01T00:00:00Z")
      (java.time.Instant/parse "9999-12-31T23:59:59Z"))))

;; ------------------------------------------------------- RFC 9581 tag 1002

(defn- unhex ^bytes [s]
  (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16))
                   (partition 2 (clojure.string/replace s #"\s" "")))))

(deftest tag-1002-ignores-elective-keys-and-scales-every-fraction
  (testing "RFC 9581 3: \"For negative integer keys and text string values of the
            key, implementations MUST ignore key/value pairs they do not
            understand; these keys are 'elective'.\" boring threw on every
            negative key but -9, which inverted a MUST and refused conforming
            durations -- including `{1: 5, -1: 0}`, whose elective key names the
            DEFAULT timescale."
    (are [hex expected] (= expected (str (boring/decode (unhex hex) o)))
      ;; -3 milliseconds (Java time) and -6 microseconds (old UNIX time) are
      ;; exactly representable and were both refused outright.
      "d903eaa2010522 1901f4"     "PT5.5S"
      "d903eaa2010525 1a0007a120" "PT5.5S"
      "d903eaa2010528 01"         "PT5.000000001S"
      ;; -12 picoseconds, landing on a whole nanosecond
      "d903eaa201052b 1903e8"     "PT5.000000001S"
      ;; elective keys boring does not implement are IGNORED, not fatal
      "d903eaa2010520 00"         "PT5S"          ; -1  timescale
      "d903eaa201052c 01"         "PT5S"          ; -13 timescale
      "d903eaa1 0105"             "PT5S"))
  (testing "what still fails, and why"
    (are [hex] (thrown-with-msg? clojure.lang.ExceptionInfo #"tag 1002"
                                 (boring/decode (unhex hex) o))
      ;; finer than a Duration's nanosecond -- refused rather than truncated
      "d903eaa201052b 01"
      ;; RFC 9581 3.3: "MUST NOT contain more than one of these keys"
      "d903eaa3010522 0525 05"
      ;; an unsigned key is CRITICAL: 3 says a reader MUST signal an error
      "d903eaa201050d 00")))

;; ------------------------------------------------------- allocation-free write

(deftest write-to-buffer-round-trips-and-reports-overflow
  (testing "the NIO sibling of write-to!. It exists because the hand-rolled
            two-liner goes reflective unless every argument is hinted, which
            costs 22 KB per call against ~0 and fails silently -- nothing
            throws, throughput just collapses where only an allocation profile
            would show it."
    (let [w (boring/writer 65536 o)
          vs [{:e 1 :a :user/name :v "ada"} [1 2 3] "plain" 42]]
      (testing "heap and direct buffers both round-trip, in sequence"
        (doseq [bb [(java.nio.ByteBuffer/allocate 4096)
                    (java.nio.ByteBuffer/allocateDirect 4096)]]
          (let [^java.nio.ByteBuffer bb bb
                counts (mapv #(boring/write-to-buffer! w % bb) vs)]
            (is (= (.position bb) (reduce + counts))
                "the buffer advanced by exactly the bytes written")
            (.flip bb)
            (let [out (byte-array (.remaining bb))]
              (.get bb out)
              (is (= vs (vec (boring/decode-seq out o)))
                  "and the bytes are a readable CBOR sequence")))))
      (testing "a value that does not fit overflows rather than truncating"
        (let [^java.nio.ByteBuffer tiny (java.nio.ByteBuffer/allocate 4)]
          (is (thrown? java.nio.BufferOverflowException
                       (boring/write-to-buffer! w {:a "much too long for four bytes"} tiny))))))))

;; ------------------------------------------------ :check-duplicate-keys (F5)

(deftest check-duplicate-keys-is-wired-and-yields-a-valid-map
  (testing "doc/SECURITY.md documented `:check-duplicate-keys false` as the way
            to turn duplicate rejection off. The Java field existed and
            defaulted to true, but NO entry point ever set it -- so the option
            was silently ignored and a duplicate map still threw with it set. A
            documented safety control that does nothing is worse than one that
            does not exist."
    (let [dup (unhex "a201010102")]                    ; {1:1, 1:2}
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"duplicate map key"
                            (boring/decode dup o))
          "rejected by default, on `decode`")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"duplicate map key"
                            (vec (boring/decode-seq dup o)))
          "and on `decode-seq`, which configures its reader separately")
      (testing "with the option off the duplicate is kept LAST-WINS, and the
                result is a VALID map. `new PersistentArrayMap(kvs)` adopts its
                array without inspecting it, so this used to hand back a map
                with two equal keys: count 2, `get` returning the first. RFC
                8949 5.6 offers reject, keep-one, or hand-to-the-application --
                a corrupt host map is none of them."
        (doseq [decode-fn [#(boring/decode dup (assoc o :check-duplicate-keys false))
                           #(first (boring/decode-seq dup (assoc o :check-duplicate-keys false)))]]
          (let [m (decode-fn)]
            (is (= {1 2} m))
            (is (= 1 (count m)))
            (is (= m (into {} m)) "round-trips through into, so it is well formed"))))
      (testing "and above the array-map threshold the two sizes agree"
        ;; 9 pairs, one key repeated -> PersistentHashMap path
        (let [big (unhex (str "a9" (apply str (for [i (range 8)] (format "%02x%02x" i i)))
                              "0009"))]
          (is (thrown? clojure.lang.ExceptionInfo (boring/decode big o)))
          (let [m (boring/decode big (assoc o :check-duplicate-keys false))]
            (is (= 8 (count m)) "nine pairs, key 0 repeated -> eight distinct")
            (is (= 9 (get m 0)) "and the LAST value for the repeated key wins")
            (is (= m (into {} m)))))))))

;; ---------------------------------------------- content-aware duplicates (F5)

(deftest duplicate-detection-sees-through-host-array-identity
  (testing "doc/SECURITY.md says duplicate detection compares encoded key bytes.
            It compared HOST equality, which is identity for arrays -- so two
            content-equal `short[]` keys were two distinct keys and a document
            with duplicate CBOR keys decoded to a map with more entries than the
            wire described. Only byte[] had a special case, and that one was an
            O(n^2) pair scan, unbounded above the array-map threshold and so
            attacker-controlled work on read. One content-aware pass now."
    (let [dup (fn [mk] (-> {} (assoc (mk) :a) (assoc (mk) :b)))]
      (doseq [[label mk] [["short[]"  #(short-array [1 2])]
                          ["int[]"    #(int-array [1 2])]
                          ["long[]"   #(long-array [1 2])]
                          ["double[]" #(double-array [1.0 2.0])]
                          ["byte[]"   #(byte-array [1 2])]]]
        (let [m (dup mk)]
          (is (= 2 (count m)) (str label ": the host map really does hold two keys"))
          ;; The TYPE, not the message: byte[] keys are additionally caught on
          ;; the ENCODE side by the writer's own check, which words it
          ;; differently. Either end refusing is the point.
          (is (= :boring/duplicate-map-key
                 (try (do (boring/decode (boring/encode m o) o) nil)
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
              (str label " keys encode identically and must be refused"))))))
  (testing "tag 258 elements get the same treatment"
    ;; d9 0102 82 41 01 41 01 -- 258([h'01', h'01'])
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"duplicate"
                          (boring/decode (unhex "d901028241014101") o))))
  (testing "and distinct content is still distinct, at both map sizes"
    (doseq [n [2 20]]
      (let [m (into {} (for [i (range n)] [(int-array [i]) i]))]
        (is (= n (count (boring/decode (boring/encode m o) o)))
            (str n " distinct array keys must all survive")))))
  (testing "non-array keys keep RFC 8949 5.6.1 semantics"
    ;; a2 01 01 fb3ff0000000000000 02 -- keys 1 and 1.0 are DISTINCT
    (is (= 2 (count (boring/decode (unhex "a20101fb3ff000000000000002") o))))))
