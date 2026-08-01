(ns cljsbench.compare
  "boring vs the CLJS incumbents. The bar projected before the port was
  transit-cljs, not fress: fress measured 5-10x SLOWER than transit on this
  same harness, so beating fress is not the interesting claim."
  (:require [boring.core :as boring]
            [fress.api :as fress]
            [fress.impl.buffer :as buf]
            [cognitect.transit :as transit]))

(def now #(js/performance.now))

(defn auto-bench [f]
  (dotimes [_ 3000] (f))
  (loop [iters 32]
    (let [t0 (now) _ (dotimes [_ iters] (f)) dt (- (now) t0)]
      (if (and (< dt 250) (< iters 20000000))
        (recur (* iters 4))
        (/ (* dt 1e6) iters)))))

(def payloads
  [["small-map"      {:name "Alice" :tags #{:a :b :c} :score 42}]
   ["mixed"          {:id 7 :n 12345678 :d 3.14159 :s "hello world" :ok true}]
   ["string-100"     (apply str (repeat 10 "0123456789"))]
   ["nested-map-50"  (into {} (for [i (range 50)]
                                [(keyword (str "field-" i)) {:v i :s (str "val-" i)}]))]
   ["datom-maps-200" (vec (for [i (range 200)]
                            {:e (+ 100 i) :a :user/name :v (str "person-" i)
                             :tx (+ 536870912 i) :added true}))]
   ["datom-vec-1k"   (vec (for [i (range 1000)]
                            [(+ 100 i) :user/name (str "person-" i) (+ 536870912 i) true]))]
   ["long-vec-1k"    (vec (range 1000))]])

(def tw (transit/writer :json))
(def tr (transit/reader :json))
(def bs (fress/byte-stream))

(defn fress-rt [v]
  (buf/reset bs)
  (fress/write-object (fress/create-writer bs) v)
  (fress/read-object (fress/create-reader bs)))

(defn cell [x] (.padStart (if (number? x) (.toFixed x 1) (str x)) 12))
(defn pad [s n] (.padEnd (str s) n))

(defn -main [& _]
  (println "node" js/process.version "— ns/op, lower is better")
  (println "JSON shown as the reference bar everyone knows.\n")
  (let [w (boring/writer 65536)]
    (println "=== ENCODE ===")
    (println (pad "payload" 17) (cell "boring") (cell "boring:shapes")
             (cell "transit") (cell "fress") (cell "JSON"))
    (doseq [[nm v] payloads]
      (let [jsonable (clj->js v)]
        (println (pad nm 17)
                 (cell (auto-bench #(boring/encode-buffered! w v)))
                 (cell (auto-bench #(boring/encode-buffered! w v {:shapes true})))
                 (cell (auto-bench #(transit/write tw v)))
                 (cell (auto-bench #(do (buf/reset bs)
                                        (fress/write-object (fress/create-writer bs) v))))
                 (cell (auto-bench #(js/JSON.stringify jsonable))))))

    (println "\n=== DECODE ===")
    ;; `boring/decode` allocates a Reader + DataView + Map per call, while the
    ;; transit reader `tr` is created once and reused. Measuring both is the
    ;; honest thing: `boring-1shot` is the like-for-like of `boring/decode`, and
    ;; `boring` reuses a reader the way transit does.
    ;; :shapes gets its own column because it is the difference between losing
    ;; to transit and beating it on the payload boring exists for. It fires on
    ;; an ARRAY of same-shaped maps and does nothing on nested-map-50, which is
    ;; why that row is flat and the datom rows are not.
    (println (pad "payload" 17) (cell "boring") (cell "boring:shapes")
             (cell "boring-1shot") (cell "transit") (cell "JSON"))
    (let [rdr (boring/reader (js/Uint8Array. 1))]
      (doseq [[nm v] payloads]
        (let [rb (boring/encode v)
              sb (boring/encode v {:shapes true})
              ts (transit/write tw v)
              js (js/JSON.stringify (clj->js v))]
          (println (pad nm 17)
                   (cell (auto-bench #(boring/decode-with rdr rb)))
                   (cell (auto-bench #(boring/decode-with rdr sb)))
                   (cell (auto-bench #(boring/decode rb)))
                   (cell (auto-bench #(transit/read tr ts)))
                   (cell (auto-bench #(js/JSON.parse js)))))))

    (println "\n=== SIZE (bytes) ===")
    (println (pad "payload" 17) (cell "boring") (cell "boring:shapes")
             (cell "transit") (cell "JSON"))
    (doseq [[nm v] payloads]
      (println (pad nm 17)
               (cell (.-length (boring/encode v)))
               (cell (.-length (boring/encode v {:shapes true})))
               (cell (.-length (js/Buffer.from (transit/write tw v) "utf8")))
               (cell (.-length (js/Buffer.from (js/JSON.stringify (clj->js v)) "utf8")))))))

(set! *main-cli-fn* -main)
