(ns mmap-write
  "Does mmap help ENCODING, the way it helps selective decoding?

  The read side has a clear answer: mapping a CBOR file lets you touch only the
  bytes on the path, and beats a `pread` per item by 2.3x. The write side is
  not symmetric, and the reason is page faults. `write(2)` hands the kernel a
  prepared buffer and copies it in one go. A mapping takes a minor fault per
  4 KiB page the first time you touch it, and on a freshly-sized file the pages
  must be allocated too. For SEQUENTIAL append that trade often goes against
  mmap -- which is the opposite of the read result, so it needs measuring
  rather than assuming.

  Four ways to get the same 200k CBOR items onto disk:

    A  write-to! -> FileOutputStream           what boring does today
    B  write-to! -> BufferedOutputStream       the same, buffered (fair baseline)
    C  encode -> byte[] -> MemorySegment.copy  into a READ_WRITE mapping
    D  as C, but the mapping is pre-faulted    isolates the page-fault cost

  Durability is reported separately, because none of the above is durable:
  a plain write only reaches the page cache, and so does a store into a
  mapping. fsync and MemorySegment::force are the comparable pair.

  Run: clojure -M:bench -m mmap-write"
  (:require [boring.core :as boring])
  (:import (java.io File FileOutputStream BufferedOutputStream RandomAccessFile)
           (java.nio.channels FileChannel FileChannel$MapMode)
           (java.nio.file StandardOpenOption)
           (java.lang.foreign Arena MemorySegment ValueLayout)))

(set! *warn-on-reflection* true)

(def ^:const N-ITEMS 200000)

(def payloads
  (vec (for [i (range N-ITEMS)]
         {:e (+ 100 i) :a :user/name :v (str "person-" i)
          :tx (+ 536870912 i) :added true
          :extra (vec (range (mod i 7)))})))

(defn- tmp ^File [] (doto (File/createTempFile "boring-mw" ".cbor") .deleteOnExit))

;; ---------------------------------------------------------------- writers

(defn write-stream!
  "A: straight to a FileOutputStream, one write per item."
  ^long [^File f]
  (let [w (boring/writer 65536)]
    (with-open [out (FileOutputStream. f)]
      (loop [i 0 n 0]
        (if (= i N-ITEMS) n
            (recur (inc i) (+ n (long (boring/write-to! w (payloads i) out)))))))))

(defn write-buffered!
  "B: same, through a BufferedOutputStream."
  ^long [^File f]
  (let [w (boring/writer 65536)]
    (with-open [out (BufferedOutputStream. (FileOutputStream. f) 262144)]
      (loop [i 0 n 0]
        (if (= i N-ITEMS) n
            (recur (inc i) (+ n (long (boring/write-to! w (payloads i) out)))))))))

(defn write-mapped!
  "C/D: encode into the writer's own buffer, then bulk-copy each item into a
  READ_WRITE mapping. `prefault?` touches every page first, so the timed loop
  does not pay the faults."
  ^long [^File f ^long capacity prefault?]
  (with-open [raf (RandomAccessFile. f "rw")]
    (.setLength raf capacity)
    (with-open [ch (.getChannel raf)
                arena (Arena/ofConfined)]
      (let [seg (.map ch FileChannel$MapMode/READ_WRITE 0 capacity arena)
            w (boring/writer 65536)]
        (when prefault?
          ;; touch one byte per 4 KiB page
          (loop [p 0] (when (< p capacity)
                        (.set seg ValueLayout/JAVA_BYTE p (byte 0))
                        (recur (+ p 4096)))))
        (let [total (loop [i 0 pos 0]
                      (if (= i N-ITEMS) pos
                          (let [len (long (boring/encode-buffered! w (payloads i)))
                                buf (boring/buffer w)]
                            (MemorySegment/copy buf 0 seg ValueLayout/JAVA_BYTE pos len)
                            (recur (inc i) (+ pos len)))))]
          total)))))

;; ------------------------------------------------------------- durability

