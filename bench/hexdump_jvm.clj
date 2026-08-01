(ns hexdump-jvm
  (:require [boring.core :as boring]
            [boring.hexdump-cases :refer [cases]]))
(defn -main [& _]
  (doseq [[label v] cases]
    (println (str label "\t" (try (apply str (map #(format "%02x" %) (boring/encode v {:shapes true})))
                                  (catch Exception e (str "ERR " (.getMessage e))))))))
