(ns mmap-compress
  "Compression and mmap'ed selective access pull in opposite directions.
  This measures the exchange rate, so the chunk size can be chosen rather
  than guessed.

  WHY THEY CONFLICT. mmap gives demand paging at 4 KiB granularity: touch a
  byte, fault one page. A compressor needs a block larger than that to find
  matches, and a compressed block is only decodable as a whole. So one random
  200-byte record costs you a whole decompressed chunk. Bigger chunk, better
  ratio, worse read amplification. Filesystem compression (btrfs, ZFS) makes
  this transparent but takes the knob away -- btrfs compresses 128 KiB extents,
  ZFS a 128 KiB recordsize by default -- and neither lets the reader say \"this
  file is read one record at a time\".

  Doing it in the application keeps the knob. It also fits what boring already
  emits: a CBOR sequence (RFC 8742) where each item carries its own stringref
  namespace and is independently decodable, so chunk boundaries can fall on
  item boundaries for free.

  The dictionary column is the interesting one. Small chunks lose ratio because
  zstd has no history to match against; a dictionary trained on sample items
  hands that history back, which is exactly what small chunks lack.

  Run: clojure -M:bench -m mmap-compress"
  (:require [boring.core :as boring])
  (:import (com.github.luben.zstd Zstd ZstdDictTrainer ZstdCompressCtx
                                  ZstdDecompressCtx ZstdDictCompress ZstdDictDecompress)
           (org.replikativ.boring Reader)))

(set! *warn-on-reflection* true)

(def ^:const N-ITEMS 200000)

(def payloads
  (vec (for [i (range N-ITEMS)]
         {:e (+ 100 i) :a :user/name :v (str "person-" i)
          :tx (+ 536870912 i) :added true
          :extra (vec (range (mod i 7)))})))

