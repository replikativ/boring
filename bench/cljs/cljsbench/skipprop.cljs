(ns cljsbench.skipprop
  "Property: `skip-from` must land exactly where `read!` lands.

  A skip that is one byte off produces an index pointing into the middle of an
  item, which reads back as a plausible WRONG VALUE rather than an error -- the
  failure class this project has spent seven audit rounds on. Run before
  trusting anything built on top of these primitives."
  (:require [boring.core :as b] [boring.reader :as rd]))
(defn gen [d]
  (let [r (rand)]
    (cond (or (> d 3) (< r 0.30)) (rand-nth [1 -1 0 1000000 "x" "" :kw 'sym true false nil 1.5
                                             (js/Uint8Array.from #js [1 2 3])])
          (< r 0.55) (vec (repeatedly (rand-int 5) #(gen (inc d))))
          (< r 0.80) (into {} (map (fn [i] [i (gen (inc d))])) (range (rand-int 5)))
          :else (set (repeatedly (rand-int 4) #(rand-int 100))))))
(defn -main [& _]
  (let [bad (atom 0)]
    (dotimes [_ 20000]
      (let [v (gen 0) bs (b/encode v {:stringref false})
            r (rd/reader bs) skipped (rd/skip-from r 0)
            _ (rd/read! r) decoded (rd/position r)]
        (when (not= skipped decoded)
          (swap! bad inc)
          (when (< @bad 4) (println "MISMATCH" skipped decoded (pr-str v))))))
    (println "skip disagreed with decode on" @bad "of 20000")
    (set! (.-exitCode js/process) (if (zero? @bad) 0 1))))
(set! *main-cli-fn* -main)
