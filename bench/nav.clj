(ns nav
  "Navigation, in two sections.

    skip    how cheap it is to walk PAST a value without decoding it -- the
            inner loop of any cursor, zipper or get-in over the wire format
    cursor  what the shipped `boring.nav` api delivers against
            decode-then-get-in

  Run: clojure -M:bench -m nav [skip|cursor]

  Encoded :stringref false throughout. With stringref on, skipping a subtree
  still has to register every string inside it, because a later reference is an
  index into that table -- a real constraint on random access, which is why
  boring.nav refuses such documents rather than resolving them wrongly.

  Findings are written up in doc/PERFORMANCE.md."
  (:require [ab :refer [ab]]
            [boring.core :as boring]
            [boring.data]
            [boring.mmap :as mmap]
            [boring.nav :as nav])
  (:import (java.io File FileOutputStream)
           (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.nio.channels FileChannel FileChannel$MapMode)
           (java.nio.file StandardOpenOption)
           (org.replikativ.boring Reader)))

(set! *warn-on-reflection* true)

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

(defn run-skip []
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

(def opts {:stringref false})

(defn- ab-us
  "ab/ab in µs/op rather than ns."
  [fa fb iters rounds]
  (mapv #(/ % 1000.0) (ab fa fb iters rounds)))

(defn- spit-bytes ^File [^bytes bs]
  (let [f (doto (File/createTempFile "boring-nav" ".cbor") .deleteOnExit)]
    (with-open [o (FileOutputStream. f)] (.write o bs))
    f))

(def customers
  (into {} (for [i (range 200)]
             [(str "customer-" i)
              {"name"    (str "Person Number " i)
               "email"   (str "person" i "@example.com")
               "address" (str i " Some Long Street Name, Some City, 12345")
               "notes"   (apply str (repeat 6 "padding text to make values bigger "))
               "id"      (+ 1000000 i)
               "active"  true}])))

(defn run-cursor []
  (let [^bytes bs (boring/encode customers opts)
        c (nav/source bs opts)
        rdr (Reader. bs)
        path ["customer-137" "name"]]

    (println (format "\nstructure: %.1f KB, 200 customers x 6 fields\n"
                     (/ (alength bs) 1024.0)))
    (println (format "%-44s %10s %10s %8s" "" "nav" "decode" "ratio"))

    (let [[a b] (ab-us #(nav/value (get-in c path))
                    #(get-in (do (.reset rdr bs) (.read rdr)) path)
                    200 30)]
      (println (format "%-44s %9.2fµs %9.2fµs %7.1fx"
                       "get-in one leaf (heap)" a b (/ b a))))

    ;; count is read from the head, so it should not depend on size at all
    (let [[a b] (ab-us #(count c)
                    #(count (do (.reset rdr bs) (.read rdr)))
                    200 30)]
      (println (format "%-44s %9.2fµs %9.2fµs %7.1fx"
                       "count the top-level map" a b (/ b a))))

    ;; Scanning every customer's :name -- the reducers case. Still beats a full
    ;; decode because the other five fields per record are never materialised.
    (let [[a b] (ab-us #(reduce (fn [acc e] (+ acc (count (nav/value (get (val e) "name")))))
                             0 c)
                    #(reduce-kv (fn [acc _ v] (+ acc (count (get v "name")))) 0
                                (do (.reset rdr bs) (.read rdr)))
                    50 20)]
      (println (format "%-44s %9.2fµs %9.2fµs %7.1fx"
                       "reduce over all 200, one field each" a b (/ b a))))

    ;; mmap: same lookup, off-heap
    (let [f (spit-bytes bs)
          [mc arena] (mmap/mmap-source f opts)
          ^java.lang.foreign.Arena arena arena]
      (let [[a b] (ab-us #(nav/value (get-in mc path))
                      #(get-in (do (.reset rdr bs) (.read rdr)) path)
                      200 30)]
        (println (format "%-44s %9.2fµs %9.2fµs %7.1fx"
                         "get-in one leaf (mmap'ed file)" a b (/ b a))))
      (.close arena))

    ;; blobs: the case where decoding IS the copy
    (let [payload (byte-array (* 1024 1024))
          blob-doc {"meta" {"id" 7} "data" payload}
          ^bytes bbs (boring/encode blob-doc opts)
          bc (nav/source bbs opts)
          brdr (Reader. bbs)]
      (println (format "\nblob: one 1 MiB bytestring beside a small map\n"))
      (println (format "%-44s %10s %10s %8s" "" "nav" "decode" "ratio"))
      (let [[a b] (ab-us #(nav/byte-span (get bc "data"))
                      #(count ^bytes (get (do (.reset brdr bbs) (.read brdr)) "data"))
                      50 20)]
        (println (format "%-44s %9.2fµs %9.2fµs %7.0fx"
                         "locate the blob vs materialise it" a b (/ b a))))
      (let [[a b] (ab-us #(nav/value (get-in bc ["meta" "id"]))
                      #(get-in (do (.reset brdr bbs) (.read brdr)) ["meta" "id"])
                      50 20)]
        (println (format "%-44s %9.2fµs %9.2fµs %7.0fx"
                         "read a field PAST the blob" a b (/ b a)))))

    ;; The log shape: a CBOR sequence of events, scanned for the few that match.
    ;; This is the case a logging backend actually has, and the one where
    ;; `items` earns its keep -- a filter on one field never builds the other
    ;; four, and `reduced` stops the walk where a decode-everything pass cannot.
    (let [events (vec (for [i (range 5000)]
                        {"lvl" (if (zero? (mod i 500)) "error" "info")
                         "n" i
                         "msg" (str "event number " i)
                         "ctx" {"thread" "main" "ns" "app.core" "line" i}}))
          baos (java.io.ByteArrayOutputStream.)
          ;; opts on the WRITER, not per call: resolving them per event costs
          ;; ~250 B of heap each time, and a navigable log needs :stringref
          ;; false so it cannot use the nil-opts fast path.
          w (boring/writer 8192 opts)
          _ (doseq [e events] (boring/write-to! w e baos))
          ^bytes log-bs (.toByteArray baos)
          xf (comp (filter #(= "error" (nav/value (get % "lvl"))))
                   (map #(nav/value (get % "n"))))]
      (println (format "\nlog: %d events, %.1f KB as a CBOR sequence\n"
                       (count events) (/ (alength log-bs) 1024.0)))
      (println (format "%-44s %10s %10s %8s" "" "nav" "decode" "ratio"))
      (let [[a b] (ab-us #(into [] xf (nav/items log-bs opts))
                      #(into [] (comp (filter (fn [e] (= "error" (get e "lvl"))))
                                      (map (fn [e] (get e "n"))))
                             (boring/decode-seq log-bs opts))
                      5 20)]
        (println (format "%-44s %9.2fµs %9.2fµs %7.1fx"
                         "scan a log for matching events" a b (/ b a))))
      (let [[a b] (ab-us #(reduce (fn [_ c] (reduced (nav/value (get c "n")))) nil
                               (nav/items log-bs opts))
                      #(reduce (fn [_ e] (reduced (get e "n"))) nil
                               (boring/decode-seq log-bs opts))
                      50 20)]
        (println (format "%-44s %9.2fµs %9.2fµs %7.1fx"
                         "first event only (early exit)" a b (/ b a)))))

    (println "\nThe blob rows are the shape that matters most: skipping a bytestring")
    (println "is a jump, not a walk, so the cost of ignoring one does not scale")
    (println "with its size. Everything above is a byte[] source except where")
    (println "noted; off-heap decode costs ~1.35x, which is why the mmap row")
    (println "trails the heap row.")
    (println)
    (println "WHERE NAV LOSES, and it is worth knowing: the early-exit row is")
    (println "SLOWER than decode-seq. decode-seq is lazy, so stopping at the")
    (println "first item already decodes only that item -- and for one small")
    (println "item, a cursor plus a key probe costs more than just decoding it.")
    (println "nav wins by what it SKIPS and what it does not materialise. When")
    (println "you are going to touch nearly everything in a small item anyway,")
    (println "decode is the cheaper call.")))

;; ------------------------------------------------------------------ index
;;
;; The container index across the axes that actually move the answer: how WIDE
;; a container is, how DEEP the nesting goes, and what the workload looks like.
;; Both knobs are shown, because they trade against each other -- `:index-min`
;; is the size knob and `:index` (stride) the lookup knob.

(def ^:private idx-opts {:profile :archival})   ; sorted keys, no stringref

(defn- lookup-sweep [c ks]
  (fn [] (doseq [k ks] (nav/value (get-in c [k "v"])))))

(defn- wide-map [n]
  (into {} (for [i (range n)] [(format "k%06d" i) {"v" i "w" (str "x" i)}])))

(defn- deep-map
  "d levels of nesting, each level a map of `w` keys whose last value descends."
  [d w]
  (reduce (fn [inner _]
            (assoc (into {} (for [i (range (dec w))] [(format "k%04d" i) {"v" i}]))
                   "zzz" inner))
          {"v" 0} (range d)))

(defn run-index []
  (println "\nWIDTH — one container, lookups spread across it")
  (println (format "  %-8s %10s %11s %12s %12s %10s"
                   "keys" "plain KB" "indexed KB" "overhead" "scan us" "indexed us"))
  (doseq [n [100 1000 10000]]
    (let [m (wide-map n)
          plain (boring/encode m idx-opts)
          bs (boring/encode-indexed m (assoc idx-opts :index 16 :index-min 16))
          ks (mapv #(format "k%06d" %) (range 0 n (max 1 (quot n 100))))
          a (timed (lookup-sweep (nav/source plain idx-opts) ks) 5 8)
          b (timed (lookup-sweep (nav/source bs idx-opts) ks) 20 8)]
      (println (format "  %-8d %10.1f %11.1f %11.1f%% %12.2f %10.3f"
                       n (/ (alength ^bytes plain) 1024.0) (/ (alength ^bytes bs) 1024.0)
                       (* 100.0 (/ (- (alength ^bytes bs) (alength ^bytes plain))
                                   (double (alength ^bytes plain))))
                       (/ a (count ks) 1000.0) (/ b (count ks) 1000.0)))
      (flush)))

  (println "\nDEPTH — reaching the innermost value through d levels")
  (println (format "  %-8s %10s %11s %12s %12s" "depth" "plain KB" "overhead" "scan us" "indexed us"))
  (doseq [d [4 16 64]]
    (let [m (deep-map d 64)
          path (vec (concat (repeat d "zzz") ["v"]))
          plain (boring/encode m idx-opts)
          bs (boring/encode-indexed m (assoc idx-opts :index 16 :index-min 16))
          cp (nav/source plain idx-opts) ci (nav/source bs idx-opts)
          a (timed #(nav/value (get-in cp path)) 200 8)
          b (timed #(nav/value (get-in ci path)) 200 8)]
      (println (format "  %-8d %10.1f %11.1f%% %12.3f %12.3f"
                       d (/ (alength ^bytes plain) 1024.0)
                       (* 100.0 (/ (- (alength ^bytes bs) (alength ^bytes plain))
                                   (double (alength ^bytes plain))))
                       (/ a 1000.0) (/ b 1000.0)))
      (flush)))

  (println "\nKNOBS — 5 000 keys; :index-min is the size knob, stride the lookup knob")
  (println (format "  %-12s %-8s %11s %12s" "index-min" "stride" "overhead" "us/lookup"))
  (let [m (wide-map 5000)
        plain (boring/encode m idx-opts)
        ks (mapv #(format "k%06d" %) (range 0 5000 50))]
    (doseq [mn [2 16 128] st [1 16 64]]
      (let [bs (boring/encode-indexed m (assoc idx-opts :index st :index-min mn))]
        (println (format "  %-12d %-8d %10.1f%% %12.3f" mn st
                         (* 100.0 (/ (- (alength ^bytes bs) (alength ^bytes plain))
                                     (double (alength ^bytes plain))))
                         (/ (timed (lookup-sweep (nav/source bs idx-opts) ks) 20 8)
                            (count ks) 1000.0)))
        (flush))))

  (println "\nSEQUENCE STRIDE — 200 000 items; the table SHAPES.md publishes.")
  (println "Slots are DELTA-encoded, so the element width is chosen per slot and")
  (println "the size no longer falls in proportion to the stride: doubling the")
  (println "stride halves the anchor count but can double the width, so there are")
  (println "bands where a denser index is free.")
  (println "Seek and decode are separated because only the seek is the index's")
  (println "doing. Materialising the item is a ~0.3 us floor whatever the stride,")
  (println "which is most of the cost at stride 1 and noise by stride 64 -- so a")
  (println "single nth+value column would understate the stride at one end and")
  (println "be indistinguishable from it at the other.")
  (println (format "  %-8s %10s %12s %11s %11s %10s %12s"
                   "stride" "anchors" "slot type" "index KB" "overhead"
                   "us/seek" "us/nth+val"))
  (let [seq-opts {:stringref false}
        vs (vec (for [i (range 200000)]
                  {"n" i "msg" (str "event " i) "lvl" "info" "ok" (even? i)}))
        build (fn ^bytes [stride]
                (let [w (boring/writer 65536 seq-opts)
                      o (java.io.ByteArrayOutputStream.)]
                  (boring/write-seq! w vs o (cond-> seq-opts
                                              stride (assoc :index stride)))
                  (.toByteArray o)))
        ^bytes plain-bs (build nil)
        plain (alength plain-bs)]
    (println (format "  (data section %d bytes, ~%.1f per item)"
                     plain (/ (double plain) 200000)))
    ;; The baseline the feature exists to remove: reaching the last item means
    ;; skipping the 199 999 before it. Few iterations -- it is milliseconds.
    (let [unindexed (nav/items plain-bs seq-opts)]
      (println (format "  %-8s %10s %12s %11s %11s %10.1f    (no index)"
                       "--" "--" "--" "--" "--"
                       (/ (timed #(nth unindexed 199999) 3 2) 1000.0))))
    (flush)
    (doseq [st [1 8 16 64 256]]
      (let [^bytes bs (build st)
            idx (- (alength bs) plain)
            ;; the slot as it sits on the wire, BEFORE expansion -- its class is
            ;; the width, since the CBOR element type is what declares it
            slot (nth (nth (boring.data/frame-payload
                            (last (vec (boring/decode-seq bs seq-opts)))) 3) 0)
            width (condp instance? slot
                    (Class/forName "[B") "u8 bytes"
                    (Class/forName "[S") "sint16"
                    "sint32")
            items (nav/items bs seq-opts)]
        (println (format "  %-8d %10d %12s %11.1f %10.2f%% %10.3f %12.3f"
                         st (quot 200000 st) width (/ idx 1024.0)
                         (* 100.0 (/ (double idx) plain))
                         (/ (timed #(nth items 199999) 2000 8) 1000.0)
                         (/ (timed #(nav/value (nth items 199999)) 500 8) 1000.0)))
        (flush))))

  (println "\nWRITE COST — building the index is a walk of the encoded bytes")
  (println (format "  %-22s %10s %12s %12s %10s" "payload" "bytes" "encode us" "+index us" "ratio"))
  (doseq [[nm v] [["wide-map-5000" (wide-map 5000)]
                  ["deep-64x64" (deep-map 64 64)]
                  ["datom-maps-200" (vec (for [i (range 200)]
                                           {"e" (+ 100 i) "a" "user/name"
                                            "v" (str "p" i) "tx" (+ 536870912 i)}))]]]
    (let [^bytes bs (boring/encode v idx-opts)
          it (max 5 (quot 2000000 (alength bs)))
          e (timed #(boring/encode v idx-opts) it 8)
          ei (timed #(boring/encode-indexed v (assoc idx-opts :index 16 :index-min 16)) it 8)]
      (println (format "  %-22s %10d %12.1f %12.1f %9.2fx" nm (alength bs)
                       (/ e 1000.0) (/ ei 1000.0) (/ ei e)))
      (flush)))

  (println "\nWHERE IT LOSES, which the DEPTH table shows: an index can be SLOWER.")
  (println "Finding the node is itself a binary search over the container list, per")
  (println "level. When a container is narrow AND its entries are cheap to skip --")
  (println "64 keys whose values are tiny maps -- walking it costs less than looking")
  (println "up how to jump. Raise :index-min above the width of containers like that")
  (println "and they fall back to walking, which is what you want.")
  (println)
  (println "The index is an optimisation: a missing or stale one, or damage that")
  (println "leaves it structurally inconsistent, falls back to walking and returns")
  (println "the same answer. Damage that leaves it CONSISTENT -- bit rot included --")
  (println "can still misdirect; see doc/SHAPES.md. Sorted keys (:canonical /")
  (println ":archival) additionally allow binary search; arrays index positionally")
  (println "under any profile.")

;; ------------------------------------------------------------- write cost
;;
;; What INDEXING costs at write time, against a baseline that is not rigged.
;;
;; Two rules this section exists to enforce, both learned the hard way here.
;;
;; The baseline must be a real `BufferedOutputStream` to a real file. Earlier
;; numbers used a Clojure `proxy` sink, which is slow enough to pad the
;; denominator and quietly shrink every overhead percentage computed from it.
;;
;; Every path is warmed before any path is timed, and the cases interleave. Two
;; warmup iterations and a straight loop put whichever case ran first at a
;; disadvantage large enough to INVERT the ranking -- one run had "no index"
;; slower than a stride-1 index, which is impossible and was the harness.

(defn run-write []
  (let [opts {:stringref false}
        vs (vec (for [i (range 50000)]
                  {"ts" (+ 1700000000 (* i 37)) "lvl" "info" "n" i
                   "msg" (str "event " i)
                   "ctx" {"thread" (str "w" (mod i 8)) "ns" "app.core"}}))
        cases [["no index" opts]
               ["stride 1" (assoc opts :index 1)]
               ["stride 8" (assoc opts :index 8)]
               ["stride 16" (assoc opts :index 16)]
               ["stride 16, min 2" (assoc opts :index 16 :index-min 2)]]
        f (doto (File/createTempFile "boring-write-bench" ".cbor") .deleteOnExit)
        w (boring/writer 65536 opts)
        run (fn [o] (let [t0 (System/nanoTime)]
                      (with-open [out (java.io.BufferedOutputStream.
                                       (FileOutputStream. f) 262144)]
                        (boring/write-seq! w vs out o))
                      (/ (- (System/nanoTime) t0) 1e6)))]
    (println "\nWRITE COST — 50 000 log records, BufferedOutputStream to a file")
    ;; warm EVERY case before timing ANY of them
    (dotimes [_ 8] (doseq [[_ o] cases] (run o)))
    (let [samples (reduce (fn [m _] (reduce (fn [m [label o]]
                                              (update m label (fnil conj []) (run o)))
                                            m cases))
                          {} (range 10))
          base (apply min (samples "no index"))]
      (println (format "  %-20s %9s %9s %10s" "case" "min ms" "median" "vs base"))
      (doseq [[label _] cases]
        (let [xs (vec (sort (samples label)))
              mn (first xs)]
          (println (format "  %-20s %9.2f %9.2f %9.0f%%"
                           label mn (nth xs (quot (count xs) 2))
                           (* 100.0 (/ (- mn base) base))))
          (flush))))
    (println "  (file size" (.length f) "bytes)")))

(defn -main [& args]
  (let [only (set args)
        run? (fn [k] (or (empty? only) (contains? only (name k))))]
    (when (run? :skip) (run-skip))
    (when (run? :cursor) (run-cursor))
    (when (run? :index) (run-index))
    (when (run? :write) (run-write))))
