(ns alloc
  "Bytes allocated per op — the axis every timing benchmark in this project has
  ignored. Timing shows throughput; allocation shows GC pressure and tail
  latency, which is what actually bites a database under sustained load.
  Method follows hako's: ThreadMXBean.getThreadAllocatedBytes over N ops."
  (:require [boring.core :as boring] [s-exp.hako :as hako] [taoensso.nippy :as nippy])
  (:import (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)
           (org.replikativ.boring Reader)))

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
  (let [w (boring/writer 65536) rdr (Reader. (byte-array 1)) n 50000]
    (println "ENCODE — bytes allocated per op\n")
    (println (format "%-16s %12s %12s %12s %12s" "payload" "boring-into!" "boring-buffered" "hako" "nippy-fast"))
    (doseq [[nm v] payloads]
      (println (format "%-16s %12d %12d %12s %12s" nm
                       (bytes-per-op #(boring/encode-into! w v) n)
                       (bytes-per-op #(boring/encode-buffered! w v) n)
                       (try (str (bytes-per-op #(hako/encode v) n)) (catch Throwable _ "n/a"))
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
