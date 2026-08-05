(ns boring.canonical-parity-test
  "Guarantees that `:profile :canonical` makes on BOTH platforms.

  THIS FILE EXISTS BECAUSE OF A PATTERN, not a bug. Three defects were fixed in
  the JVM writer -- canonical maps emitting duplicate keys, the canonical
  scratch writer not inheriting `:encode-fallback`, and map keys renewing the
  `:max-depth` budget -- and every one of them was still live in
  `boring/writer.cljs` afterwards, because every test written for them was
  JVM-only. Fixing one runtime three times and never opening the other is not
  three separate misses; it is one structural gap, and a portable test file is
  the fix for it.

  So: anything `:canonical` promises portably belongs here, in `.cljc`, and not
  in a `.clj` test beside the JVM implementation."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]))

(def canonical {:profile :canonical})

;; ------------------------------------------------------------------- S1

(deftest canonical-maps-refuse-keys-that-encode-identically
  (testing "canonical encoding REDUCES source distinctions, so two host values
            that are distinct map keys can become the same CBOR key -- and a map
            with two identical keys is output this library's own reader rejects.
            A successful encode must never produce bytes the paired decoder
            refuses.

            Canonical SETS have always checked this. Maps did not, on either
            platform; the JVM was fixed first and ClojureScript kept the gap."
    (let [m #?(:clj (doto (java.util.IdentityHashMap.)
                      (.put (Long/valueOf 1) "a")
                      (.put java.math.BigInteger/ONE "b"))
               ;; `1` and `(js/BigInt 1)` are distinct keys that both encode to
               ;; the single byte 01.
               :cljs {1 "a" (js/BigInt 1) "b"})]
      (is (= 2 (count m)) "the fixture must really hold two distinct keys")
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (boring/encode m canonical))))))

(deftest canonical-sets-refuse-elements-that-encode-identically
  (testing "the same rule for sets, which is where it was already enforced --
            here so the two live side by side and neither can regress alone"
    ;; A Clojure set literal cannot hold these: `=` says Long 1 and
    ;; BigInteger 1 are the same element. A java.util.LinkedHashSet uses
    ;; `.equals`, which says they are not -- so it can, and boring encodes any
    ;; java.util.Set.
    (let [s #?(:clj (doto (java.util.LinkedHashSet.)
                      (.add (Long/valueOf 1))
                      (.add java.math.BigInteger/ONE))
               :cljs #{1 (js/BigInt 1)})]
      (when (= 2 (count s))
        (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                     (boring/encode s canonical)))))))

;; ------------------------------------------------------------------- S2

(deftest map-keys-do-not-renew-the-depth-budget
  (testing "`:max-depth` is documented as an encode-side stack and security
            bound. Canonical keys are staged through a scratch writer that is
            reset before every key, and a reset zeroes the depth counter -- so
            copying the parent's depth into the scratch did nothing, and a value
            in KEY position started from zero. Nesting through keys could then
            renew the cap indefinitely.

            The fix is a separate offset that survives the reset and accumulates
            across nested scratch writers, which is what the JVM already had."
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (boring/encode {[[1]] :v} (assoc canonical :max-depth 3)))
        "a key nested deeper than the cap must be refused")
    (is (some? (boring/encode {[[1]] :v} (assoc canonical :max-depth 64)))
        "and a generous cap must still encode it")
    (testing "depth through keys accumulates rather than restarting"
      (let [deep (reduce (fn [x _] {x 0}) :leaf (range 8))]
        (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                     (boring/encode deep (assoc canonical :max-depth 2))))
        (is (some? (boring/encode deep (assoc canonical :max-depth 64))))))))

(deftest the-depth-cap-does-not-poison-a-reused-writer
  (testing "a rejected value must not leave the counter raised, or the next
            shallow encode on the same writer fails too -- a typed error that
            poisons the writer is worse than an untyped one, because it looks
            recoverable"
    (let [w (boring/writer 4096)
          deep (reduce (fn [x _] [x]) :leaf (range 40))]
      (dotimes [_ 5]
        (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                     (boring/encode-into! w deep {:max-depth 4}))))
      (is (some? (boring/encode-into! w [1 2 3] {:max-depth 4}))
          "a shallow value must still encode after repeated deep failures"))))

;; ------------------------------------------------------------------- S3

(defn- fallback-opts [f] (assoc canonical :encode-fallback f))

(deftest encode-fallback-applies-to-canonical-keys-and-set-elements
  (testing "keys and set elements are pre-encoded in a scratch writer, which did
            not inherit the fallback -- so an option that rescued a value
            everywhere else silently did not apply in exactly the two positions
            where a document is hardest to repair by hand"
    (let [unsupported #?(:clj (Object.) :cljs (js/WeakMap.))
          o (fallback-opts (fn [_] "fell-back"))]
      (is (= {"fell-back" 1 "ok" 2}
             (boring/decode (boring/encode {unsupported 1 "ok" 2} o) o))
          "the fallback must apply to a canonical map KEY"))))

(deftest a-recursive-fallback-raises-rather-than-overflowing
  (testing "inheriting the callback without its re-entry guard lets a fallback
            whose result still contains the unsupported value recurse through a
            fresh scratch writer forever. Both halves have to be inherited."
    (let [unsupported #?(:clj (Object.) :cljs (js/WeakMap.))
          o (fallback-opts (fn [_] {unsupported 1}))]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (boring/encode {unsupported 1} o))
          "must be a typed error, not a stack overflow"))))
