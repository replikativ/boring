(ns k7-segments
  "Can boring + k7 be a log you can QUERY?

  k7 is already the log half of a mini-Kafka: preallocated mmap'd segment
  files, CRC-verified recovery, independent consumer groups with crash-safe
  cursors, and -- the part that matters here -- `msg->payload` hands back a
  READ-ONLY ByteBuffer SLICE INTO THE MMAP. Nothing is copied to give it to
  you. What k7 does not have, and does not want, is any opinion about what the
  bytes mean; a payload is a `byte[]` going in and a buffer coming out.

  So the three layers compose without any of them knowing about the others:

      k7          the log        segments, offsets, cursors, recovery
      boring      the payload    CBOR, shaped, indexed
      boring.nav  the query      a field without decoding the record

  and the ONLY glue is `BufferSource`, which is a ByteSource over a ByteBuffer
  and has no k7 in it at all.

  THE QUESTION THIS MEASURES is not whether that works -- it does -- but
  whether ONE EVENT PER MESSAGE is the right framing. It is the obvious one and
  it is what a queue's API invites, but it gives up the two things boring is
  good at: a 5-field map is far too small for an index to pay for, and shapes
  need a COLLECTION of uniform maps and never fire on a single one.

  The alternative is a SEGMENT: batch N events into one message, written as a
  shaped, indexed array. Replay then reads a column instead of a stream of
  records, and a point lookup is a binary search rather than a scan. The cost
  is latency (an event is not readable until its segment closes) and blast
  radius (a partial segment is N events, not one), which is exactly the
  trade-off Kafka makes with its own record batches, so the shape of the answer
  is at least not novel.

  Four ways to hold it, measured side by side:

    per-event / nippy      one event per message, nippy-frozen
    per-event / boring     one event per message, boring
    segment / decode       N events per message, decoded whole
    segment / nav          N events per message, read through nav, no decode

  Run: clojure -M:k7 -m k7-segments"
  (:require [boring.core :as boring]
            [boring.nav :as nav]
            [taoensso.nippy :as nippy]
            [s-exp.k7 :as k7])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (org.replikativ.boring BufferSource)
           (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)))

(set! *warn-on-reflection* true)

(def n-events 50000)
(def segment-size 1000)

(defn- event [i]
  {:e (+ 100 i) :a :user/name :v (str "person-" i)
   :tx (+ 536870912 i) :added true})

(def ^:private store-opts {:shapes true :stringref true})

(defn- tmpdir ^String [nm]
  (str (Files/createTempDirectory nm (make-array FileAttribute 0))))

(def ^:private ^ThreadMXBean tmx (ManagementFactory/getThreadMXBean))
(defn- allocated ^long [] (.getThreadAllocatedBytes tmx (.getId (Thread/currentThread))))

