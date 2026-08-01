(ns cljsbench.fuzz
  "Mutation fuzzer for the CLJS decoder.

  Everything fuzzed so far ran on the JVM only, but CLJS has a separate reader
  — and every CLJS-specific bug found in this project (the stringref hole, the
  quadratic shape check, the BigInt arithmetic) was invisible to the JVM fuzzer."
  (:require [boring.core :as boring]
            [clojure.test.check.generators :as gen]
            [boring.generative-test :as g]))
(enable-console-print!)

(defn outcome [bs]
  (try {:ok (boring/decode bs)}
       (catch ExceptionInfo e
         (if (= "boring" (some-> (ex-data e) :type namespace))
           {:typed (:type (ex-data e))}
           {:untyped (pr-str (ex-data e))}))
       (catch :default e
         {:untyped (str (.-name e) ": " (.-message e))})))

;; Deterministic LCG — Math/random would make failures unreproducible.
(def ^:mutable seed 12345)
(defn rnd [n] (set! seed (mod (+ (* seed 1103515245) 12345) 2147483648)) (mod seed n))

(defn -main [& [n-str]]
  (let [n (js/parseInt (or n-str "100000"))
        seeds (vec (for [i (range 300)] (gen/generate g/gen-value 15 i)))
        corpus (vec (keep #(try (boring/encode % {:shapes true}) (catch :default _ nil)) seeds))
        tally (atom {}) bad (atom [])]
    (println "corpus:" (count corpus) "valid encodings;" n "mutants")
    (dotimes [_ n]
      (let [src (nth corpus (rnd (count corpus)))
            bs (.slice src 0)
            nmut (inc (rnd 4))]
        (dotimes [_ nmut]
          (when (pos? (.-length bs)) (aset bs (rnd (.-length bs)) (rnd 256))))
        (let [r (outcome bs)
              k (cond (contains? r :ok) :ok (contains? r :typed) (:typed r) :else :UNTYPED)]
          (swap! tally update k (fnil inc 0))
          (when (:untyped r) (swap! bad conj [(vec (array-seq bs)) (:untyped r)])))))
    (println "\noutcomes:")
    (doseq [[k v] (sort-by (comp - val) @tally)]
      (println (str "  " (.padEnd (str k) 30) v)))
    (when (seq @bad)
      (println "\nUNTYPED FAILURES:")
      (doseq [[bs msg] (take 6 @bad)]
        (println "  " msg)
        (println "    bytes:" (apply str (map #(.padStart (.toString % 16) 2 "0") (take 60 bs))))))
    (println (if (seq @bad) "\nFAIL" "\nno untyped failures"))
    ;; Exit non-zero so bin/ci and CI gate on this rather than treating the
    ;; fuzzer as advisory.
    (when (seq @bad) (set! (.-exitCode js/process) 1))))
(set! *main-cli-fn* -main)
