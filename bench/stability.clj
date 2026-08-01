(ns stability
  "Repeat quick-bench N times and report min/median, so the large-payload cells
  can be reported with a defensible number rather than a single noisy sample."
  (:require [boring.core :as boring]
            [taoensso.nippy :as nippy]
            [s-exp.hako :as hako]
            [criterium.core :as crit])
  (:import (org.replikativ.boring Writer)))

(def datom-maps-200
  (vec (for [i (range 200)]
         {:e (+ 100 i) :a :user/name :v (str "person-" i)
          :tx (+ 536870912 i) :added true})))

(def datom-vec-1k
  (vec (for [i (range 1000)]
         [(+ 100 i) :user/name (str "person-" i) (+ 536870912 i) true])))

;; Same shape but with a small value vocabulary, so stringref actually hits.
(def datom-vec-1k-dup
  (vec (for [i (range 1000)]
         [(+ 100 i) :user/name (str "person-" (mod i 20)) (+ 536870912 i) true])))

(defn samples [n f]
  (sort (for [_ (range n)] (* 1e6 (first (:mean (crit/quick-benchmark (f) {})))))))

(defn row [nm f]
  (let [s (samples 5 f)]
    (println (format "  %-18s min %8.2f   med %8.2f   max %8.2f" nm
                     (first s) (nth s 2) (last s)))
    (flush)))

(defn -main [& _]
  (let [w   (boring/writer 65536)
        nsr (doto (Writer. 65536) (-> .-stringref (set! false)))]
    (doseq [[nm v] [["datom-maps-200" datom-maps-200]
                    ["datom-vec-1k (unique strings)" datom-vec-1k]
                    ["datom-vec-1k (20 distinct strings)" datom-vec-1k-dup]]]
      (println)
      (println nm "— encode µs/op, 5 quick-bench runs")
      (row "boring (stringref)" #(boring/encode-into! w v))
      (row "boring (no stringref)" #(do (.reset nsr) (.writeValue nsr v) (.toByteArray nsr)))
      (row "hako" #(hako/encode v))
      (row "nippy-fast" #(nippy/fast-freeze v))
      (println (format "  sizes: boring-sr %d  boring-nosr %d  hako %d  nippy %d"
                       (alength ^bytes (boring/encode-into! w v))
                       (do (.reset nsr) (.writeValue nsr v) (alength ^bytes (.toByteArray nsr)))
                       (alength ^bytes (hako/encode v))
                       (alength ^bytes (nippy/fast-freeze v)))))))
