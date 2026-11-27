(ns canonical-cases
  (:require [boring.core :as b]))

(defn- rejected? [f]
  (try (f) false (catch Exception _ true)))

(defn -main []
  (let [opts {:profile :canonical :stringref false :max-depth 2}
        value (array-map "a" 1 1000 2)
        wire (b/encode value opts)
        strict {:max-depth 2 :max-items 5 :tolerate-unknown-tags false
                :on-unknown-record :error :stringref false}]
    ;; RFC 8949 bytewise order places the three-byte integer before the shorter text key.
    (assert (= [162 25 3 232 2 97 97 1] (mapv #(bit-and 255 %) wire)))
    (assert (= value (b/decode wire strict)))
    (assert (rejected? #(b/decode wire (assoc strict :max-items 4))))
    (assert (rejected? #(b/encode [[[1]]] opts)))
    (assert (rejected? #(b/decode (b/encode [[[1]]]) strict)))
    (assert (rejected? #(b/decode (byte-array [(unchecked-byte 200) 0]) strict)))
    (assert (rejected? #(b/decode (byte-array (map unchecked-byte [216 27 130 97 88 160])) strict)))
    (assert (rejected? #(b/encode (hash-map (byte-array [1]) 1 (byte-array [1]) 2) opts)))
    (assert (rejected? #(b/encode value (assoc opts :stringref true))))
    (println "Canonical byte order and decoder limits passed.")))
