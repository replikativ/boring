(ns cljsbench.hexdump
  (:require [boring.core :as boring] [boring.hexdump-cases :as cases]))
(defn hexs [bs]
  (apply str (for [i (range (.-length bs))]
               (.padStart (.toString (aget bs i) 16) 2 "0"))))
(defn -main [& _]
  (doseq [[label v] cases/cases]
    (println (str label "\t" (try (hexs (boring/encode v {:shapes true})) (catch :default e (str "ERR " (.-message e))))))))
(set! *main-cli-fn* -main)
