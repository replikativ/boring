(ns cljsbench.xplatw
  "CLJS encodes generated values under every profile; the JVM decodes the same
  corpus. The browser-writes/server-reads direction."
  (:require [boring.core :as boring]
            [cljsbench.xdiff :as x]
            [clojure.test.check.generators :as gen]
            [boring.generative-test :as g]))
(enable-console-print!)
(def fs (js/require "fs"))
(defn write-file [p c] ((aget fs "writeFileSync") p c))

(def cases [nil {:shapes true} {:stringref false} {:profile :interop}
            {:profile :archival} {:profile :canonical} {:profile :canonical-rfc7049}])

(defn -main [& [out expected n-str]]
  (let [n (js/parseInt (or n-str "8000"))
        chunks (array) lines (array) idx (atom 0)]
    (dotimes [k n]
      (let [v (gen/generate g/gen-value (+ 5 (mod k 25)) k)]
        (doseq [o cases]
          (when-let [e (try (boring/encode v o) (catch :default _ nil))]
            (let [len (.-length e)
                  hdr (js/Uint8Array. #js [(bit-and (bit-shift-right len 24) 255)
                                           (bit-and (bit-shift-right len 16) 255)
                                           (bit-and (bit-shift-right len 8) 255)
                                           (bit-and len 255)])]
              (.push chunks hdr) (.push chunks e)
              (.push lines (str @idx "\t" (x/hexb e) "\tOK "
                                (try (x/lnorm (boring/decode e o)) (catch :default t (str "ERR " (.-message t))))
                                "\t" (pr-str o)))
              (swap! idx inc))))))
    (let [total (reduce + (map #(.-length %) (array-seq chunks)))
          buf (js/Uint8Array. total)]
      (loop [i 0 off 0]
        (when (< i (.-length chunks))
          (.set buf (aget chunks i) off)
          (recur (inc i) (+ off (.-length (aget chunks i))))))
      (write-file out (js/Buffer.from (.-buffer buf) 0 total)))
    (write-file expected (str (.join lines "\n") "\n"))
    (println "wrote" @idx "encodings")))
(set! *main-cli-fn* -main)
