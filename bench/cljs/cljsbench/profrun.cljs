(ns cljsbench.profrun
  (:require [boring.core :as boring]))
(enable-console-print!)
(def datoms (vec (for [i (range 200)]
                   {:e (+ 100 i) :a :user/name :v (str "person-" i)
                    :tx (+ 536870912 i) :added true})))
(defn -main [& _]
  (let [shaped (boring/encode datoms {:shapes true})
        rdr (boring/reader (js/Uint8Array. 1))]
    (dotimes [_ 2000] (boring/encode-buffered! (boring/writer 65536) datoms {:shapes true}))
    (println "profiling encode...")
    (let [w (boring/writer 65536)]
      (dotimes [_ 20000] (boring/encode-buffered! w datoms {:shapes true})))
    (println "done")))
(set! *main-cli-fn* -main)