(defmacro timed
  "[millis alloc-bytes result]."
  [& body]
  `(let [a0# (allocated) t0# (System/nanoTime)
         r# (do ~@body)
         t1# (System/nanoTime) a1# (allocated)]
     [(/ (- t1# t0#) 1e6) (- a1# a0#) r#]))

;; THE READ IS WARM AND IT IS A MINIMUM, and both were learned the hard way
;; here.
;;
;; The first version timed ONE COLD PASS per configuration. `segment / nav` and
;; `segment / decode` came out at 21.1 ms against 21.8 -- indistinguishable,
;; contradicting every other measurement in this repo -- because 50 000 events
;; is one pass through a cold JIT and what it timed was compilation.
;;
;; Warming fixed that and left something worse: a single warm pass had nav
;; LOSING to decode, 14.0 ms against 10.4. Isolated outside k7 entirely, on the
;; same 1000-row segment, nav wins every pairing:
;;
;;   nav,    heap byte[] (Reader fast path)     33.6 us
;;   nav,    direct buffer                      48.8 us
;;   decode, heap byte[]                        63.6 us
;;   decode, direct buffer                      80.3 us
;;
;; So the ordering was an artifact. One pass allocates 7-15 MB depending on the
;; path, and a collection triggered by an EARLIER pass lands inside whichever
;; pass happens to be running. The min over several passes is what `ab/ab` takes
;; and for the same reason: the round that lands in a quiet moment is the one
;; that measured the code rather than the machine.
;;
;; The off-heap penalty above is real and worth knowing: ~1.45x for nav, ~1.26x
;; for decode, against the ~1.35x doc/PERFORMANCE.md records for FFM. It does
;; NOT invert the comparison, but it is why `segment / nav, heap` exists as a
;; row -- a full column scan touches most of the bytes, which is where
;; `boring.mmap`'s "navigation touches so few bytes that off-heap hardly
;; matters" stops holding.
;;
;; THE TABLE BELOW HAS nav LOSING TO decode, and nav wins everywhere else. That
;; was chased down; the answer is that IT IS NOT A boring RESULT, and the
;; `payload-scan-only` section at the bottom is the proof. Same 50 payload
;; buffers, same mmap, k7 out of the timed region:
;;
;;   decode           75.2 us/segment
;;   nav              58.6 us/segment       <- nav wins, 1.28x
;;   nav, heap copy   56.1 us/segment
;;
;; End-to-end the same work is nav 9.0 ms against decode 7.9. Subtracting the
;; scans leaves 6.1 ms inside k7 for nav and 4.1 for decode -- for the IDENTICAL
;; drain loop, differing only in the function applied to each payload. `poll!`
;; does adaptive batching, so consumer speed feeds back into the polling path.
;; That asymmetry is k7's, and the two columns should be read as END-TO-END
;; THROUGHPUT, not as codec numbers.
;;
;; Ruled out along the way, each by measurement rather than argument:
;;
;;   JIT           8 passes, half discarded. The first version of this file
;;                 timed one cold pass and had nav and decode indistinguishable
;;                 at 21 ms; what it measured was compilation.
;;   GC            min over the measured passes, not the last one. A pass
;;                 allocates 7-15 MB and a collection triggered by an earlier
;;                 pass lands inside whichever pass is running.
;;   mmap pages    bench/source_shapes.clj, one source shape per JVM: an mmap'd
;;                 read-only slice costs 38.1 us against 39.2 for anonymous
;;                 `allocateDirect`. Identical. This was the leading hypothesis
;;                 and it is wrong.
;;   megamorphism  polluting BufferSource's call site with four ByteBuffer
;;                 implementation classes first costs ~8%, 38.1 -> 41.0. Real,
;;                 and the reason source_shapes runs one shape per process, but
;;                 far too small to matter here.
;;   FFM           SegmentSource over the SAME mapping costs 40.3 against
;;                 BufferSource's 38.1. There is no bounds-check win waiting on
;;                 the foreign-memory path; the ByteBuffer one is already at
;;                 parity.
;;
;; WHAT IS REAL: the off-heap penalty is ASYMMETRIC. nav pays 1.55x moving from
;; byte[] to mmap (24.6 -> 38.1 us), decode pays 1.32x (54.6 -> 72.2), because
;; nav does more small reads per byte of useful output and so feels the
;; interface more. nav still wins 1.9x in every shape.
;;
;; AND THE DESIGN CONSEQUENCE, which is why `nav, heap copy` is a row: zero-copy
;; is worth it for SELECTIVE access and not for full scans. A point read off
;; mmap costs 1.87 us against 1.60 on heap, so copying 26 KB to reach it would
;; cost far more than it saves. A full column scan is the opposite -- the heap
;; copy measured 56.1 us against 58.6 zero-copy, so the copy pays for itself.
(def read-passes 8)

;; ------------------------------------------------------------------ writing

(defn- fill-per-event!
  "One event per message. `enqueue!` takes a byte[], so each event is encoded
  on its own -- no index (a 5-field map is far below the threshold and one
  would cost more than it saves) and no shape (there is no collection to
  shape). Returns the total payload bytes written."
  ^long [q encode]
  (loop [i 0 n 0]
    (if (= i n-events)
      n
      (let [^bytes bs (encode (event i))]
        (k7/enqueue! q bs)
        (recur (inc i) (+ n (alength bs)))))))

(defn- fill-segments!
  "N events per message, as one shaped indexed array."
  ^long [q]
  (reduce (fn [^long n batch]
            (let [^bytes bs (boring/encode-indexed (vec batch) store-opts)]
              (k7/enqueue! q bs)
              (+ n (alength bs))))
          0
          (partition-all segment-size (map event (range n-events)))))

;; ------------------------------------------------------------------ reading

(defn- drain
  "Every message from offset 0, through `f`, acking as it goes. `poll!` returns
  a vector, so the batch is materialised either way -- what differs is what `f`
  does with each payload.

  `seek!` to 0 first, so the same consumer group can replay the log for a warm
  pass. That is also the operation a real replay is, which is the point."
  ^long [cg f]
  (k7/seek! cg 0)
  (loop [acc 0]
    (let [batch (k7/poll! cg {:max-batch 256 :timeout-ms 1})]
      (if (empty? batch)
        acc
        (recur (long (reduce (fn [^long a m]
                               (let [r (long (f (k7/msg->payload m)))]
                                 (k7/ack! cg m)
                                 (+ a r)))
                             (long acc) batch)))))))

(defn- sum-column-decoding
  "Decode the whole segment, then sum one field. What a store without
  navigation must do."
  ^long [payload]
  (reduce (fn [^long a r] (+ a (long (:e r))))
          0 (boring/decode (BufferSource/of payload))))

(defn- sum-column-nav
  "Sum one column through the shape. The payload is never copied out of the
  mmap and no row is ever built."
  ^long [payload]
  (let [s (nav/source (BufferSource/of payload) nil)
        sh (nav/shape s (nav/root-offset s))
        col (nav/shape-column sh :e)]
    (long (nav/reduce-at s (nav/shape-rows sh)
                         (fn [^long acc ^long ro]
                           (+ acc (nav/long-at s (nav/nth-offset s ro col))))
                         0))))

(defn- sum-column-nav-heap
  "The same scan, but over a HEAP COPY of the payload.

  Deliberately the slower-looking option -- it allocates and copies the very
  bytes the zero-copy path exists to avoid -- and it is here because the
  zero-copy path lost to `boring/decode` on this workload and that needed
  explaining rather than reporting. `Reader` branches on a byte[] and skips the
  ByteSource interface entirely when it has one; a read-only DIRECT buffer has
  no array, so every byte a scan touches becomes a virtual call. Navigation
  usually shrugs that off because it touches so few bytes -- but a full column
  scan touches most of them, which is exactly where the reasoning in
  `boring.mmap`'s docstring stops applying."
  ^long [payload]
  (let [s (nav/source (k7/payload->bytes payload) nil)
        sh (nav/shape s (nav/root-offset s))
        col (nav/shape-column sh :e)]
    (long (nav/reduce-at s (nav/shape-rows sh)
                         (fn [^long acc ^long ro]
                           (+ acc (nav/long-at s (nav/nth-offset s ro col))))
                         0))))

(defn- sum-one-nippy ^long [payload] (long (:e (nippy/fast-thaw (k7/payload->bytes payload)))))
(defn- sum-one-boring ^long [payload] (long (:e (boring/decode (BufferSource/of payload)))))

;; -------------------------------------------------------------------- report

(defn- payload-scan-only
  "Collect every payload buffer FIRST, then scan them with k7 out of the timed
  region entirely.

  This is the experiment that decides #49. `bench/source_shapes.clj` measures
  one payload, reused, and gets nav 38 us against decode 72 -- nav winning by
  1.9x on exactly the memory k7 hands over, mmap'd and read-only. The end-to-end
  table gets the opposite. Everything between the two is either k7's polling,
  which the floor row measures, or the fact that end-to-end walks 50 DISTINCT
  buffers where the isolation walks one.

  So: same 50 distinct buffers, no poll, no ack, no CRC."
  [dir]
  (let [q (k7/queue dir {:fsync-strategy :async})
        cg (k7/consumer-group q "collector")
        _ (k7/seek! cg 0)
        payloads (loop [acc []]
                   (let [b (k7/poll! cg {:max-batch 256 :timeout-ms 1})]
                     (if (empty? b)
                       acc
                       (recur (into acc (map k7/msg->payload) b)))))
        run (fn [f] (reduce (fn [^long a p] (+ a (long (f p)))) 0 payloads))]
    (println (format "\ncollected %d payload buffers; scanning with k7 out of the loop"
                     (count payloads)))
    (doseq [[label f] [["decode" sum-column-decoding]
                       ["nav" sum-column-nav]
                       ["nav, heap copy" sum-column-nav-heap]]]
      (dotimes [_ 20] (run f))
      (let [best (apply min (repeatedly 10 #(first (timed (run f)))))]
        (println (format "  %-16s %7.2f ms   %7.1f us/segment"
                         label best (/ (* best 1000.0) (count payloads))))))
    (k7/close-consumer-group! cg)
    (k7/close-queue! q)))

(defn -main [& _]
  (println (format "%d events, segments of %d, read pass %d of %d (warm)\n"
                   n-events segment-size read-passes read-passes))
  (println (format "%-22s %9s %12s %9s %14s %10s"
                   "" "write ms" "payload B" "read ms" "read alloc B" "ns/event"))

  ;; THE FLOOR: poll and ack every message, touching no payload. Whatever this
  ;; costs is charged to every row of the table below and is not a codec
  ;; number. Printed rather than subtracted, because subtracting invites
  ;; treating the remainder as if it were measured.
  (let [dir (tmpdir "k7floor")
        q (k7/queue dir {:fsync-strategy :async})
        _ (fill-segments! q)
        _ (k7/close-queue! q)
        q2 (k7/queue dir {:fsync-strategy :async})
        cg (k7/consumer-group q2 "reader")
        _ (dotimes [_ (quot read-passes 2)] (drain cg (fn [_] 0)))
        floor (apply min (repeatedly (quot read-passes 2)
                                     #(first (timed (drain cg (fn [_] 0))))))]
    (k7/close-consumer-group! cg)
    (k7/close-queue! q2)
    (println (format "k7 floor (poll + ack, %d segment messages, no payload read): %.1f ms\n"
                     (quot n-events segment-size) floor)))

  (let [expected (reduce + (map #(+ 100 %) (range n-events)))]
    (doseq [[label fill read-fn]
            [["per-event / nippy" #(fill-per-event! % nippy/fast-freeze) sum-one-nippy]
             ["per-event / boring" #(fill-per-event! % (fn [v] (boring/encode v))) sum-one-boring]
             ["segment / decode" fill-segments! sum-column-decoding]
             ["segment / nav" fill-segments! sum-column-nav]
             ["segment / nav, heap" fill-segments! sum-column-nav-heap]]]
      (let [dir (tmpdir "k7seg")
            q (k7/queue dir {:fsync-strategy :async})
            [wms _ payload-bytes] (timed (fill q))
            _ (k7/close-queue! q)
            q2 (k7/queue dir {:fsync-strategy :async})
            cg (k7/consumer-group q2 "reader")
            ;; Half the passes warm, the rest measured; report the MIN. See
            ;; `read-passes`.
            _ (dotimes [_ (quot read-passes 2)] (drain cg read-fn))
            passes (doall (repeatedly (quot read-passes 2)
                                      #(timed (drain cg read-fn))))
            [rms ralloc total] (apply min-key first passes)]
        (k7/close-consumer-group! cg)
        (k7/close-queue! q2)
        (when-not (= total expected)
          (println (format "  !! %s summed %d, expected %d" label total expected)))
        (println (format "%-22s %9.1f %12d %9.1f %14d %10.0f"
                         label wms (long payload-bytes) rms ralloc
                         (/ (* rms 1e6) n-events)))
        (flush)))
    (println "\nAll four sum the same column over the same events; a mismatch")
    (println "prints above and none should.")
    (println "\n`payload B` is the bytes handed to enqueue!, not the file size --")
    (println "k7 preallocates 256 MB segments, so the directory is 256 MB either way."))

  ;; #49: the same payloads, scanned with k7 out of the timed region.
  (let [dir (tmpdir "k7scan")
        q (k7/queue dir {:fsync-strategy :async})]
    (fill-segments! q)
    (k7/close-queue! q)
    (payload-scan-only dir)))
