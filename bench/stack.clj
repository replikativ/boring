(ns stack
  "The honest head-to-head: a konserve blob, serialized AND compressed.

  Every earlier comparison measured a codec against a codec, or a codec against
  nippy's codec-plus-LZ4, which is not the same contest. What a konserve store
  actually does is `(compressor (serializer))` -- so compression time belongs
  inside the encode number and decompression inside the decode number, and the
  baseline to beat is whatever nippy produces with its own defaults.

  nippy appears twice on purpose:
    nippy-fast   fast-freeze/fast-thaw, no compression
    nippy        freeze/thaw, which applies LZ4 above a size threshold

  Quoting boring's raw bytes against nippy's compressed bytes -- or the reverse
  -- is the mistake this table exists to avoid.

  Warmed result, load 1.9 (512 datoms plus metadata):

    config              bytes   enc us   dec us    round
    boring              12369     25.1     19.8     44.9   <- fastest
    boring +zstd         2507     59.1     32.6     91.7   <- smallest
    boring +lz4hc        4767    543.0    155.7    698.6
    fressian            10506    278.4    137.9    416.3
    fressian +zstd       2728    329.4    155.6    485.0
    nippy-fast          17371     97.6     88.5    186.1
    nippy (LZ4)          6341    123.1     84.9    208.0
    nippy +zstd          2615    139.0     95.4    234.4
    hako                10318     23.9     50.9     74.8
    hako +zstd           2785     54.9     64.6    119.5

  Compressed, boring is the smallest and 1.3x faster than the next compressed
  configuration. Uncompressed it ties hako on encode and is 2.6x faster on
  decode.

  The warmup pass matters and is not optional: without it the first rows absorb
  JIT for the shared encode path, and two boring variants that produce
  byte-identical output timed 76% apart. The lz4-hc row also halves with a warm
  JIT (1602 -> 543 us) while staying an order of magnitude off zstd."
  (:require [boring.core :as boring]
            [clojure.data.fressian :as fress]
            [criterium.core :as crit]
            [s-exp.hako :as hako]
            [taoensso.nippy :as nippy])
  (:import [com.github.luben.zstd Zstd]
           [java.io ByteArrayOutputStream]
           [net.jpountz.lz4 LZ4Factory LZ4FrameInputStream LZ4FrameOutputStream
            LZ4FrameOutputStream$BLOCKSIZE LZ4FrameOutputStream$FLG$Bits]
           [net.jpountz.xxhash XXHashFactory]))

;; --------------------------------------------------------------------------
;; Payload: what a konserve blob holds in this stack -- an index node's worth
;; of datoms, plus its metadata.
;; --------------------------------------------------------------------------

(def payload
  {:konserve.core/id #uuid "b0e11a6f-0000-4000-8000-000000000001"
   :branch :db
   :max-tx 536871412
   :datoms (vec (for [i (range 512)]
                  [(+ 100000 i)
                   (nth [:person/name :person/age :person/email :person/city :person/friend]
                        (mod i 5))
                   (if (even? i) (str "person-" i) (long (* i 37)))
                   (+ 536870912 (quot i 20))
                   true]))})

;; --------------------------------------------------------------------------
;; Serializers
;; --------------------------------------------------------------------------

(defn- fress-enc ^bytes [v]
  (let [baos (ByteArrayOutputStream.)
        w (fress/create-writer baos)]
    (fress/write-object w v) (.flush baos) (.toByteArray baos)))

(defn- fress-dec [^bytes bs]
  (fress/read-object (fress/create-reader (java.io.ByteArrayInputStream. bs))))

;; --------------------------------------------------------------------------
;; Compressors, exactly as konserve applies them
;; --------------------------------------------------------------------------

(def ^:private lz4-bits
  (into-array LZ4FrameOutputStream$FLG$Bits
              [LZ4FrameOutputStream$FLG$Bits/BLOCK_INDEPENDENCE]))

(defn- lz4hc ^bytes [^bytes bs]
  (let [baos (ByteArrayOutputStream.)
        o (LZ4FrameOutputStream. baos LZ4FrameOutputStream$BLOCKSIZE/SIZE_4MB (long -1)
                                 (.highCompressor (LZ4Factory/fastestInstance))
                                 (.hash32 (XXHashFactory/fastestInstance))
                                 lz4-bits)]
    (.write o bs) (.close o) (.toByteArray baos)))

