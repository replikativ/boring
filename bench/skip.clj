(ns skip
  "How cheap is SKIPPING a CBOR value without decoding it?

  This is the number that decides whether lazy navigation over a CBOR blob --
  cursor, zipper, get-in, Specter-style navigators -- is worth building. A
  navigator's inner loop is `skip`: to reach the 5th key of a map you parse
  heads and step over the four values you do not want. CBOR maps and arrays
  carry an ELEMENT count, not a byte length, so seeking is a scan, not a jump.
  The scan allocates nothing, but it is O(subtree), and that constant is what
  is measured here.

  Also measured, because it decides the API's shape: the same scanner run over
  three backings -- `byte[]`, a heap `MemorySegment` wrapping that same array,
  and a native mmap'ed segment. If they are at parity, ONE segment-based
  implementation can serve heap buffers and mmap'ed files alike, and boring
  would not need two readers. See doc/PERFORMANCE.md.

  Encoded :stringref false throughout. With stringref on, skipping a subtree
  still has to register every string inside it, because a later reference is an
  index into that table -- so in-place skipping needs extra bookkeeping. That
  is a genuine constraint on random access, not an oversight.

  Run: clojure -M:bench -m skip"
  (:require [boring.core :as boring])
  (:import (java.io File FileOutputStream)
           (java.nio.channels FileChannel FileChannel$MapMode)
           (java.nio.file StandardOpenOption)
           (java.lang.foreign Arena MemorySegment ValueLayout)))

(set! *warn-on-reflection* true)

;; ------------------------------------------------ scanner over a segment

(defn- ub ^long [^MemorySegment s ^long p]
  (bit-and (long (.get s ValueLayout/JAVA_BYTE p)) 0xFF))

(defn- head-arg
  "The head's argument at p: a count, a length, or an immediate."
  ^long [^MemorySegment s ^long p]
  (let [ai (bit-and (ub s p) 31)]
    (cond
      (< ai 24) ai
      (= ai 24) (ub s (+ p 1))
      (= ai 25) (bit-or (bit-shift-left (ub s (+ p 1)) 8) (ub s (+ p 2)))
      (= ai 26) (bit-or (bit-shift-left (ub s (+ p 1)) 24)
                        (bit-shift-left (ub s (+ p 2)) 16)
                        (bit-shift-left (ub s (+ p 3)) 8)
                        (ub s (+ p 4)))
      :else (loop [i 0 acc 0]
              (if (= i 8) acc
                  (recur (inc i) (bit-or (bit-shift-left acc 8) (ub s (+ p 1 i)))))))))

(defn- head-end
  "Offset past the head at p. For major type 7 this already covers the float."
  ^long [^MemorySegment s ^long p]
  (let [ai (bit-and (ub s p) 31)]
    (cond (< ai 24) (+ p 1) (= ai 24) (+ p 2) (= ai 25) (+ p 3)
          (= ai 26) (+ p 5) :else (+ p 9))))

(defn skip-seg
  "Offset just past the whole value at p. Allocates nothing."
  ^long [^MemorySegment s ^long p]
  (let [b (ub s p)
        mt (bit-shift-right b 5)
        nxt (head-end s p)]
    (case (int mt)
      0 nxt
      1 nxt
      2 (+ nxt (head-arg s p))
      3 (+ nxt (head-arg s p))
      4 (let [n (head-arg s p)]
          (loop [i 0 q nxt] (if (= i n) q (recur (inc i) (skip-seg s q)))))
      5 (let [n (head-arg s p)]
          (loop [i 0 q nxt] (if (= i n) q (recur (inc i) (skip-seg s (skip-seg s q))))))
      6 (skip-seg s nxt)
      nxt)))

;; ------------------------------------------------- same scanner, byte[]

(defn- ubb ^long [^bytes a ^long p] (bit-and (long (aget a (int p))) 0xFF))

(defn- head-arg-b ^long [^bytes a ^long p]
  (let [ai (bit-and (ubb a p) 31)]
    (cond
      (< ai 24) ai
      (= ai 24) (ubb a (+ p 1))
      (= ai 25) (bit-or (bit-shift-left (ubb a (+ p 1)) 8) (ubb a (+ p 2)))
      (= ai 26) (bit-or (bit-shift-left (ubb a (+ p 1)) 24)
                        (bit-shift-left (ubb a (+ p 2)) 16)
                        (bit-shift-left (ubb a (+ p 3)) 8)
                        (ubb a (+ p 4)))
      :else (loop [i 0 acc 0]
              (if (= i 8) acc
                  (recur (inc i) (bit-or (bit-shift-left acc 8) (ubb a (+ p 1 i)))))))))

(defn- head-end-b ^long [^bytes a ^long p]
  (let [ai (bit-and (ubb a p) 31)]
    (cond (< ai 24) (+ p 1) (= ai 24) (+ p 2) (= ai 25) (+ p 3)
          (= ai 26) (+ p 5) :else (+ p 9))))

(defn skip-bytes ^long [^bytes a ^long p]
  (let [b (ubb a p)
        mt (bit-shift-right b 5)
        nxt (head-end-b a p)]
    (case (int mt)
      0 nxt
      1 nxt
      2 (+ nxt (head-arg-b a p))
      3 (+ nxt (head-arg-b a p))
      4 (let [n (head-arg-b a p)]
          (loop [i 0 q nxt] (if (= i n) q (recur (inc i) (skip-bytes a q)))))
      5 (let [n (head-arg-b a p)]
          (loop [i 0 q nxt] (if (= i n) q (recur (inc i) (skip-bytes a (skip-bytes a q))))))
      6 (skip-bytes a nxt)
      nxt)))

