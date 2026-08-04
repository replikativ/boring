(ns cljsbench.audit5
  "Scratch audit namespace. DELETE AFTER USE."
  (:require [boring.core :as boring]
            [boring.data :as bdata]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def dir "/tmp/claude-1000/-home-christian-weilbach-Development-boring/f011c97d-0e88-42d3-b435-cb80b8ba9317/scratchpad/")

(defn hx [s]
  (let [s (str/replace s #"\s" "")
        n (/ (count s) 2)
        a (js/Uint8Array. n)]
    (dotimes [i n] (aset a i (js/parseInt (subs s (* 2 i) (+ 2 (* 2 i))) 16)))
    a))

(defn hexstr [b]
  (apply str (map #(let [s (.toString % 16)] (if (== 1 (count s)) (str "0" s) s))
                  (array-seq b))))

(defn cat-bytes [& arrs]
  (let [n (reduce + (map #(.-length %) arrs))
        out (js/Uint8Array. n)]
    (loop [o 0 [a & r] arrs]
      (when a (.set out a o) (recur (+ o (.-length a)) r)))
    out))

(defn ptr8 [n]
  (let [a (js/Uint8Array. 8)]
    (dotimes [i 8] (aset a (- 7 i) (bit-and (bit-shift-right n (* 8 i)) 0xff)))
    a))

(defn try* [f]
  (try {:ok (f)}
       (catch :default e
         (if-let [d (ex-data e)] {:err (:type d)} {:untyped (.-message e)}))))

(def O {:stringref false})

(defn pull-of [blocks]
  (let [i (atom 0)]
    (fn [] (let [k @i] (when (< k (count blocks)) (swap! i inc) (nth blocks k))))))

(defn show [xs] (mapv (fn [x] (if (bdata/unknown-record? x) :FRAME x)) xs))

;; ---------------------------------------------------------------- corpus run
(defn corpus []
  (let [lines (str/split-lines (.toString ((aget fs "readFileSync") (str dir "corpus.txt"))))
        sb (js/Array.)]
    (doseq [line lines]
      (when (seq line)
        (let [[label h] (str/split line #"\t")
              bs (hx h)
              verdict (try
                        (let [v (boring/decode bs)
                              re (try (hexstr (boring/encode v)) (catch :default _ "NOENC"))]
                          (str "ok\t" re))
                        (catch :default e
                          (if-let [d (ex-data e)]
                            (str "err:" (:type d) "\t-")
                            (str "UNTYPED:" (.-name e) "\t-"))))]
          (.push sb (str label "\t" verdict "\n")))))
    ((aget fs "writeFileSync") (str dir "cljs.txt") (.join sb ""))
    (println "cljs corpus done:" (.-length sb))))

;; ------------------------------------------------------- decode-seq-from bugs
(defn part1 []
  (let [frame (boring/encode (bdata/unknown-record "boring/index"
                                                   [0 0 0 0 false (ptr8 0)]) O)
        rest-b (cat-bytes (boring/encode 3 O) (boring/encode 4 O) (boring/encode 5 O))
        whole (cat-bytes frame rest-b)]
    (println "=== 1. frame at offset 0 ending exactly at a pull-block boundary ===")
    (println "  frame =" (hexstr frame) "(" (.-length frame) "bytes )")
    (println "  decode-seq (array)            ->" (pr-str (try* #(show (boring/decode-seq whole O)))))
    (println "  decode-seq-from [whole]       ->" (pr-str (try* #(show (boring/decode-seq-from (pull-of [whole]) O)))))
    (println "  decode-seq-from [frame][rest] ->" (pr-str (try* #(show (boring/decode-seq-from (pull-of [frame rest-b]) O)))))))

(defn part2 []
  ;; A genuine footer whose absolute offset is NOT its buffer-relative offset
  ;; once compaction has discarded earlier bytes.
  (let [items (mapv #(boring/encode % O) (range 1 41))
        data (apply cat-bytes items)
        d (.-length data)
        frame (boring/encode (bdata/unknown-record "boring/index"
                                                   [0 0 0 0 false (ptr8 d)]) O)
        whole (cat-bytes data frame)]
    (println)
    (println "=== 2. genuine footer at ABSOLUTE offset" d "- does compaction break detection? ===")
    (println "  total" (.-length whole) "bytes; decode-seq ->"
             (pr-str (try* #(count (show (boring/decode-seq whole O))))))
    (doseq [bs [1000 64 32 16 8]]
      (let [blocks (vec (for [i (range 0 (.-length whole) bs)]
                          (.slice whole i (min (.-length whole) (+ i bs)))))]
        (println (str "  decode-seq-from block " bs " -> "
                      (pr-str (try* #(count (show (boring/decode-seq-from
                                                   (pull-of blocks)
                                                   (assoc O :chunk-size bs)))))))))))
  ;; the same, but the footer's pointer equals its BUFFER-relative offset
  (let [items (mapv #(boring/encode % O) (range 1 41))
        data (apply cat-bytes items)
        frame (boring/encode (bdata/unknown-record "boring/index"
                                                   [0 0 0 0 false (ptr8 0)]) O)
        whole (cat-bytes data frame)]
    (println "  -- footer with pointer 0 (a LIE about its offset) --")
    (println "  decode-seq (array) ->" (pr-str (try* #(count (show (boring/decode-seq whole O))))))
    (doseq [bs [1000 16]]
      (let [blocks (vec (for [i (range 0 (.-length whole) bs)]
                          (.slice whole i (min (.-length whole) (+ i bs)))))]
        (println (str "  decode-seq-from block " bs " -> "
                      (pr-str (try* #(count (show (boring/decode-seq-from
                                                   (pull-of blocks)
                                                   (assoc O :chunk-size bs))))))))))))

(defn part3 []
  (println)
  (println "=== 3. register-tag / option validation ===")
  (doseq [[l f] [["register-tag 2^64-1" #(boring/register-tag (boring/tag-registry) (js/BigInt "18446744073709551615") nil nil identity)]
                 ["register-tag 2^53"   #(boring/register-tag (boring/tag-registry) 9007199254740992 nil nil identity)]
                 ["register-tag -1"     #(boring/register-tag (boring/tag-registry) -1 nil nil identity)]
                 ["decode {:max-depth nil}"  #(boring/decode (hx "83010203") {:max-depth nil})]
                 ["decode {:max-depth \"5\"}" #(boring/decode (hx "83010203") {:max-depth "5"})]
                 ["decode {:max-depth 3e9}"  #(boring/decode (hx "83010203") {:max-depth 3000000000})]
                 ["decode {:max-depth 1.5}"  #(boring/decode (hx "83010203") {:max-depth 1.5})]
                 ["decode {:max-items \"x\"}" #(boring/decode (hx "83010203") {:max-items "x"})]
                 ["decode nil"               #(boring/decode nil)]
                 ["decode \"abc\""           #(boring/decode "abc")]]]
    (println (str "  " l " -> " (pr-str (try* f))))))

(defn -main [& _] (corpus) (part1) (part2) (part3))

(set! *main-cli-fn* -main)
