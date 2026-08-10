(ns capability
  "The table that is not about codec speed.

  `bench.clj` and `hako-ab` measure encode and decode -- how fast a codec turns
  a value into bytes and back. On those, boring, hako and nippy are within a
  factor of two or three of each other, and which one wins depends on the
  payload. Nothing there tells a user why they would choose one.

  THIS MEASURES SOMETHING ONLY ONE OF THE THREE CAN DO: reading a field out of
  stored bytes WITHOUT materialising the value they belong to. hako's public
  API is `decode`, `decode-into!` and `decode-many`; nippy's is `thaw`. There
  is no partial read, no cursor, no early stop -- `decode-many` returns a
  PersistentVector, checked, not a lazy seq. To see one field of one row, both
  must build every row.

  So the comparison is not a handicap match, it is the question a store asks.
  konserve-lmdb holds 20k blobs and wants one field from each; a log holds a
  million events and wants one column. Decoding the other 99% is the cost being
  compared, and it is the reason `boring.nav` exists.

  THREE OPERATIONS, chosen because they are what a query engine does:

    point     one field of one row, by position
    column    one field of EVERY row, reduced -- the map-reduce case
    filter    scan a column, and project a second field only where it matches

  WHAT IS TIER-MATCHED, and this is where a benchmark like this usually cheats:

    - The reader is built ONCE and reused, for all three. boring gets a
      `nav/source`, hako gets a `hako/reader`, and both are outside the timing
      loop. nippy has no reusable reader -- `fast-thaw` wraps a fresh
      ByteBuffer per call and there is no API to avoid it -- so its column is
      the fastest thing nippy can be asked to do, not a penalty.

    - The SHAPE is built inside the point-read loop (one per read, which is
      what a one-shot read pays) and outside the scan loops (one per scan,
      amortised over every row, which is what a scan pays). Neither is a
      favour; both are what the calling code would do.

    - `boring decode` is a column too. Most of the win here is NAVIGATION, not
      the codec, and separating them is the only way to say so honestly: on the
      column scan boring's own full decode loses to hako's.

  Run: clojure -M:bench -m capability [no-warmup]"
  (:require [boring.core :as boring]
            [boring.nav :as nav]
            [s-exp.hako :as hako]
            [taoensso.nippy :as nippy]
            [ab :refer [ab]])
  (:import (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)))

(set! *warn-on-reflection* true)

;; A datom-shaped row: what datahike stores and what konserve-lmdb holds most
;; of. Five fields, mixed types, one of them a repeated keyword -- so stringref
;; and shapes both have something to do.
(defn- table [n]
  (vec (for [i (range n)]
         {:e (+ 100 i) :a :user/name :v (str "person-" i)
          :tx (+ 536870912 i) :added true})))

(def sizes [200 1000 5000])

;; THE `:store` PROFILE: indexed, shaped, stringref'd. `encode-indexed` because
;; the point read is what the index is for, and the other two because they are
;; the density -- and, contrary to what an earlier version of this file assumed,
;; they COMPOSE rather than compete. On the 200-row table:
;;
;;   plain                12613 B
;;   stringref only       10037 B
;;   shapes only           6648 B
;;   shapes + stringref    5063 B      hako: 5165 B
;;
;; Shapes hoist the KEYS out of every row; stringref then dedupes the repeated
;; VALUES that remain (`:user/name`, 200 times). Neither subsumes the other, and
;; only together do they land under hako. All four are navigable -- combining
;; stringref with an index is what #7 was for.
(def ^:private store-opts {:shapes true :stringref true})

;; --------------------------------------------------------------- allocation

(def ^:private ^ThreadMXBean tmx (ManagementFactory/getThreadMXBean))

(defn- allocated ^long []
  (.getThreadAllocatedBytes tmx (.getId (Thread/currentThread))))

(defn- bytes-per-op ^long [f ^long n]
  (dotimes [_ 20000] (f))
  (System/gc)
  (let [before (allocated)]
    (dotimes [_ n] (f))
    (quot (- (allocated) before) n)))

;; ------------------------------------------------------------- the operations
;;
;; Every implementation of an operation returns the SAME value, and `-main`
;; checks that before timing anything. A benchmark whose two sides compute
;; different things is the failure mode this file would otherwise invite: it is
;; very easy to make the navigating side skip work the decoding side does.

(defn- b-point
  "One field of one row, through the offset layer. The shape is built here
  rather than hoisted -- a one-shot read pays for it."
  [src ^long i]
  (let [sh (nav/shape src (nav/root-offset src))
        row (nav/nth-offset src (nav/shape-rows sh) i)]
    (nav/value-at src (nav/nth-offset src row (nav/shape-column sh :v)))))

(defn- b-column
  "Sum one integer column. The shape and the column index leave the loop --
  that is the entire point of the encoding, and an API that cannot express it
  gives up the density it just paid for."
  ^long [src]
  (let [sh (nav/shape src (nav/root-offset src))
        col (nav/shape-column sh :e)
        rows (nav/shape-rows sh)]
    (long (nav/reduce-at src rows
                         (fn [^long acc ^long ro]
                           (+ acc (nav/long-at src (nav/nth-offset src ro col))))
                         0))))

