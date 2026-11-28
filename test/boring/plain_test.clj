(ns boring.plain-test
  (:require [boring.core :as boring]
            [boring.plain :as plain]
            [clojure.test :refer [deftest is]]))

(deftest plain-writer-agrees-with-boring
  (doseq [v [nil true false -1 0 23 24 255 256 65535 65536 4294967296
             Long/MIN_VALUE Long/MAX_VALUE :a/b "€" "" "café" (apply str (repeat 256 "a"))
             [] {} (java.util.Date. 1577934245006)
             (array-map :k [nil true -42 256] :s "abc")]]
    (let [reference (boring/encode v {:stringref false})
          actual (plain/encode v)]
      (is (= (seq reference) (seq actual)) (pr-str v))
      (is (= v (plain/decode reference))))))

(deftest malformed-and-unsupported
  (doseq [octets [[97] [97 255] [1 2] [249 0 20] [216 25 1]
                  [162 1 2 1 3] [27 128 0 0 0 0 0 0 0]]]
    (is (thrown? Exception (plain/decode (byte-array (map unchecked-byte octets)))))))

(deftest indexed-unreferenced-value-is-written-once
  (let [v (Object.)
        calls (atom 0)
        writer (fn [_] (swap! calls inc) "payload")]
    (binding [plain/*registry* {:writers {Object {:tag 42 :writer writer}}}]
      (let [expected (plain/encode-indexed v {:stringref false})]
        (reset! calls 0)
        (is (java.util.Arrays/equals ^bytes expected ^bytes (plain/encode-indexed v)))
        (is (= 1 @calls))))))
