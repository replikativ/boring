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
