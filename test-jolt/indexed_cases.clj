(ns indexed-cases (:require [boring.core :as boring])
  (:import [java.nio.file Files Paths]))
(def values
  [;; Integers on either side of each CBOR argument-width transition.
   [0 23 24 255 256 65535 65536 4294967295 4294967296 Long/MAX_VALUE
    -1 -24 -25 -256 -257 -65536 -65537 -4294967296 -4294967297 Long/MIN_VALUE]
   (mapv (fn [i] (array-map :attribute-name "repeated-value" :entity-id i)) (range 512))
   [(symbol "op") (symbol "datahike.kabel" "dispatch") (symbol "datahike.kabel" "dispatch")]
   (array-map :immutable? true :key "abc123" :type :edn :revision "r1")
   ;; The three-byte text header and three-byte stringref namespace bring
   ;; these first-pass bodies to 4095, 4096 and 4097 bytes.
   (apply str (repeat 4089 "a")) (apply str (repeat 4090 "a")) (apply str (repeat 4091 "a"))
   () (list 1 2) (map inc (range 256)) [] [1 2] (vec (range 256))
   (vec (repeat 50 (vec (repeat 25 :x/abc))))
   ;; Short parents can contain indexed children. Unsorted maps may use step one.
   (mapv (fn [n] (vec (repeat n (vec (range 64))))) [15 16 17])
   (apply array-map (mapcat #(vector % (vec (range 64))) ["d" "c" "b" "a"]))
   (apply array-map (mapcat #(vector (str %) (vec (range 10))) (range 100)))])

(defn -main [dir]
  (doseq [[i v] (map-indexed vector values)
          [mode writer] [["indexed" boring/encode-indexed] ["value" boring/encode]]]
    (let [path (Paths/get (str dir "/" mode "-" i ".cbor") (make-array String 0))
          expected (Files/readAllBytes path)
          actual (writer v)]
      (let [decoded (boring/decode expected)]
        (assert (= v decoded))
        (when (seq? v)
          (assert (seq? decoded))
          (assert (= (seq (writer (vec v))) (seq (writer v {:seqs false}))))))
      (assert (= (seq actual) (seq expected)) (str mode " bytes differ for case " i))))
  (doseq [[wire expected] [[[67 0 127 255] [0 127 -1]]
                           [[64] []]]]
    (let [raw (byte-array (map unchecked-byte wire))]
      (assert (= expected (vec (boring/decode raw))))
      (assert (= (seq raw) (seq (boring/encode (byte-array expected) {:stringref false}))))))
  (doseq [wire [[216 37 65 0] [217 1 2 130 65 1 65 1] [162 65 1 0 65 1 1]]]
    (assert (try (boring/decode (byte-array (map unchecked-byte wire))) false (catch Exception _ true))))
  (println "JVM and Jolt encoded and decoded all fixtures identically."))
