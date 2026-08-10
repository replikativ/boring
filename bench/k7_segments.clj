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
;; AND THE TABLE BELOW STILL DISAGREES WITH ALL OF THAT, which is stated here
;; rather than smoothed over. Warm, min-over-passes, floor subtracted, nav
;; costs ~190 us per 1000-row segment against decode's ~108. Isolated from k7
;; on the SAME shape of data it costs 48.8 against 80.3 -- the other way round,
;; by about 4x on nav specifically. Ruled out, each by measurement:
;;
;;   JIT           8 passes, half discarded
;;   GC            min over passes, not last-pass
;;   k7 framing    the floor row, 2.1 ms of the read, charged to every row
;;   cache         50 DISTINCT 26 KB segments, 1.3 MB total, is the same
;;                 working set k7 walks -- nav still wins there, 1.42 ms
;;                 against 2.94 heap and 2.20 against 4.11 direct
;;
;; What is NOT ruled out is the mmap'd page path: k7's payload is a window into
;; a 256 MB preallocated segment file, while every isolation above uses
;; `allocateDirect`, which is committed and resident. nav's index-driven jumps
;; touch scattered pages where a linear decode faults one page and uses all of
;; it. That is a hypothesis, not a finding. See the task filed against this
;; file; do not quote the per-segment numbers here as codec numbers until it is
;; settled.
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
    (println "k7 preallocates 256 MB segments, so the directory is 256 MB either way.")))
