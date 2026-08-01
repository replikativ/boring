(ns bench
  "Head-to-head: boring vs nippy-fast vs hako vs clj-cbor.

  Answers: can a faithful CBOR codec match nippy/hako?

  The second question this once answered -- does the hot path have to be Java?
  -- was settled by a pure-Clojure writer that was never committed. Its numbers
  are in doc/PERFORMANCE.md."
  (:require [boring.core :as boring]
            [taoensso.nippy :as nippy]
            [clj-cbor.core :as cbor]
            [s-exp.hako :as hako]
            [criterium.core :as crit])
  (:import (org.replikativ.boring Reader Writer)))

(def payloads
  [["small-map"      {:name "Alice" :tags #{:a :b :c} :score 42}]
   ["mixed"          {:id 7 :n 12345678901 :d 3.14159 :s "hello world" :ok true}]
   ["string-100"     (apply str (repeat 10 "0123456789"))]
   ["nested-map-50"  (into {} (for [i (range 50)]
                                [(keyword (str "field-" i)) {:v i :s (str "val-" i)}]))]
   ["datom-maps-200" (vec (for [i (range 200)]
                            {:e (+ 100 i) :a :user/name :v (str "person-" i)
                             :tx (+ 536870912 i) :added true}))]
   ["datom-vec-1k"   (vec (for [i (range 1000)]
                            [(+ 100 i) :user/name (str "person-" i)
                             (+ 536870912 i) true]))]
   ["long-vec-1k"    (vec (range 1000))]])

(defn t
  "Mean µs/op via criterium quick-bench."
  [f]
  (* 1e6 (first (:mean (crit/quick-benchmark (f) {})))))

(defn safe [f] (try (f) (catch Throwable e (str "ERR " (.getMessage e)))))
(defn fmt [x] (if (number? x) (format "%9.3f" x) (format "%9s" (str x))))

(defn -main [& _]
  (let [w      (boring/writer 8192)
        w-nosr (doto (Writer. 8192) (-> .-stringref (set! false)))
        hw     (hako/writer 8192)
        rdr    (Reader. (byte-array 1))]
    (println)
    (println "=== ENCODE (µs/op, lower is better) ===")
    (println (format "%-16s %9s %9s %9s %9s %9s"
                     "payload" "boring" "boring-nosr" "nippy-f" "hako" "clj-cbor"))
    (doseq [[nm v] payloads]
      (println (format "%-16s %s %s %s %s %s" nm
                       (fmt (safe #(t (fn [] (boring/encode-into! w v)))))
                       (fmt (safe #(t (fn [] (do (.reset w-nosr) (.writeValue w-nosr v) (.toByteArray w-nosr))))))
                       (fmt (safe #(t (fn [] (nippy/fast-freeze v)))))
                       (fmt (safe #(t (fn [] (hako/encode v)))))
                       (fmt (safe #(t (fn [] (cbor/encode v)))))))
      (flush))

    (println)
    (println "=== DECODE (µs/op) ===")
    (println (format "%-16s %9s %9s %9s %9s"
                     "payload" "boring" "nippy-f" "hako" "clj-cbor"))
    (doseq [[nm v] payloads]
      (let [rb (safe #(boring/encode-into! w v))
            nb (nippy/fast-freeze v)
            hb (safe #(hako/encode v))
            cb (cbor/encode v)]
        (println (format "%-16s %s %s %s %s" nm
                         (fmt (if (bytes? rb) (safe #(t (fn [] (boring/decode-with rdr rb)))) rb))
                         (fmt (safe #(t (fn [] (nippy/fast-thaw nb)))))
                         (fmt (if (bytes? hb) (safe #(t (fn [] (hako/decode hb)))) hb))
                         (fmt (safe #(t (fn [] (cbor/decode cb)))))))
        (flush)))

    (println)
    (println "=== SIZE (bytes) ===")
    (println (format "%-16s %9s %9s %9s %9s"
                     "payload" "boring" "nippy-f" "hako" "clj-cbor"))
    (doseq [[nm v] payloads]
      (println (format "%-16s %9s %9s %9s %9s" nm
                       (safe #(alength ^bytes (boring/encode-into! w v)))
                       (alength ^bytes (nippy/fast-freeze v))
                       (safe #(alength ^bytes (hako/encode v)))
                       (alength ^bytes (cbor/encode v))))
      (flush))

    (println)
    (println "=== ROUND-TRIP CORRECTNESS ===")
    (doseq [[nm v] payloads]
      (let [r (safe #(boring/decode-with rdr (boring/encode-into! w v)))]
        (println (format "%-16s %s" nm (if (= r v) "ok" (str "MISMATCH -> " (pr-str r)))))))))
