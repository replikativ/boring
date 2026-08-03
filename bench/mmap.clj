(ns mmap
  "What memory mapping does, and does not, buy a CBOR file.

  Three sections, because the answers differ and two of them are negative:

    read      selective decode out of a mapping beats a pread per item 2.3x
    write     mmap LOSES to a buffered stream for append
    compress  chunked zstd against random access -- the curve that picks a
              chunk size, and why filesystem compression is the wrong regime

  Run: clojure -M:bench -m mmap [read|write|compress]

  Findings are written up in doc/PERFORMANCE.md; this file is how they are
  reproduced."
  (:require [ab :refer [ab]]
            [boring.core :as boring])
  (:import (com.github.luben.zstd Zstd ZstdCompressCtx ZstdDecompressCtx
                                  ZstdDictCompress ZstdDictDecompress ZstdDictTrainer)
           (java.io BufferedOutputStream File FileOutputStream RandomAccessFile)
           (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.nio ByteBuffer)
           (java.nio.channels FileChannel FileChannel$MapMode)
           (java.nio.file StandardOpenOption)
           (org.replikativ.boring Reader)))

(set! *warn-on-reflection* true)

(def ^:const N-ITEMS 200000)
(def ^:const N-PROBES 20000)

(defn- tmp ^File [] (doto (File/createTempFile "boring-mmap" ".cbor") .deleteOnExit))

(defn mmap-file ^MemorySegment [^File f ^Arena arena]
  (with-open [ch (FileChannel/open (.toPath f)
                                   (into-array StandardOpenOption
                                               [StandardOpenOption/READ]))]
    (.map ch FileChannel$MapMode/READ_ONLY 0 (.size ch) arena)))

(def payloads
  (vec (for [i (range N-ITEMS)]
         {:e (+ 100 i) :a :user/name :v (str "person-" i)
          :tx (+ 536870912 i) :added true
          :extra (vec (range (mod i 7)))})))

;; ------------------------------------------------------------------- read
;;
;; mmap gives demand paging: pages you never probe are never faulted in, so a
;; lookup costs what the ITEM costs, not what the file costs. Today's move is
;; to copy the one item you want into a reusable scratch byte[] -- boring's
;; Reader takes a ByteSource, but staging through the array path is cheaper
;; than decoding in place off-heap (see doc/PERFORMANCE.md).

(defn- build-items! [^File f]
  (let [w (boring/writer 65536)
        offs (int-array N-ITEMS)
        lens (int-array N-ITEMS)]
    (with-open [out (FileOutputStream. f)]
      (loop [i 0 pos 0]
        (when (< i N-ITEMS)
          (let [len (boring/write-to! w (payloads i) out)]
            (aset offs i pos)
            (aset lens i (int len))
            (recur (inc i) (+ pos (long len)))))))
    {:offs offs :lens lens :size (.length f)}))

(defn run-read []
  (let [f (tmp)
        built (build-items! f)
        offs ^ints (:offs built)
        lens ^ints (:lens built)
        size (long (:size built))
        arena (Arena/ofShared)
        seg (mmap-file f arena)
        scratch (byte-array 4096)
        rdr (Reader. (byte-array 1))
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

        sweep-mmap #(loop [i 0 acc 0]
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
        sweep-heap #(loop [i 0 acc 0]
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
    (println (format "\nREAD — %.1f MB, %d items, %d random probes\n"
                     (/ size 1048576.0) N-ITEMS N-PROBES))
    (let [[a b] (ab sweep-mmap sweep-floor 1 12)]
      (println (format "  %-38s %8.0f ns/item" "mmap -> scratch -> decode" (/ a N-PROBES)))
      (println (format "  %-38s %8.0f ns/item   <- floor, no copy"
                       "pre-sliced byte[] -> decode" (/ b N-PROBES)))
      (println (format "  %-38s %8.1f%%" "copy overhead over floor"
                       (* 100.0 (/ (- a b) b)))))
    (let [[a b] (ab sweep-heap sweep-floor 1 12)]
      (println (format "  %-38s %8.0f ns/item   (%.1f%% over floor)"
                       "heap byte[] -> scratch -> decode" (/ a N-PROBES)
                       (* 100.0 (/ (- a b) b)))))
    (let [[a b] (ab sweep-pread sweep-floor 1 12)]
      (println (format "  %-38s %8.0f ns/item  (%.1fx floor)"
                       "pread syscall -> decode" (/ a N-PROBES) (/ a b))))
    (.close ch)
    (.close arena)
    (.delete f)))

;; ------------------------------------------------------------------ blobs
;;
;; For a bytestring, "decoding" IS the copy, so not materialising one is worth
;; three orders of magnitude. This is the case that justifies nav/byte-span.

(defn run-blobs []
  (let [f (tmp)
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
        full #(loop [i 0 acc 0]
                (if (= i n) acc
                    (do (MemorySegment/copy seg ValueLayout/JAVA_BYTE
                                            (aget offs i) scratch 0 (aget lens i))
                        (.reset rdr scratch)
                        (recur (inc i) (+ acc (alength ^bytes (:data (.read rdr))))))))
        slice #(loop [i 0 acc 0]
                 (if (= i n) acc
                     (recur (inc i)
                            (+ acc (.byteSize (.asSlice seg (long (aget offs i))
                                                        (long (aget lens i))))))))]
    (println (format "\nBLOBS — %d x 1 MiB bytestrings, %.0f MB\n"
                     n (/ (.length f) 1048576.0)))
    (let [[a b] (ab full slice 1 10)]
      (println (format "  %-38s %8.1f µs/blob" "full decode (materialises it)" (/ a n 1000.0)))
      (println (format "  %-38s %8.3f µs/blob" "slice only (zero-copy ceiling)" (/ b n 1000.0)))
      (println (format "  %-38s %8.0fx" "ratio" (/ a (max b 1.0)))))
    (.close arena)
    (.delete f)))

