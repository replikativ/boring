(ns cljsbench.probe
  "SCRATCH -- cross-platform audit round 9. Delete when done."
  (:require [boring.core :as b]
            [cljsbench.probe-cases :as pc]))

(defn hexs [bs]
  (apply str (for [i (range (.-length bs))]
               (.padStart (.toString (aget bs i) 16) 2 "0"))))

(defn err [e]
  (str "ERR " (or (:type (ex-data e)) (.-name e)) " " (.-message e)))

(defn -main [& _]
  (doseq [[label v opts] pc/encode-indexed-cases]
    (println (str "EI\t" label "\t"
                  (try (hexs (b/encode-indexed v opts)) (catch :default e (err e))))))
  (doseq [[label v opts] pc/encode-cases]
    (println (str "EN\t" label "\t"
                  (try (hexs (b/encode v opts)) (catch :default e (err e))))))
  ;; round-trip grade
  (doseq [[label v opts] pc/encode-cases]
    (println (str "RT\t" label "\t"
                  (try (let [d (b/decode (b/encode v opts))]
                         (str (= d v) " " (pr-str (type d))
                              " sorted=" (boolean (and (map? d) (satisfies? ISorted d)))
                              " meta=" (pr-str (meta d))))
                       (catch :default e (err e)))))))

(set! *main-cli-fn* -main)
