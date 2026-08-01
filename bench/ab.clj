(ns ab
  "A/B benchmark robust to a loaded machine.

  criterium measures A for ~7s then B for ~7s; if background load drifts over
  that window the comparison is garbage. This machine has a 12-hour 96%-CPU JVM
  on it, which is why earlier runs showed hako's unchanged kw-flat at
  12.3 / 10.0 / 25.0 µs.

  Instead: alternate A and B in short bursts inside one loop, so both see the
  same conditions, and take the min over many rounds — the round that lands in a
  quiet moment gives a clean reading for both."
  (:require [boring.core :as boring]
            [s-exp.hako :as hako]
            [taoensso.nippy :as nippy])
  (:import (org.replikativ.boring Reader Writer)))

(defn ab
  "Returns [ns-per-op-a ns-per-op-b]. Alternating bursts, min over rounds."
  ([fa fb] (ab fa fb 200 60))
  ([fa fb burst rounds]
   (dotimes [_ (* burst 20)] (fa) (fb))               ; warm both
   (loop [r 0, best-a Long/MAX_VALUE, best-b Long/MAX_VALUE]
     (if (= r rounds)
       [(/ best-a burst 1.0) (/ best-b burst 1.0)]
       (let [t0 (System/nanoTime)
             _  (dotimes [_ burst] (fa))
             t1 (System/nanoTime)
             _  (dotimes [_ burst] (fb))
             t2 (System/nanoTime)]
         (recur (inc r)
                (min best-a (- t1 t0))
                (min best-b (- t2 t1))))))))

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
   ["long-vec-1k"    (vec (range 1000))]
   ["kw-flat-1k"     (vec (take 1000 (cycle [:e :a :v :tx :added])))]])

(defn fmt [a b]
  (format "%9.2f %9.2f  %5.2fx %s" (/ a 1000.0) (/ b 1000.0) (/ a b)
          (cond (< a (* 0.97 b)) "boring"
                (> a (* 1.03 b)) "hako"
                :else "tie ")))

(defn global-warmup!
  "Exercise every payload through every codec before measuring anything.

  Per-cell warmup is not enough: measuring hako's small-map encode four times in
  a row gave 2.52 / 1.30 / 1.15 / 1.08 µs, i.e. the whole process keeps speeding
  up long past 4000 iterations. Without this, whichever cell runs first is
  penalised and the first block of results is fiction."
  [w rdr]
  (dotimes [_ 3]
    (doseq [[_ v] payloads]
      (let [rb (boring/encode v) hb (hako/encode v) nb (nippy/fast-freeze v)]
        (dotimes [_ 2000]
          (boring/encode-into! w v)
          (boring/encode-buffered! w v)
          (hako/encode v)
          (boring/decode-with rdr rb)
          (hako/decode hb)
          (nippy/fast-thaw nb))))))

(defn -main
  "Pass \"no-warmup\" when the caller has already warmed the process --
  `suite` does, and warming twice costs a minute and buys nothing."
  [& args]
  (let [w (boring/writer 65536)
        rdr (Reader. (byte-array 1))]
    (when-not (contains? (set args) "no-warmup")
      (print "global warmup... ") (flush)
      (global-warmup! w rdr)
      (println "done"))
    (println "A/B interleaved, min over 60 rounds. µs/op.\n")
    (println (format "%-16s %9s %9s  %5s %s" "payload" "boring" "hako" "ratio" "winner"))
    (println "--- ENCODE ---")
    (doseq [[nm v] payloads]
      (let [[a b] (ab #(boring/encode-into! w v) #(hako/encode v))]
        (println (format "%-16s %s" nm (fmt a b))) (flush)))
    (println "--- ENCODE, no copy-out (encode-buffered!) vs hako ---")
    (doseq [[nm v] payloads]
      (let [[a b] (ab #(boring/encode-buffered! w v) #(hako/encode v))]
        (println (format "%-16s %s" nm (fmt a b))) (flush)))
    (println "--- DECODE ---")
    (doseq [[nm v] payloads]
      (let [rb (boring/encode v) hb (hako/encode v)
            [a b] (ab #(boring/decode-with rdr rb) #(hako/decode hb))]
        (println (format "%-16s %s" nm (fmt a b))) (flush)))
    (println "\n--- DECODE vs nippy-fast ---")
    (doseq [[nm v] payloads]
      (let [rb (boring/encode v) nb (nippy/fast-freeze v)
            [a b] (ab #(boring/decode-with rdr rb) #(nippy/fast-thaw nb))]
        (println (format "%-16s %9.2f %9.2f  %5.2fx" nm (/ a 1000.0) (/ b 1000.0) (/ a b)))
        (flush)))))
