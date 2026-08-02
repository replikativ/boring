(ns alloc
  "Bytes allocated per op — the axis every timing benchmark in this project has
  ignored. Timing shows throughput; allocation shows GC pressure and tail
  latency, which is what actually bites a database under sustained load.
  Method follows hako's: ThreadMXBean.getThreadAllocatedBytes over N ops.

  READ THIS BEFORE QUOTING THE HAKO COLUMN. getThreadAllocatedBytes counts
  HEAP allocation. hako's buffer lives in a native confined Arena, so the
  bytes it writes into are invisible here; boring's growable byte[] is heap and
  fully counted. The column therefore measures GC pressure -- which is the
  thing that drives tail latency, and the thing the comparison is about -- and
  NOT total memory traffic. It is not a handicap either way, but it is not a
  like-for-like byte count and must not be quoted as one.

  Tiers are matched: `hako/encode` allocates a fresh Writer and Arena per call
  and is not the path hako's author expects to be built on, so the reused
  `encode-into!` is what appears here, in both its copy-out and no-copy forms.
  See `hako-tiers`."
  (:require [boring.core :as boring] [s-exp.hako :as hako] [taoensso.nippy :as nippy])
  (:import (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)
           (java.lang.foreign MemorySegment ValueLayout)
           (org.replikativ.boring Reader)))

(defn seg->bytes ^bytes [^MemorySegment seg]
  (let [n (.byteSize seg)
        arr (byte-array n)]
    (MemorySegment/copy seg ValueLayout/JAVA_BYTE 0 arr 0 n)
    arr))

(def ^ThreadMXBean tmx (ManagementFactory/getThreadMXBean))
(defn allocated ^long [] (.getThreadAllocatedBytes tmx (.getId (Thread/currentThread))))

(defn bytes-per-op [f n]
  (dotimes [_ 20000] (f))                       ; warm + let JIT settle
  (System/gc)
  (let [before (allocated)]
    (dotimes [_ n] (f))
    (long (/ (- (allocated) before) n))))

(def payloads
  [["small-map"      {:name "Alice" :tags #{:a :b :c} :score 42}]
   ["datom-maps-200" (vec (for [i (range 200)]
                            {:e (+ 100 i) :a :user/name :v (str "person-" i)
                             :tx (+ 536870912 i) :added true}))]
   ["long-vec-1k"    (vec (range 1000))]
   ["long-array-1k"  (long-array (range 1000))]])

(defn -main [& _]
  (let [w (boring/writer 65536) hw (hako/writer 65536)
        rdr (Reader. (byte-array 1)) n 50000]
    (println "ENCODE — bytes allocated per op (heap only; see ns docstring)\n")
    (println (format "%-16s %12s %12s %12s %12s %12s" "payload"
                     "boring-into!" "hako-into!+cp" "boring-buffered" "hako-into!" "nippy-fast"))
    (doseq [[nm v] payloads]
      (println (format "%-16s %12d %12s %12d %12s %12s" nm
                       (bytes-per-op #(boring/encode-into! w v) n)
                       (try (str (bytes-per-op #(seg->bytes (hako/encode-into! hw v)) n))
                            (catch Throwable _ "n/a"))
                       (bytes-per-op #(boring/encode-buffered! w v) n)
                       (try (str (bytes-per-op #(hako/encode-into! hw v) n)) (catch Throwable _ "n/a"))
                       (try (str (bytes-per-op #(nippy/fast-freeze v) n)) (catch Throwable _ "n/a"))))
      (flush))
    (println "\nDECODE — bytes allocated per op\n")
    (println (format "%-16s %12s %12s %12s" "payload" "boring" "hako" "nippy-fast"))
    (doseq [[nm v] payloads]
      (let [rb (boring/encode v)
            hb (try (hako/encode v) (catch Throwable _ nil))
            nb (nippy/fast-freeze v)]
        (println (format "%-16s %12d %12s %12s" nm
                         (bytes-per-op #(boring/decode-with rdr rb) n)
                         (if hb (str (bytes-per-op #(hako/decode hb) n)) "n/a")
                         (str (bytes-per-op #(nippy/fast-thaw nb) n))))
        (flush)))))
