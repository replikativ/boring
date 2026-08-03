(ns hako-ab
  "The tier-matched boring vs hako comparison. Timing AND allocation.

  WHY THIS EXISTS. `bench.clj` used to call `hako/encode` and `hako/decode`
  while calling boring's reused-writer `encode-into!` and reused-reader
  `decode-with`. hako's encode/decode build a fresh Writer and a fresh confined
  Arena per call and copy the result out; hako's author confirms they are
  porting conveniences and that `encode-into!` / `decode-into!` are the paths
  he expects code to be built on. The old table therefore measured boring's
  fast path against hako's slow one -- it even bound a `hako/writer` and never
  used it -- and it flattered boring on small maps.

  There was briefly a second, criterium-based version of this table. It is
  gone: criterium measures A for ~7s then B for ~7s, and on this machine that
  produced boring's FRESH reader beating its own REUSED reader on two payloads,
  which cannot be true. Everything here goes through `ab/ab` instead --
  alternating bursts, min over rounds, so both sides of a pair see the same
  conditions. See bench/README.md, \"Warnings earned the hard way\".

  THE TIERS. Compare like with like:

    T1  fresh codec, fresh byte[]     boring/encode           hako/encode
    T2  reused codec, fresh byte[]    boring/encode-into!     hako/encode-into! + copy
    T3  reused codec, no copy         boring/encode-buffered! hako/encode-into!

    T1  fresh reader                  boring/decode           hako/decode
    T2  reused reader                 boring/decode-with      hako/decode-into!

  T3 is the honest head-to-head: boring returns a byte count into an on-heap
  buffer you must not retain, hako returns a MemorySegment slice into an arena
  you must not retain past the next call. Same contract, different memory.

  ALLOCATION IS HEAP-ONLY. `getThreadAllocatedBytes` cannot see hako's arena,
  and sees all of boring's growable byte[]. The columns measure GC pressure --
  which is what drives tail latency, and what the comparison is about -- and
  NOT total memory traffic. Do not quote them as a like-for-like byte count.

  Run: clojure -M:bench -m hako-ab [no-warmup]"
  (:require [boring.core :as boring]
            [s-exp.hako :as hako]
            [ab :refer [ab]])
  (:import (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)
           (java.lang.foreign MemorySegment ValueLayout)))

(set! *warn-on-reflection* true)

