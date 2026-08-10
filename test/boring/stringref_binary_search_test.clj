(ns boring.stringref-binary-search-test
  "The binary search over a container whose keys are STRINGREF REFERENCES.

  WHAT WAS WRONG. The index frame's `sorted` bit is computed from the emitted
  key bytes, and on a repeated map those bytes are references -- `d8 27 d8 19
  NN` -- whose indices are handed out in first-occurrence order. So they really
  do ascend, and the bit is HONEST. What it is not is comparable against the
  probe, which `probe-for` always builds as a LITERAL. Every lookup on such a
  container therefore ran a binary search in the wrong space, walked the wrong
  way, and reported a miss; `confirm` then re-derived the answer by scanning
  the whole container. Measured, 19 of 20 present keys took that path, and an
  indexed miss on a 2000-key map cost 4041 skips against 3998 UNINDEXED -- the
  index was not failing to help, it was overhead on top of the walk.

  Nothing caught it because nothing tested this combination. The conformance
  corpus pins `:stringref false`, the robustness fixtures use `:profile
  :canonical` (which pins it off too), and the golden corpus carries no index
  frame at all. Indexed AND stringref -- the DEFAULT for `encode-indexed` --
  was covered by no test in either builder.

  WHY TWO SEARCHES. Resolving the anchors to their defining literals and
  comparing there is unsound: reference order is first-occurrence order, and
  the literals it resolves to are in no order at all (19 of 39 adjacent pairs
  descend on a 40-key document). The comparison has to stay in wire space, so
  the PROBE moves instead. One search does not suffice either, because a single
  container can hold both encodings -- see the mixed-container test below."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.nav :as nav]))

(def ^:private kw #(keyword (format "key-number-%03d" %)))

(defn- rows-of
  "`n` identical maps of `k` keyword keys. Row 0 defines the strings; every row
  after it carries references, which is the case under test."
  [k n]
  (vec (repeat n (into {} (for [i (range k)] [(kw i) i])))))

(deftest every-present-key-of-a-reference-keyed-row-is-found
  (testing "the core regression: 19 of 20 of these returned -1 from the search"
    (doseq [k [40 200]]
      (let [c (nav/root (boring/encode-indexed (rows-of k 20) {:index 1}) nil)]
        (doseq [row-i [1 7 19]]
          (let [row (nth c row-i)]
            (doseq [i (range k)]
              (is (= i (some-> (get row (kw i)) nav/value))
                  (str k " keys, row " row-i ", key " (kw i))))))))))

(deftest row-zero-is-literal-keyed-and-still-answers
  (testing "the FIRST row defines the strings, so its keys are literals and its
            node is sorted in literal space. The literal search must still be
            the one that answers there -- it is tried first for exactly this."
    (let [row (nth (nav/root (boring/encode-indexed (rows-of 40 20) {:index 1}) nil) 0)]
      (doseq [i (range 40)]
        (is (= i (some-> (get row (kw i)) nav/value)))))))

(deftest a-container-holding-both-encodings-answers-for-both
  (testing "keys repeated from an earlier row are REFERENCES; keys first seen
            in this row are LITERALS. Such a row is still bytewise sorted -- a
            literal's `0x6X` head sorts before a reference's `0xd8` -- so the
            index marks it sorted and the search runs. No single probe encoding
            compares against all of those anchors: a literal-only search finds
            the fresh keys and misses the repeated ones, and a reference-only
            search does the reverse. Measured on such a container before the
            fix, 10 of 20 keys came back wrong."
    (let [shared (mapv kw (range 30))
          fresh (fn [r] (mapv #(keyword (format "fresh-%02d-%02d" r %)) (range 30)))
          row (fn [r] (into {} (concat (map vector shared (range))
                                       (map vector (fresh r) (range 100 130)))))
          rows (mapv row (range 20))
          c (nav/root (boring/encode-indexed rows {:index 1}) nil)]
      (doseq [r [1 5 19]]
        (let [cur (nth c r)]
          (doseq [[k v] (nth rows r)]
            (is (= v (some-> (get cur k) nav/value))
                (str "row " r ", key " k))))))))

(deftest string-keys-take-the-bare-tag-25-shape
  (testing "a repeated KEYWORD is `tag 39` wrapping `tag 25` and its pointer
            names the text alone; a repeated STRING is a bare `tag 25` compared
            whole. Both shapes have to compile back to a probe."
    (let [m (into {} (for [i (range 60)] [(format "string-key-%03d" i) i]))
          c (nav/root (boring/encode-indexed (vec (repeat 12 m)) {:index 1}) nil)]
      (doseq [row-i [0 3 11]]
        (let [row (nth c row-i)]
          (doseq [[k v] m]
            (is (= v (some-> (get row k) nav/value)) (str "row " row-i " " k))))))))

(deftest an-absent-key-is-nil-and-never-a-phantom
  (testing "the search must not turn a miss into a hit now that a second probe
            shape reaches it. A reference probe is `d8 27 d8 19 NN`, five or
            six bytes, and a short integer probe is three -- the shape that
            once phantom-matched 14 of 2000 through the SCAN path."
    (let [row (nth (nav/root (boring/encode-indexed (rows-of 40 20) {:index 1}) nil) 5)]
      (is (nil? (get row :not-a-key)))
      (is (nil? (get row "key-number-001")) "a string is not the keyword")
      (is (zero? (count (filter #(some? (get row %)) (range 2000))))
          "integer probe sweep"))))

(deftest the-indexed-answer-is-the-decoded-answer
  (testing "the oracle is `decode`, not the other builder -- an oracle that
            shares the defect proves nothing, which is how this survived."
    (doseq [stride [1 4 16]]
      (let [rows (rows-of 50 15)
            bs (boring/encode-indexed rows {:index stride})
            c (nav/root bs nil)]
        (is (= rows (boring/decode bs)))
        (doseq [r [0 1 14]]
          (is (= (nth rows r)
                 (into {} (for [i (range 50)]
                            [(kw i) (some-> (get (nth c r) (kw i)) nav/value)])))
              (str "stride " stride ", row " r)))))))
