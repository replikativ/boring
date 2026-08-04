(ns xplat
  "Write a corpus of boring's OWN encodings of generated values, one per
  profile, alongside the JVM's normalization of the value. ClojureScript then
  decodes the same corpus and the two normalizations are diffed: this is the
  server-writes/browser-reads path."
  (:require [boring.core :as b]
            [lnorm-jvm :as ln]
            [clojure.test.check.generators :as gen]
            [boring.generative-test :as g])
  (:import (java.io DataOutputStream FileOutputStream BufferedOutputStream
                    BufferedWriter FileWriter)))

(def cases [nil {:shapes true} {:stringref false} {:profile :interop}
            {:profile :archival} {:profile :canonical} {:profile :canonical-rfc7049}])

(defn -main [& [out expected n-str]]
  (let [n (Long/parseLong (or n-str "8000"))]
    (with-open [dos (DataOutputStream. (BufferedOutputStream. (FileOutputStream. ^String out)))
                w (BufferedWriter. (FileWriter. ^String expected))]
      (let [i (atom 0)]
        (dotimes [k n]
          (let [v (gen/generate g/gen-value (+ 5 (mod k 25)) k)]
            (doseq [o cases]
              (when-let [e (try (b/encode v o) (catch Throwable _ nil))]
                (.writeInt dos (alength ^bytes e))
                (.write dos ^bytes e 0 (alength ^bytes e))
                ;; the JVM's own decode of its own bytes is the reference: it
                ;; already round-trips (roundtrip.clj proves it), and it is what
                ;; ClojureScript has to agree with.
                (.write w (str @i "\t" (ln/hex e) "\t"
                               (try (str "OK " (ln/lnorm (b/decode e o)))
                                    (catch Throwable t (str "ERR " (.getMessage t))))
                               "\t" (pr-str o) "\n"))
                (swap! i inc)))))
        (println "wrote" @i "encodings")))))