(defn write-stream-sync! ^long [^File f]
  (let [w (boring/writer 65536)]
    (with-open [fos (FileOutputStream. f)
                out (BufferedOutputStream. fos 262144)]
      (let [n (loop [i 0 n 0]
                (if (= i N-ITEMS) n
                    (recur (inc i) (+ n (long (boring/write-to! w (payloads i) out))))))]
        (.flush out)
        (.. fos getFD sync)
        n))))

(defn write-mapped-force! ^long [^File f ^long capacity]
  (with-open [raf (RandomAccessFile. f "rw")]
    (.setLength raf capacity)
    (with-open [ch (.getChannel raf)
                arena (Arena/ofConfined)]
      (let [seg (.map ch FileChannel$MapMode/READ_WRITE 0 capacity arena)
            w (boring/writer 65536)
            total (loop [i 0 pos 0]
                    (if (= i N-ITEMS) pos
                        (let [len (long (boring/encode-buffered! w (payloads i)))]
                          (MemorySegment/copy (boring/buffer w) 0 seg
                                              ValueLayout/JAVA_BYTE pos len)
                          (recur (inc i) (+ pos len)))))]
        (.force seg)
        total))))

;; ---------------------------------------------------------------- harness

(defn timed
  "Median of `rounds` runs, ms. Each round gets a fresh file, because writing
  to an already-populated file measures something else entirely."
  [f ^long rounds]
  (let [ts (vec (sort (for [_ (range rounds)]
                        (let [file (tmp)
                              t0 (System/nanoTime)
                              _ (f file)
                              t1 (System/nanoTime)]
                          (.delete file)
                          (- t1 t0)))))]
    (/ (nth ts (quot rounds 2)) 1e6)))

(defn -main [& _]
  ;; warm the encoder itself so we are not measuring JIT
  (let [w (boring/writer 65536)]
    (dotimes [_ 3] (doseq [v (take 20000 payloads)] (boring/encode-buffered! w v))))

  (let [size (let [f (tmp) n (write-buffered! f)] (.delete f) n)
        cap (long (* 1.5 size))]
    (println (format "\n%d items, %.1f MB of CBOR\n" N-ITEMS (/ size 1048576.0)))
    (println (format "%-42s %9s %11s" "strategy" "ms" "MB/s"))
    (let [mbps (fn [ms] (/ (/ size 1048576.0) (/ ms 1000.0)))]
      (doseq [[nm f] [["A  FileOutputStream (today)"        #(write-stream! %)]
                      ["B  BufferedOutputStream"            #(write-buffered! %)]
                      ["C  mmap + bulk copy per item"       #(write-mapped! % cap false)]
                      ["D  mmap, pages pre-faulted"         #(write-mapped! % cap true)]]]
        (let [ms (timed f 5)]
          (println (format "%-42s %9.1f %11.0f" nm ms (mbps ms)))
          (flush)))

      ;; The floor. A writer that encoded STRAIGHT into the mapping -- the
      ;; primitive neither boring nor hako has, since hako's Writer always
      ;; allocates its own confined arena and cannot target a caller's segment
      ;; -- could at best reach this plus the page faults. Everything above it
      ;; is I/O and copying; everything below it is the encoder itself.
      (let [w (boring/writer 65536)
            ms (/ (let [t0 (System/nanoTime)]
                    (dotimes [i N-ITEMS] (boring/encode-buffered! w (payloads i)))
                    (- (System/nanoTime) t0))
                  1e6)]
        (println (format "%-42s %9.1f %11.0f   <- floor: encode only, no I/O"
                         "E  encode-buffered! only (no file)" ms (mbps ms))))

      (println "\ndurable (data actually on the device):")
      (doseq [[nm f] [["B + fsync"                          #(write-stream-sync! %)]
                      ["C + MemorySegment::force"           #(write-mapped-force! % cap)]]]
        (let [ms (timed f 5)]
          (println (format "%-42s %9.1f %11.0f" nm ms (mbps ms)))
          (flush))))

    (println "\nNote D includes the pre-fault pass in its own timing, so it is not")
    (println "free -- it moves the cost, it does not remove it. The gap between C")
    (println "and D is what page faults cost on this filesystem.")))
