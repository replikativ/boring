(ns mmap
  "Can boring decode selectively out of an mmap'ed CBOR file, at competitive
  speed, without an off-heap reader?

  The FFM question (`bench/java/FfmProbe.java`) answered the primitive half:
  a native `MemorySegment` is at parity with `byte[]` on stock HotSpot once you
  stop using `withOrder(BIG_ENDIAN)` and swap explicitly. This ns answers the
  half that actually decides the API -- how much a segment-native reader would
  be worth on top of what boring can already do today.

  Today's move is: mmap the file, copy the ONE item you want into a reusable
  scratch `byte[]`, decode it with the existing reader. The mapping is never
  fully read, so pages you do not probe are never faulted in; only the bytes of
  items you actually decode cross into the heap.

  Two payload shapes, because they answer differently:

    structure-heavy   200k small maps        copy is 6.8% of decode
    blob-heavy        64 x 1 MiB bytestrings copy IS the decode

  Run: clojure -M:bench -m mmap"
  (:require [boring.core :as boring])
  (:import (java.io File FileOutputStream)
           (java.nio ByteBuffer)
           (java.nio.channels FileChannel FileChannel$MapMode)
           (java.nio.file StandardOpenOption)
           (java.lang.foreign Arena MemorySegment ValueLayout)
           (org.replikativ.boring Reader)))

(set! *warn-on-reflection* true)

(defn mmap-file ^MemorySegment [^File f ^Arena arena]
  (with-open [ch (FileChannel/open (.toPath f)
                                   (into-array StandardOpenOption
                                               [StandardOpenOption/READ]))]
    (.map ch FileChannel$MapMode/READ_ONLY 0 (.size ch) arena)))

(defn- ab
  "Alternating bursts, min over rounds -- see bench/ab.clj for why min."
  [fa fb rounds per]
  (dotimes [_ 5] (fa) (fb))
  (loop [r 0 ba Long/MAX_VALUE bb Long/MAX_VALUE]
    (if (= r rounds)
      [(/ ba (double per)) (/ bb (double per))]
      (let [t0 (System/nanoTime) _ (fa)
            t1 (System/nanoTime) _ (fb)
            t2 (System/nanoTime)]
        (recur (inc r) (min ba (- t1 t0)) (min bb (- t2 t1)))))))

;; ---------------------------------------------------------------- structure

(def ^:const N-ITEMS 200000)
(def ^:const N-PROBES 20000)

(defn build-items! [^File f]
  (let [w (boring/writer 65536)
        offs (int-array N-ITEMS)
        lens (int-array N-ITEMS)]
    (with-open [out (FileOutputStream. f)]
      (loop [i 0 pos 0]
        (when (< i N-ITEMS)
          (let [v {:e (+ 100 i) :a :user/name :v (str "person-" i)
                   :tx (+ 536870912 i) :added true
                   :extra (vec (range (mod i 7)))}
                len (boring/write-to! w v out)]
            (aset offs i pos)
            (aset lens i (int len))
            (recur (inc i) (+ pos (long len)))))))
    {:offs offs :lens lens :size (.length f)}))

