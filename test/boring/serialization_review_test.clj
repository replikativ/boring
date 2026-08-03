(ns boring.serialization-review-test
  "Findings from doc/SERIALIZATION-CORRECTNESS-REVIEW.md that are JVM-specific.
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
             ["payload a vector"      [[2 2] [1 2 3 4]]]
             ["payload nil"           [[0 0] nil]]
             ["dimensions overflow"   [[100000000 100000000] (byte-array 4)]]
             ["three dimensions"      [[1 2 3] (byte-array 6)]]]]
      (let [bs (boring/encode (data/tagged-value 40 content) o)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tag 40|dimension|2-dimensional"
                              (boring/decode bs o))
            (str label " must be a typed :boring/bad-tag-content")))))
  (testing "and a valid matrix still decodes"
    (let [m (into-array (Class/forName "[D")
                        [(double-array [1.0 2.0]) (double-array [3.0 4.0])])]
      (is (= "[[D" (.getName (class (boring/decode (boring/encode m o) o))))))))

;; ------------------------------------------------------------------- S6

(deftest matrix-writing-handles-empty-and-null-rows
  (testing "three cases contradicted the method's own comments: a zero-row
            matrix took the non-rectangular fallback and came back a vector,
            losing the source type on a value with no content to disagree
            about; and `rowLen(rows[0])` ran BEFORE the loop's null check, so a
            null FIRST row threw a raw NullPointerException and the documented
            null-row fallback was unreachable for the row most likely to be
            null."
    (testing "zero rows keep their type -- 0x0 is rectangular"
      (is (= "[[D" (.getName (class (boring/decode
                                     (boring/encode (make-array Double/TYPE 0 0) o) o)))))
      (is (= "[[J" (.getName (class (boring/decode
                                     (boring/encode (make-array Long/TYPE 0 0) o) o))))))
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
                           ["other scaled fraction"    {1 2 -3 5}]
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
            `decode` on the same bytes gives a typed error. Bounded at 512
            because this is a Clojure recursion whose frames give out between
            600 and 800, so the decoder's 1024 would not be a bound at all."
    (let [deep (fn [n] (byte-array (concat (repeat n (unchecked-byte 0x81)) [(byte 1)])))]
      (is (some? (boring/build-index (deep 100) {:index 4 :index-min 0}))
          "ordinary nesting still indexes")
      (doseq [n [1200 20000]]
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