;; ------------------------------------------------------------------ write
;;
;; The read result does not mirror. A mapping takes a minor fault per 4 KiB
;; page and the file's pages must be allocated, while write(2) hands the kernel
;; one prepared buffer. The number that matters is the FLOOR: encoding is most
;; of the job, so the addressable win from a better write path is small.

(defn- write-stream! ^long [^File f buffered?]
  (let [w (boring/writer 65536)]
    (with-open [fos (FileOutputStream. f)
                ^java.io.OutputStream out (if buffered?
                                            (BufferedOutputStream. fos 262144)
                                            fos)]
      (loop [i 0 n 0]
        (if (= i N-ITEMS) n
            (recur (inc i) (+ n (long (boring/write-to! w (payloads i) out)))))))))

(defn- write-mapped! ^long [^File f ^long capacity prefault?]
  (with-open [raf (RandomAccessFile. f "rw")]
    (.setLength raf capacity)
    (with-open [ch (.getChannel raf)
                arena (Arena/ofConfined)]
      (let [seg (.map ch FileChannel$MapMode/READ_WRITE 0 capacity arena)
            w (boring/writer 65536)]
        (when prefault?
          (loop [p 0] (when (< p capacity)
                        (.set seg ValueLayout/JAVA_BYTE p (byte 0))
                        (recur (+ p 4096)))))
        (loop [i 0 pos 0]
          (if (= i N-ITEMS) pos
              (let [len (long (boring/encode-buffered! w (payloads i)))]
                (MemorySegment/copy (boring/buffer w) 0 seg ValueLayout/JAVA_BYTE pos len)
                (recur (inc i) (+ pos len)))))))))

(defn- timed-fresh
  "Median of `rounds` runs, ms. Each round gets a fresh file -- writing to an
  already-populated one measures something else."
  [f ^long rounds]
  (let [ts (vec (sort (for [_ (range rounds)]
                        (let [file (tmp)
                              t0 (System/nanoTime)
                              _ (f file)
                              t1 (System/nanoTime)]
                          (.delete file)
                          (- t1 t0)))))]
    (/ (nth ts (quot rounds 2)) 1e6)))

