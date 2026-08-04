(ns cljsbench.xdiff
  "Decode a boring fuzz corpus with the ClojureScript reader and emit outcome
  lines in the same platform-neutral normalization decode_jvm.clj's `lnorm`
  produces, so the two can be diffed byte for byte."
  (:require [boring.core :as boring]
            [cljs.reader]
            [boring.data :as bd]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(defn read-file [p] ((aget fs "readFileSync") p))
(defn write-file [p c] ((aget fs "writeFileSync") p c))

(defn hexb [^js u8]
  (let [sb (array)]
    (dotimes [i (.-length u8)]
      (.push sb (.padStart (.toString (aget u8 i) 16) 2 "0")))
    (.join sb "")))

(def enc (js/TextEncoder.))
(defn shex [s] (hexb (.encode enc s)))

(def dv (js/DataView. (js/ArrayBuffer. 8)))
(defn dbits [x]
  (.setFloat64 dv 0 x)
  (let [hi (.getUint32 dv 0) lo (.getUint32 dv 4)]
    (str (.padStart (.toString hi 16) 8 "0") (.padStart (.toString lo 16) 8 "0"))))

(declare lnorm)

(defn- num-norm [v]
  (cond
    (js/Number.isNaN v) "fNaN"
    (= v js/Infinity) "fInf"
    (= v (- js/Infinity)) "f-Inf"
    (and (= v 0) (< (/ 1 v) 0)) "f-0.0"
    (and (js/Number.isInteger v) (<= (js/Math.abs v) 9007199254740992)) (str "i" (.toFixed v 0))
    :else (str "f" (dbits v))))

(defn- typed-array? [v]
  (or (instance? js/Int8Array v) (instance? js/Uint8Array v)
      (instance? js/Int16Array v) (instance? js/Uint16Array v)
      (instance? js/Int32Array v) (instance? js/Uint32Array v)
      (instance? js/Float32Array v) (instance? js/Float64Array v)
      (instance? js/BigInt64Array v) (instance? js/BigUint64Array v)))

(defn lnorm [v]
  (cond
    (nil? v) "null"
    (true? v) "T"
    (false? v) "F"
    (= "bigint" (goog/typeOf v)) (str "i" (.toString v))
    (number? v) (num-norm v)
    (string? v) (str "s" (shex v))
    (keyword? v) (str "K" (shex (str (namespace v) "/" (name v))))
    (symbol? v) (str "Y" (shex (str (namespace v) "/" (name v))))
    (instance? bd/SimpleValue v) (if (= 23 (:n v)) "UNDEF" (str "SIMPLE" (:n v)))
    (instance? bd/TaggedValue v) (str "TAG" (lnorm (:tag v)) "(" (lnorm (:value v)) ")")
    (instance? js/Date v) (str "D" (.getTime v))
    (instance? js/RegExp v) (str "RE" (shex (.-source v)))
    (uuid? v) (str "U" (str v))
    (instance? js/Uint8Array v) (str "b" (hexb v))
    (typed-array? v) (str "TA[" (str/join "," (map lnorm (array-seq v))) "]")
    (map? v) (str "{" (str/join "," (sort (map (fn [[k x]] (str (lnorm k) "=>" (lnorm x))) v))) "}")
    (set? v) (str "#{" (str/join "," (sort (map lnorm v))) "}")
    (sequential? v) (str "[" (str/join "," (map lnorm v)) "]")
    (array? v) (str "[" (str/join "," (map lnorm (array-seq v))) "]")
    (record? v) (str "REC:" (pr-str v))
    :else (str "?" (goog/typeOf v) ":" (pr-str v))))

(defn rt [v opts]
  (try (let [e (boring/encode v opts)] (str " | E" (hexb e) " | " (lnorm (boring/decode e opts))))
       (catch ExceptionInfo e
         (if (= "boring" (some-> (ex-data e) :type namespace))
           (str " | EERR " (name (:type (ex-data e))))
           (str " | EUNTYPED " (.-message e))))
       (catch :default e (str " | EUNTYPED " (.-name e) " " (.-message e)))))

(defn outcome [bs opts]
  (try
    (let [v (boring/decode bs opts)] (str "OK " (lnorm v) (rt v opts)))
    (catch ExceptionInfo e
      (if (= "boring" (some-> (ex-data e) :type namespace))
        (str "ERR " (name (:type (ex-data e))))
        (str "UNTYPED ex-info " (pr-str (ex-data e)) " " (.-message e))))
    (catch :default e
      (str "UNTYPED " (.-name e) " " (.-message e)))))

(defn -main [& [corpus out opts-str]]
  (let [opts (when (and opts-str (not= opts-str "nil")) (cljs.reader/read-string opts-str))
        ^js buf (read-file corpus)
        u8 (js/Uint8Array. (.-buffer buf) (.-byteOffset buf) (.-length buf))
        n (.-length u8)
        lines (array)]
    (loop [p 0 i 0]
      (when (<= (+ p 4) n)
        (let [len (+ (* (aget u8 p) 16777216) (* (aget u8 (+ p 1)) 65536)
                     (* (aget u8 (+ p 2)) 256) (aget u8 (+ p 3)))
              d (.slice u8 (+ p 4) (+ p 4 len))]
          (.push lines (str i "\t" (hexb d) "\t" (outcome d opts)))
          (recur (+ p 4 len) (inc i)))))
    (write-file out (str (.join lines "\n") "\n"))
    (println "done" (.-length lines))))

(set! *main-cli-fn* -main)
