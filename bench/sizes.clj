(ns sizes
  "Payload SIZE across codecs, with and without general-purpose compression.

  Sizes are deterministic, so unlike the timing benchmarks this one is
  trustworthy on a busy machine.

  Two questions it exists to answer:

    1. How much bigger is a faithful CBOR encoding than the Clojure-specific
       formats (nippy, fressian, hako) that are free to invent their own
       caching?
    2. Does general-purpose compression close that gap — i.e. is CBOR's
       overhead redundancy that a compressor can find, or real information?

  nippy is measured BOTH ways on purpose: `freeze` applies LZ4 above a size
  threshold by default, so comparing boring's raw bytes against nippy's default
  output would be comparing a codec against a codec-plus-compressor."
  (:require [boring.core :as boring]
            [clj-cbor.core :as cbor]
            [clojure.data.fressian :as fress]
            [s-exp.hako :as hako]
            [taoensso.nippy :as nippy])
  (:import [java.io ByteArrayOutputStream]
           [java.util.zip GZIPOutputStream Deflater DeflaterOutputStream]
           [net.jpountz.lz4 LZ4Factory]))

;; --------------------------------------------------------------------------
;; Payloads — the shapes that actually matter for datahike, plus controls.
;; --------------------------------------------------------------------------

(def payloads
  [["datom-vec-1k"
    (vec (for [i (range 1000)]
           [(+ 100 i)
            (nth [:person/name :person/age :person/email :person/city :person/friend]
                 (mod i 5))
            (if (even? i) (str "person-" i) (long (* i 37)))
            (+ 536870912 (quot i 20))
            true]))]
   ["datom-maps-200"
    (vec (for [i (range 200)]
           {:e (+ 100 i) :a :user/name :v (str "person-" i)
            :tx (+ 536870912 i) :added true}))]
   ["nested-map-50"
    (into {} (for [i (range 50)]
               [(keyword (str "field-" i)) {:v i :s (str "val-" i)}]))]
   ["long-vec-1k" (vec (range 1000))]
   ["string-100" (apply str (repeat 10 "0123456789"))]
   ["small-map" {:name "Alice" :tags #{:a :b :c} :score 42}]])

;; --------------------------------------------------------------------------
;; Codecs
;; --------------------------------------------------------------------------

(defn- fressian-bytes [v]
  (let [baos (ByteArrayOutputStream.)
        w (fress/create-writer baos)]
    (fress/write-object w v)
    (.flush baos)
    (.toByteArray baos)))

(def codecs
  [["boring"        #(boring/encode %)]
   ["boring-nosr"   #(boring/encode % {:stringref false})]
   ["boring-shapes" #(boring/encode % {:shapes true})]
   ["fressian"      fressian-bytes]
   ["nippy-raw"     #(nippy/freeze % {:compressor nil})]
   ["nippy"         #(nippy/freeze %)]
   ["hako"          #(hako/encode %)]
   ["clj-cbor"      #(cbor/encode %)]])

;; --------------------------------------------------------------------------
;; Compression — the question is whether CBOR's overhead is redundancy.
;;
;; THREE compressors, not one, because they are not interchangeable and the
;; answer differs by which you pick:
;;
;;   lz4      what konserve actually applies to a blob. Very fast, weak.
;;   deflate  what a websocket applies via permessage-deflate.
;;   gzip     deflate plus an 18-byte header; shown to keep the comparison
;;            honest for anyone quoting gzip numbers on small payloads, where
;;            that header dominates.
;; --------------------------------------------------------------------------

(def ^:private lz4-c (.fastCompressor (LZ4Factory/fastestInstance)))
(def ^:private lz4-hc (.highCompressor (LZ4Factory/fastestInstance)))

(defn- lz4 ^bytes [^bytes bs]
  (.compress lz4-c bs))

(defn- lz4-high ^bytes [^bytes bs]
  (.compress lz4-hc bs))

(defn- gzip ^bytes [^bytes bs]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [g (GZIPOutputStream. baos)] (.write g bs))
    (.toByteArray baos)))

(defn- deflate-raw ^bytes [^bytes bs]
  ;; Raw DEFLATE at max level, no gzip header -- closer to what a transport
  ;; (permessage-deflate) or a store's block compressor would actually apply.
  (let [baos (ByteArrayOutputStream.)
        d (Deflater. 9 true)]
    (with-open [o (DeflaterOutputStream. baos d)] (.write o bs))
    (.toByteArray baos)))

(defn- n-of [f v] (try (alength ^bytes (f v)) (catch Throwable e (str "ERR"))))
(defn- cn [f v g] (try (alength ^bytes (g (f v))) (catch Throwable _ "ERR")))

(defn- pct [x base]
  (if (and (number? x) (number? base) (pos? base))
    (format "%+6.1f%%" (* 100.0 (/ (- x base) (double base))))
    "     -"))

(defn -main [& _]
  (doseq [[label v] payloads]
    (println)
    (println (str "=== " label " ==="))
    (printf "%-14s %9s %9s %9s %9s   %8s%n"
            "codec" "raw" "lz4" "lz4-hc" "deflate" "vs boring")
    (println (apply str (repeat 64 \-)))
    (let [rows (for [[cname f] codecs]
                 {:name cname :raw (n-of f v)
                  :lz4 (cn f v lz4) :hc (cn f v lz4-high)
                  :def (cn f v deflate-raw) :gz (cn f v gzip)})
          boring-raw (:raw (first (filter #(= "boring" (:name %)) rows)))
          best (apply min (filter number? (map :raw rows)))]
      (doseq [{:keys [name raw lz4 hc def]} rows]
        (printf "%-14s %9s %9s %9s %9s   %8s%n"
                name (str raw) (str lz4) (str hc) (str def)
                (pct raw boring-raw)))
      (let [pick (fn [n k] (k (first (filter #(= n (:name %)) rows))))]
        (println)
        (printf "  boring vs fressian:  raw %s   lz4 %s   lz4-hc %s   deflate %s%n"
                (pct (pick "boring" :raw) (pick "fressian" :raw))
                (pct (pick "boring" :lz4) (pick "fressian" :lz4))
                (pct (pick "boring" :hc)  (pick "fressian" :hc))
                (pct (pick "boring" :def) (pick "fressian" :def)))
        (printf "  boring vs nippy-raw: raw %s   lz4 %s   lz4-hc %s   deflate %s%n"
                (pct (pick "boring" :raw) (pick "nippy-raw" :raw))
                (pct (pick "boring" :lz4) (pick "nippy-raw" :lz4))
                (pct (pick "boring" :hc)  (pick "nippy-raw" :hc))
                (pct (pick "boring" :def) (pick "nippy-raw" :def))))))
  (println)
  (shutdown-agents))
