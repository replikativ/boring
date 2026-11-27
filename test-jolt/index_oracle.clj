(ns index-oracle
  (:require [boring.core :as boring]
            [indexed-cases :as cases]))

(defn -main [dir]
  (doseq [[i v] (map-indexed vector cases/values)
          [mode writer] [["indexed" boring/encode-indexed] ["value" boring/encode]]]
    (with-open [out (java.io.FileOutputStream. (str dir "/" mode "-" i ".cbor"))]
      (.write out ^bytes (writer v)))))
