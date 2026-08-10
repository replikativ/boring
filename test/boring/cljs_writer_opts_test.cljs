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

(deftest write-seq-refuses-stringref-the-same-way-the-jvm-does
  ;; D1, pinned. `write-root!` resets the writer per top-level item, so every
  ;; item opens its own stringref namespace numbered from zero, and one index
  ;; frame carries one pointer table -- it can describe at most one of them.
  ;;
  ;; The JVM used to force `:stringref false` only when indexing, on the
  ;; grounds that indexing declares navigational intent. This platform cannot
  ;; index at all, so it had no such trigger and forced unconditionally. That
  ;; left `{:index 0}` -- the documented OFF switch -- as the one spelling
  ;; where the two platforms wrote DIFFERENT BYTES for the same call, and only
  ;; the JVM's were unnavigable. Both now force at every stride.
  (let [v ["repeated-text" "repeated-text"]
        w (boring/writer 64)]
    (testing "an explicit :stringref true is refused, not silently dropped"
      (is (= :boring/incompatible-options
             (try (do (boring/write-seq! w [v v] (fn [_]) {:stringref true}) nil)
                  (catch :default e (:type (ex-data e)))))))
    (testing ":stringref false is how you say it out loud, and still works"
      (let [chunks (atom [])]
        (boring/write-seq! w [v v] #(swap! chunks conj %) {:stringref false})
        (is (= 2 (count @chunks)))))
    (testing "the default output opens no namespace, so nav can read it"
      ;; d9 0100 is tag 256. Its absence is the whole navigability claim.
      (let [chunks (atom [])]
        (boring/write-seq! w [v v] #(swap! chunks conj %))
        (is (every? #(not= [0xd9 0x01 0x00] (vec (.slice % 0 3))) @chunks))))))

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
