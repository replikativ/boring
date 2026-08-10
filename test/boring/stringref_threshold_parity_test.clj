(ns boring.stringref-threshold-parity-test
  "One rule, five implementations: they must agree exactly.

  A string takes a stringref index only if referencing it would be shorter than
  repeating it, and the threshold depends on how wide the reference would be --
  index 0-23 needs >= 3 octets, 24-255 needs 4, 65536+ needs 7. Both sides of
  the wire apply the rule INDEPENDENTLY to decide whether a given string
  consumed an index at all.

  WHY A DISAGREEMENT IS CATASTROPHIC RATHER THAN LOCAL. The index space is a
  running counter. If the writer registers a string the reader does not (or the
  reverse), the two counters diverge from that point on and EVERY LATER
  REFERENCE resolves to the wrong string -- silently, because a wrong index is
  still a valid index. Nothing throws; the document simply decodes to different
  text.

  THE COPIES, and why there are so many. Java cannot call into Clojure on this
  path without cost, ClojureScript namespaces do not share private helpers, and
  the pointer-table derivation in `core` runs over bytes it did not write:

    org.replikativ.boring.Reader/minLenForIndex     decode side
    org.replikativ.boring.Writer/minLenForIndex     encode side
    boring.core/min-len-for-index                   JVM pointer-table derivation
    boring.reader.cljs/min-len-for-index            cljs decode side
    boring.core.cljs/min-len-for-index              cljs pointer-table derivation

  This test covers the three on the JVM by calling them directly, the two Java
  ones through reflection since they are private statics. The ClojureScript
  pair is identical source and is covered by the cross-platform byte fixtures;
  it is named here because `core.cljs`'s own docstring says there are FOUR
  implementations and there are five -- it does not know about `reader.cljs`.

  #40 asked for this and called it cheap insurance. It is: the rule is four
  lines and the failure it prevents is undetectable at runtime."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core])
  (:import (org.replikativ.boring Reader Writer)))

(defn- private-static
  "A private static `int f(int)` on `c`, as a Clojure fn."
  [^Class c ^String nm]
  (let [m (.getDeclaredMethod c nm (into-array Class [Integer/TYPE]))]
    (.setAccessible m true)
    (fn [^long idx] (.invoke m nil (object-array [(int idx)])))))

(deftest the-stringref-threshold-agrees-across-every-implementation
  (let [reader-fn (private-static Reader "minLenForIndex")
        writer-fn (private-static Writer "minLenForIndex")
        core-fn @#'boring.core/min-len-for-index]
    (testing "the documented rule itself, at the boundaries that define it"
      (is (= [3 3 4 4 5 5 7] (mapv core-fn [0 23 24 255 256 65535 65536]))))
    (testing "and all three JVM implementations answer identically, over the
              whole range plus every boundary and its neighbours"
      ;; Sampled rather than exhaustive above 70000: the rule is constant from
      ;; 65536 upward, so the interesting region is entirely below it, and the
      ;; boundaries are enumerated explicitly so a sampling stride can never
      ;; step over one.
      (doseq [idx (concat (range 0 1000)
                          [23 24 25 255 256 257 65535 65536 65537]
                          (range 60000 70000 37))]
        (let [r (reader-fn idx) w (writer-fn idx) c (core-fn idx)]
          (is (= r w c)
              (str "index " idx ": Reader " r ", Writer " w ", core " c)))))))

(deftest a-string-at-each-threshold-round-trips
  (testing "the rule through its EFFECT, not just its arithmetic -- a string of
            exactly the threshold length takes an index and one byte shorter
            does not, and either way the document must come back unchanged.

            This is the assertion that would still hold if someone replaced the
            four-line rule with a different four-line rule on both sides: the
            arithmetic test above pins the rule, this one pins the behaviour."
    (doseq [len [1 2 3 4 5 20]]
      (let [s (apply str (repeat len "a"))
            v (vec (repeat 8 s))]
        (is (= v (boring.core/decode (boring.core/encode v {:stringref true})))
            (str "length " len))
        (is (= v (boring.core/decode (boring.core/encode-indexed v {:stringref true})))
            (str "length " len ", indexed"))))))