(defn run-structure []
  (let [f (doto (File/createTempFile "boring-mmap" ".cbor") .deleteOnExit)
        built (build-items! f)
        offs ^ints (:offs built)
        lens ^ints (:lens built)
        size (long (:size built))
        arena (Arena/ofShared)
        seg (mmap-file f arena)
        scratch (byte-array 4096)
        rdr (Reader. (byte-array 1))
        ;; whole file on heap, so we can isolate the copy from everything else
        heap (let [a (byte-array size)]
               (MemorySegment/copy seg ValueLayout/JAVA_BYTE 0 a 0 size) a)
        ;; per-item byte[]: the floor, no copy at decode time at all
        slices (let [a (object-array N-ITEMS)]
                 (dotimes [i N-ITEMS]
                   (let [b (byte-array (aget lens i))]
                     (System/arraycopy heap (aget offs i) b 0 (aget lens i))
                     (aset a i b)))
                 a)
        probes (let [r (java.util.Random. 42) a (int-array N-PROBES)]
                 (dotimes [i N-PROBES] (aset a i (.nextInt r N-ITEMS))) a)
        ch (FileChannel/open (.toPath f)
                             (into-array StandardOpenOption [StandardOpenOption/READ]))
        bb (ByteBuffer/wrap scratch)

        sweep-mmap  #(loop [i 0 acc 0]
                       (if (= i N-PROBES) acc
                           (let [j (aget ^ints probes i)]
                             (MemorySegment/copy seg ValueLayout/JAVA_BYTE
                                                 (aget offs j) scratch 0 (aget lens j))
                             (.reset rdr scratch)
                             (recur (inc i) (+ acc (if (.read rdr) 1 0))))))
        sweep-floor #(loop [i 0 acc 0]
                       (if (= i N-PROBES) acc
                           (let [j (aget ^ints probes i)]
                             (.reset rdr ^bytes (aget ^objects slices j))
                             (recur (inc i) (+ acc (if (.read rdr) 1 0))))))
        ;; Same copy, but out of a heap byte[] via System/arraycopy. Splits the
        ;; per-item cost into "copying at all" (this row) and "copying across
        ;; the mapping boundary" (the mmap row minus this one).
        sweep-heap  #(loop [i 0 acc 0]
                       (if (= i N-PROBES) acc
                           (let [j (aget ^ints probes i)]
                             (System/arraycopy heap (aget offs j) scratch 0 (aget lens j))
                             (.reset rdr scratch)
                             (recur (inc i) (+ acc (if (.read rdr) 1 0))))))
        sweep-pread #(loop [i 0 acc 0]
                       (if (= i N-PROBES) acc
                           (let [j (aget ^ints probes i)]
                             (.clear bb)
                             (.limit bb (aget lens j))
                             (.read ch bb (aget offs j))
                             (.reset rdr scratch)
                             (recur (inc i) (+ acc (if (.read rdr) 1 0))))))]
    (println (format "\nstructure-heavy: %.1f MB, %d items, %d random probes"
                     (/ size 1048576.0) N-ITEMS N-PROBES))
    (let [[a b] (ab sweep-mmap sweep-floor 12 N-PROBES)]
      (println (format "  %-38s %8.0f ns/item" "mmap -> scratch -> decode" a))
      (println (format "  %-38s %8.0f ns/item   <- floor, no copy" "pre-sliced byte[] -> decode" b))
      (println (format "  %-38s %8.1f%%" "copy overhead over floor" (* 100.0 (/ (- a b) b)))))
    (let [[a b] (ab sweep-heap sweep-floor 12 N-PROBES)]
      (println (format "  %-38s %8.0f ns/item   (%.1f%% over floor)"
                       "heap byte[] -> scratch -> decode" a (* 100.0 (/ (- a b) b)))))
    (let [[a b] (ab sweep-pread sweep-floor 12 N-PROBES)]
      (println (format "  %-38s %8.0f ns/item  (%.1fx floor)" "pread syscall -> decode" a (/ a b))))
    (.close ch)
    (.close arena)))

;; --------------------------------------------------------------------- blobs

(defn run-blobs []
  (let [f (doto (File/createTempFile "boring-blobs" ".cbor") .deleteOnExit)
        n 64
        payload (byte-array (* 1024 1024))
        _ (dotimes [i (alength payload)] (aset-byte payload i (byte (mod i 127))))
        w (boring/writer (* 8 1024 1024))
        offs (int-array n) lens (int-array n)
        _ (with-open [out (FileOutputStream. f)]
            (loop [i 0 pos 0]
              (when (< i n)
                (let [len (boring/write-to! w {:id i :data payload} out)]
                  (aset offs i pos) (aset lens i (int len))
                  (recur (inc i) (+ pos (long len)))))))
        arena (Arena/ofShared)
        seg (mmap-file f arena)
        scratch (byte-array (* 2 1024 1024))
        rdr (Reader. (byte-array 1))

        ;; what boring does today: mapping -> scratch -> a fresh heap byte[]
        ;; for the blob. Two copies of every megabyte, plus the garbage.
        full  #(loop [i 0 acc 0]
                 (if (= i n) acc
                     (do (MemorySegment/copy seg ValueLayout/JAVA_BYTE
                                             (aget offs i) scratch 0 (aget lens i))
                         (.reset rdr scratch)
                         (recur (inc i)
                                (+ acc (alength ^bytes (:data (.read rdr))))))))
        ;; upper bound for a zero-copy reader: locate the item, hand back a
        ;; slice, materialise nothing. Not a real decoder -- it is the ceiling
        ;; such a decoder could approach, which is the number worth knowing.
        slice #(loop [i 0 acc 0]
                 (if (= i n) acc
                     (recur (inc i)
                            (+ acc (.byteSize (.asSlice seg (long (aget offs i))
                                                        (long (aget lens i))))))))]
    (println (format "\nblob-heavy: %d x 1 MiB bytestrings, %.0f MB"
                     n (/ (.length f) 1048576.0)))
    (let [[a b] (ab full slice 10 n)]
      (println (format "  %-38s %8.1f µs/blob" "full decode (materialises the blob)" (/ a 1000.0)))
      (println (format "  %-38s %8.3f µs/blob" "slice only (zero-copy ceiling)" (/ b 1000.0)))
      (println (format "  %-38s %8.0fx" "ratio" (/ a (max b 1.0)))))
    (.close arena)))

(defn -main [& _]
  (run-structure)
  (run-blobs)
  (println "\nReading: the 6.8% is all a segment-native reader could win back on")
  (println "structure. The blob ratio is what a zero-copy bytestring accessor is")
  (println "worth, and it does not require an off-heap reader at all -- only the")
  (println "ability to NOT materialise a payload. See doc/PERFORMANCE.md."))
