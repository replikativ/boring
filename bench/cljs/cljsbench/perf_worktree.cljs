(ns cljsbench.perf-worktree
  "Focused A/B probes for changes developed in the performance worktree."
  (:require [boring.core :as boring]))

(enable-console-print!)

(defn- now [] (js/performance.now))

(defn- bench [f]
  (dotimes [_ 5000] (f))
  (loop [n 128]
    (let [t0 (now)]
      (dotimes [_ n] (f))
      (let [ms (- (now) t0)]
        (if (and (< ms 150) (< n 1000000))
          (recur (* n 2))
          (/ (* ms 1e6) n))))))

(def payloads
  [["small-map" {:name "Alice" :tags #{:a :b :c} :score 42}]
   ["mixed" {:id 7 :n 12345678 :d 3.14159 :s "hello world" :ok true}]
   ["datom-maps-200"
    (vec (for [i (range 200)]
           {:e (+ 100 i) :a :user/name :v (str "person-" i)
            :tx (+ 536870912 i) :added true}))]])

(defn -main [& _]
  (println "node" js/process.version "— ns/op")
  (doseq [[nm v] payloads]
    (let [bs (boring/encode v)
          w (boring/writer 65536)
          on (boring/reader bs)
          off (boring/reader bs {:check-duplicate-keys false})]
      (println nm
               "encode" (.toFixed (bench #(boring/encode v)) 1)
               "reused/default" (.toFixed (bench #(boring/encode-buffered! w v)) 1)
               "reused/resolve" (.toFixed (bench #(boring/encode-buffered! w v {})) 1)
               "decode/check" (.toFixed (bench #(boring/decode-with on bs)) 1)
               "decode/no-check" (.toFixed (bench #(boring/decode-with off bs)) 1)))))

(set! *main-cli-fn* -main)