(defn run-write []
  (let [w (boring/writer 65536)]
    (dotimes [_ 3] (doseq [v (take 20000 payloads)] (boring/encode-buffered! w v))))
  (let [size (let [f (tmp) n (write-stream! f true)] (.delete f) n)
        cap (long (* 1.5 size))
        mbps (fn [ms] (/ (/ size 1048576.0) (/ ms 1000.0)))]
    (println (format "\nWRITE — %d items, %.1f MB\n" N-ITEMS (/ size 1048576.0)))
    (println (format "  %-40s %9s %11s" "strategy" "ms" "MB/s"))
    (doseq [[nm f] [["A  FileOutputStream (unbuffered)" #(write-stream! % false)]
                    ["B  BufferedOutputStream"          #(write-stream! % true)]
                    ["C  mmap + bulk copy per item"     #(write-mapped! % cap false)]
                    ["D  mmap, pages pre-faulted"       #(write-mapped! % cap true)]]]
      (let [ms (timed-fresh f 5)]
        (println (format "  %-40s %9.1f %11.0f" nm ms (mbps ms)))
        (flush)))
    (let [w (boring/writer 65536)
          ms (/ (let [t0 (System/nanoTime)]
                  (dotimes [i N-ITEMS] (boring/encode-buffered! w (payloads i)))
                  (- (System/nanoTime) t0))
                1e6)]
      (println (format "  %-40s %9.1f %11.0f   <- floor: encode only"
                       "E  encode-buffered! only (no I/O)" ms (mbps ms))))
    (println "\n  D includes its pre-fault pass, so it moves the cost rather than")
    (println "  removing it. The gap between E and B is the whole I/O budget.")))

;; --------------------------------------------------------------- compress
;;
;; mmap pages at 4 KiB; a compressor needs a bigger block to find matches, and
;; a compressed block only decodes as a whole. So the chunk size IS the
;; exchange rate between ratio and random-access cost.

(defn- concat-bytes ^bytes [items]
  (let [n (reduce + (map #(alength ^bytes %) items))
        out (byte-array n)]
    (loop [is items p 0]
      (if (seq is)
        (let [^bytes b (first is)]
          (System/arraycopy b 0 out p (alength b))
          (recur (rest is) (+ p (alength b))))
        out))))

(defn run-compress []
  (let [encoded (mapv #(boring/encode % {:stringref false}) payloads)
        total-raw (reduce + (map #(alength ^bytes %) encoded))
        chunkify (fn [^long target]
                   (let [chunks (java.util.ArrayList.)
                         i->c (int-array N-ITEMS) i->o (int-array N-ITEMS)]
                     (loop [i 0 acc [] acc-len 0 ci 0 off 0]
                       (if (= i N-ITEMS)
                         (do (when (seq acc) (.add chunks (concat-bytes acc)))
                             {:chunks (vec chunks) :item->chunk i->c :item->off i->o})
                         (let [^bytes b (encoded i) len (alength b)]
                           (aset i->c i ci) (aset i->o i off)
                           (if (>= (+ acc-len len) target)
                             (do (.add chunks (concat-bytes (conj acc b)))
                                 (recur (inc i) [] 0 (inc ci) 0))
                             (recur (inc i) (conj acc b) (+ acc-len len) ci (+ off len))))))))
        probes (let [r (java.util.Random. 11) a (int-array 5000)]
                 (dotimes [i 5000] (aset a i (.nextInt r N-ITEMS))) a)
        rdr (Reader. (byte-array 1))
        scratch (byte-array (* 4 1024 1024))
        item-buf (byte-array 4096)
        timed (fn [f ^long rounds]
                (dotimes [_ 3] (dotimes [i (alength probes)] (f (aget probes i))))
                (loop [r 0 best Long/MAX_VALUE]
                  (if (= r rounds) (/ best (double (alength probes)))
                      (let [t0 (System/nanoTime)
                            _ (dotimes [i (alength probes)] (f (aget probes i)))
                            t1 (System/nanoTime)]
                        (recur (inc r) (min best (- t1 t0)))))))
        base (timed (fn [i] (.reset rdr ^bytes (encoded i)) (.read rdr)) 20)
        row (fn [label comp-total ns]
              (println (format "  %-14s %10.2f %7.2fx %14.0f %11.1fx"
                               label (/ comp-total 1048576.0)
                               (/ (double total-raw) comp-total) ns (/ ns base))))]
    (println (format "\nCOMPRESS — %d items, %.1f MB raw, zstd level 3\n"
                     N-ITEMS (/ total-raw 1048576.0)))
    (println (format "  %-14s %10s %8s %14s %12s"
                     "chunk" "comp MB" "ratio" "ns/lookup" "vs raw"))
    (println (format "  %-14s %10.2f %8s %14.0f %12s"
                     "uncompressed" (/ total-raw 1048576.0) "1.00x" base "1.0x"))
    (doseq [target [4096 16384 65536 262144]]
      (let [{:keys [chunks ^ints item->chunk ^ints item->off]} (chunkify target)
            cvec (mapv (fn [^bytes c] (Zstd/compress c 3)) chunks)
            comp-total (reduce + (map #(alength ^bytes %) cvec))
            dctx (ZstdDecompressCtx.)
            lookup (fn [i]
                     (let [^bytes cc (cvec (aget item->chunk i))
                           _ (.decompressByteArray dctx scratch 0 (alength scratch)
                                                   cc 0 (alength cc))
                           off (aget item->off i)]
                       ;; Reader.reset has no offset arity, so the item's own
                       ;; bytes are copied out rather than the whole chunk tail.
                       (if (zero? off)
                         (do (.reset rdr scratch) (.read rdr))
                         (let [len (alength ^bytes (encoded i))]
                           (System/arraycopy scratch off item-buf 0 len)
                           (.reset rdr item-buf)
                           (.read rdr)))))]
        (row (str (quot target 1024) " KB") comp-total (timed lookup 20))
        (flush)))
    (println "\n  Lookup cost scales with chunk size; ratio saturates almost at once.")
    (println "  btrfs compresses 128 KiB extents and ZFS a 128 KiB recordsize, so")
    (println "  filesystem compression lands at the bottom of this table with no knob.")
    (let [whole (Zstd/compress (concat-bytes encoded) 3)]
      (println (format "  whole-file zstd: %.2f MB, %.2fx -- and no random access at all"
                       (/ (alength whole) 1048576.0)
                       (/ (double total-raw) (alength whole)))))))

(defn -main [& args]
  (let [only (set args)
        run? (fn [k] (or (empty? only) (contains? only (name k))))]
    (when (run? :read) (run-read) (run-blobs))
    (when (run? :write) (run-write))
    (when (run? :compress) (run-compress))))
