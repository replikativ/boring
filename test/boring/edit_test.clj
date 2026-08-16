(ns boring.edit-test
  "In-place / splice editing must be INDISTINGUISHABLE from decode-edit-encode.

  The whole point of `boring.edit` is to avoid materialising a value just to
  change one leaf of it. That is only sound if the bytes it produces decode to
  exactly what `clojure.core`'s `update-in`/`assoc-in`/`dissoc` would have
  produced through a full round trip. So the central assertion here is a
  property: over random nested data and random paths,

      (decode (edit/update-in-bytes (encode v) path f)) == (update-in v path f)

  for both a bare value and an indexed one, and with the index rebuilt or
  dropped. Everything else -- the structural ops, poke, the typed refusals --
  is a named case around that property."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.nav :as nav]
            [boring.edit :as edit]
            [boring.frame :as frame]))

(def O {:profile :archival})

(defn- rt
  "Decode what `edit` produced, for comparison against the clojure result."
  [^bytes b] (boring/decode b))

;; ---------------------------------------------------------------- named cases

(deftest replace-existing-leaf
  (let [data {"a" {"x" 1 "y" [10 20 30]} "b" 5}
        blob (boring/encode data O)]
    (testing "same-length replace and size-growing replace both match update-in"
      (is (= (assoc-in data ["a" "x"] 7)
             (rt (edit/assoc-in-bytes blob ["a" "x"] 7 O))))
      (is (= (assoc-in data ["a" "x"] 1000000)
             (rt (edit/assoc-in-bytes blob ["a" "x"] 1000000 O))))
      (is (= (assoc-in data ["a" "y" 1] 99999)
             (rt (edit/assoc-in-bytes blob ["a" "y" 1] 99999 O)))))))

