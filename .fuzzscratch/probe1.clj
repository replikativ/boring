(require '[clojure.string])
(require '[boring.core :as b] '[boring.data :as bd])
(defn h [s] (let [s (clojure.string/replace s " " "")] (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16)) (partition 2 s)))))
(defn t [label s]
  (print (format "%-30s %-22s " label s))
  (try (let [v (b/decode (h s))]
         (println (pr-str v) " class=" (some-> v class .getSimpleName)))
       (catch Exception e (println "ERR" (:type (ex-data e)) (.getMessage e)))))
;; tag 39 identifier edge cases
(t "tag39 empty text"      "d827 60")
(t "tag39 \"]\""           "d827 615d")
(t "tag39 \":\""           "d827 613a")
(t "tag39 \"::a\""         "d827 633a3a61")
(t "tag39 \"a/b/c\""       "d827 6561 2f62 2f63")
(t "tag39 \" \""           "d827 6120")
(t "tag39 \"a b\""         "d827 6361 2062")
(t "tag39 nested tag39"    "d827 d827 6161")
;; tag 55799 self-describe
(t "55799 wrapping 1"      "d9d9f7 01")
(t "55799 wrapping map"    "d9d9f7 a10102")
;; tag 100 / 1004
(t "tag100 int"            "d864 13")
(t "tag1004 string"        "d903ec 6a323032302d30312d3031")
;; tag 32 uri
(t "tag32 uri"             "d820 63612f62")
