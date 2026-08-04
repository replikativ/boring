(ns idem
  "Re-encode/re-decode idempotence over a generated CBOR corpus.

  Property: for a document d that boring accepts,
      norm(decode(encode(decode d, P), P)) == norm(decode d)
  under every profile P, plus byte stability
      encode(decode(encode(decode d))) == encode(decode d)."
  (:require [boring.core :as b]
            [decode-jvm :as dj])
  (:import (java.io DataInputStream FileInputStream BufferedInputStream)))

(def profiles
  [[:clojure nil]
   [:clojure-nosr {:stringref false}]
   [:clojure-shapes {:shapes true}]
   [:interop {:profile :interop}]
   [:archival {:profile :archival}]
   [:canonical {:profile :canonical}]
   [:canonical7049 {:profile :canonical-rfc7049}]])

(defn docs [path]
  (let [in (DataInputStream. (BufferedInputStream. (FileInputStream. ^String path)))]
    (letfn [(step []
              (lazy-seq
               (let [n (try (.readInt in) (catch java.io.EOFException _ nil))]
                 (if n
                   (let [bs (byte-array n)] (.readFully in bs) (cons bs (step)))
                   (do (.close in) nil)))))]
      (step))))

(defn -main [& paths]
  (let [tally (atom {})
        bad (atom [])]
    (doseq [p paths
            [i ^bytes d] (map-indexed vector (docs p))]
      (let [v (try {:v (b/decode d)} (catch Throwable _ nil))]
        (when v
          (let [n0 (dj/norm (:v v))]
            (doseq [[pname opts] profiles]
              (let [r (try
                        (let [e1 (b/encode (:v v) opts)
                              v2 (b/decode e1)
                              n2 (dj/norm v2)
                              e2 (b/encode v2 opts)]
                          (cond
                            (not= n0 n2) [:value-drift n0 n2 (dj/hex e1)]
                            (not (java.util.Arrays/equals ^bytes e1 ^bytes e2)) [:byte-drift (dj/hex e1) (dj/hex e2)]
                            :else nil))
                        (catch clojure.lang.ExceptionInfo e
                          (if (= "boring" (some-> (ex-data e) :type namespace))
                            [:typed-throw (:type (ex-data e))]
                            [:UNTYPED (pr-str (ex-data e)) (.getMessage e)]))
                        (catch Throwable e [:UNTYPED (.getName (class e)) (str (.getMessage e))]))]
                (swap! tally update [pname (if r (first r) :ok)] (fnil inc 0))
                (when r
                  (swap! bad conj [p i pname (dj/hex d) r]))))))))
    (println "\n== tally ==")
    (doseq [[k v] (sort-by (comp - val) @tally)] (println (format "  %-40s %7d" (pr-str k) v)))
    (println "\n== failures:" (count @bad) "==")
    ;; shortest first, grouped by (profile, kind)
    (doseq [[k grp] (group-by (fn [[_ _ pn _ r]] [pn (first r)]) @bad)]
      (println "\n---" (pr-str k) (count grp))
      (doseq [[p i pn h r] (take 4 (sort-by (fn [[_ _ _ h _]] (count h)) grp))]
        (println "   " p "#" i "\n     doc:" h "\n     " (pr-str r))))))
