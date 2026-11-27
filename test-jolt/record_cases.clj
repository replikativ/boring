(ns record-cases
  (:require [boring.core :as b] [boring.data :as data])
  (:import [java.nio.file Files Paths]))

(defrecord Point [x y])
(defrecord Token [v])
(def point (assoc (->Point 7 8) :extra :value))
(def token (->Token "hello"))
(def point-name "record-cases/Point")
(def registry (-> (b/tag-registry)
                  (b/register-records {(symbol point-name) map->Point})
                  (b/register-tag 40001 (type token) :v ->Token)))

(defn -main [mode dir]
  (assert (= point-name (data/record-type-name point)))
  (doseq [[label writer] [["ordinary" b/encode] ["indexed" b/encode-indexed]]
          [i v] (map-indexed vector [point token [point token] (vec (repeat 128 point))
                                    (with-meta [token (with-meta point {:child true})] {:root true})
                                    (vec (repeat 128 (with-meta token {:index true})))
                                    #{:only}
                                    (vec (repeat 128 [#uuid "12345678-1234-1234-9234-123456789012"
                                                      "after UUID" "after UUID"]))])]
    (let [bs (writer v {:registry registry})
          file (str dir "/record-" label "-" i)]
      (if (= mode "write")
        (with-open [out (java.io.FileOutputStream. file)] (.write out ^bytes bs))
        (let [oracle (Files/readAllBytes (Paths/get file (make-array String 0)))]
          (assert (= (seq oracle) (seq bs)) (str label "/" i))
          (assert (= v (b/decode oracle {:registry registry})))
          (when (= i 4)
            (let [back (b/decode oracle {:registry registry})]
              (assert (= {:root true} (meta back)))
              (assert (= {:child true} (meta (second back))))
              (assert (nil? (meta (b/decode (writer v {:registry registry :incl-metadata? false})
                                            {:registry registry}))))))
          (when (= i 0)
            (let [unknown (b/decode oracle)]
              (assert (data/unknown-record? unknown))
              (assert (= point-name (data/record-type unknown)))
              (assert (= (seq oracle) (seq (writer unknown))))))
          (when (= i 1)
            (let [unknown (b/decode oracle)]
              (assert (data/tagged-value? unknown))
              (assert (= (seq oracle) (seq (writer unknown)))))))))))
