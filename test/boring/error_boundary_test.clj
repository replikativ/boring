(ns boring.error-boundary-test
  "Every JVM read path types every error.

  `with-decode-errors` existed and was applied to four of the six paths that
  turn bytes into a value. The two it missed -- `decode-seq-from`'s `readNext`
  and `boring.nav`'s `realize` -- grew weaker substitutes instead:
  `decode-seq-from` hand-rolled the `IndexOutOfBoundsException` half of the
  conversion, and `nav` had nothing. Measured before the fix, on a 256 KiB
  stack, both leaked a bare `java.lang.StackOverflowError` where `decode` on
  the same bytes gave `:boring/max-depth-exceeded`:

      decode           :boring/max-depth-exceeded
      decode-with      :boring/max-depth-exceeded
      decode-seq       :boring/max-depth-exceeded
      decode-seq-from  java.lang.StackOverflowError    <-- untyped
      nav/value        java.lang.StackOverflowError    <-- untyped

  doc/SECURITY.md promises that cannot escape, on input an attacker chooses.

  ClojureScript needs no counterpart: `reader.cljs` throws typed from inside,
  and `boring.conformance-test` asserts it on all three of its read paths. That
  is a real platform difference, not a gap -- named here rather than left to a
  reader conditional, which is how two half-applied fixes hid before."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.nav :as nav]))

;; Tag 55799 is "self-described CBOR": it wraps ANY item and means nothing, so
;; a chain of them is valid CBOR at every depth. That is what makes it usable
;; as a control. The obvious choice -- a chain of tag 0 -- raises
;; :boring/bad-tag-content at depth ONE, because tag 0 demands a text string;
;; a suite built on it would report five typed errors while never reaching the
;; decoder at all. Which is the failure this branch has shipped three times.
(defn- tag-chain ^bytes [n]
  (byte-array (concat (mapcat (fn [_] (map unchecked-byte [0xd9 0xd9 0xf7])) (range n))
                      [(unchecked-byte 0x01)])))

(defn- array-chain ^bytes [n]
  (byte-array (concat (repeat n (unchecked-byte 0x81)) [(unchecked-byte 0x01)])))

(def ^:private small-stack (* 256 1024))

(defn- on-small-stack
  "Run `f` on a thread with a stack small enough that deep input overflows it.

  The bound the decoder enforces is `:max-depth`, counted in ITEMS; the stack
  a level costs is not uniform, so a document can exhaust the stack with the
  item bound nowhere in sight. Reproducing that on the test thread's 8 MiB
  would need a document large enough to be its own problem."
  [f]
  (let [p (promise)]
    (doto (Thread. nil #(deliver p (try {:ok (f)}
                                        (catch Throwable t {:threw t})))
                   "boring-error-boundary" small-stack)
      (.start)
      (.join 60000))
    (if (realized? p) @p {:threw ::timeout})))

(def ^:private documented-types
  #{:boring/max-depth-exceeded :boring/truncated-input :boring/max-items-exceeded
    :boring/bad-count :boring/bad-tag-content})

(defn- stream-of ^java.io.InputStream [^bytes bs]
  (java.io.ByteArrayInputStream. bs))

(defn- paths
  "The six JVM entry points that turn bytes into a Clojure value."
  [^bytes bs opts]
  [["decode"          #(boring/decode bs opts)]
   ["decode-with"     #(boring/decode-with (boring/reader (byte-array 0) opts) bs)]
   ["decode-seq"      #(doall (boring/decode-seq bs opts))]
   ["decode-seq-from" #(doall (boring/decode-seq-from (stream-of bs) opts))]
   ["nav/value"       #(nav/value (nav/root bs))]
   ["nav/children"    #(doall (map nav/value (nav/children (nav/root bs))))]])

(deftest shallow-input-decodes-on-every-path
  (testing "the control. Without it, a suite asserting 'this throws typed' can
            pass while the input dies in validation before reaching the
            decoder, which is what makes the deep assertions below mean
            anything"
    (doseq [[nm bs] [["tags" (tag-chain 20)] ["arrays" (array-chain 20)]]
            [label f] (paths bs {:max-depth 2048})]
      ;; nav/children needs a container at the root; the chains bottom out in
      ;; an integer, so only the value paths are asserted to succeed.
      (when-not (= "nav/children" label)
        (let [r (on-small-stack f)]
          (is (contains? r :ok) (str nm " " label " -> " (pr-str (:threw r)))))))))

(deftest no-read-path-leaks-an-untyped-throwable
  (testing "1000 levels on a 256 KiB stack, with :max-depth deliberately set
            ABOVE it so the item bound cannot fire first -- the stack has to be
            what runs out. Each path may succeed (a larger stack) or fail, but
            failing means a typed boring error, never a raw Error."
    (let [outcomes
          (doall
           (for [[nm bs] [["tags" (tag-chain 1000)] ["arrays" (array-chain 1000)]]
                 [label f] (paths bs {:max-depth 2048})]
             (let [r (on-small-stack f)
                   t (:threw r)]
               (when t
                 (is (instance? clojure.lang.ExceptionInfo t)
                     (str nm " " label " threw " (if (= ::timeout t)
                                                   "nothing (timed out)"
                                                   (.getName (class t)))))
                 (is (contains? documented-types (:type (ex-data t)))
                     (str nm " " label " -> " (pr-str (:type (ex-data t))))))
               (some? t))))]
      (testing "and the deep input really did overflow something, so the
                assertions above were reached rather than skipped"
        (is (some true? outcomes))))))

(deftest a-converted-stack-overflow-keeps-the-original-as-its-cause
  (testing "the StackOverflowError branch used to drop the throwable it caught,
            which left a caller unable to tell a document that nests too deep
            from their own recursion overflowing inside a nav traversal"
    (let [r (on-small-stack #(nav/value (nav/root (tag-chain 1000))))
          t (:threw r)]
      ;; THE PRECONDITION IS ASSERTED, not assumed. This whole body used to sit
      ;; inside `(when (instance? ExceptionInfo t) ...)`, so if `nav/value`
      ;; succeeded, timed out, or threw anything else, ZERO assertions ran and
      ;; the test was green. Its sibling above already carries this guard --
      ;; `(is (some true? outcomes))`, "the assertions above were reached rather
      ;; than skipped" -- for exactly this reason.
      (is (instance? clojure.lang.ExceptionInfo t)
          (str "expected a typed error, got "
               (cond (nil? t) "no throw at all"
                     (= ::timeout t) "nothing (timed out)"
                     :else (.getName (class t)))))
      (when (instance? clojure.lang.ExceptionInfo t)
        (is (= :boring/max-depth-exceeded (:type (ex-data t))))
        (is (instance? StackOverflowError (.getCause ^Throwable t)))))))
