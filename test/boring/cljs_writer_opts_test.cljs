(ns boring.cljs-writer-opts-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [boring.core :as boring]))

(defn- bytes= [a b]
  (= (vec a) (vec b)))

(deftest reusable-writer-keeps-pre-resolved-options
  (let [v ["repeated-text" "repeated-text"]
        w (boring/writer 64 {:stringref false})]
    (testing "the two-arity reusable paths use options resolved at construction"
      (is (bytes= (boring/encode v {:stringref false})
                  (boring/encode-into! w v)))
      (let [n (boring/encode-buffered! w v)]
        (is (bytes= (boring/encode v {:stringref false})
                    (.slice (boring/buffer w) 0 n)))))
    (testing "an explicit call overrides but does not mutate the writer default"
      (is (not (bytes= (boring/encode v {:stringref false})
                       (boring/encode-into! w v {:stringref true}))))
      (is (bytes= (boring/encode v {:stringref false})
                  (boring/encode-into! w v))))
    (testing "write-seq!'s no-options arity uses the same resolved options"
      (let [chunks (atom [])]
        (boring/write-seq! w [v v] #(swap! chunks conj %))
        (is (every? #(bytes= (boring/encode v {:stringref false}) %) @chunks))))))
