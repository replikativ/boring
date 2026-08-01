(ns cljsbench.baseline
  "Incumbent baseline for the CLJS side: fress vs transit-cljs on the same
  payloads used in the JVM bench, so the two tables line up.

  Runs BEFORE boring has a CLJS implementation — the point is to know the bar
  before designing to it, exactly as nippy/hako/clj-cbor were measured on the
  JVM first.

  fress is measured two ways, because the gap between them is large:
    - `fresh`  : new byte-stream + new writer per call (naive usage)
    - `reused` : one byte-stream, reset between calls (fress's own bench idiom)"
  (:require [fress.api :as fress]
            [fress.impl.buffer :as buf]
            [cognitect.transit :as transit]))

(def now
  (if (exists? js/performance)
    #(js/performance.now)
    #(.now js/Date)))

(defn auto-bench
  "Warm up hard (V8 needs it), then run enough iterations to fill ~300 ms.
  Returns ns/op."
  [f]
  (dotimes [_ 2000] (f))
  (loop [iters 32]
    (let [t0 (now)
          _  (dotimes [_ iters] (f))
          dt (- (now) t0)]
      (if (and (< dt 300) (< iters 50000000))
        (recur (* iters 4))
        (/ (* dt 1e6) iters)))))

;; ---------------------------------------------------------------- payloads

(def payloads
  [["small-map"      {:name "Alice" :tags #{:a :b :c} :score 42}]
   ["mixed"          {:id 7 :n 12345678901 :d 3.14159 :s "hello world" :ok true}]
   ["string-100"     (apply str (repeat 10 "0123456789"))]
   ["nested-map-50"  (into {} (for [i (range 50)]
                                [(keyword (str "field-" i)) {:v i :s (str "val-" i)}]))]
   ["datom-maps-200" (vec (for [i (range 200)]
                            {:e (+ 100 i) :a :user/name :v (str "person-" i)
                             :tx (+ 536870912 i) :added true}))]
   ["datom-vec-1k"   (vec (for [i (range 1000)]
                            [(+ 100 i) :user/name (str "person-" i)
                             (+ 536870912 i) true]))]
   ["long-vec-1k"    (vec (range 1000))]])

;; ---------------------------------------------------------------- fress

(defn fress-encode-fresh [v]
  (let [bs (fress/byte-stream)]
    (fress/write-object (fress/create-writer bs) v)
    @bs))

(def shared-bs (fress/byte-stream))

(defn fress-encode-reused [v]
  (buf/reset shared-bs)
  (fress/write-object (fress/create-writer shared-bs) v)
  @shared-bs)

(defn fress-roundtrip-reused [v]
  (buf/reset shared-bs)
  (fress/write-object (fress/create-writer shared-bs) v)
  (fress/read-object (fress/create-reader shared-bs)))

;; ---------------------------------------------------------------- transit

(def tw (transit/writer :json))
(def tr (transit/reader :json))

;; ---------------------------------------------------------------- run

(def ERR (js-obj))

(defn safe [f] (try (f) (catch :default e (str "ERR " (.-message e)))))

(defn cell [x]
  (.padStart (if (number? x) (.toFixed x 1) (str x)) 12))

(defn pad [s n] (.padEnd (str s) n))

(defn -main [& _]
  (println "node" js/process.version "— ns/op, lower is better")
  (println "(no boring CLJS implementation yet; this is the incumbent bar)\n")

  (println "=== ENCODE ===")
  (println (pad "payload" 17) (cell "fress-fresh") (cell "fress-reuse") (cell "transit"))
  (doseq [[nm v] payloads]
    (println (pad nm 17)
             (cell (safe #(auto-bench (fn [] (fress-encode-fresh v)))))
             (cell (safe #(auto-bench (fn [] (fress-encode-reused v)))))
             (cell (safe #(auto-bench (fn [] (transit/write tw v))))))
    )

  (println "\n=== DECODE ===")
  (println (pad "payload" 17) (cell "fress") (cell "transit"))
  (doseq [[nm v] payloads]
    (let [ts (transit/write tw v)]
      ;; fress decode needs a stream positioned at 0 with the encoded bytes;
      ;; re-encode into the shared stream then read it back.
      (println (pad nm 17)
               (cell (safe #(auto-bench (fn [] (fress-roundtrip-reused v)))))
               (cell (safe #(auto-bench (fn [] (transit/read tr ts))))))))
  (println "  note: the fress DECODE column is encode+decode (round-trip) —")
  (println "        fress's reader consumes the stream, so decode cannot be")
  (println "        isolated without re-encoding. Subtract fress-reuse encode.")

  (println "\n=== SIZE (bytes; transit is a JS string -> UTF-8 length) ===")
  (println (pad "payload" 17) (cell "fress") (cell "transit"))
  (doseq [[nm v] payloads]
    (println (pad nm 17)
             (cell (safe #(alength (fress-encode-fresh v))))
             (cell (safe #(.-length (js/Buffer.from (transit/write tw v) "utf8"))))))

  (println "\n=== ROUND-TRIP CORRECTNESS ===")
  (doseq [[nm v] payloads]
    (println (pad nm 17)
             "fress:" (pad (safe #(= v (fress-roundtrip-reused v))) 7)
             "transit:" (safe #(= v (transit/read tr (transit/write tw v)))))))

(set! *main-cli-fn* -main)
