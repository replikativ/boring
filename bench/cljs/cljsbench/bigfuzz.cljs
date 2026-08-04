(ns cljsbench.bigfuzz
  "CLJS mutation fuzzing well past bin/ci fuzz-cljs: seven mutation operators,
  randomized decode options, and decode-seq as well as decode."
  (:require [boring.core :as boring]
            [clojure.test.check.generators :as gen]
            [boring.generative-test :as g]))
(enable-console-print!)

(def fs (js/require "fs"))
(defn read-file [p] ((aget fs "readFileSync") p))

(def ^:mutable seed 20260804)
(defn rnd [n] (set! seed (mod (+ (* seed 1103515245) 12345) 2147483648)) (mod seed n))

(def opt-pool
  [nil {:profile :interop} {:shapes true} {:stringref false}
   {:max-depth 8} {:max-depth 2000} {:max-items 64}
   {:tolerate-unknown-tags false} {:check-duplicate-keys false}
   {:profile :canonical}])

(defn cat2 [^js a ^js b]
  (let [o (js/Uint8Array. (+ (.-length a) (.-length b)))]
    (.set o a 0) (.set o b (.-length a)) o))

(defn mutate [^js src]
  (let [n (.-length src)]
    (case (rnd 7)
      0 (let [bs (.slice src 0)]
          (dotimes [_ (inc (rnd 4))] (when (pos? n) (aset bs (rnd n) (rnd 256))))
          bs)
      1 (if (pos? n) (.slice src 0 (rnd n)) src)
      2 (let [p (if (pos? n) (rnd n) 0)
              ins (js/Uint8Array. (inc (rnd 3)))]
          (dotimes [i (.-length ins)] (aset ins i (rnd 256)))
          (cat2 (cat2 (.slice src 0 p) ins) (.slice src p)))
      3 (if (< n 2) src
            (let [p (rnd (dec n)) k (inc (rnd (min 4 (- n p))))]
              (cat2 (.slice src 0 p) (.slice src (+ p k)))))
      4 (if (zero? n) src
            (let [p (rnd n) k (inc (rnd (- n p)))]
              (cat2 src (.slice src p (+ p k)))))
      5 (let [bs (.slice src 0)]
          (when (pos? n) (aset bs (rnd (min n 4)) (rnd 256)))
          bs)
      6 (let [pre (js/Uint8Array. #js [0xd8 (rnd 256)])] (cat2 pre src)))))

(defn classify [f]
  (try {:ok (f)}
       (catch ExceptionInfo e
         (if (= "boring" (some-> (ex-data e) :type namespace))
           {:typed (:type (ex-data e))}
           {:untyped (str "ex-info " (pr-str (ex-data e)) " " (.-message e))}))
       (catch :default e
         (if (instance? js/RangeError e)
           {:untyped (str "RangeError " (.-message e))}
           {:untyped (str (.-name e) ": " (.-message e))}))))

(defn corpus-from [path]
  (let [^js buf (read-file path)
        u8 (js/Uint8Array. (.-buffer buf) (.-byteOffset buf) (.-length buf))
        n (.-length u8)
        out (array)]
    (loop [p 0]
      (when (<= (+ p 4) n)
        (let [len (+ (* (aget u8 p) 16777216) (* (aget u8 (+ p 1)) 65536)
                     (* (aget u8 (+ p 2)) 256) (aget u8 (+ p 3)))]
          (.push out (.slice u8 (+ p 4) (+ p 4 len)))
          (recur (+ p 4 len)))))
    out))

(defn hexb [^js u8]
  (let [sb (array)]
    (dotimes [i (.-length u8)] (.push sb (.padStart (.toString (aget u8 i) 16) 2 "0")))
    (.join sb "")))

(defn -main [& args]
  (let [n (js/parseInt (first args))
        seeds (vec (for [i (range 300)] (gen/generate g/gen-value 18 i)))
        corpus (array)]
    (doseq [v seeds
            o [nil {:shapes true} {:profile :canonical}]]
      (try (.push corpus (boring/encode v o)) (catch :default _ nil)))
    (doseq [p (rest args)] (doseq [d (corpus-from p)] (.push corpus d)))
    (println "corpus:" (.-length corpus) "docs;" n "mutants")
    (let [tally (atom {}) bad (atom [])
          t0 (js/Date.now)]
      (dotimes [i n]
        (let [src (aget corpus (rnd (.-length corpus)))
              m (mutate src)
              opts (nth opt-pool (rnd (count opt-pool)))
              mode (rnd 3)
              t1 (js/Date.now)
              r (if (= mode 2)
                  (classify (fn [] (doall (take 50 (boring/decode-seq m opts)))))
                  (classify (fn [] (boring/decode m opts))))
              dt (- (js/Date.now) t1)]
          (swap! tally update [mode (cond (contains? r :ok) :ok
                                          (contains? r :typed) (:typed r) :else :UNTYPED)]
                 (fnil inc 0))
          (when (> dt 3000) (swap! bad conj [:SLOW mode dt (hexb m)]))
          (when (:untyped r) (swap! bad conj [:UNTYPED mode (:untyped r) (hexb m) (pr-str opts)]))
          (when (zero? (mod (inc i) 200000))
            (println "  .." (inc i) "in" (- (js/Date.now) t0) "ms; bad" (count @bad)))))
      (println "\n== outcomes ==")
      (doseq [[k v] (sort-by (comp - val) @tally)] (println " " (pr-str k) v))
      (println "\n== defects:" (count @bad) "==")
      (doseq [[k grp] (group-by (fn [x] [(first x) (nth x 2)]) @bad)]
        (println "\n---" (pr-str k) (count grp))
        (doseq [x (take 3 (sort-by #(count (nth % 3)) grp))] (println "   " (pr-str (drop 3 x)))))
      (when (seq @bad) (set! (.-exitCode js/process) 1)))))
(set! *main-cli-fn* -main)
