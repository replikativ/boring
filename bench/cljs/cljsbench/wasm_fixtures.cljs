(ns cljsbench.wasm-fixtures
  "Writes the CBOR fixtures bench/wasm/bench.js reads. See bench/wasm/README.md." (:require [boring.core :as boring]))
(def datoms (vec (for [i (range 200)]
                   {:e (+ 100 i) :a :user/name :v (str "person-" i)
                    :tx (+ 536870912 i) :added true})))
(defn -main [& _]
  (let [b (boring/encode datoms) s (boring/encode datoms {:shapes true})]
    (.writeFileSync (js/require "fs") "./target/wasm/plain.cbor" (js/Buffer.from b))
    (.writeFileSync (js/require "fs") "./target/wasm/shaped.cbor" (js/Buffer.from s))
    (.log js/console "wrote" (.-length b) "and" (.-length s) "bytes to target/wasm/")))
(set! *main-cli-fn* -main)
