(ns lnorm-jvm
  "Platform-neutral normalization matching cljsbench.xdiff/lnorm."
  (:require [boring.core :as b]
            [clojure.string :as str])
  (:import (java.io DataInputStream FileInputStream BufferedInputStream
                    BufferedWriter FileWriter)))

(defn hex ^String [^bytes bs]
  (let [sb (StringBuilder.)]
    (dotimes [i (alength bs)] (.append sb (format "%02x" (bit-and (aget bs i) 0xff))))
    (.toString sb)))

(defn shex [^String s] (hex (.getBytes s "UTF-8")))

(declare lnorm)

(defn- num-norm [v]
  (let [d (double v)]
    (cond
      (Double/isNaN d) "fNaN"
      (Double/isInfinite d) (if (pos? d) "fInf" "f-Inf")
      (and (zero? d) (neg? (Math/copySign 1.0 d))) "f-0.0"
      (and (== d (Math/rint d)) (<= (Math/abs d) 9007199254740992.0))
      (str "i" (.toBigInteger (BigDecimal/valueOf d)))
      :else (str "f" (format "%016x" (Double/doubleToLongBits d))))))

(defn- ident-norm [v]
  (shex (str (namespace v) "/" (name v))))

(defn lnorm [v]
  (cond
    (nil? v) "null"
    (true? v) "T"
    (false? v) "F"
    (integer? v) (str "i" (biginteger v))
    (float? v) (num-norm v)
    (string? v) (str "s" (shex v))
    (bytes? v) (str "b" (hex v))
    (keyword? v) (str "K" (ident-norm v))
    (symbol? v) (str "Y" (ident-norm v))
    (instance? boring.data.SimpleValue v) (if (= 23 (:n v)) "UNDEF" (str "SIMPLE" (:n v)))
    (instance? boring.data.TaggedValue v) (str "TAG" (lnorm (:tag v)) "(" (lnorm (:value v)) ")")
    (instance? java.util.Date v) (str "D" (.getTime ^java.util.Date v))
    (instance? java.util.regex.Pattern v) (str "RE" (shex (.pattern ^java.util.regex.Pattern v)))
    (uuid? v) (str "U" (str v))
    (instance? (Class/forName "[S") v) (str "TA[" (str/join "," (map lnorm (seq ^shorts v))) "]")
    (instance? (Class/forName "[I") v) (str "TA[" (str/join "," (map lnorm (seq ^ints v))) "]")
    (instance? (Class/forName "[J") v) (str "TA[" (str/join "," (map lnorm (seq ^longs v))) "]")
    (instance? (Class/forName "[F") v) (str "TA[" (str/join "," (map lnorm (seq ^floats v))) "]")
    (instance? (Class/forName "[D") v) (str "TA[" (str/join "," (map lnorm (seq ^doubles v))) "]")
    (map? v) (str "{" (str/join "," (sort (map (fn [[k x]] (str (lnorm k) "=>" (lnorm x))) v))) "}")
    (set? v) (str "#{" (str/join "," (sort (map lnorm v))) "}")
    (sequential? v) (str "[" (str/join "," (map lnorm v)) "]")
    (instance? (Class/forName "[Ljava.lang.Object;") v) (str "[" (str/join "," (map lnorm v)) "]")
    (record? v) (str "REC:" (pr-str v))
    :else (str "?" (.getSimpleName (class v)) ":" (pr-str v))))

(defn rt [v opts]
  (try (let [e (b/encode v opts)] (str " | E" (hex e) " | " (lnorm (b/decode e opts))))
       (catch clojure.lang.ExceptionInfo e
         (if (= "boring" (some-> (ex-data e) :type namespace))
           (str " | EERR " (name (:type (ex-data e))))
           (str " | EUNTYPED " (.getMessage e))))
       (catch Throwable e (str " | EUNTYPED " (.getName (class e)) " " (.getMessage e)))))

(defn outcome [^bytes bs opts]
  (try (let [v (b/decode bs opts)] (str "OK " (lnorm v) (rt v opts)))
       (catch clojure.lang.ExceptionInfo e
         (if (= "boring" (some-> (ex-data e) :type namespace))
           (str "ERR " (name (:type (ex-data e))))
           (str "UNTYPED ex-info " (pr-str (ex-data e)) " " (.getMessage e))))
       (catch StackOverflowError _ "UNTYPED StackOverflowError")
       (catch Throwable e (str "UNTYPED " (.getName (class e)) " " (.getMessage e)))))

(defn -main [& [corpus out opts-str]]
  (let [opts (when (and opts-str (not= opts-str "nil")) (read-string opts-str))]
    (with-open [in (DataInputStream. (BufferedInputStream. (FileInputStream. ^String corpus)))
                w  (BufferedWriter. (FileWriter. ^String out))]
      (loop [i 0]
        (let [n (try (.readInt in) (catch java.io.EOFException _ nil))]
          (when n
            (let [bs (byte-array n)]
              (.readFully in bs)
              (.write w (str i "\t" (hex bs) "\t"
                             (try (outcome bs opts)
                                  (catch Throwable e (str "UNTYPED-OUTER " (.getName (class e)))))
                             "\n"))
              (recur (inc i)))))))
    (println "done")))