(def payloads
  [["small-map"      {:name "Alice" :tags #{:a :b :c} :score 42}]
   ["mixed"          {:id 7 :n 12345678901 :d 3.14159 :s "hello world" :ok true}]
   ["nested-map-50"  (into {} (for [i (range 50)]
                                [(keyword (str "field-" i)) {:v i :s (str "val-" i)}]))]
   ["datom-maps-200" (vec (for [i (range 200)]
                            {:e (+ 100 i) :a :user/name :v (str "person-" i)
                             :tx (+ 536870912 i) :added true}))]
   ["datom-vec-1k"   (vec (for [i (range 1000)]
                            [(+ 100 i) :user/name (str "person-" i)
                             (+ 536870912 i) true]))]
   ["long-vec-1k"    (vec (range 1000))]])

(defn seg->bytes
  "hako's `encode-into!` returns a slice into its arena, valid until the next
  call. Copying it out is what makes it comparable to `encode-into!`, which
  returns a fresh byte[]."
  ^bytes [^MemorySegment seg]
  (let [n (.byteSize seg)
        arr (byte-array n)]
    (MemorySegment/copy seg ValueLayout/JAVA_BYTE 0 arr 0 n)
    arr))

;; ------------------------------------------------------------- allocation

(def ^ThreadMXBean tmx (ManagementFactory/getThreadMXBean))

(defn- allocated ^long []
  (.getThreadAllocatedBytes tmx (.getId (Thread/currentThread))))

(defn bytes-per-op ^long [f ^long n]
  (dotimes [_ 20000] (f))
  (System/gc)
  (let [before (allocated)]
    (dotimes [_ n] (f))
    (long (/ (- (allocated) before) n))))

;; ----------------------------------------------------------------- output

(defn- fmt-t [a b]
  (format "%9.2f %9.2f  %5.2fx %s" (/ a 1000.0) (/ b 1000.0) (/ a b)
          (cond (< a (* 0.97 b)) "boring"
                (> a (* 1.03 b)) "hako"
                :else "tie ")))

(defn- fmt-a [a b]
  (format "%9d %9d  %5s %s" a b
          (if (zero? b) "n/a" (format "%.2fx" (/ (double a) b)))
          (cond (< a b) "boring" (> a b) "hako" :else "tie ")))

(defn global-warmup!
  "Every payload through every codec and tier before anything is measured.
  Per-cell warmup is not enough -- see ab/global-warmup!."
  [bw hw]
  (dotimes [_ 3]
    (doseq [[_ v] payloads]
      (let [rb (boring/encode v)
            hb (hako/encode v)
            brd (org.replikativ.boring.Reader. (byte-array 1))
            hrd (hako/reader hb)]
        (dotimes [_ 2000]
          (boring/encode-into! bw v)
          (boring/encode-buffered! bw v)
          (seg->bytes (hako/encode-into! hw v))
          (boring/decode-with brd rb)
          (hako/decode-into! hrd hb))))))

(defn -main [& args]
  (let [bw (boring/writer 65536)
        hw (hako/writer 65536)
        n 50000]
    (when-not (contains? (set args) "no-warmup")
      (print "global warmup... ") (flush)
      (global-warmup! bw hw)
      (println "done"))

    (println "\n=== TIME: A/B interleaved, min over 60 rounds, µs/op ===\n")
    (println (format "%-16s %9s %9s  %5s %s" "payload" "boring" "hako" "ratio" "winner"))

    (println "\n--- T2 ENCODE: reused codec, fresh byte[] out ---")
    (doseq [[nm v] payloads]
      (let [[a b] (ab #(boring/encode-into! bw v)
                      #(seg->bytes (hako/encode-into! hw v)))]
        (println (format "%-16s %s" nm (fmt-t a b))) (flush)))

    (println "\n--- T3 ENCODE: reused codec, no copy out ---")
    (doseq [[nm v] payloads]
      (let [[a b] (ab #(boring/encode-buffered! bw v)
                      #(hako/encode-into! hw v))]
        (println (format "%-16s %s" nm (fmt-t a b))) (flush)))

    (println "\n--- T1 ENCODE: fresh codec, fresh byte[] (hako's non-idiomatic tier) ---")
    (doseq [[nm v] payloads]
      (let [[a b] (ab #(boring/encode v) #(hako/encode v))]
        (println (format "%-16s %s" nm (fmt-t a b))) (flush)))

    (println "\n--- T2 DECODE: reused reader ---")
    (doseq [[nm v] payloads]
      (let [rb (boring/encode v)
            hb (hako/encode v)
            brd (org.replikativ.boring.Reader. (byte-array 1))
            hrd (hako/reader hb)
            [a b] (ab #(boring/decode-with brd rb) #(hako/decode-into! hrd hb))]
        (println (format "%-16s %s" nm (fmt-t a b))) (flush)))

    (println "\n--- T2 DECODE, hako fed a pre-wrapped MemorySegment ---")
    (println "    (what an off-heap source is worth; boring cannot express it today)")
    (doseq [[nm v] payloads]
      (let [rb (boring/encode v)
            hb (hako/encode v)
            brd (org.replikativ.boring.Reader. (byte-array 1))
            hrd (hako/reader hb)
            hseg (MemorySegment/ofArray ^bytes hb)
            [a b] (ab #(boring/decode-with brd rb) #(hako/decode-into! hrd hseg))]
        (println (format "%-16s %s" nm (fmt-t a b))) (flush)))

    (println "\n--- T1 DECODE: fresh reader ---")
    (doseq [[nm v] payloads]
      (let [rb (boring/encode v)
            hb (hako/encode v)
            [a b] (ab #(boring/decode rb) #(hako/decode hb))]
        (println (format "%-16s %s" nm (fmt-t a b))) (flush)))

    (println "\n\n=== ALLOCATION: heap bytes/op (see ns docstring -- NOT total memory) ===\n")
    (println (format "%-16s %9s %9s  %5s %s" "payload" "boring" "hako" "ratio" "winner"))

    (println "\n--- T2 ENCODE: reused codec, fresh byte[] out ---")
    (doseq [[nm v] payloads]
      (println (format "%-16s %s" nm
                       (fmt-a (bytes-per-op #(boring/encode-into! bw v) n)
                              (bytes-per-op #(seg->bytes (hako/encode-into! hw v)) n))))
      (flush))

    (println "\n--- T3 ENCODE: reused codec, no copy out ---")
    (doseq [[nm v] payloads]
      (println (format "%-16s %s" nm
                       (fmt-a (bytes-per-op #(boring/encode-buffered! bw v) n)
                              (bytes-per-op #(hako/encode-into! hw v) n))))
      (flush))

    (println "\n--- T2 DECODE: reused reader ---")
    (doseq [[nm v] payloads]
      (let [rb (boring/encode v)
            hb (hako/encode v)
            brd (org.replikativ.boring.Reader. (byte-array 1))
            hrd (hako/reader hb)]
        (println (format "%-16s %s" nm
                         (fmt-a (bytes-per-op #(boring/decode-with brd rb) n)
                                (bytes-per-op #(hako/decode-into! hrd hb) n))))
        (flush)))))
