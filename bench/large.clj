(ns large
  "Large-payload and streaming throughput — the scale gap in every earlier
  benchmark, all of which topped out around 34 KB while datahike dumps run to
  hundreds of MB. Reports MB/s, which is the number that matters at scale, plus
  a bounded-memory check for the streaming path."
  (:require [boring.core :as boring] [s-exp.hako :as hako] [taoensso.nippy :as nippy])
  (:import (org.replikativ.boring Reader)
           (java.io ByteArrayOutputStream)))

(defn datoms [n]
  (vec (for [i (range n)]
         {:e (+ 100 i) :a (nth [:user/name :user/email :user/age :db/ident] (mod i 4))
          :v (str "value-" i) :tx (+ 536870912 (quot i 8)) :added (even? i)})))

(defn mbps [bytes-len nanos] (/ (* bytes-len 1000.0) nanos))

(defn timed
  "Min-of-5 wall time for f, in nanos."
  [f]
  (dotimes [_ 3] (f))
  (apply min (for [_ (range 5)]
               (let [t (System/nanoTime)] (f) (- (System/nanoTime) t)))))

(defn -main [& _]
  (println "Large payloads — encode/decode throughput\n")
  (println (format "%-22s %9s %11s %11s %11s %11s"
                   "payload" "size" "boring-enc" "boring-dec" "hako-enc" "hako-dec"))
  (doseq [n [10000 100000 400000]]
    (let [v (datoms n)
          w (boring/writer (* 1024 1024))
          rb (boring/encode v)
          hb (hako/encode v)
          rdr (Reader. (byte-array 1))
          sz (alength rb)]
      (println (format "%-22s %8.1fM %10.1f▲ %10.1f▲ %10.1f▲ %10.1f▲"
                       (str n " datoms") (/ sz 1048576.0)
                       (mbps sz (timed #(boring/encode-buffered! w v)))
                       (mbps sz (timed #(boring/decode-with rdr rb)))
                       (mbps (alength hb) (timed #(hako/encode v)))
                       (mbps (alength hb) (timed #(hako/decode hb)))))
      (flush)))
  (println "  ▲ = MB/s\n")

  (println "Streaming — 200k datoms as a CBOR sequence\n")
  (let [vals (datoms 200000)
        w (boring/writer 65536)
        bos (ByteArrayOutputStream. (* 32 1024 1024))
        t0 (System/nanoTime)
        written (boring/write-seq! w vals bos)
        t1 (System/nanoTime)
        bs (.toByteArray bos)]
    (println (format "  write-seq!   %6.1f MB at %6.1f MB/s"
                     (/ written 1048576.0) (mbps written (- t1 t0))))
    (let [t2 (System/nanoTime)
          cnt (count (boring/decode-seq bs))
          t3 (System/nanoTime)]
      (println (format "  decode-seq   %6d items at %6.1f MB/s" cnt (mbps written (- t3 t2)))))
    ;; bounded memory: reduce over the lazy seq without retaining the head
    (System/gc)
    (let [before (.. (java.lang.management.ManagementFactory/getMemoryMXBean)
                     getHeapMemoryUsage getUsed)
          total (reduce (fn [^long acc m] (+ acc (long (:e m)))) 0 (boring/decode-seq bs))
          after (.. (java.lang.management.ManagementFactory/getMemoryMXBean)
                    getHeapMemoryUsage getUsed)]
      (println (format "  streaming reduce over %d items: heap delta %.1f MB (sum %d)"
                       (count vals) (/ (- after before) 1048576.0) total))
      (println (format "  vs. realising the whole seq: %.1f MB"
                       (let [_ (System/gc)
                             b (.. (java.lang.management.ManagementFactory/getMemoryMXBean)
                                   getHeapMemoryUsage getUsed)
                             all (vec (boring/decode-seq bs))
                             a (.. (java.lang.management.ManagementFactory/getMemoryMXBean)
                                   getHeapMemoryUsage getUsed)]
                         (count all)
                         (/ (- a b) 1048576.0)))))))
