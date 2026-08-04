(ns cljsbench.probe2main
  (:require [cljsbench.probe2 :as p]))
(defn -main [& _] (p/run-all))
(set! *main-cli-fn* -main)
