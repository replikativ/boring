(ns bigfuzz
  "Mutation fuzzing far past `bin/ci fuzz`: more mutants, more mutation
  operators (flip/insert/delete/truncate/splice/header-rewrite), randomized
  decode options, and the sealed-sequence surfaces (decode-seq, nav) as well as
  plain decode. Any untyped throwable, hang, or OOM is a defect."
  (:require [boring.core :as b]
            [boring.nav :as nav]
            [clojure.test.check.generators :as gen]
            [boring.generative-test :as g])
  (:import (java.io DataInputStream FileInputStream BufferedInputStream
                    ByteArrayOutputStream)))

(defn hex ^String [^bytes bs]
  (let [sb (StringBuilder.)]
    (dotimes [i (alength bs)] (.append sb (format "%02x" (bit-and (aget bs i) 0xff))))
    (.toString sb)))

(defn docs [path]
  (let [in (DataInputStream. (BufferedInputStream. (FileInputStream. ^String path)))]
    (loop [acc (transient [])]
      (let [n (try (.readInt in) (catch java.io.EOFException _ nil))]
        (if n
          (let [bs (byte-array n)] (.readFully in bs) (recur (conj! acc bs)))
          (do (.close in) (persistent! acc)))))))

(def opt-pool
  [nil
   {:profile :interop}
   {:shapes true}
   {:stringref false}
   {:max-depth 8}
   {:max-depth 2000}
   {:max-items 64}
   {:tolerate-unknown-tags false}
   {:check-duplicate-keys false}
   {:instant-type :instant}
   {:date-type :sql-date}
   {:profile :canonical}])

(defn mutate! [^java.util.Random rnd ^bytes src]
  (let [n (alength src)]
    (case (int (.nextInt rnd 7))
      0 (let [bs (java.util.Arrays/copyOf src n)]                    ; flips
          (dotimes [_ (inc (.nextInt rnd 4))]
            (when (pos? n) (aset bs (.nextInt rnd n) (byte (- (.nextInt rnd 256) 128)))))
          bs)
      1 (if (pos? n) (java.util.Arrays/copyOf src (.nextInt rnd n)) src)   ; truncate
      2 (let [o (ByteArrayOutputStream.)                             ; insert
              p (if (pos? n) (.nextInt rnd n) 0)]
          (.write o src 0 p)
          (dotimes [_ (inc (.nextInt rnd 3))] (.write o (.nextInt rnd 256)))
          (.write o src p (- n p))
          (.toByteArray o))
      3 (if (< n 2) src                                              ; delete
            (let [p (.nextInt rnd (dec n))
                  k (inc (.nextInt rnd (min 4 (- n p))))
                  o (ByteArrayOutputStream.)]
              (.write o src 0 p) (.write o src (+ p k) (- n p k)) (.toByteArray o)))
      4 (let [o (ByteArrayOutputStream.)]                            ; duplicate a slice
          (.write o src 0 n)
          (when (pos? n)
            (let [p (.nextInt rnd n) k (inc (.nextInt rnd (- n p)))]
              (.write o src p k)))
          (.toByteArray o))
      5 (let [bs (java.util.Arrays/copyOf src n)]                    ; header rewrite
          (when (pos? n)
            (let [p (.nextInt rnd (min n 4))]
              (aset bs p (byte (- (.nextInt rnd 256) 128)))))
          bs)
      6 (let [o (ByteArrayOutputStream.)]                            ; prepend a tag
          (.write o 0xd8) (.write o (.nextInt rnd 256)) (.write o src 0 n)
          (.toByteArray o)))))

(defn classify [f]
  (try {:ok (f)}
       (catch clojure.lang.ExceptionInfo e
         (if (= "boring" (some-> (ex-data e) :type namespace))
           {:typed (:type (ex-data e))}
           {:untyped (str "ex-info " (pr-str (ex-data e)) " " (.getMessage e))}))
       (catch StackOverflowError _ {:untyped "StackOverflowError"})
       (catch OutOfMemoryError _ {:untyped "OutOfMemoryError"})
       (catch Throwable e {:untyped (str (.getName (class e)) ": " (.getMessage e))})))

(defn -main [& args]
  (let [n (Long/parseLong (first args))
        corpora (rest args)
        rnd (java.util.Random. 20260804)
        seeds (map #(gen/generate g/gen-value 18 %) (range 400))
        enc (vec (concat
                  (keep #(try (b/encode %) (catch Throwable _ nil)) seeds)
                  (keep #(try (b/encode % {:shapes true}) (catch Throwable _ nil)) seeds)
                  (keep #(try (b/encode % {:profile :canonical}) (catch Throwable _ nil)) seeds)
                  (keep #(try (b/encode-indexed % {:index 2 :index-min 1}) (catch Throwable _ nil)) seeds)
                  (mapcat docs corpora)))
        tally (atom {})
        bad (atom [])
        t0 (System/currentTimeMillis)]
    (println "corpus:" (count enc) "documents;" n "mutants")
    (dotimes [i n]
      (let [src ^bytes (nth enc (.nextInt rnd (count enc)))
            m (mutate! rnd src)
            opts (nth opt-pool (.nextInt rnd (count opt-pool)))
            mode (.nextInt rnd 4)
            start (System/nanoTime)
            r (case mode
                (0 1) (classify #(b/decode m opts))
                2     (classify #(doall (take 50 (b/decode-seq m opts))))
                3     (classify (fn [] (let [items (nav/items m)] (doall (take 20 (map (fn [x] (nav/value x)) items)))))))
            dt (- (System/nanoTime) start)]
        (swap! tally update [mode (cond (contains? r :ok) :ok
                                        (contains? r :typed) (:typed r)
                                        :else :UNTYPED)] (fnil inc 0))
        (when (> dt 3000000000) (swap! bad conj [:SLOW mode (quot dt 1000000) (hex m)]))
        (when (:untyped r) (swap! bad conj [:UNTYPED mode (:untyped r) (hex m) (pr-str opts)]))
        (when (zero? (mod (inc i) 200000))
          (println "  .." (inc i) "in" (- (System/currentTimeMillis) t0) "ms; bad" (count @bad)))))
    (println "\n== outcomes ==")
    (doseq [[k v] (sort-by (comp - val) @tally)] (println (format "  %-46s %8d" (pr-str k) v)))
    (println "\n== defects:" (count @bad) "==")
    (doseq [[kind grp] (group-by (fn [x] [(first x) (nth x 2)]) @bad)]
      (println "\n---" (pr-str kind) (count grp))
      (doseq [x (take 3 (sort-by #(count (nth % 3)) grp))] (println "   " (pr-str (drop 3 x)))))))
