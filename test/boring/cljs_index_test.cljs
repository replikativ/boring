(ns boring.cljs-index-test
  "The ClojureScript writer emits an index frame the JVM reads but this platform
  has no reader for, so nothing here is covered by a round trip. These check the
  parts only this side can get wrong."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [boring.core :as boring]
            [boring.data :as data]))

(defn- frame-containers
  "The `containers` element of the frame `seal-index!` emits for a single node
  sitting at byte offset `off`."
  [off]
  (let [bs (boring/seal-index! {:stride 1 :containers [off] :counts [1]
                                :slots [[off]] :sorted [false]}
                               0 {})]
    (nth (data/frame-payload (boring/decode bs {:on-unknown-record :fallback})) 1)))

(deftest container-offsets-promote-rather-than-wrap
  (testing "offsets that fit stay int32, so ordinary files are byte-identical"
    (let [c (frame-containers 1000)]
      (is (instance? js/Int32Array c))
      (is (= 1000 (aget c 0)))))
  ;; `js/Int32Array.from` applies ToInt32, which wraps SILENTLY. Emitting it
  ;; unconditionally turned an offset at or above 2^31 negative -- precisely the
  ;; case 64-bit offsets exist for -- and a reader on `:trust-index :trusted`
  ;; skips the ascending check that would otherwise have caught it.
  (testing "an offset past 2^31 promotes to int64 and keeps its value"
    (let [off (+ 2147483648 7)
          c (frame-containers off)]
      (is (instance? js/BigInt64Array c))
      (is (= off (js/Number (aget c 0)))))))
