(ns boring.typed-nav-test
  "Indexing an RFC 8746 typed array without building it.

   A typed array's elements are PACKED into a byte string rather than written as
   CBOR items, so element i sits at `data + i*size`. That makes `nth` arithmetic
   -- no walk, and no index needed. Reaching one element used to realise the
   whole primitive array: 70.90 us on a 100 000-element long[] against 1.766 us
   navigated, and the navigated cost is flat in length.

   THE RISK HERE IS A WRONG NUMBER, NOT A SLOW ONE. Each of RFC 8746's tags has
   its own widening rule -- uint8 becomes short[], not byte[], so that 200 does
   not read back as -56 -- so descent is implemented only for the five tags
   boring WRITES, and every other tag falls back to realising.

   The tests therefore compare BOXED TYPE as well as value. `=` on numbers is
   numeric in Clojure, so `(= (short 1) (long 1))` is true and a widening
   mistake would pass a value-only check."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring]
            [boring.nav :as nav]
            [boring.nav-conformance :as nc]))

(def ^:private opts {:stringref false})

(def ^:private kinds
  [["short[]"  (fn [n] (short-array (map short (range n))))]
   ["int[]"    (fn [n] (int-array (map int (range n))))]
   ["long[]"   (fn [n] (long-array (range n)))]
   ["float[]"  (fn [n] (float-array (map (fn [i] (+ 0.5 (float i))) (range n))))]
   ["double[]" (fn [n] (double-array (map (fn [i] (+ 0.25 (double i))) (range n))))]])

(deftest navigating-a-typed-array-agrees-with-realising-it
  (doseq [[label mk] kinds
          n [0 1 5 64 300]]
    (testing (str label " of " n)
      (let [a (mk n)
            bs (boring/encode a opts)
            c (nav/root bs opts)
            realised (boring/decode bs opts)]
        (is (= (seq a) (seq realised)) "the fixture must round-trip at all")
        (is (= n (count c))
            "count used to refuse every tag while nth on this one worked")
        (dotimes [i n]
          (let [navigated (nth c i)
                expected (nth realised i)]
            (is (= expected navigated) (str "element " i))
            (is (= (class expected) (class navigated))
                (str "element " i " boxed type -- = on numbers is numeric, so
                     a widening mistake would pass a value-only check"))
            (is (= navigated (get c i)) (str "get agrees with nth at " i))))
        (is (nil? (nth c n nil)) "one past the end")
        (is (nil? (nth c -1 nil)) "negative index")
        (is (nil? (get c :nope)) "a keyword key is absent")))))

(deftest conformance-agrees-on-typed-arrays
  (testing "through the helper, and over the SIGNED half of the domain -- the
            fixtures below build from `(range n)`, which is exactly how S1 hid:
            checked casts only throw once a value goes negative."
    (doseq [[label mk] kinds
            n [0 1 5 64]]
      (is (nil? (nc/check-value (mk n) opts)) (str label " " n " non-negative"))))
  (doseq [[label a] [["short[]" (short-array (map short [-2 -1 0 1]))]
                     ["int[]" (int-array [-2 -1 0 1])]
                     ["long[]" (long-array [-2 -1 0 1])]
                     ["float[]" (float-array [-1.5 0.0 1.5])]
                     ["double[]" (double-array [-1.5 0.0 1.5])]]]
    (is (nil? (nc/check-value a opts)) (str label " with negatives"))))

(deftest a-typed-array-of-bytes-still-realises
  (testing "boring writes uint8 payloads as a plain byte string, and tags whose
            widening is not mirrored fall back to realising. Whatever the wire
            form, the ANSWERS must not change."
    (let [a (byte-array (map byte [1 2 3 -4]))
          bs (boring/encode a opts)
          c (nav/root bs opts)
          realised (boring/decode bs opts)]
      (is (= (seq a) (seq realised)))
      ;; whether this navigates or realises is an implementation choice; that
      ;; it answers correctly is not
      (is (= (nth realised 0) (nth c 0 (nth realised 0)))))))

(defspec typed-array-descent-agrees-with-realising 200
  (prop/for-all
   [xs (gen/vector gen/large-integer 0 40)]
   (let [a (long-array xs)
         bs (boring/encode a opts)
         c (nav/root bs opts)
         realised (boring/decode bs opts)]
     (and (= (count xs) (count c))
          (every? (fn [i]
                    (let [v (nth c i)]
                      (and (= (nth realised i) v)
                           (= Long (class v)))))
                  (range (count xs)))
          (nil? (nth c (count xs) nil))))))
