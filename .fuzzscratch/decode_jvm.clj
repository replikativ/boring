(ns decode-jvm
  "Decode a corpus file with boring on the JVM and emit one outcome line per doc."
  (:require [boring.core :as b]
            [boring.data :as bd]
            [clojure.string :as str])
  (:import (java.io DataInputStream FileInputStream BufferedInputStream
                    BufferedWriter FileWriter)))

(defn hex ^String [^bytes bs]
  (let [sb (StringBuilder.)]
    (dotimes [i (alength bs)]
      (.append sb (format "%02x" (bit-and (aget bs i) 0xff))))
    (.toString sb)))

(declare norm)

(defn- norm-map [m]
  (str "{" (str/join "," (sort (map (fn [[k v]] (str (norm k) "=>" (norm v))) m))) "}"))

(defn norm [v]
  (cond
    (nil? v) "null"
    (true? v) "T"
    (false? v) "F"
    (integer? v) (str "i" (biginteger v))
    (float? v) (let [d (double v)]
                 (cond (Double/isNaN d) "fNaN"
                       (Double/isInfinite d) (if (pos? d) "fInf" "f-Inf")
                       (and (zero? d) (neg? (Math/copySign 1.0 d))) "f-0.0"
                       :else (str "f" (Double/toString d))))
    (string? v) (str "s" (pr-str v))
    (bytes? v) (str "b" (hex v))
    (instance? boring.data.SimpleValue v) (if (= 23 (:n v)) "UNDEF" (str "SIMPLE" (:n v)))
    (instance? boring.data.TaggedValue v) (str "TAG" (:tag v) "(" (norm (:value v)) ")")
    (keyword? v) (str "K" (subs (str v) 1))
    (symbol? v) (str "Y" v)
    (map? v) (norm-map v)
    (set? v) (str "#{" (str/join "," (sort (map norm v))) "}")
    (sequential? v) (str "[" (str/join "," (map norm v)) "]")
    (instance? (Class/forName "[Ljava.lang.Object;") v) (str "[" (str/join "," (map norm v)) "]")
    (instance? (Class/forName "[S") v) (str "TA[" (str/join "," (map norm (seq ^shorts v))) "]")
    (instance? (Class/forName "[I") v) (str "TA[" (str/join "," (map norm (seq ^ints v))) "]")
    (instance? (Class/forName "[J") v) (str "TA[" (str/join "," (map norm (seq ^longs v))) "]")
    (instance? (Class/forName "[F") v) (str "TA[" (str/join "," (map norm (seq ^floats v))) "]")
    (instance? (Class/forName "[D") v) (str "TA[" (str/join "," (map norm (seq ^doubles v))) "]")
    :else (str "?" (.getSimpleName (class v)) ":" (pr-str v))))

(defn outcome [^bytes bs opts]
  (try
    (let [v (b/decode bs opts)] (str "OK " (norm v)))
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
