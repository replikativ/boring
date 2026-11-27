(ns indexed-cases (:require [boring.core :as boring])
  (:import [java.nio.file Files Paths]))
(def values
  [(mapv (fn [i] (array-map :attribute-name "repeated-value" :entity-id i)) (range 512))
   [] [1 2] (vec (range 256))
   (vec (repeat 50 (vec (repeat 25 :x/abc))))
   (apply array-map (mapcat #(vector (str %) (vec (range 10))) (range 100)))])

(defn -main [dir]
  (doseq [[i v] (map-indexed vector values)
          [mode writer] [["indexed" boring/encode-indexed] ["value" boring/encode]]]
    (let [path (Paths/get (str dir "/" mode "-" i ".cbor") (make-array String 0))
          expected (Files/readAllBytes path)
          actual (writer v)]
      (assert (= v (boring/decode expected)))
      (assert (= (seq actual) (seq expected)) (str mode " bytes differ for case " i))))
  (println "JVM and Jolt encoded and decoded all fixtures identically."))
