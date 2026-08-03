(require '[boring.core :as b]
         '[boring.nav :as nav])

(let [payload (vec (for [i (range 32)] (vec (repeat 5000 i))))
      bs      (b/encode-indexed payload {:profile :canonical
                                         :index 4
                                         :index-min 4})
      root    (nav/source bs {:profile :canonical})
      cursors (mapv #(nth root %) (range 32))
      bad     (atom [])
      jobs    (doall
               (for [t (range 16)]
                 (future
                   (dotimes [j 200]
                     (let [i (mod (+ j t) 32)]
                       (try
                         (let [v (nav/value (nth cursors i))]
                           (when (or (not= 5000 (count v))
                                     (not= i (first v))
                                     (not= i (peek v)))
                             (swap! bad conj
                                    [:wrong t j i (count v) (first v) (peek v)])))
                         (catch Throwable e
                           (swap! bad conj
                                  [:throw t j i (class e) (.getMessage e)]))))))))]
  (doseq [job jobs] @job)
  (prn {:failures (count @bad) :sample (take 5 @bad)}))