(defn- b-filter
  "Project `:v` where `:e` is divisible by 97. Two columns, one of them read
  only for the rows that pass -- which is the shape of every WHERE clause, and
  the case a full decode cannot avoid paying for."
  [src]
  (let [sh (nav/shape src (nav/root-offset src))
        ce (nav/shape-column sh :e)
        cv (nav/shape-column sh :v)
        rows (nav/shape-rows sh)]
    (nav/reduce-at src rows
                   (fn [acc ^long ro]
                     (if (zero? (rem (nav/long-at src (nav/nth-offset src ro ce)) 97))
                       (conj acc (nav/value-at src (nav/nth-offset src ro cv)))
                       acc))
                   [])))

(defn- v-point [rows ^long i] (:v (nth rows i)))
(defn- v-column ^long [rows] (reduce (fn [^long a r] (+ a (long (:e r)))) 0 rows))
(defn- v-filter [rows]
  (reduce (fn [acc r] (if (zero? (rem (long (:e r)) 97)) (conj acc (:v r)) acc))
          [] rows))

;; ------------------------------------------------------------------- output

(defn- fmt [label a b]
  (format "%-22s %10.2f %10.2f  %8s" label (/ a 1000.0) (/ b 1000.0)
          (format "%.1fx" (/ b a))))

(defn- alloc-row [label ^long a ^long b]
  (format "%-22s %10d %10d  %8s" label a b
          (if (zero? a) "inf" (format "%.1fx" (/ (double b) a)))))

(defn- global-warmup!
  "Every payload through every codec and operation before anything is measured.
  Per-cell warmup is not enough -- see ab/global-warmup!."
  []
  (doseq [n sizes]
    (let [rows (table n)
          bb (boring/encode-indexed rows store-opts)
          hb (hako/encode rows)
          nb (nippy/fast-freeze rows)
          src (nav/source bb nil)
          hrd (hako/reader hb)
          i (quot n 2)]
      (dotimes [_ 2000]
        (b-point src i) (b-column src) (b-filter src)
        (v-point (hako/decode-into! hrd hb) i)
        (v-column (hako/decode-into! hrd hb))
        (v-point (nippy/fast-thaw nb) i)
        (v-column (boring/decode bb))))))

(defn -main [& args]
  (when-not (contains? (set args) "no-warmup")
    (print "global warmup... ") (flush) (global-warmup!) (println "done"))

  (doseq [n sizes]
    (let [rows (table n)
          bb (boring/encode-indexed rows store-opts)
          hb (hako/encode rows)
          nb (nippy/fast-freeze rows)
          src (nav/source bb nil)
          hrd (hako/reader hb)
          i (quot n 2)]

      (println (format "\n\n=== %d rows x 5 fields ===" n))
      ;; `hako/encode` returns a byte[] on some versions and a MemorySegment on
      ;; others, and hard-coding either is a ClassCastException on the other.
      (println (format "sizes: boring %d B (indexed+shaped)   hako %d B   nippy %d B"
                       (count bb)
                       (if (bytes? hb)
                         (alength ^bytes hb)
                         (.byteSize ^java.lang.foreign.MemorySegment hb))
                       (count nb)))

      ;; SAME ANSWER, checked before timing. See the comment above the ops.
      (assert (= (b-point src i) (v-point rows i)
                 (v-point (hako/decode-into! hrd hb) i)
                 (v-point (nippy/fast-thaw nb) i)))
      (assert (= (b-column src) (v-column rows)
                 (v-column (hako/decode-into! hrd hb))))
      (assert (= (b-filter src) (v-filter rows)
                 (v-filter (hako/decode-into! hrd hb))))

      (println "\n-- TIME, µs/op (A/B interleaved, min over 60 rounds) --")
      (println (format "%-22s %10s %10s  %8s" "" "boring" "other" "speedup"))

      (doseq [[label bf of]
              [["point / hako" #(b-point src i) #(v-point (hako/decode-into! hrd hb) i)]
               ["point / nippy" #(b-point src i) #(v-point (nippy/fast-thaw nb) i)]
               ["point / boring decode" #(b-point src i) #(v-point (boring/decode bb) i)]
               ["column / hako" #(b-column src) #(v-column (hako/decode-into! hrd hb))]
               ["column / nippy" #(b-column src) #(v-column (nippy/fast-thaw nb))]
               ["column / boring decode" #(b-column src) #(v-column (boring/decode bb))]
               ["filter / hako" #(b-filter src) #(v-filter (hako/decode-into! hrd hb))]
               ["filter / nippy" #(b-filter src) #(v-filter (nippy/fast-thaw nb))]]]
        (let [[a b] (ab bf of)]
          (println (fmt label a b)) (flush)))

      (println "\n-- ALLOCATION, heap bytes/op (GC pressure; hako's arena is invisible here) --")
      (println (format "%-22s %10s %10s  %8s" "" "boring" "other" "ratio"))
      (doseq [[label bf of]
              [["point / hako" #(b-point src i) #(v-point (hako/decode-into! hrd hb) i)]
               ["column / hako" #(b-column src) #(v-column (hako/decode-into! hrd hb))]
               ["column / nippy" #(b-column src) #(v-column (nippy/fast-thaw nb))]
               ["filter / hako" #(b-filter src) #(v-filter (hako/decode-into! hrd hb))]]]
        (println (alloc-row label (bytes-per-op bf 20000) (bytes-per-op of 20000)))
        (flush))))

  (println "\nNote: hako's allocation column excludes its off-heap arena --")
  (println "these numbers are GC pressure, not total memory traffic."))
