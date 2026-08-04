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

(deftest instant-type-can-be-a-constructor
  (testing "JavaScript has one time type, so the JVM's `:date`/`:instant`
            keyword choice has no counterpart -- and the option was accepted
            and silently ignored, which this platform's own policy says it does
            not do. A caller using a cross-platform time library
            (`cljc.java-time`, `tick`) has a type they would rather have back,
            and boring should not depend on js-joda to allow it. So the option
            takes a function of epoch milliseconds."
    (let [bs (boring/encode (js/Date. 1234567890123))]
      (testing "the control: without it, a js/Date comes back as always"
        (is (instance? js/Date (boring/decode bs))))
      (testing "with a constructor, the caller's value comes back"
        (is (= {:epoch 1234567890123}
               (boring/decode bs {:instant-type (fn [ms] {:epoch ms})}))))
      (testing "and a value that is neither a known keyword nor a function is
                still refused, rather than ignored as it used to be"
        (is (= :boring/bad-option
               (try (do (boring/decode bs {:instant-type "nope"}) nil)
                    (catch :default e (:type (ex-data e))))))))))
