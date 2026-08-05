(ns boring.mmap-test
  "Navigation over an mmap'ed file must agree with navigation over a byte[],
  which must agree with decoding the whole document.

  The generative specs in boring.generative-test all run over a byte[] source,
  so they exercise the Reader's ARRAY accessor and never its segment one. Those
  are different code paths -- `arr != null ? arr[p] : src.at(p)` -- and the
  segment side additionally goes through `SegmentSource`, native-order loads
  and an explicit byte swap. A bug in the swap, or an off-by-one in a bounds
  check, would be invisible to every other test in this suite.

  JDK GUARD. boring.mmap is the only namespace that touches java.lang.foreign,
  which is final in JDK 22. Everything else -- including boring.nav -- runs on
  JDK 9, and the full suite passes on JDK 21. So this namespace must not
  require boring.mmap at load time: it resolves it lazily and reports a skip
  when FFM is absent, rather than failing a runtime we deliberately support."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring]
            [boring.nav :as nav]
            [boring.conformance :as c])
  (:import (java.io File FileOutputStream)))

(def ffm?
  "Whether this JVM can actually load the JDK-22 segment implementation.

  NOT `Class/forName \"java.lang.foreign.MemorySegment\"`. That check looks
  right and is wrong: JDK 21 HAS that class, as a preview API, so the probe
  succeeds and the tests then die on our own class with

    SegmentSource has been compiled by a more recent version of the Java
    Runtime (class file version 66.0), this version ... up to 65.0

  Probing the class we actually need answers the question we actually have."
  (try (Class/forName "org.replikativ.boring.ffm.SegmentSource") true
       (catch Throwable _ false)))

(def opts {:stringref false})

(defn- spit-bytes ^File [^bytes bs]
  (let [f (doto (File/createTempFile "boring-mmap-test" ".cbor") .deleteOnExit)]
    (with-open [o (FileOutputStream. f)] (.write o bs))
    f))

