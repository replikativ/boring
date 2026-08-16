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
                               heap (nav/root bs opts)
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
              (let [c (nav/root (seg-source (.written snk)) opts)]
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
            (let [open! (requiring-resolve 'boring.mmap/mmap-source)
                  [c a] (open! (.getPath f)
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
                   (:type (ex-data (try (let [open! (requiring-resolve
                                                     'boring.mmap/mmap-source)]
                                          (open! (.getPath f)
                                                 {:offset off :length 99999999}))
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

(deftest decoding-a-whole-value-from-a-bytesource
  (when ffm?
    (testing "the Java Reader has taken a ByteSource since boring.nav needed
              one, but the Clojure API was byte[]-only -- so a caller holding
              off-heap bytes had to COPY into a byte[] to decode them at all.
              Navigation could read a source and a whole-value decode could
              not, which is backwards: a store's normal case is wanting the
              whole value, not part of it.

              The motivating caller is konserve-lmdb, which hands out a
              MemorySegment over LMDB's own mapping per read."
      (let [v {:meta {:k "x"}
               :value (vec (for [i (range 256)] [i :a (str "p" i) true]))}
            ^bytes bs (boring/encode v {:stringref false})]
        (with-open [arena (java.lang.foreign.Arena/ofShared)]
          (let [seg (.allocate arena (long (alength bs)))
                _   (java.lang.foreign.MemorySegment/copy
                     bs 0 seg java.lang.foreign.ValueLayout/JAVA_BYTE 0 (alength bs))
                src ((requiring-resolve 'boring.mmap/segment-source) seg)]
            (testing "decode takes a ByteSource and agrees with the byte[] path"
              (is (= v (boring/decode src)))
              (is (= v (boring/decode bs))))
            (testing "and so does a reused Reader, which is the shape a store
                      wants: one Reader, a fresh segment per value"
              (let [r (boring/reader bs)]
                (is (= v (boring/decode-with r src)))
                (is (= v (boring/decode-with r src {})))
                (testing "the byte[] arm still works on the same reader"
                  (is (= v (boring/decode-with r bs))))))
            (testing "a Reader can be built from a source directly"
              (is (= v (boring/decode-with (boring/reader src) src))))))))))

(deftest a-bad-decode-input-is-typed
  (testing "nil and a wrong type are `:boring/bad-argument`, not a raw NPE or
            ClassCastException -- doc/SECURITY.md's third guarantee says no raw
            host exception escapes a read path, and widening these to accept
            two input kinds is exactly where a stray cast would appear"
    (doseq [[label f] [["decode nil" #(boring/decode nil)]
                       ["decode number" #(boring/decode 42)]
                       ["reader nil" #(boring/reader nil)]
                       ["reader number" #(boring/reader 42)]
                       ["decode-with nil" #(boring/decode-with (boring/reader (boring/encode 1)) nil)]
                       ["decode-with number" #(boring/decode-with (boring/reader (boring/encode 1)) 42)]]]
      (is (= :boring/bad-argument
             (:type (ex-data (try (f) (catch Exception e e)))))
          label))))

;; `with-mmap` IS A MACRO IN A NAMESPACE THIS FILE MUST NOT REQUIRE at load
;; time -- see the JDK guard in the ns docstring -- so it cannot be called
;; normally and cannot be reached through `requiring-resolve` either. The form
;; is built and `eval`ed instead, and everything inside it is a STRING or a MAP
;; literal: an `eval`ed form carrying a File object fails to compile, which is
;; how the first version of this test failed.
(def escaped-cursor
  "Where `with-mmap-closes-even-when-the-body-throws` stashes the cursor its
  body leaks. A VAR the eval'ed form names by symbol, not an atom spliced into
  it: an `eval`ed form carrying an object -- a File, an Atom -- fails to
  compile, which is how two versions of that test failed."
  (atom nil))

(defn- eval-with-mmap [path body-fn]
  (eval `(do (require 'boring.mmap)
             (~'boring.mmap/with-mmap [~'c ~path ~opts] ~(body-fn 'c)))))

(deftest with-mmap-maps-binds-and-closes
  (if-not ffm?
    (is true "skipped: this JVM has no java.lang.foreign (JDK < 22)")
    (testing "`with-mmap` had NO TEST. Its only appearance anywhere was a code
              sample in doc/STORAGE.md, which is the kind of coverage that
              looks like documentation and is actually an untested claim -- the
              macro could stop closing the arena, or stop binding, and nothing
              would notice. Found by an API surface review, not by a failure."
      (let [^bytes bs (boring/encode {"customer-137" {"name" "Ada"}
                                      "customer-9"   {"name" "Grace"}}
                                     opts)
            path (.getPath (spit-bytes bs))]
        (testing "the sample from doc/STORAGE.md, run rather than quoted"
          (is (= "Ada"
                 (eval-with-mmap
                  path (fn [c] `(nav/value (get-in ~c ["customer-137" "name"])))))))
        (testing "the BODY's value is the macro's value -- `with-open` returns
                  its body, and a macro that returned the arena or nil would
                  still pass a test that only checked for no exception"
          (is (= 2 (eval-with-mmap path (fn [c] `(count ~c))))))))))

(deftest with-mmap-closes-even-when-the-body-throws
  (if-not ffm?
    (is true "skipped: this JVM has no java.lang.foreign (JDK < 22)")
    (testing "`with-open` semantics are the whole reason to reach for the macro
              rather than calling `mmap-source` by hand. A body that throws must
              still release the mapping, or a failing request leaks an arena per
              call -- and that leak is invisible until the process runs out of
              address space."
      (let [^bytes bs (boring/encode {"a" 1} opts)
            path (.getPath (spit-bytes bs))]
        (reset! escaped-cursor nil)
        (is (thrown? clojure.lang.ExceptionInfo
                     (eval `(do (require 'boring.mmap)
                                (~'boring.mmap/with-mmap [~'c ~path ~opts]
                                                         (reset! boring.mmap-test/escaped-cursor ~'c)
                                                         (throw (ex-info "boom" {})))))))
        (testing "and the arena really did close: the cursor the body leaked is
                  dead. That is also the hazard the docstring warns about when
                  it says not to let a cursor escape the body -- pinned here so
                  the warning is demonstrated rather than asserted"
          (is (some? @escaped-cursor))
          (is (thrown? Throwable (nav/value (get @escaped-cursor "a")))))))))

(def poke-opts {:profile :archival})

(deftest poke!-overwrites-in-place-same-length
  (if-not ffm?
    (is true "skipped: this JVM has no java.lang.foreign (JDK < 22)")
    (let [poke! (requiring-resolve 'boring.mmap/poke!)
          rd    (fn [^File f] (boring/decode (java.nio.file.Files/readAllBytes (.toPath f))))]
      (testing "a same-length poke lands in the file and matches assoc-in"
        (let [data {"a" {"x" 10 "y" 2} "b" 5}
              f (spit-bytes (boring/encode data poke-opts))]
          (is (= 1 (poke! (.getPath f) ["a" "x"] 7 poke-opts)))
          (is (= (assoc-in data ["a" "x"] 7) (rd f)))))
      (testing "an indexed file pokes a back field without a full scan, index stays valid"
        (let [data (into {} (for [i (range 300)] [(format "k%03d" i) (mod i 20)]))
              f (spit-bytes (boring/encode-indexed data poke-opts))]
          (poke! (.getPath f) ["k299"] 7 poke-opts)
          (is (= (assoc data "k299" 7) (rd f)))))
      (testing ":offset pokes a value that sits past a header, leaving the header untouched"
        (let [value (boring/encode {"a" {"x" 10} "b" 5} poke-opts)
              hdr   (byte-array (range 20))            ; arbitrary 20-byte prefix
              blob  (byte-array (concat hdr value))
              f     (spit-bytes blob)]
          (poke! (.getPath f) ["a" "x"] 7 (assoc poke-opts :offset 20))
          (let [b2 (java.nio.file.Files/readAllBytes (.toPath f))]
            (is (= (seq (range 20)) (seq (take 20 b2))) "header bytes untouched")
            (is (= {"a" {"x" 7} "b" 5}
                   (boring/decode (java.util.Arrays/copyOfRange b2 20 (alength b2))))))))
      (testing "a length-changing poke is refused and the file is left untouched"
        (let [data {"a" 10 "b" 5}
              f (spit-bytes (boring/encode data poke-opts))
              before (java.nio.file.Files/readAllBytes (.toPath f))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (poke! (.getPath f) ["a"] 1000000 poke-opts)))
          (is (= (seq before) (seq (java.nio.file.Files/readAllBytes (.toPath f)))))))
      (testing "a missing path is refused, file untouched"
        (let [f (spit-bytes (boring/encode {"a" 1} poke-opts))
              before (java.nio.file.Files/readAllBytes (.toPath f))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (poke! (.getPath f) ["nope"] 1 poke-opts)))
          (is (= (seq before) (seq (java.nio.file.Files/readAllBytes (.toPath f))))))))))

(deftest poke-update!-applies-a-fn-in-place
  (if-not ffm?
    (is true "skipped: this JVM has no java.lang.foreign (JDK < 22)")
    (let [poke-update! (requiring-resolve 'boring.mmap/poke-update!)
          rd (fn [^File f] (boring/decode (java.nio.file.Files/readAllBytes (.toPath f))))]
      (testing "a same-length fn result is poked in place, returns [old new]"
        (let [data {"n" 10 "s" "x"}
              f (spit-bytes (boring/encode data poke-opts))]
          (is (= [10 11] (poke-update! (.getPath f) ["n"] inc poke-opts)))
          (is (= (assoc data "n" 11) (rd f)))))
      (testing "a length-changing fn result is refused, file untouched"
        (let [f (spit-bytes (boring/encode {"n" 10} poke-opts))
              before (java.nio.file.Files/readAllBytes (.toPath f))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (poke-update! (.getPath f) ["n"] (fn [x] (+ x 1000000)) poke-opts)))
          (is (= (seq before) (seq (java.nio.file.Files/readAllBytes (.toPath f))))))))))

(deftest splice!-in-place-size-change-maintains-index
  (if-not ffm?
    (is true "skipped: this JVM has no java.lang.foreign (JDK < 22)")
    (let [splice! (requiring-resolve 'boring.mmap/splice!)
          rd (fn [^File f] (boring/decode (java.nio.file.Files/readAllBytes (.toPath f))))]
      (testing "unframed value grows in place"
        (let [f (spit-bytes (boring/encode {"a" {"x" 1} "b" 5} poke-opts))]
          (is (= [1 5] (splice! (.getPath f) ["a" "x"] 1000000000 poke-opts)))
          (is (= {"a" {"x" 1000000000} "b" 5} (rd f)))))
      (testing "framed value: front/back/nested grow, index stays a valid jump"
        (let [m (assoc (into {} (for [i (range 400)] [(format "k%03d" i) i])) "z" [1 2 3])
              f (spit-bytes (boring/encode-indexed m poke-opts))]
          (splice! (.getPath f) ["k000"] 5000000000 poke-opts)
          (splice! (.getPath f) ["k399"] 5000000000 poke-opts)
          (splice! (.getPath f) ["z" 1] 5000000000 poke-opts)
          (is (= (-> m (assoc "k000" 5000000000) (assoc "k399" 5000000000) (assoc-in ["z" 1] 5000000000))
                 (rd f)))
          (let [bs (java.nio.file.Files/readAllBytes (.toPath f))]
            (is (= 200 (nav/value (get (nav/root bs) "k200"))))
            (is (= 5000000000 (nav/value (get (nav/root bs) "k399")))))))
      (testing "shrink in place"
        (let [f (spit-bytes (boring/encode {"a" 1000000000 "b" 5} poke-opts))]
          (splice! (.getPath f) ["a"] 7 poke-opts)
          (is (= {"a" 7 "b" 5} (rd f)))))
      (testing "offset past a header"
        (let [value (boring/encode {"a" {"x" 1} "b" 5} poke-opts)
              blob  (byte-array (concat (range 20) value))
              f     (spit-bytes blob)]
          (splice! (.getPath f) ["a" "x"] 1000000000 (assoc poke-opts :offset 20))
          (let [b2 (java.nio.file.Files/readAllBytes (.toPath f))]
            (is (= (seq (range 20)) (seq (take 20 b2))))
            (is (= {"a" {"x" 1000000000} "b" 5}
                   (boring/decode (java.util.Arrays/copyOfRange b2 20 (alength b2)))))))))))

(deftest splice!-review-regressions
  (if-not ffm?
    (is true "skipped: this JVM has no java.lang.foreign (JDK < 22)")
    (let [splice! (requiring-resolve 'boring.mmap/splice!)
          rd (fn [^File f] (boring/decode (java.nio.file.Files/readAllBytes (.toPath f))))]
      (testing "finding 1: replacing a whole indexed array is REFUSED in place --
                a shift would corrupt its nodes -- so the caller can rebuild;
                the file is left untouched"
        (let [data {"big" (vec (range 300)) "z" 5}
              f (spit-bytes (boring/encode-indexed data poke-opts))
              before (java.nio.file.Files/readAllBytes (.toPath f))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"index node"
                                (splice! (.getPath f) ["big"] (vec (range 250)) poke-opts)))
          (is (= (seq before) (seq (java.nio.file.Files/readAllBytes (.toPath f))))
              "untouched on refusal")))
      (testing "finding 3: an UNFRAMED value ending in a footer-shaped byte string still splices"
        (let [data {"a" 1 "b" (byte-array [0 0 0 0 0 0 0 5])}  ; last bytes look like a footer
              f (spit-bytes (boring/encode data poke-opts))]
          (is (neg? (boring.frame/footer-start (boring/encode data poke-opts))) "fixture is unframed")
          (splice! (.getPath f) ["a"] 1000000000 poke-opts)   ; must not throw unmaintainable
          (let [got (rd f)]
            (is (= 1000000000 (get got "a")))
            (is (= (seq (byte-array [0 0 0 0 0 0 0 5])) (seq (get got "b"))))))))))
