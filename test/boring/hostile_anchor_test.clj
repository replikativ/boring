(ns boring.hostile-anchor-test
  "A frame whose anchors point a container AT OR BEFORE ITSELF, built through
  the public API and sealed as a well-formed document.

  WHY THIS EXISTS. `skip-value`'s remainder walk recurses through
  `skip-value` (so a large indexed container inside the remainder is jumped,
  28x). The recursion argument comes off the FRAME -- and a frame is bytes
  somebody else wrote. An anchor equal to the container's own offset recursed
  `skip-value(vp) -> skip-value(vp)` to StackOverflowError, which is an Error
  and walked straight through the RuntimeException catch: an UNTYPED crash
  from hostile input, the exact defect class doc/SECURITY.md promises away.
  Found by reading during the nav fuzzer's construction; the fuzzer's random
  mutations had not hit the 8 exact bytes in 60 000 mutants, which is why
  this is a DIRECTED test and not a hope.

  The fix is three guards, each falling back to the honest walk: a last
  anchor at or before the head throws into the existing catch; each remainder
  step must land strictly past where it started; and recursion depth caps at
  1024 (honest anchors nest no deeper than the document, which `:max-depth`
  bounds the same way).

  THE ASSERTION IS THE CORRECT ANSWER, not merely no-crash: every guard ends
  in `.skipFrom`, so the walk must still find `:tail`."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.nav :as nav])
  (:import [java.io ByteArrayOutputStream]))

(defn- sealed-with-anchors
  "`v` sealed with every node's anchors REWRITTEN by `(f container-offset anchors)`."
  ^bytes [v f]
  (let [o {:index 1 :index-min 4 :stringref false}
        bs (boring/encode v {:stringref false})
        idx (boring/build-index bs o)
        ^longs cs (:containers idx)
        evil (assoc idx :slots
                    (vec (map-indexed
                          (fn [i ^longs a] (f (aget cs i) a))
                          (:slots idx))))
        out (ByteArrayOutputStream.)]
    (.write out ^bytes bs)
    (boring/seal-index! (boring/writer 256 {:stringref false}) out evil (alength ^bytes bs) o)
    (.toByteArray out)))

(def ^:private v {:a 1
                  :big (vec (repeat 40 {:k 1 :j 2}))
                  :tail {:city "B"}})

(deftest self-pointing-anchors-fall-back-to-the-walk
  (testing "every anchor equals its container's own offset -- the recursion
            trigger. The answer must be CORRECT, because every guard ends in
            .skipFrom"
    (let [bs (sealed-with-anchors v (fn [c ^longs a] (long-array (alength a) c)))
          src (nav/source bs {:stringref false})]
      (is (= "B" (nav/value-at src (nav/walk-from src (nav/root-offset src)
                                                  [:tail :city])))))))

(deftest backward-anchors-crash-nothing
  (testing "anchors one byte after the head -- in bounds, past the head, and
            nonsense. This is the DOCUMENTED trust boundary: an in-bounds
            anchor that is not an entry boundary desynchronises the scan, and
            the answer may be a typed absent rather than the truth (the
            skip-via-index tests say so in as many words). What the guards
            close is the CRASH class: the walk must terminate and any error
            must be typed. Asserting the correct answer here was tried and is
            more than the contract promises."
    (let [bs (sealed-with-anchors v (fn [c ^longs a]
                                      (long-array (alength a) (inc c))))
          src (nav/source bs {:stringref false})
          r (try (let [o (nav/walk-from src (nav/root-offset src) [:tail :city])]
                   {:ok (when-not (neg? o) (nav/value-at src o))})
                 (catch clojure.lang.ExceptionInfo e
                   (if (= "boring" (some-> (ex-data e) :type namespace))
                     {:typed (:type (ex-data e))}
                     {:untyped e}))
                 (catch Throwable e {:untyped e}))]
      (is (not (contains? r :untyped))
          (str "hostile anchors must never crash untyped: " (:untyped r))))))

(deftest honest-frames-still-jump
  (testing "the guards must not tax the honest case into the walk: an
            untampered seal still answers, and the fixture is the same"
    (let [bs (boring/encode-indexed v {:index 1 :index-min 4 :stringref false})
          src (nav/source bs {:stringref false})]
      (is (= "B" (nav/value-at src (nav/walk-from src (nav/root-offset src)
                                                  [:tail :city])))))))
