(ns cljsbench.probe-cases
  "SCRATCH -- cross-platform audit round 9. Delete when done.")

;; Values built identically on both platforms.
;; array-map preserves insertion order on BOTH platforms at any size, so map
;; ordering is not a confound. sorted-map iterates in key order on both.

(defn- k2 [i] (str "k" (if (< i 10) (str "0" i) (str i))))

(def m40 (apply array-map (mapcat (fn [i] [(k2 i) i]) (range 40))))
(def sm40 (into (sorted-map) m40))
;; deliberately descending keys => sorted flag must be FALSE
(def unsorted40 (apply array-map (mapcat (fn [i] [(k2 i) i]) (reverse (range 40)))))
(def v40 (vec (range 40)))
;; entries wide enough that anchor deltas exceed 0xFF
(def wide20 (vec (map (fn [i] (apply str (repeat 300 (char (+ 97 (mod i 26)))))) (range 20))))
;; entries wide enough that deltas exceed 0x7FFF
(def huge20 (vec (map (fn [i] (apply str (repeat 40000 (char (+ 97 (mod i 26)))))) (range 20))))
(def nested (vec (repeat 20 m40)))

(defn bs [& xs]
  #?(:clj (byte-array (map unchecked-byte xs))
     :cljs (js/Uint8Array.from (clj->js (vec xs)))))

(def encode-indexed-cases
  [["ei-default-m40"       m40        nil]
   ["ei-m40-s4-min1"       m40        {:index 4 :index-min 1}]
   ["ei-m40-s1-min1"       m40        {:index 1 :index-min 1}]
   ["ei-sorted40-s4-min1"  sm40       {:index 4 :index-min 1}]
   ["ei-unsorted40-s4"     unsorted40 {:index 4 :index-min 1}]
   ["ei-v40-s4-min1"       v40        {:index 4 :index-min 1}]
   ["ei-wide20-s1-min1"    wide20     {:index 1 :index-min 1}]
   ["ei-huge20-s1-min1"    huge20     {:index 1 :index-min 1}]
   ["ei-nested-s4-min1"    nested     {:index 4 :index-min 1}]
   ["ei-canonical-m40"     m40        {:profile :canonical :index 4 :index-min 1}]
   ["ei-archival-m40"      m40        {:profile :archival :index 4 :index-min 1}]
   ["ei-shapes-rows"       (vec (repeat 20 (array-map "a" 1 "b" 2))) {:shapes true :index 4 :index-min 1}]
   ["ei-stringref-true"    (vec (repeat 20 "hello world hello")) {:stringref true :index 4 :index-min 1}]
   ["ei-index-min-huge"    m40        {:index 4 :index-min 100000}]
   ["ei-empty-map"         {}         {:index 4 :index-min 1}]
   ["ei-map-with-empty"    (array-map "a" {} "b" [] "c" 1) {:index 4 :index-min 0}]
   ["ei-min41-none"        v40        {:index 4 :index-min 41}]
   ["ei-set40"             (into (sorted-set) (range 40)) {:index 4 :index-min 1}]
   ["ei-bytes"             (bs 1 2 3) {:index 4 :index-min 1}]])

(def encode-cases
  [["f-nan"          ##NaN        nil]
   ["f-inf"          ##Inf        nil]
   ["f-neginf"       ##-Inf       nil]
   ["f-negzero"      -0.0         nil]
   ["f-negzero-can"  -0.0         {:profile :canonical}]
   ["f-1.5"          1.5          nil]
   ["f-1.5-can"      1.5          {:profile :canonical}]
   ["f-65504"        65504.0      {:profile :canonical}]
   ["f-1e300"        1e300        {:profile :canonical}]
   ["f-5e-324"       5e-324       {:profile :canonical}]
   ["f-nan-can"      ##NaN        {:profile :canonical}]
   ["f-inf-can"      ##Inf        {:profile :canonical}]
   ["i-2^53-1"       9007199254740991 nil]
   ["i-neg2^53"      -9007199254740991 nil]
   ["can-mixed-keys" (array-map 1000 "x" "a" "y") {:profile :canonical}]
   ["can7049-mixed"  (array-map 1000 "x" "a" "y") {:profile :canonical-rfc7049}]
   ["can-mixed2"     (array-map "aa" 1 "b" 2 1 3 -1 4) {:profile :canonical}]
   ["can-set"        #{"aa" "b" "ccc"} {:profile :canonical}]
   ["can-nested-map" (array-map "z" (array-map "b" 1 "a" 2) "y" 3) {:profile :canonical}]
   ["meta-vec"       (with-meta [1 2 3] {:a 1}) nil]
   ["meta-map"       (with-meta (array-map "x" 1) {:a 1}) nil]
   ["meta-off"       (with-meta [1 2 3] {:a 1}) {:incl-metadata? false}]
   ["meta-can"       (with-meta [1 2 3] {:a 1}) {:profile :canonical}]
   ["meta-nested"    [(with-meta [1] {:a 1}) (with-meta [2] {:a 1})] nil]
   ["meta-set"       (with-meta #{1} {:a 1}) nil]
   ["sr-bytes"       [(bs 1 2 3 4 5 6 7 8) (bs 1 2 3 4 5 6 7 8)] nil]
   ["sr-repeat"      ["hello world" "hello world" "hello world"] nil]
   ["sr-off"         ["hello world" "hello world"] {:stringref false}]
   ["sr-nested"      [["abcdefgh" "abcdefgh"] ["abcdefgh"]] nil]
   ["sr-uuid"        [#uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"
                      #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"] nil]
   ["sorted-map"     (into (sorted-map) {"b" 1 "a" 2}) nil]
   ["sorted-set"     (into (sorted-set) ["b" "a"]) nil]
   ["shapes-sorted"  [(into (sorted-map) {"0" 1})] {:shapes true :stringref false}]
   ["shapes-plain"   [(array-map "0" 1) (array-map "0" 2)] {:shapes true :stringref false}]
   ["shapes-set"     [#{1 2} #{1 2}] {:shapes true :stringref false}]
   ["shapes-meta"    [(with-meta (array-map "a" 1) {:m 1}) (array-map "a" 2)] {:shapes true :stringref false}]
   ["queue"          (into #?(:clj clojure.lang.PersistentQueue/EMPTY
                              :cljs cljs.core.PersistentQueue.EMPTY) [1 2]) nil]
   ["empty-set"      #{} nil]
   ["set-of-1"       #{1} nil]
   ["char-a"         #?(:clj \a :cljs "a") nil]])
