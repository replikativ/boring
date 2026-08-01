(ns compressors
  "Which compressor actually compensates for CBOR's larger raw encoding?

  The answer is not 'compression' generically -- it differs sharply by
  compressor, and the one konserve applies today is the WORST case for us:

    lz4-fast   what konserve's LZ4FrameOutputStream uses. Fast, weak.
    lz4-hc     same library, high-compression mode.
    deflate    what a websocket applies via permessage-deflate.
    zstd-3     zstd's default. Faster than deflate AND smaller.
    zstd-19    zstd at high effort.
    zstd-dict  zstd with a dictionary trained on sample messages.

  The dictionary column is the interesting one and it exists because of a
  property peculiar to a WIRE: messages are small and independent. A compressor
  starts each message with no model, so on a 56-byte frame it has nothing to
  learn from and returns roughly what it was given -- gzip on our small-map is
  LARGER than the input. A shared dictionary hands it the model up front, which
  is exactly the redundancy a Clojure-specific format bakes into its codec."
  (:require [boring.core :as boring]
            [clojure.data.fressian :as fress]
            [taoensso.nippy :as nippy])
  (:import [com.github.luben.zstd Zstd ZstdDictTrainer ZstdCompressCtx ZstdDictCompress]
           [java.io ByteArrayOutputStream]
           [java.util.zip Deflater DeflaterOutputStream]
           [net.jpountz.lz4 LZ4Factory]))

(defn- fressian-bytes ^bytes [v]
  (let [baos (ByteArrayOutputStream.)
        w (fress/create-writer baos)]
    (fress/write-object w v) (.flush baos) (.toByteArray baos)))

(def ^:private lz4f (.fastCompressor (LZ4Factory/fastestInstance)))
(def ^:private lz4h (.highCompressor (LZ4Factory/fastestInstance)))

(defn- deflate ^bytes [^bytes bs]
  (let [baos (ByteArrayOutputStream.) d (Deflater. 9 true)]
    (with-open [o (DeflaterOutputStream. baos d)] (.write o bs))
    (.toByteArray baos)))

(defn- zstd ^bytes [^bytes bs level] (Zstd/compress bs (int level)))

;; --------------------------------------------------------------------------
;; Payload: one wire-sized message, and MANY of them, which is what a peer or a
;; store actually sees. A per-message compressor is measured per message.
;; --------------------------------------------------------------------------

(defn- messages [n]
  (vec (for [i (range n)]
         {:type :sync/publish
          :topic :datahike/store
          :sender #uuid "aaaaaaaa-0000-0000-0000-000000000001"
          :key (keyword (str "node-" i))
          :value {:e (+ 100 i) :a :person/name :v (str "person-" i)
                  :tx (+ 536870912 i) :added true}})))

(defn- total [f coll] (reduce + (map #(alength ^bytes (f %)) coll)))

(defn- train-dict
  "A zstd dictionary trained on the first `k` encoded messages."
  ^bytes [encoded k size]
  (let [t (ZstdDictTrainer. (* 1024 1024) size)]
    (doseq [^bytes b (take k encoded)] (.addSample t b))
    (.trainSamples t)))

(defn- dict-total [encoded ^bytes dict level]
  (let [d (ZstdDictCompress. dict (int level))]
    (with-open [ctx (ZstdCompressCtx.)]
      (.loadDict ctx d)
      (reduce + (map (fn [^bytes b] (alength (.compress ctx b))) encoded)))))

(defn -main [& _]
  (let [msgs (messages 500)
        codecs [["boring"        #(boring/encode %)]
                ["boring-shapes" #(boring/encode % {:shapes true})]
                ["fressian"      fressian-bytes]
                ["nippy-raw"     #(nippy/freeze % {:compressor nil})]]]
    (println)
    (println "500 wire messages, compressed INDEPENDENTLY (total bytes)")
    (println "-- this is what a peer or a per-blob store actually does --")
    (println)
    (printf "%-14s %8s %8s %8s %8s %8s %8s %9s%n"
            "codec" "raw" "lz4" "lz4-hc" "deflate" "zstd-3" "zstd-19" "zstd-dict")
    (println (apply str (repeat 82 \-)))
    (doseq [[cname f] codecs]
      (let [enc (mapv f msgs)
            dict (train-dict enc 200 (* 16 1024))]
        (printf "%-14s %8d %8d %8d %8d %8d %8d %9d%n"
                cname
                (total identity enc)
                (total #(.compress lz4f ^bytes %) enc)
                (total #(.compress lz4h ^bytes %) enc)
                (total deflate enc)
                (total #(zstd % 3) enc)
                (total #(zstd % 19) enc)
                (dict-total enc dict 3))
        (flush))))
  (println)
  (shutdown-agents))