(defn- unlz4 ^bytes [^bytes bs]
  (let [in (LZ4FrameInputStream. (java.io.ByteArrayInputStream. bs))
        out (ByteArrayOutputStream.)]
    (.transferTo in out) (.toByteArray out)))

(defn- zstd ^bytes [^bytes bs] (Zstd/compress bs 3))
(defn- unzstd ^bytes [^bytes bs]
  (let [n (Zstd/decompressedSize bs)] (Zstd/decompress bs (int n))))

(def ^:private identity-bytes (fn ^bytes [^bytes bs] bs))

;; --------------------------------------------------------------------------
;; The matrix
;; --------------------------------------------------------------------------

(def configs
  [;; label            encode                       decode                       compress  decompress
   ["boring"           #(boring/encode %)           #(boring/decode %)           identity-bytes identity-bytes]
   ["boring +lz4hc"    #(boring/encode %)           #(boring/decode %)           lz4hc     unlz4]
   ["boring +zstd"     #(boring/encode %)           #(boring/decode %)           zstd      unzstd]
   ["boring shapes"    #(boring/encode % {:shapes true}) #(boring/decode %)      identity-bytes identity-bytes]
   ["boring shapes+zstd" #(boring/encode % {:shapes true}) #(boring/decode %)    zstd      unzstd]
   ["fressian"         fress-enc                    fress-dec                    identity-bytes identity-bytes]
   ["fressian +lz4hc"  fress-enc                    fress-dec                    lz4hc     unlz4]
   ["fressian +zstd"   fress-enc                    fress-dec                    zstd      unzstd]
   ["nippy-fast"       #(nippy/fast-freeze %)       #(nippy/fast-thaw %)         identity-bytes identity-bytes]
   ["nippy (LZ4)"      #(nippy/freeze %)            #(nippy/thaw %)              identity-bytes identity-bytes]
   ["nippy +zstd"      #(nippy/fast-freeze %)       #(nippy/fast-thaw %)         zstd      unzstd]
   ["hako"             #(hako/encode %)             #(hako/decode %)             identity-bytes identity-bytes]
   ["hako +zstd"       #(hako/encode %)             #(hako/decode %)             zstd      unzstd]])

(defn- us [f] (* 1e6 (first (:mean (crit/quick-benchmark (f) {})))))

(defn -main [& _]
  (println)
  (println "One konserve blob: 512 datoms plus metadata.")
  (println "Encode includes compression; decode includes decompression.")
  (println)
  (printf "%-20s %8s %9s %9s %9s%n" "config" "bytes" "enc µs" "dec µs" "round µs")
  (println (apply str (repeat 60 \-)))
  ;; Warm EVERY config before timing ANY of them. Without this the first rows
  ;; pay JIT and class-loading for code paths the later rows then find hot --
  ;; which showed up as two boring variants producing byte-identical output at
  ;; visibly different speeds, i.e. as noise masquerading as a result.
  (doseq [[_ enc dec comp decomp] configs]
    (dotimes [_ 200] (dec (decomp (comp (enc payload))))))
  (let [rows
        (doall
         (for [[label enc dec comp decomp] configs]
           (let [stored (comp (enc payload))
                 back (dec (decomp stored))
                 e (us #(comp (enc payload)))
                 d (us #(dec (decomp stored)))]
             (when-not (= (:max-tx payload) (:max-tx back))
               (println "  !! ROUND TRIP FAILED for" label))
             {:label label :bytes (alength ^bytes stored) :enc e :dec d})))
        best-bytes (apply min (map :bytes rows))
        best-round (apply min (map #(+ (:enc %) (:dec %)) rows))]
    (doseq [{:keys [label bytes enc dec]} rows]
      (printf "%-20s %8d %9.1f %9.1f %9.1f%s%n"
              label bytes enc dec (+ enc dec)
              (str (when (= bytes best-bytes) "  <- smallest")
                   (when (= (+ enc dec) best-round) "  <- fastest"))))
    (println)
    (printf "smallest: %d B    fastest round trip: %.1f µs%n" best-bytes best-round))
  (println)
  (shutdown-agents))
