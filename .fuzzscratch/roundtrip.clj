(ns roundtrip
  "decode ∘ encode = identity over generated Clojure values, under every
  profile and every combination of :shapes / :stringref, plus byte stability
  encode(decode(encode v)) = encode v."
  (:require [boring.core :as b]
            [lnorm-jvm :as ln]
            [clojure.test.check.generators :as gen]
            [boring.generative-test :as g]))

(def cases
  (for [p [nil :interop :archival :canonical :canonical-rfc7049]
        sr [nil true false]
        sh [nil true]
        :let [o (cond-> {} p (assoc :profile p) (some? sr) (assoc :stringref sr) (some? sh) (assoc :shapes sh))]
        ;; profiles lock :stringref / :shapes; skip the conflicting combinations
        :when (or (nil? p) (= p :clojure) (and (nil? sr) (nil? sh)))]
    (if (empty? o) nil o)))

(defn -main [& [n-str]]
  (let [n (Long/parseLong (or n-str "20000"))
        tally (atom {}) bad (atom [])]
    (println "cases:" (pr-str cases))
    (dotimes [i n]
      (let [v (gen/generate g/gen-value (+ 5 (mod i 25)) i)]
        (doseq [o cases]
          (let [r (try
                    (let [e1 (b/encode v o)
                          v2 (b/decode e1 o)
                          e2 (b/encode v2 o)]
                      (cond
                        (not= (ln/lnorm v) (ln/lnorm v2)) [:value-drift (ln/lnorm v) (ln/lnorm v2)]
                        (not (java.util.Arrays/equals ^bytes e1 ^bytes e2)) [:byte-drift (ln/hex e1) (ln/hex e2)]
                        :else nil))
                    (catch clojure.lang.ExceptionInfo e
                      (if (= "boring" (some-> (ex-data e) :type namespace))
                        [:typed (:type (ex-data e))]
                        [:UNTYPED (pr-str (ex-data e)) (.getMessage e)]))
                    (catch Throwable e [:UNTYPED (.getName (class e)) (str (.getMessage e))]))]
            (swap! tally update [o (if r (first r) :ok)] (fnil inc 0))
            (when (and r (not= :typed (first r)))
              (swap! bad conj [i o (ln/lnorm v) r]))))))
    (println "\n== tally ==")
    (doseq [[k v] (sort-by (comp - val) @tally)] (println (format "  %-58s %8d" (pr-str k) v)))
    (println "\n== failures:" (count @bad) "==")
    (doseq [[k grp] (group-by (fn [[_ o r]] [o (first (nth r 0))]) @bad)]
      (println "\n---" (pr-str k) (count grp))
      (doseq [x (take 3 (sort-by #(count (nth % 2)) grp))] (println "   " (pr-str x))))))
