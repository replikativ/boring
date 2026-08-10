(ns k7-throughput
  "What can k7 actually move, and where does the ceiling sit?

  `k7_segments.clj` compares framings end to end. This one asks the narrower
  question: with the codec taken out of the loop entirely -- payloads encoded
  up front, held in a vector, handed to `enqueue!` as ready byte arrays -- how
  many messages and how many bytes per second does the log itself do, and how
  does that change with message size?

  It matters because the whole segment argument rests on a ratio. Encoding
  parallelises across cores; appending does not, because `enqueue!` is
  single-writer by contract. If the writer is the bottleneck the design has to
  change. If it is not, encode on a pool and hand finished segments to one
  thread.

  Sizes are chosen to bracket the segment decision: 1 event, 100, 1000, 10000.

  Run: clojure -M:k7 -m k7-throughput"
  (:require [boring.core :as boring]
            [s-exp.k7 :as k7])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(def total-events 200000)

(defn- event [i]
  {:e (+ 100 i) :a :user/name :v (str "person-" i)
   :tx (+ 536870912 i) :added true})

(def ^:private store-opts {:shapes true :stringref true})

(defn- tmpdir ^String [nm]
  (str (Files/createTempDirectory nm (make-array FileAttribute 0))))

(defn- payloads
  "Every message pre-encoded, so nothing below measures the codec."
  [^long per-msg]
  (mapv (fn [batch]
          (if (= 1 per-msg)
            (boring/encode (first batch))
            (boring/encode-indexed (vec batch) store-opts)))
        (partition-all per-msg (map event (range total-events)))))

(defn- ms ^double [f]
  (let [t0 (System/nanoTime)] (f) (/ (- (System/nanoTime) t0) 1e6)))

(defn- best ^double [n f] (apply min (repeatedly n #(ms f))))

(defn- run [^long per-msg fsync]
  (let [msgs (payloads per-msg)
        n-msgs (count msgs)
        bytes (reduce + (map #(alength ^bytes %) msgs))
        ;; WRITE. A fresh queue per timed run: enqueue! appends, so repeating
        ;; into one queue measures a growing file and eventually a new segment
        ;; allocation. Directory creation is outside the timer.
        wms (best 5 (fn []
                      (let [d (tmpdir "k7tp")
                            q (k7/queue d {:fsync-strategy fsync})]
                        (run! (fn [^bytes b] (k7/enqueue! q b)) msgs)
                        (k7/close-queue! q))))
        ;; READ. One queue, replayed -- `seek!` to 0 is what a real replay does.
        d (tmpdir "k7tp-r")
        q (k7/queue d {:fsync-strategy fsync})
        _ (run! (fn [^bytes b] (k7/enqueue! q b)) msgs)
        _ (k7/close-queue! q)
        q2 (k7/queue d {:fsync-strategy fsync})
        cg (k7/consumer-group q2 "r")
        drain (fn []
                (k7/seek! cg 0)
                (loop [c 0]
                  (let [b (k7/poll! cg {:max-batch 256 :timeout-ms 1})]
                    (if (empty? b)
                      c
                      (recur (long (reduce (fn [^long a m] (k7/ack! cg m) (inc a))
                                           (long c) b)))))))
        _ (dotimes [_ 3] (drain))
        rms (best 5 drain)]
    (k7/close-consumer-group! cg)
    (k7/close-queue! q2)
    (println
     (format "%7d %8d %10d %9.1f %10.2f %9.0f %9.1f %10.2f %9.0f"
             per-msg n-msgs bytes
             wms (/ n-msgs wms 1000.0) (/ total-events wms 0.001 1e6)
             rms (/ n-msgs rms 1000.0) (/ total-events rms 0.001 1e6)))
    (flush)))

(defn -main [& _]
  (println (format "%d events total, payloads pre-encoded (no codec in the loop)\n"
                   total-events))
  (doseq [fsync [:async :flush]]
    (println (format "--- fsync %s ---" fsync))
    (println (format "%7s %8s %10s %9s %10s %9s %9s %10s %9s"
                     "ev/msg" "msgs" "bytes" "w ms" "w Mmsg/s" "w Mev/s"
                     "r ms" "r Mmsg/s" "r Mev/s"))
    (doseq [n [1 100 1000 10000]] (run n fsync))
    (println)))
