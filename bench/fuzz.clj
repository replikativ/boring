(ns fuzz
  "Heavier fuzzing than the test suite runs: mutate VALID encodings, which
  reaches far deeper into the decoder than random bytes (random bytes usually
  die on the first header). Any untyped failure — including StackOverflowError
  and OutOfMemoryError — is a defect."
  (:require [boring.core :as boring]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.random :as random]
            [boring.generative-test :as g]))

(defn outcome [^bytes bs]
  (try {:ok (boring/decode bs)}
       (catch clojure.lang.ExceptionInfo e
         (if (= "boring" (some-> (ex-data e) :type namespace))
           {:typed (:type (ex-data e))}
           {:untyped (pr-str (ex-data e))}))
       (catch Throwable e {:untyped (str (.getName (class e)) ": " (.getMessage e))})))

(defn -main [& [n-str]]
  (let [n (Long/parseLong (or n-str "40000"))
        rng (random/make-random 42)
        seeds (map #(gen/generate g/gen-value 15 %) (range 200))
        encoded (vec (keep #(try (boring/encode %) (catch Throwable _ nil)) seeds))
        rnd (java.util.Random. 4242)
        tally (atom {})
        bad (atom [])]
    (println "seed corpus:" (count encoded) "valid encodings")
    (dotimes [_ n]
      (let [src ^bytes (rand-nth encoded)
            bs (java.util.Arrays/copyOf src (alength src))
            nmut (inc (.nextInt rnd 4))]
        (dotimes [_ nmut]
          (when (pos? (alength bs))
            (aset bs (.nextInt rnd (alength bs)) (byte (- (.nextInt rnd 256) 128)))))
        (let [r (outcome bs)]
          ;; contains?, not (:ok r): a successful decode of nil is falsy
          (swap! tally update (cond (contains? r :ok) :ok (contains? r :typed) (:typed r) :else :UNTYPED) (fnil inc 0))
          (when (:untyped r) (swap! bad conj [(vec bs) (:untyped r)])))))
    (println "\noutcomes over" n "mutants:")
    (doseq [[k v] (sort-by (comp - val) @tally)]
      (println (format "  %-28s %6d" k v)))
    (when (seq @bad)
      (println "\nUNTYPED FAILURES (defects):")
      (doseq [[bs msg] (take 5 @bad)] (println "  " msg "\n    bytes:" bs)))
    (println (if (seq @bad) "\nFAIL" "\nno untyped failures"))
    ;; Exit non-zero so bin/ci and CI gate on this. Printing FAIL and exiting 0
    ;; made the fuzzer advisory rather than a gate -- it would have been
    ;; reported as a passing stage.
    (when (seq @bad) (System/exit 1))))
