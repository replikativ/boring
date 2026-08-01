(ns boring.nippy-bench
  "boring measured on nippy's OWN benchmark, by nippy's own method.

  The point is credibility, not convenience. Our benchmarks choose our
  payloads, so they cannot answer 'did you pick data that suits you'. This one
  uses:

  - nippy's data: `taoensso.nippy/stress-data`, the map its maintainers use to
    define what handling Clojure means;
  - nippy's filter: their `default-bench-data` keeps only the subset that the
    EDN reader AND fressian can both round-trip, so no competitor is scored on
    data it cannot represent. That filter is conservative FOR US -- boring
    handles keys the filter removes -- and it is kept anyway, because the
    alternative is choosing our own subset;
  - nippy's timing loop: `taoensso.encore/bench`, same laps and warmup as
    `taoensso.nippy-benchmarks`.

  Nothing is vendored. nippy is EPL-1.0 and boring Apache-2.0; this calls
  published API only, and lives in the same isolated source root as the stress
  test. Their harness lives in their test tree, which is not on a consumer's
  classpath, so the method is reproduced rather than required.

  Run on a QUIET machine:

      clojure -M:nippy-stress -m boring.nippy-bench"
  (:require [boring.core :as boring]
            [clojure.data.fressian :as fress]
            [taoensso.encore :as enc]
            [taoensso.nippy :as nippy])
  (:import (com.github.luben.zstd Zstd)))

(defn- freeze-fress [x]
  (let [^java.nio.ByteBuffer bb (fress/write x)
        len (.remaining bb)
        ba  (byte-array len)]
    (.get bb ba 0 len)
    ba))

(defn- thaw-fress [^bytes ba] (fress/read (java.nio.ByteBuffer/wrap ba)))

(def bench-data
  "nippy's `default-bench-data`, reproduced: stress-data minus anything the
  reader or fressian cannot round-trip."
  (let [sd (nippy/stress-data {:comparable? true})]
    (reduce-kv
     (fn [m k v]
       (try
         (-> v enc/pr-edn enc/read-edn)
         (-> v freeze-fress thaw-fress)
         m
         (catch Throwable _ (dissoc m k))))
     sd sd)))

(defn- zstd [^bytes ba] (Zstd/compress ba 3))
(defn- unzstd [^bytes ba]
  (let [n (Zstd/decompressedSize ba)
        out (byte-array n)]
    (Zstd/decompress out ba)
    out))

;; nippy's own benchmark reports six rows, so this reports the same six -- an
;; earlier version kept four and dropped nippy/lzma2 and nippy/encrypted, which
;; are the two where nippy's SIZE is strongest.
;;
;; boring's compressed tiers are here for the same reason: nippy/freeze
;; compresses above a size threshold, so a raw-boring-vs-nippy size column
;; compares a codec against a codec-plus-compressor.
(def ^:private codecs
  [["reader"      #(enc/pr-edn %)          #(enc/read-edn %)
    (fn [^String s] (count (.getBytes s "UTF-8")))]
   ["fressian"    freeze-fress             thaw-fress            count]
   ["nippy/fast"  #(nippy/fast-freeze %)   #(nippy/fast-thaw %)  count]
   ["nippy"       #(nippy/freeze %)        #(nippy/thaw %)       count]
   ["nippy/lzma2" #(nippy/freeze % {:compressor nippy/lzma2-compressor})
                  #(nippy/thaw   % {:compressor nippy/lzma2-compressor}) count]
   ["nippy/encr"  #(nippy/freeze % {:password [:cached "p"]})
                  #(nippy/thaw   % {:password [:cached "p"]})     count]
   ["boring"      #(boring/encode %)       #(boring/decode %)    count]
   ["boring :shapes" #(boring/encode % {:shapes true}) #(boring/decode %) count]
   ["boring+zstd" #(zstd (boring/encode %)) #(boring/decode (unzstd %)) count]
   ["boring :shapes+zstd" #(zstd (boring/encode % {:shapes true}))
                          #(boring/decode (unzstd %))             count]
   ["fressian+zstd" #(zstd (freeze-fress %)) #(thaw-fress (unzstd %)) count]])

(defn -main [& _]
  (let [laps 1e4 warmup 25e3
        all  (nippy/stress-data {:comparable? true})]
    (println (format "\nnippy's bench-data: %d of %d stress-data keys survive the\n"
                     (count bench-data) (count all))
             "reader+fressian filter. Excluded:"
             (pr-str (sort (clojure.set/difference (set (keys all)) (set (keys bench-data))))))
    (println (format "\n%-22s %10s %10s %10s %9s" "codec" "freeze µs" "thaw µs" "round µs" "bytes"))
    (println (apply str (repeat 66 \-)))
    (doseq [[nm freeze thaw sizer] codecs]
      (let [r (try
                (let [frozen (freeze bench-data)
                      tf (enc/bench laps {:nlaps-warmup warmup} (freeze bench-data))
                      tt (enc/bench laps {:nlaps-warmup warmup} (thaw frozen))]
                  {:freeze tf :thaw tt :size (sizer frozen)})
                (catch Throwable e {:err (.getMessage e)}))]
        (if (:err r)
          (println (format "%-22s %s" nm (str "ERR " (:err r))))
          (println (format "%-22s %10.1f %10.1f %10.1f %9d" nm
                           (double (:freeze r)) (double (:thaw r))
                           (double (+ (:freeze r) (:thaw r))) (:size r))))
        (flush)))))
