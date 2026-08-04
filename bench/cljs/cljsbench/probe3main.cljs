(ns cljsbench.probe3main (:require [cljsbench.probe3 :as p]))
(defn -main [& _] (p/run))
(set! *main-cli-fn* -main)
