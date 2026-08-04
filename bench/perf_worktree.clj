(ns perf-worktree
  "Focused probes used while developing the performance worktree."
  (:require [boring.core :as boring]
            [alloc :as alloc])
  (:import (java.io ByteArrayOutputStream OutputStream)
           (org.replikativ.boring Reader Writer)))

(defn- min-ns [f burst rounds]
  (dotimes [_ (* burst 20)] (f))
  (loop [i 0 best Long/MAX_VALUE]
    (if (= i rounds)
      (/ best burst 1.0)
      (let [t0 (System/nanoTime)]
        (dotimes [_ burst] (f))
        (recur (inc i) (min best (- (System/nanoTime) t0)))))))

(def datoms
  (vec (for [i (range 200)]
         {:e (+ 100 i) :a :user/name :v (str "person-" i)
          :tx (+ 536870912 i) :added true})))

(defn- staged! [^Writer w v ^OutputStream out]
  (let [n (int (boring/encode-buffered! w v))]
    (.write out ^bytes (boring/buffer w) 0 n)
    n))

(defn -main [& _]
  (let [bs (boring/encode datoms)
        on (boring/reader bs)
        off (boring/reader bs {:check-duplicate-keys false})
        w (boring/writer 65536)]
    ;; Whole-process warmup before either side is timed.
    (dotimes [_ 3]
      (dotimes [_ 3000]
        (boring/encode-buffered! w datoms)
        (boring/decode-with on bs)
        (boring/decode-with off bs)))
    (println {:datom-encode-ns (long (min-ns #(boring/encode-buffered! w datoms) 200 100))
              :datom-decode-check-ns (long (min-ns #(boring/decode-with on bs) 200 100))
              :datom-decode-no-check-ns (long (min-ns #(boring/decode-with off bs) 200 100))
              :datom-decode-check-bytes (alloc/bytes-per-op #(boring/decode-with on bs) 20000)}))
  (doseq [n [65536 1048576]]
    (let [v (byte-array n)
          direct-w (boring/writer 4096)
          staged-w (boring/writer 4096)
          direct-out (ByteArrayOutputStream. (+ n 16))
          staged-out (ByteArrayOutputStream. (+ n 16))
          direct #(do (.reset direct-out) (boring/write-to! direct-w v direct-out))
          staged #(do (.reset staged-out) (staged! staged-w v staged-out))
          burst (if (= n 1048576) 10 50)]
      (println {:byte-leaf n
                :direct-ns (long (min-ns direct burst 60))
                :staged-ns (long (min-ns staged burst 60))
                :direct-buffer (alength ^bytes (boring/buffer direct-w))
                :staged-buffer (alength ^bytes (boring/buffer staged-w))}))))