;; ------------------------------------------------------------- harness

(defn ab
  "Alternating bursts of `iters` calls each, min over rounds. Both sides see
  the same machine conditions -- see bench/ab.clj."
  [fa fb ^long iters ^long rounds]
  (dotimes [_ (* 2 iters)] (fa) (fb))
  (loop [r 0 ba Long/MAX_VALUE bb Long/MAX_VALUE]
    (if (= r rounds)
      [(/ ba (double iters)) (/ bb (double iters))]
      (let [t0 (System/nanoTime) _ (dotimes [_ iters] (fa))
            t1 (System/nanoTime) _ (dotimes [_ iters] (fb))
            t2 (System/nanoTime)]
        (recur (inc r) (min ba (- t1 t0)) (min bb (- t2 t1)))))))

(defn timed
  "ns/op, min over rounds, `iters` calls per round.

  Timing a SINGLE call is meaningless here: nanoTime's own resolution is tens
  of ns and a cheap skip is on that order, so the first version of this fn
  reported 14 550 ns to skip a 180-byte map. Every sample must amortise the
  clock over many calls."
  [f ^long iters ^long rounds]
  (dotimes [_ (* 2 iters)] (f))
  (loop [r 0 best Long/MAX_VALUE]
    (if (= r rounds) (/ best (double iters))
        (let [t0 (System/nanoTime)
              _ (dotimes [_ iters] (f))
              t1 (System/nanoTime)]
          (recur (inc r) (min best (- t1 t0)))))))

(def shapes
  [["small-map-8"    {"name" "Person Number 137" "email" "p137@example.com"
                      "address" "137 Some Long Street, Some City, 12345"
                      "notes" "padding text to make the value bigger"
                      "id" 1000137 "active" true "score" 205.5 "tags" [0 1 2]}]
   ["wide-map-200"   (into {} (for [i (range 200)] [(str "k" i) i]))]
   ["long-vec-1k"    (vec (range 1000))]
   ["text-100"       (apply str (repeat 10 "0123456789"))]
   ["bytes-64k"      (byte-array 65536)]
   ["nested-deep-20" (reduce (fn [acc _] {"x" acc}) {"leaf" 1} (range 20))]
   ["datom-maps-200" (vec (for [i (range 200)]
                            {"e" (+ 100 i) "a" "user/name" "v" (str "person-" i)
                             "tx" (+ 536870912 i) "added" true}))]])

(defn -main [& _]
  (let [arena (Arena/ofShared)]
    (println "\n=== SKIP vs DECODE, per value ===")
    (println "skip = walk the structure, build nothing. decode = materialise it.\n")
    (println (format "%-16s %8s %10s %10s %9s %10s"
                     "shape" "bytes" "skip ns" "decode ns" "ratio" "skip ns/B"))
    (doseq [[nm v] shapes]
      (let [^bytes bs (boring/encode v {:stringref false})
            seg (MemorySegment/ofArray bs)
            len (alength bs)
            rdr (org.replikativ.boring.Reader. (byte-array 1))
            iters (max 200 (long (/ 2000000 (max 1 len))))
            s-ns (timed #(skip-seg seg 0) iters 40)
            d-ns (timed #(do (.reset rdr bs) (.read rdr)) iters 40)]
        (println (format "%-16s %8d %10.0f %10.0f %8.1fx %10.3f"
                         nm len s-ns d-ns (/ d-ns s-ns) (/ s-ns len)))
        (flush)))

    (println "\n=== BACKING: same scanner, three sources ===")
    (println "if these are at parity, one segment-based reader serves both.\n")
    (let [big (vec (for [i (range 2000)]
                     {"e" (+ 100 i) "a" "user/name" "v" (str "person-" i)
                      "tx" (+ 536870912 i) "added" true}))
          ^bytes bs (boring/encode big {:stringref false})
          len (alength bs)
          heap-seg (MemorySegment/ofArray bs)
          f (doto (File/createTempFile "boring-skip" ".cbor") .deleteOnExit)
          _ (with-open [out (FileOutputStream. f)] (.write out bs))
          nat-seg (with-open [ch (FileChannel/open
                                  (.toPath f)
                                  (into-array StandardOpenOption [StandardOpenOption/READ]))]
                    (.map ch FileChannel$MapMode/READ_ONLY 0 (.size ch) arena))]
      (println (format "payload: %d bytes, 2000 maps\n" len))
      (let [[a b] (ab #(skip-bytes bs 0) #(skip-seg heap-seg 0) 200 40)]
        (println (format "  %-30s %9.1f µs" "byte[] + aget" (/ a 1000.0)))
        (println (format "  %-30s %9.1f µs   (%.2fx byte[])" "heap MemorySegment" (/ b 1000.0) (/ b a))))
      (let [[a b] (ab #(skip-bytes bs 0) #(skip-seg nat-seg 0) 200 40)]
        (println (format "  %-30s %9.1f µs   (%.2fx byte[])" "native mmap MemorySegment" (/ b 1000.0) (/ b a))))
      ;; correctness: all three must agree on where the value ends
      (println (format "\n  all three agree on end offset: %s"
                       (= (skip-bytes bs 0) (skip-seg heap-seg 0) (skip-seg nat-seg 0) len))))
    (.close arena)))
