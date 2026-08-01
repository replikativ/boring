(ns published
  "The exact tables published in README.md and doc/PERFORMANCE.md.

  These existed as numbers with no harness behind them. `str-maps-200` appeared
  in both documents and in no bench file, the column set (boring / boring
  :shapes / hako / nippy) matched no committed namespace, and re-measuring the
  payloads that DID exist reproduced none of the figures -- hako's
  datom-maps-200 size differed by 74%, and we do not modify hako, so the
  payload definitions must have differed too.

  Published numbers nobody can regenerate are the same defect as a comment
  describing code that changed. This is the harness; the payloads are now
  committed rather than implied by a label.

      clojure -M:bench -m published          # everything
      clojure -M:bench -m published size     # deterministic only

  Timing needs a QUIET machine -- see bench/README.md."
  (:require [ab]
            [boring.core :as boring]
            [criterium.core :as crit]
            [s-exp.hako :as hako]
            [clojure.data.fressian]
            [taoensso.nippy :as nippy])
  (:import (com.github.luben.zstd Zstd)
           (org.replikativ.boring Reader)))

(def payloads
  [["small-map"      {:name "Alice" :tags #{:a :b :c} :score 42}]
   ["mixed"          {:id 7 :n 12345678901 :d 3.14159 :s "hello world" :ok true}]
   ["nested-map-50"  (into {} (for [i (range 50)]
                                [(keyword (str "field-" i)) {:v i :s (str "val-" i)}]))]
   ["datom-maps-200" (vec (for [i (range 200)]
                            {:e (+ 100 i) :a :user/name :v (str "person-" i)
                             :tx (+ 536870912 i) :added true}))]
   ["long-vec-1k"    (vec (range 1000))]
   ;; 200 maps over one string-keyed shape. Shapes help here, which is the
   ;; point of the row; the definition was never committed before.
   ["str-maps-200"   (vec (for [i (range 200)]
                            {"id" (+ 100 i) "kind" "person" "name" (str "person-" i)
                             "seq" (+ 536870912 i) "live" true}))]])

(def codecs
  [["boring"          #(boring/encode %) #(boring/decode %)]
   ["boring :shapes"  #(boring/encode % {:shapes true}) #(boring/decode %)]
   ["hako"            #(hako/encode %)   #(hako/decode %)]
   ;; nippy/freeze compresses above a size threshold, so comparing boring's raw
   ;; bytes against it compares a codec with a codec-plus-compressor. fast-freeze
   ;; is the like-for-like codec, and is what the timing rows use.
   ["nippy"           #(nippy/fast-freeze %) #(nippy/fast-thaw %)]])

(defn- t [f] (* 1e6 (first (:mean (crit/quick-benchmark (f) {})))))
(defn- safe [f] (try (f) (catch Throwable e (str "ERR " (.getMessage e)))))
(defn- cell [x] (if (number? x) (format "%.2f" x) (str x)))

(defn sizes []
  (println "\nWire size, bytes:\n")
  (println (str "| payload | " (clojure.string/join " | " (map first codecs)) " |"))
  (println (str "|---|" (apply str (repeat (count codecs) "---:|"))))
  (doseq [[nm v] payloads]
    (println (str "| " nm " | "
                  (clojure.string/join
                   " | " (for [[_ enc _] codecs]
                           (safe #(format "%,d" (alength ^bytes (enc v))))))
                  " |"))))

;; The compressed tier: what you would actually deploy.
;;
;; nippy/freeze compresses by itself above a size threshold, so comparing
;; boring's RAW bytes against nippy's DEFAULT compares a codec against a
;; codec-plus-compressor -- which flatters nippy and is the comparison its own
;; README makes. Putting every codec behind the same compressor is the honest
;; version, and it is also the configuration konserve and kabel actually run.
(defn compressed-sizes []
  (println "\nWire size after compression, bytes:\n")
  (println "| payload | boring+zstd | boring :shapes+zstd | hako+zstd | fressian+zstd | nippy (own LZ4) |")
  (println "|---|---:|---:|---:|---:|---:|")
  (doseq [[nm v] payloads]
    (let [z  (fn [enc] (safe #(format "%,d" (alength (Zstd/compress ^bytes (enc v) 3)))))]
      (println (str "| " nm " | "
                    (z #(boring/encode %)) " | "
                    (z #(boring/encode % {:shapes true})) " | "
                    (z #(hako/encode %)) " | "
                    (z #(let [bb (clojure.data.fressian/write %)
                              n (.remaining ^java.nio.ByteBuffer bb)
                              ba (byte-array n)]
                          (.get ^java.nio.ByteBuffer bb ba 0 n) ba)) " | "
                    (safe #(format "%,d" (alength ^bytes (nippy/freeze v)))) " |")))
    (flush)))

(defn timings []
  (println "\nµs/op, lower is better:\n")
  (println (str "| payload | op | " (clojure.string/join " | " (map first codecs)) " |"))
  (println (str "|---|---|" (apply str (repeat (count codecs) "---:|"))))
  (doseq [[nm v] payloads]
    (println (str "| " nm " | encode | "
                  (clojure.string/join " | " (for [[_ enc _] codecs]
                                               (cell (safe #(t (fn [] (enc v)))))))
                  " |"))
    (flush)
    (println (str "| " nm " | decode | "
                  (clojure.string/join
                   " | " (for [[_ enc dec] codecs]
                           (let [bs (safe #(enc v))]
                             (cell (if (bytes? bs) (safe #(t (fn [] (dec bs)))) bs)))))
                  " |"))
    (flush)))

(defn typed-arrays
  "The 'hand it a primitive array instead of a vector' table in
  doc/PERFORMANCE.md. It was published with no harness behind it, and its
  vector-decode figure had drifted 2.3x from the main table above."
  []
  (let [xs   (vec (range 1000))
        reps [["vector of 1000 ints" xs]
              ["long[] (tag 79)"     (long-array xs)]
              ["int[] (tag 78)"      (int-array xs)]
              ["short[] (tag 77)"    (short-array xs)]]]
    (println "\nOne thousand small integers, four representations:\n")
    (println "| representation | bytes | decode |")
    (println "|---|---:|---:|")
    (doseq [[nm v] reps
            :let [bs (boring/encode v)]]
      (println (format "| %s | %,d | %.2f µs |" nm (count bs)
                       (t #(boring/decode bs)))))
    (let [hb (hako/encode xs)]
      (println (format "| hako vector | %,d | %.2f µs |" (count hb)
                       (t #(hako/decode hb)))))
    (flush)))

(defn -main [& args]
  (let [only (set args)]
    (println "machine:" (System/getProperty "os.name")
             (System/getProperty "java.vm.name") (System/getProperty "java.version"))
    (sizes)
    (compressed-sizes)
    (when (or (empty? only) (contains? only "timing"))
      (print "\nglobal warmup... ") (flush)
      (ab/global-warmup! (boring/writer 65536) (Reader. (byte-array 1)))
      (println "done")
      (timings)
      (typed-arrays))))
