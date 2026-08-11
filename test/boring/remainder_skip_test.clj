(ns boring.remainder-skip-test
  "The remainder walk must skip ITS entries through the index too.

  `skip-value` jumps to a container's last anchor and then walks the entries
  after it -- at most `stride` of them. Those entries are small in the case the
  jump was designed for, and they are not always small: an entry's VALUE can be
  a large container with a node of its own, and `.skipFrom` past one is the
  full subtree walk the jump exists to remove. The failure is not a wrong
  answer, it is the optimisation silently not applying, which is why it needs
  a test that measures rather than asserts a value.

  MEASURED at 107.3 us against 3.78 on the fixture below, 28.4x, on a
  byte-identical blob. The bound here is deliberately loose -- 20x the
  all-small-entries case -- because this runs on CI machines under contention
  and the effect is nearly two orders of magnitude."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.nav :as nav]))

(defn- lookup-us
  "Median us per `[\"tail\" \"city\"]` lookup on `v`, sealed at stride 16."
  ^double [v]
  (let [bs (boring/encode-indexed v {:index 16 :stringref false})
        o {:stringref false}
        c (nav/cursor (nav/source bs o) 0)
        run #(nav/value-at c (nav/walk-from c (nav/root-offset c) ["tail" "city"]))]
    (is (= "Berlin" (run)) "the fixture must still answer correctly")
    (dotimes [_ 3000] (run))
    (nth (sort (for [_ (range 5)]
                 (let [t (System/nanoTime)]
                   (dotimes [_ 1000] (run))
                   (/ (- (System/nanoTime) t) 1000.0 1000.0))))
         2)))

(deftest a-big-container-inside-the-remainder-is-jumped-not-walked
  (testing "entry 18 of a 20-element array at stride 16 falls AFTER the last
            anchor, so it is walked -- and it is a 3000-element array with a
            node. Through `.skipFrom` that is the whole subtree."
    (let [small {"outer" (vec (repeat 20 {"s" 1}))
                 "tail" {"city" "Berlin"}}
          heavy {"outer" (vec (concat (repeat 18 {"s" 1})
                                      [(vec (for [i (range 3000)]
                                              {"t" i "p" (str "/p/" i)}))]
                                      [{"s" 2}]))
                 "tail" {"city" "Berlin"}}
          base (lookup-us small)
          big (lookup-us heavy)]
      (is (< big (* 20 base))
          (str "reaching the field past a 3000-element array in the remainder "
               "took " (format "%.2f" big) " us against " (format "%.2f" base)
               " us for the same shape with small entries -- the remainder walk "
               "is not consulting the index")))))