(defn- mmap-source* [f]
  ((requiring-resolve 'boring.mmap/mmap-source) f opts))

;; Maps whose values are containers often enough to make two-level paths real.
(def gen-doc
  (gen/map (gen/one-of [gen/string-ascii gen/large-integer])
           (gen/one-of [(gen/map gen/string-ascii gen/large-integer {:max-elements 4})
                        (gen/vector gen/large-integer 0 5)
                        gen/string-ascii
                        gen/large-integer
                        (gen/fmap byte-array (gen/vector (gen/choose -128 127) 0 20))])
           {:max-elements 6}))

(defn- paths-of
  "Paths the WIRE says are navigable -- not what `map?` says, which is true for
  records and would ask the navigator to descend into a tag."
  [c v]
  (concat (for [k (keys v)] [k])
          (for [k (keys v)
                :let [inner (get c k)]
                :when (and (some? inner) (= :map (nav/value-type inner)))
                ik (keys (get v k))]
            [k ik])))

(deftest mmap-navigation-agrees-with-heap-and-with-decode
  (if-not ffm?
    (is true "skipped: this JVM has no java.lang.foreign (JDK < 22)")
    (let [result
          (tc/quick-check
           150
           (prop/for-all [v gen-doc]
                         (let [^bytes bs (boring/encode v opts)
                               f (spit-bytes bs)
                               heap (nav/source bs opts)
                               decoded (boring/decode bs opts)
                               [mm arena] (mmap-source* f)]
                           (try
                             (every?
                              (fn [path]
                                (let [via-mm (get-in mm path)
                                      via-heap (get-in heap path)]
                                  (and (some? via-mm)
                                       (some? via-heap)
                           ;; mmap == heap == decode, all three
                                       (c/equiv? (nav/value via-mm) (nav/value via-heap))
                                       (c/equiv? (nav/value via-mm) (get-in decoded path)))))
                              (paths-of heap v))
                             (finally (.close ^java.lang.AutoCloseable arena))))))]
      (is (:pass? result) (pr-str result)))))

(deftest mmap-raw-bytes-and-count-agree
  (if-not ffm?
    (is true "skipped: this JVM has no java.lang.foreign (JDK < 22)")
    (let [result
          (tc/quick-check
           150
           (prop/for-all [v gen-doc]
                         (let [^bytes bs (boring/encode v opts)
                               f (spit-bytes bs)
                               [mm arena] (mmap-source* f)]
                           (try
                             (and (= (count mm) (count v))
                      ;; a subtree lifted out of the MAPPING must decode alone
                                  (every? (fn [k]
                                            (let [cur (get mm k)]
                                              (c/equiv? (boring/decode (nav/raw-bytes cur) opts)
                                                        (get v k))))
                                          (keys v)))
                             (finally (.close ^java.lang.AutoCloseable arena))))))]
      (is (:pass? result) (pr-str result)))))

(deftest closing-the-arena-invalidates-cursors
  (if-not ffm?
    (is true "skipped: this JVM has no java.lang.foreign (JDK < 22)")
    (testing "use-after-close throws rather than reading freed memory -- the
              property MappedByteBuffer never had, and the reason the mapping
              is owned by an Arena at all"
      (let [^bytes bs (boring/encode {"a" {"b" 1}} opts)
            f (spit-bytes bs)
            [c arena] (mmap-source* f)]
        (is (= 1 (nav/value (get-in c ["a" "b"]))))
        (.close ^java.lang.AutoCloseable arena)
        (is (thrown? Throwable (nav/value (get-in c ["a" "b"])))
            "reading through a closed arena must throw")))))

(deftest mmap-and-heap-see-identical-bytes
  (if-not ffm?
    (is true "skipped: this JVM has no java.lang.foreign (JDK < 22)")
    (testing "the segment accessor's byte swap is exercised by every multi-byte
              width, which small fixtures miss: 1-, 2-, 4- and 8-byte arguments
              all take different branches of the head parser"
      (let [widths {"u8"  0xFF                    ; needs a 1-byte argument
                    "u16" 0xFFFF                  ; 2
                    "u32" 0xFFFFFFF               ; 4
                    "u64" 1234567890123456789     ; 8
                    "neg" -9007199254740993
                    "f64" 3.141592653589793
                    "txt" (apply str (repeat 300 "x"))}
            ^bytes bs (boring/encode widths opts)
            f (spit-bytes bs)
            [mm arena] (mmap-source* f)]
        (try
          (doseq [k (keys widths)]
            (is (c/equiv? (nav/value (get mm k)) (get widths k))
                (str "mmap disagreed on " k)))
          (finally (.close ^java.lang.AutoCloseable arena)))))))

(deftest an-indexed-sequence-is-usable-over-a-mapping
  (if-not ffm?
    (is true "skipped: this JVM has no java.lang.foreign (JDK < 22)")
    (testing "the index's slot arrays are DELTAS, decoded off the wire and
              expanded once when the index loads. Over a mapping that decode
              runs through the segment accessor rather than the byte[] fast
              path, so this is the one place the two could disagree -- and a
              disagreement would not throw, it would silently seek to the wrong
              offset. Every stride is checked because each picks a different
              element width, and the widths swap bytes differently."
      (doseq [stride [1 8 64]]
        (let [vs (vec (for [i (range 500)]
                        {"n" i "msg" (str "event " i) "ok" (even? i)}))
              o (java.io.ByteArrayOutputStream.)
              _ (boring/write-seq! (boring/writer 65536 opts) vs o
                                   (assoc opts :index stride))
              ^bytes bs (.toByteArray o)
              f (spit-bytes bs)
              ;; `mmap-source` is the single-value shape and hands back a
              ;; cursor; a sequence needs `mmap-items`.
              [mapped arena] ((requiring-resolve 'boring.mmap/mmap-items) f opts)]
          (try
            (let [heap (nav/items bs opts)]
              (is (= 500 (count (vec (seq mapped))))
                  (str "stride " stride ": the index item must not be yielded as data"))
              (doseq [i [0 1 7 8 9 250 498 499]]
                (is (= (vs i)
                       (nav/value (nth heap i))
                       (nav/value (nth mapped i)))
                    (str "stride " stride ", item " i))))
            (finally (.close ^java.lang.AutoCloseable arena))))))))

;; ---------------------------------------------------------------- write side

(deftest streaming-into-a-memory-segment
  (testing "the write-side mirror of segment-source: `boring.core/write-to!`
            takes an OutputStream, and `segment-sink` is one, so the streaming
            encoder reaches off-heap memory with no changes to the writer -- and
            without dragging FFM into the JDK 9 source set, which is the whole
            reason the sink type is an OutputStream.

            hako's `encode-into!` returns a MemorySegment slice, which is
            zero-copy OUT of a reused arena; this is the other property, bounded
            memory ON THE WAY IN. boring has both."
    (if-not ffm?
      (is true "JDK < 22: skipped")
      (let [seg-sink (requiring-resolve 'boring.mmap/segment-sink)
            seg-source (requiring-resolve 'boring.mmap/segment-source)
            arena-cls (Class/forName "java.lang.foreign.Arena")
            shared (.invoke (.getMethod arena-cls "ofShared" (into-array Class []))
                            nil (object-array 0))]
        (with-open [^java.lang.AutoCloseable a shared]
          (let [alloc (.getMethod (Class/forName "java.lang.foreign.SegmentAllocator")
                                  "allocate" (into-array Class [Long/TYPE]))
                seg (.invoke alloc shared (object-array [(long (* 4 1024 1024))]))
                snk (seg-sink seg)
                w (boring/writer 4096 opts)           ; tiny buffer -> many flushes
                value (mapv (fn [i] {:id i :name (str "customer-" i) :tags #{:a :b}})
                            (range 5000))
                n (boring/write-to! w value snk opts)]
            (is (pos? n))
            (is (= n (.position snk)) "the sink saw every byte")
            (is (<= (alength ^bytes (boring/buffer w)) 4096)
                "and the heap buffer stayed bounded while doing it")
            (testing "the bytes are readable in place, never staged through the heap"
              (let [c (nav/source (seg-source (.written snk)) opts)]
                (is (= value (nav/value c)))))
            (testing "overflow is loud, not a silent truncation -- the index frame
                      is at the END, so a quietly short write would look like a
                      corrupt index rather than a full buffer"
              (let [tiny (.invoke alloc shared (object-array [(long 32)]))
                    snk2 (seg-sink tiny)
                    w2 (boring/writer 16 opts)]
                (is (thrown? IllegalStateException
                             (boring/write-to! w2 (vec (range 1000)) snk2 opts)))))))))))

(deftest mapping-part-of-a-file
  (when ffm?
  (testing "a CBOR document is not always the whole file. konserve writes a
            blob as a 20-byte header, then metadata, then the value -- so the
            value begins partway in, and mapping from zero addresses the header
            as though it were CBOR. `:offset`/`:length` narrow the mapping.

            The mechanism (MemorySegment.asSlice) already worked; what was
            missing was a way to ask for it without reaching past this
            namespace into asSlice and segment-source by hand."
    (let [value   (into {} (for [i (range 200)]
                             [(str "customer-" i) {"name" (str "name-" i)}]))
          meta-bs (boring/encode {:key "k"} {:stringref false})
          val-bs  (boring/encode-indexed value)
          off     (+ 20 (alength ^bytes meta-bs))
          f       (java.io.File/createTempFile "boring-slice" ".bin")]
      (try
        (with-open [o (java.io.FileOutputStream. f)]
          (.write o (byte-array (repeat 20 (byte 0))))
          (.write o ^bytes meta-bs)
          (.write o ^bytes val-bs))
        (testing ":offset alone runs to the end of the file"
          (let [[c a] ((requiring-resolve 'boring.mmap/mmap-source) (.getPath f) {:offset off})]
            (with-open [_ a]
              (is (= "name-137" (nav/value (get-in c ["customer-137" "name"])))))))
        (testing ":offset with an explicit :length"
          (let [[c a] ((requiring-resolve 'boring.mmap/mmap-source) (.getPath f)
                 {:offset off :length (alength ^bytes val-bs)})]
            (with-open [_ a]
              (is (= "name-137" (nav/value (get-in c ["customer-137" "name"])))))))
        (testing "no offset still maps the whole file, so nothing changed for
                  callers that do not ask"
          (let [[_ a] ((requiring-resolve 'boring.mmap/mmap-source) (.getPath f))]
            (.close ^java.lang.AutoCloseable a)
            (is true "constructing over the header must not throw here")))
        (testing "an offset past the end is a TYPED error naming the size,
                  not an IndexOutOfBoundsException from asSlice"
          (is (= :boring/bad-argument
                 (:type (ex-data (try ((requiring-resolve 'boring.mmap/mmap-source) (.getPath f) {:offset 99999999})
                                      (catch Exception e e)))))))
        (testing "and so is a length that runs past the end"
          (is (= :boring/bad-argument
                 (:type (ex-data (try ((requiring-resolve 'boring.mmap/mmap-source) (.getPath f)
                        {:offset off :length 99999999})
                                      (catch Exception e e)))))))
        (finally (.delete f)))))))

(deftest mapping-part-of-a-sequence-file
  (when ffm?
  (testing "the same for `mmap-items`, which is the log shape -- and the index
            still drives `nth` through the slice"
    (let [w  (boring/writer 8192)
          f  (java.io.File/createTempFile "boring-slice-seq" ".bin")]
      (try
        (with-open [o (java.io.FileOutputStream. f)]
          (.write o (byte-array (repeat 20 (byte 0))))
          (boring/write-seq! w (vec (for [i (range 5000)] {:id i})) o))
        (let [[it a] ((requiring-resolve 'boring.mmap/mmap-items) (.getPath f) {:offset 20})]
          (with-open [_ a]
            (is (= {:id 4999} (nav/value (nth it 4999))))
            (is (= {:id 0} (nav/value (nth it 0))))))
        (finally (.delete f)))))))
