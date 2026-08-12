(ns boring.tl-reuse-test
  "`encode`/`decode` reuse a thread-local Writer/Reader. The wins are measured
  on the fns' own docstrings; what THESE tests pin is everything reuse could
  break -- because every failure mode here is silent bytes-level corruption,
  not an exception.

  The independent oracle is `encode-into!` on a FRESH explicit writer: it
  shares no cache with `encode`, so agreement means the cache changed nothing
  but the allocation."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]))

(defn- fresh-bytes
  "Encode through a fresh explicit writer -- the no-cache reference path.
  `encode-into!` returns the encoded bytes themselves."
  ^bytes [v opts]
  (boring/encode-into! (boring/writer 256 opts) v))

(deftest cached-encode-produces-the-reference-bytes
  (testing "across calls, sizes, and alternating options -- an option leaking
            from the parked writer would show as a bytes mismatch on the NEXT
            call, so the alternation is the test"
    (doseq [v [{:a 1} [1 2 3] "hello" {:k (vec (range 500))}
               {:s (apply str (repeat 400 "x")) :t {:u [:a :b :a :b]}}]]
      (is (java.util.Arrays/equals (boring/encode v) ^bytes (fresh-bytes v nil))
          (pr-str v))
      (is (java.util.Arrays/equals ^bytes (boring/encode v {:stringref false})
                                   ^bytes (fresh-bytes v {:stringref false}))
          (str "stringref off after on, " (pr-str v)))
      (is (= v (boring/decode (boring/encode v)))))))

(deftype ^:private Wrap [inner])

(deftest reentrant-encode-builds-fresh-instead-of-corrupting
  (testing "a tag write-fn that itself calls `encode` runs INSIDE the outer
            encode. Take-and-clear means it finds an empty cache and builds
            fresh; sharing the parked writer would interleave two documents
            into one buffer."
    (let [reg (boring/register-tag (boring/tag-registry) 40999 Wrap
                                   (fn [^Wrap w] (boring/encode (.inner w)))
                                   (fn [content] (boring/decode content)))
          o {:registry reg}
          v {:outer [1 2 (Wrap. {:nested "value" :n 42}) 3]}]
      (is (= {:outer [1 2 {:nested "value" :n 42} 3]}
             (boring/decode (boring/encode v o) o)))
      ;; and the cache still works afterwards
      (is (= {:after true} (boring/decode (boring/encode {:after true})))))))

(deftest a-throwing-encode-leaves-the-cache-usable
  (testing "the parked writer is reset in a finally, and `write-root!` resets
            on entry besides -- so bytes after a failure must equal the
            reference path, not merely decode"
    (is (thrown? Exception (boring/encode (Object.))))
    (let [v {:recovered true :xs (vec (range 100))}]
      (is (java.util.Arrays/equals (boring/encode v) ^bytes (fresh-bytes v nil))))))

(deftest decode-options-are-not-sticky
  (testing "`configure-reader!` sets every field per call, so the SAME parked
            reader must alternate behaviour with the caller's options"
    (let [bs (byte-array [0xd9 0x27 0x0f 0x01])] ; tag 9999(1), unregistered
      (dotimes [_ 3]
        (is (some? (boring/decode bs)) "tolerated by default")
        (is (thrown? Exception (boring/decode bs {:tolerate-unknown-tags false})))))))

(deftest threads-do-not-share-a-parked-writer
  (testing "each thread has its own cache; corruption across threads would
            surface as a round-trip mismatch under contention"
    (let [results (mapv deref
                        (mapv (fn [t]
                                (future
                                  (every? (fn [i]
                                            (let [v {:t t :i i :pad (vec (range (mod i 50)))}]
                                              (= v (boring/decode (boring/encode v)))))
                                          (range 300))))
                              (range 8)))]
      (is (every? true? results)))))