(deftest structural-ops
  (let [data {"a" {"x" 1 "y" 2} "b" 5 "v" [10 20 30]}
        blob (boring/encode data O)]
    (is (= (assoc-in data ["a" "z"] 99) (rt (edit/assoc-in-bytes blob ["a" "z"] 99 O)))
        "add a key to a nested map")
    (is (= (assoc data "c" 7) (rt (edit/assoc-in-bytes blob ["c"] 7 O)))
        "add a top-level key")
    (is (= (update data "a" dissoc "x") (rt (edit/dissoc-in-bytes blob ["a" "x"] O)))
        "remove a nested key")
    (is (= (dissoc data "b") (rt (edit/dissoc-in-bytes blob ["b"] O)))
        "remove a top-level key")
    (is (= (update-in data ["a" "w"] (fnil inc 0))
           (rt (edit/update-in-bytes blob ["a" "w"] (fnil inc 0) O)))
        "update-in an absent path creates it like clojure.core")
    (is (= (update-in data ["v"] conj 40)
           (rt (edit/update-in-bytes blob ["v"] #(conj % 40) O)))
        "append to a vector")))

(deftest index-frame-preserved
  (let [data (assoc (into {} (for [i (range 300)] [(format "k%03d" i) i])) "nested" [1 2 3])
        blob (boring/encode-indexed data O)]
    (is (<= 0 (frame/footer-start blob)) "the fixture is actually framed")
    (let [out (edit/assoc-in-bytes blob ["k299"] 5000000000 O)]
      (is (<= 0 (frame/footer-start out)) "rebuild re-seals a frame")
      (is (= (assoc data "k299" 5000000000) (rt out)))
      (testing "navigation over the rebuilt blob jumps correctly, edited value included"
        (is (= 150 (nav/value (get (nav/root out) "k150"))))
        (is (= 5000000000 (nav/value (get (nav/root out) "k299"))))))
    (testing ":index :drop is still correct, just no frame"
      (let [out (edit/assoc-in-bytes blob ["k299"] 5000000000 (assoc O :index :drop))]
        (is (neg? (frame/footer-start out)))
        (is (= (assoc data "k299" 5000000000) (rt out)))))))

(deftest poke-in-place
  (let [data {"a" 10 "b" "hello" "c" [1 2 3]}
        blob (boring/encode data O)]
    (is (true? (edit/same-length? blob ["a"] 7 O)) "10 and 7 are both one byte")
    (is (false? (edit/same-length? blob ["a"] 1000000 O)))
    (testing "poke mutates in place and leaves an equal value"
      (let [b (aclone blob)]
        (edit/poke-in-bytes b ["a"] 7 O)
        (is (= (assoc data "a" 7) (rt b)))))
    (testing "a length-changing poke is refused, not silently splitting"
      (is (thrown? clojure.lang.ExceptionInfo
                   (edit/poke-in-bytes (aclone blob) ["a"] 1000000 O))))))

(deftest missing-parent-throws
  (let [blob (boring/encode {"a" {"x" 1}} O)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (edit/assoc-in-bytes blob ["nope" "deep"] 1 O))
        "no container at the parent path -> path-absent, caller falls back")))

;; ------------------------------------------------------------- the property

(defn- gen [^java.util.Random rng depth]
  (letfn [(ri [n] (.nextInt rng (int n)))
          (scalar [] (case (ri 4) 0 (ri 1000000) 1 (str "v" (ri 100)) 2 (< (ri 2) 1) 3 (ri 20)))
          (grow [d] (if (or (zero? d) (< (ri 3) 1))
                      (scalar)
                      (if (< (ri 2) 1)
                        (into {} (for [i (range (+ 2 (ri 6)))] [(str "k" i) (grow (dec d))]))
                        (vec (for [_ (range (+ 2 (ri 6)))] (grow (dec d)))))))]
    (let [v (grow depth)] (if (coll? v) v {"root" v}))))

(defn- leaf-path [^java.util.Random rng v]
  (let [ri (fn [n] (.nextInt rng (int n)))]
    (loop [v v p []]
      (cond
        (and (map? v) (seq v) (>= (ri 3) 1))
        (let [k (nth (vec (keys v)) (ri (count v)))] (recur (get v k) (conj p k)))
        (and (vector? v) (seq v) (>= (ri 3) 1))
        (let [i (ri (count v))] (recur (nth v i) (conj p i)))
        :else p))))

(deftest property-matches-update-in
  (let [rng (java.util.Random. 987654321)
        ri  (fn [n] (.nextInt rng (int n)))]
    (dotimes [_ 3000]
      (let [data (gen rng 4)
            path (leaf-path rng data)
            nv   (case (ri 3) 0 (ri 1000000) 1 (str "v" (ri 100)) 2 (< (ri 2) 1))
            exp  (if (empty? path) nv (assoc-in data path nv))
            blob (boring/encode-indexed data O)]
        (is (= exp (rt (edit/assoc-in-bytes blob path nv O)))
            (str "rebuild: path " (pr-str path)))
        (is (= exp (rt (edit/assoc-in-bytes blob path nv (assoc O :index :drop))))
            (str "drop: path " (pr-str path)))))))

(deftest maintain-index-equals-rebuild
  (testing "for a size-changing LEAF replace, :maintain seals a byte-identical
            frame to :rebuild -- the incremental shift matches a full walk"
    (let [data (assoc (into {} (for [i (range 5000)] [(format "k%05d" i) i]))
                      "nested" [1 2 3])
          blob (boring/encode-indexed data O)]
      (is (<= 0 (frame/footer-start blob)) "fixture is framed")
      (doseq [[k v] [["k00000" 1000000000]   ; front, grows 1->9 bytes
                     ["k02500" 1000000000]   ; middle
                     ["k04999" 1000000000]   ; back
                     ["nested" 1]            ; wait: replaces whole vector -- skip
                     ]
              :when (not= k "nested")]
        (is (java.util.Arrays/equals
             ^bytes (edit/assoc-in-bytes blob [k] v (assoc O :index :maintain))
             ^bytes (edit/assoc-in-bytes blob [k] v (assoc O :index :rebuild)))
            (str "maintain==rebuild for " k)))
      (testing "a nested array-element replace also matches"
        (is (java.util.Arrays/equals
             ^bytes (edit/assoc-in-bytes blob ["nested" 1] 1000000000 (assoc O :index :maintain))
             ^bytes (edit/assoc-in-bytes blob ["nested" 1] 1000000000 (assoc O :index :rebuild)))))
      (testing "and it still decodes correctly"
        (is (= (assoc data "k02500" 777)
               (boring/decode (edit/assoc-in-bytes blob ["k02500"] 777 (assoc O :index :maintain))))))
      (testing "navigation over the maintained blob jumps correctly"
        (let [out (edit/assoc-in-bytes blob ["k00000"] 1000000000 (assoc O :index :maintain))]
          (is (= 1000000000 (nav/value (get (nav/root out) "k00000"))))
          (is (= 2500 (nav/value (get (nav/root out) "k02500")))))))))

(deftest maintain-falls-back-when-replacing-an-indexed-container
  (testing "replacing a whole INDEXED container (array/map) can't be a uniform
            shift -- :maintain must fall back to :rebuild and stay byte-identical
            and navigable (regression: review finding 1)"
    (let [data {"big" (vec (range 300)) "z" 5}          ; big is an indexed array
          blob (boring/encode-indexed data O)
          newbig (vec (range 250))]
      (is (<= 0 (frame/footer-start blob)) "fixture is framed")
      (is (java.util.Arrays/equals
           ^bytes (edit/assoc-in-bytes blob ["big"] newbig (assoc O :index :maintain))
           ^bytes (edit/assoc-in-bytes blob ["big"] newbig (assoc O :index :rebuild)))
          "container replace: maintain == rebuild")
      (let [out (edit/assoc-in-bytes blob ["big"] newbig (assoc O :index :maintain))]
        (is (= (assoc data "big" newbig) (boring/decode out)))
        (testing "and array navigation into the replaced container is correct, not garbage"
          (is (= 0   (nav/value (nth (get (nav/root out) "big") 0))))
          (is (= 125 (nav/value (nth (get (nav/root out) "big") 125))))
          (is (= 249 (nav/value (nth (get (nav/root out) "big") 249))))
          (is (= 5   (nav/value (get (nav/root out) "z")))))))))