(def encoded
  "Each item as its own byte[]. Chunks are built from these, so every chunk
  boundary is an item boundary."
  (mapv #(boring/encode % {:stringref false}) payloads))

(def total-raw (reduce + (map #(alength ^bytes %) encoded)))

(defn- concat-bytes ^bytes [items]
  (let [n (reduce + (map #(alength ^bytes %) items))
        out (byte-array n)]
    (loop [is items p 0]
      (if (seq is)
        (let [^bytes b (first is)]
          (System/arraycopy b 0 out p (alength b))
          (recur (rest is) (+ p (alength b))))
        out))))

(defn chunkify
  "Group consecutive items into chunks of at least `target` bytes. Returns
  {:chunks [byte[]] :item->chunk int[] :item->off int[]}."
  [^long target]
  (let [chunks (java.util.ArrayList.)
        i->c (int-array N-ITEMS)
        i->o (int-array N-ITEMS)]
    (loop [i 0 acc [] acc-len 0 ci 0 off 0]
      (if (= i N-ITEMS)
        (do (when (seq acc) (.add chunks (concat-bytes acc)))
            {:chunks (vec chunks) :item->chunk i->c :item->off i->o})
        (let [^bytes b (encoded i)
              len (alength b)]
          (aset i->c i ci)
          (aset i->o i off)
          (if (>= (+ acc-len len) target)
            (do (.add chunks (concat-bytes (conj acc b)))
                (recur (inc i) [] 0 (inc ci) 0))
            (recur (inc i) (conj acc b) (+ acc-len len) ci (+ off len))))))))

(defn train-dict ^bytes [^long size]
  (let [t (ZstdDictTrainer. (* 8 1024 1024) size)]
    (doseq [^bytes b (take 8000 encoded)] (.addSample t b))
    (.trainSamples t)))

(defn- timed-lookups
  "ns per random single-item lookup, min over rounds."
  [f ^ints probes ^long rounds]
  (dotimes [_ 3] (dotimes [i (alength probes)] (f (aget probes i))))
  (loop [r 0 best Long/MAX_VALUE]
    (if (= r rounds) (/ best (double (alength probes)))
        (let [t0 (System/nanoTime)
              _ (dotimes [i (alength probes)] (f (aget probes i)))
              t1 (System/nanoTime)]
          (recur (inc r) (min best (- t1 t0)))))))

(defn -main [& _]
  (let [probes (let [r (java.util.Random. 11) a (int-array 5000)]
                 (dotimes [i 5000] (aset a i (.nextInt r N-ITEMS))) a)
        rdr (Reader. (byte-array 1))
        dict (train-dict (* 110 1024))
        scratch (byte-array (* 4 1024 1024))
        item-buf (byte-array 4096)]

    (println (format "\n%d items, %.1f MB raw CBOR, zstd level 3" N-ITEMS
                     (/ total-raw 1048576.0)))
    (println (format "dictionary: %d KB, trained on 8000 sample items\n"
                     (quot (alength dict) 1024)))

    ;; uncompressed baseline: the item is already its own byte[], so this is
    ;; decode with no chunk work at all.
    (let [base (timed-lookups (fn [i] (.reset rdr ^bytes (encoded i)) (.read rdr)) probes 20)]
      (println (format "%-14s %10s %8s %14s %12s"
                       "chunk target" "comp MB" "ratio" "ns/lookup" "vs raw"))
      (println (format "%-14s %10.2f %8s %14.0f %12s"
                       "uncompressed" (/ total-raw 1048576.0) "1.00x" base "1.0x"))

      (doseq [target [4096 16384 65536 262144]]
        (let [{:keys [chunks ^ints item->chunk ^ints item->off]} (chunkify target)
              cchunks (mapv (fn [^bytes c] (Zstd/compress c 3)) chunks)
              comp-total (reduce + (map #(alength ^bytes %) cchunks))
              cvec (vec cchunks)
              dctx (ZstdDecompressCtx.)
              lookup (fn [i]
                       (let [^bytes cc (cvec (aget item->chunk i))
                             _ (.decompressByteArray dctx scratch 0 (alength scratch)
                                                     cc 0 (alength cc))
                             off (aget item->off i)]
                         ;; Reader.reset has no offset arity, so the item's own
                         ;; bytes are copied out. Copying the whole chunk tail
                         ;; instead would charge compression for a gap boring
                         ;; would close with reset(byte[], off, len).
                         (if (zero? off)
                           (do (.reset rdr scratch) (.read rdr))
                           (let [len (alength ^bytes (encoded i))]
                             (System/arraycopy scratch off item-buf 0 len)
                             (.reset rdr item-buf)
                             (.read rdr)))))
              ns (timed-lookups lookup probes 20)]
          (println (format "%-14s %10.2f %7.2fx %14.0f %11.1fx"
                           (str (quot target 1024) " KB")
                           (/ comp-total 1048576.0)
                           (/ (double total-raw) comp-total)
                           ns (/ ns base)))
          (flush)))

      (println "\nwith a trained dictionary (recovers the history small chunks lack):")
      (println (format "%-14s %10s %8s %14s %12s"
                       "chunk target" "comp MB" "ratio" "ns/lookup" "vs raw"))
      (doseq [target [4096 16384 65536]]
        (let [{:keys [chunks ^ints item->chunk ^ints item->off]} (chunkify target)
              cctx (doto (ZstdCompressCtx.) (.loadDict (ZstdDictCompress. dict 3)) (.setLevel 3))
              cchunks (mapv (fn [^bytes c] (.compress cctx c)) chunks)
              comp-total (reduce + (map #(alength ^bytes %) cchunks))
              cvec (vec cchunks)
              dctx (doto (ZstdDecompressCtx.) (.loadDict (ZstdDictDecompress. dict)))
              lookup (fn [i]
                       (let [^bytes cc (cvec (aget item->chunk i))
                             _ (.decompressByteArray dctx scratch 0 (alength scratch)
                                                     cc 0 (alength cc))
                             off (aget item->off i)]
                         (if (zero? off)
                           (do (.reset rdr scratch) (.read rdr))
                           (let [len (alength ^bytes (encoded i))]
                             (System/arraycopy scratch off item-buf 0 len)
                             (.reset rdr item-buf)
                             (.read rdr)))))
              ns (timed-lookups lookup probes 20)]
          (println (format "%-14s %10.2f %7.2fx %14.0f %11.1fx"
                           (str (quot target 1024) " KB")
                           (/ comp-total 1048576.0)
                           (/ (double total-raw) comp-total)
                           ns (/ ns base)))
          (flush))))

    (println "\nWhole-file zstd, for reference (no random access at all):")
    (let [whole (Zstd/compress (concat-bytes encoded) 3)]
      (println (format "  %.2f MB, %.2fx -- but one lookup means decompressing everything"
                       (/ (alength whole) 1048576.0)
                       (/ (double total-raw) (alength whole)))))))
