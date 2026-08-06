(ns boring.shaped-nav-test
  "Navigating a shaped array without realising it.

   A shaped array is `39649([keys, [row, row, ...]])`: the keys are hoisted out
   and each row is an ARRAY of values positionally matching them. It REALISES to
   a vector of maps, so a cursor standing on a row has array bytes and map
   semantics, and every question asked of it has to be answered in terms of the
   value rather than the bytes.

   THE INVARIANT IS ONE LINE: navigating must agree with realising, whatever the
   wire form. That is what these tests check, and they check it against the SAME
   data encoded WITHOUT `:shapes` -- comparing the shaped path to a known-good
   one rather than to itself.

   Before this existed, `valAt` on the tag realised the whole array to answer one
   lookup. Correct, and O(array): 206.83 us on 5000 rows against 5.60 us
   navigated, with the navigated cost FLAT in row count."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring]
            [boring.nav :as nav]
            [boring.nav-conformance :as nc]))

(def ^:private opts {:stringref false})
(def ^:private trusted (assoc opts :trust-index :trusted))

(defn- shaped ^bytes [v]
  (boring/encode-indexed v (assoc opts :shapes true :index 16 :index-min 8)))

(defn- plain ^bytes [v]
  (boring/encode-indexed v (assoc opts :index 16 :index-min 8)))

(defn- tagged?
  "Whether the writer actually emitted a shaped array. It shapes only when the
   key sets match and it pays, so tests must detect rather than assume."
  [^bytes bs]
  (= 6 (bit-shift-right (bit-and (aget bs 0) 0xff) 5)))

(def ^:private records
  (vec (for [i (range 200)]
         {:id i
          :name (str "user-" i)
          :city (nth ["Berlin" "Hamburg" "Munich"] (mod i 3))
          :score (double (mod (* i 7) 100))})))

(deftest a-shaped-array-navigates-as-the-vector-of-maps-it-realises-to
  (let [bs (shaped records)
        c (nav/source bs trusted)]
    (is (tagged? bs) "the writer must have shaped this, or the test proves nothing")
    (testing "the array itself"
      (is (= (count records) (count c)))
      (is (= records (nav/value c)))
      (is (= records (mapv nav/value (seq c))))
      (is (= records (into [] (map nav/value) c)) "reduce")
      (is (= (records 0) (nav/value (get c 0))) "an integer key indexes rows")
      (is (nil? (get c :nope)) "a vector has no keyword keys")
      (is (nil? (nth c (count records) nil)) "out of range")
      (is (nil? (nth c -1 nil)) "negative index"))
    (testing "a row is a MAP even though its bytes are an array"
      (doseq [i [0 1 99 199]]
        (let [row (nth c i)]
          (is (= :map (nav/value-type row)) "value-type must not report the bytes")
          (is (= (records i) (nav/value row)))
          (is (= 4 (count row)))
          (is (= (records i) (into {} (map (fn [[k v]] [k (nav/value v)])) (seq row))))
          (is (= (records i) (into {} (map (fn [[k v]] [k (nav/value v)])) row))
              "reduce over a row yields entries")
          (is (contains? row :id))
          (is (not (contains? row :nope)))
          (is (nil? (nth row 0 nil))
              "nth on a map answers not-found, and a row IS a map"))))))

(deftest shaped-and-unshaped-answer-identically
  (testing "the same data, two wire forms, every key of every row -- including
            keys and indexes that are absent."
    (doseq [n [0 1 2 7 200]]
      (let [v (subvec records 0 n)
            cs (nav/source (shaped v) trusted)
            cp (nav/source (plain v) trusted)]
        (is (= (nav/value cp) (nav/value cs)) (str n ": whole value"))
        (is (= (count cp) (count cs)) (str n ": count"))
        (dotimes [i n]
          (doseq [k [:id :name :city :score :nope "id" 0 nil]]
            (is (= (nav/value (get (nth cp i) k))
                   (nav/value (get (nth cs i) k)))
                (str n "/" i " key " (pr-str k)))))))))

(deftest conformance-agrees-on-shaped-arrays
  (testing "the same navigate-equals-realise property the assertions below check
            by hand, through the helper downstream users are told to run. Its
            absence here is why A2 -- `zipper` never learning about descents --
            survived: nothing exercised the whole surface in one place."
    (doseq [n [0 1 2 40]]
      (let [v (vec (for [i (range n)] {:id i :name (str "u" i) :ok (even? i)}))]
        (is (nil? (nc/check-value v (assoc opts :shapes true)))
            (str n " shaped rows"))))))

(deftest a-tag-with-no-descent-still-realises
  (testing "descent is an optimisation for forms boring itself writes. A tag
            with no descent must keep realising, including the typed refusal
            from `reduce` -- which must not decay into a silent empty result.

            A SET is the example, not a sorted-map: sorted-maps grew a descent
            of their own in `boring.record-nav-test`, and this test asserted
            they had none. Sets keep `get`-as-membership semantics that no
            structural descent reproduces, so they are the honest case."
    (let [bs (boring/encode-indexed #{1 2 3} opts)
          c (nav/source bs trusted)]
      (is (= #{1 2 3} (nav/value c)))
      (is (= 1 (nav/value (get c 1))) "membership still answers through realising")
      (is (nil? (get c 99)) "and a non-member is absent")
      (is (thrown? clojure.lang.ExceptionInfo (reduce (fn [a _] a) nil c))
          "a tag with no descent is still refused by reduce, not silently empty"))))

(defspec navigating-a-shaped-array-agrees-with-realising-it 150
  (prop/for-all
   [ms (gen/vector
        ;; the same key set in every map, which is what the writer shapes
        (gen/let [id gen/small-integer
                  nm gen/string-alphanumeric
                  ok gen/boolean]
          {:id id :nm nm :ok ok})
        0 25)]
   (let [v (vec ms)
         bs (shaped v)
         c (nav/source bs trusted)]
     (and (= v (nav/value c))
          (= (count v) (count c))
          (= v (mapv nav/value (seq c)))
          (every? (fn [i]
                    (let [row (nth c i)]
                      (and (= (v i) (nav/value row))
                           (= (count (v i)) (count row))
                           (every? #(= (get (v i) %) (nav/value (get row %)))
                                   [:id :nm :ok :absent]))))
                  (range (count v)))))))
