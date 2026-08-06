(ns boring.reader-reset-test
  "Pointing one Reader at many messages, and what the limit has to say about it.

   A decoder's whole contract on damaged input is that it produces a TYPED
   ERROR rather than a value. `Reader.reset(byte[])` broke that for one caller
   shape -- many short messages through one reused buffer -- because the
   array's length is the only limit it can take, so a short message left the
   limit at the buffer's capacity and every truncation check consulted bytes
   the PREVIOUS message had left behind. Damaged input then decoded as the
   previous message.

   `reset(byte[], int)` is the fix. These tests pin the property that matters:
   a reader over a reused buffer must answer exactly as a fresh reader over an
   exactly-sized array -- on undamaged input AND on damaged input."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [boring.core :as b]
            [boring.nav :as nav]
            [boring.generative-test :refer [gen-value]])
  (:import [org.replikativ.boring Reader]
           [java.util Arrays]))

(def ^:private opts {:stringref false})

(defn- fresh
  "The control: a reader that can only see this message."
  [^bytes bs ^long n]
  (.readFrom (Reader. (Arrays/copyOf bs (int n))) 0))

(defn- through-buffer
  "The same message through a reader whose buffer is deliberately too big and
   deliberately dirty -- which is what a fold over a scan looks like."
  [^Reader r ^bytes buf ^bytes bs ^long n]
  (System/arraycopy bs 0 buf 0 n)
  (.reset r buf (int n))
  (.readFrom r 0))

(defn- attempt
  "The outcome of a read, in a form that actually compares.

   `=` on decoded values compares Java ARRAYS BY IDENTITY, so two correct
   decodes of the same bytes look different and a property built on `=` reports
   phantom divergence. Re-encoding is deterministic and sees through every
   wrapper, which is the comparison this codebase settled on."
  [f]
  (try {:ok (vec (b/encode (f) opts))}
       (catch Exception e {:err (or (:type (ex-data e)) (class e))})))

(deftest a-reused-buffer-does-not-leak-the-previous-message
  (testing "the defect, pinned. A long message then a truncated one: without a
            length the truncated read returned the LONG one, complete, because
            the limit still described the buffer rather than the message."
    (let [long-row (b/encode {:aaa 1 :bbb "hello world padding padding"} opts)
          n (- (alength ^bytes long-row) 6)
          buf (byte-array 256)
          r (Reader. (byte-array 1))]
      ;; warm the buffer, so there is something stale to find
      (through-buffer r buf long-row (alength ^bytes long-row))
      (let [got (attempt #(through-buffer r buf long-row n))]
        (is (contains? got :err)
            "a truncated message must raise, not decode")
        (is (= (:err (attempt #(fresh long-row n))) (:err got))
            "and raise the SAME typed error a fresh reader raises")))))

(deftest a-short-message-after-a-long-one-reads-as-itself
  (let [rows (mapv #(b/encode % opts)
                   [{:aaa 1 :bbb "hello world padding padding padding"}
                    {:aaa 2}
                    {:x (vec (range 40))}
                    {}
                    {:aaa 3 :bbb "tiny"}])
        buf (byte-array 512)
        r (Reader. (byte-array 1))]
    (doseq [^bytes row rows]
      (is (= (fresh row (alength row))
             (through-buffer r buf row (alength row)))))
    (testing "and again in reverse, so every message follows a longer one"
      (doseq [^bytes row (reverse rows)]
        (is (= (fresh row (alength row))
               (through-buffer r buf row (alength row))))))))

(deftest reset-rejects-a-length-outside-the-array
  (let [r (Reader. (byte-array 1))
        buf (byte-array 8)]
    (is (thrown? IllegalArgumentException (.reset r buf 9)))
    (is (thrown? IllegalArgumentException (.reset r buf -1)))
    (testing "the boundaries themselves are fine"
      (.reset r buf 0)
      (.reset r buf 8))))

(deftest reset-with-a-length-clears-what-reset-clears
  (testing "the length form assigns its fields directly rather than going
            through bindArray, so it has to clear the same per-message state --
            otherwise a stringref table would survive into the next message."
    (let [a (b/encode {:aaa "shared" :bbb "shared"} {:stringref true})
          buf (byte-array 256)
          r (Reader. (byte-array 1))]
      (dotimes [_ 3]
        (is (= (fresh a (alength ^bytes a))
               (through-buffer r buf a (alength ^bytes a)))
            "a stringref message decoded repeatedly must not accumulate")))))

(defspec a-reused-buffer-agrees-with-a-fresh-reader 300
  (prop/for-all
   [v gen-value]
   (let [bs (b/encode v opts)
         n (alength ^bytes bs)
         buf (byte-array (max 1 (* 2 n)))
         r (Reader. (byte-array 1))]
     ;; dirty the buffer with something else first, so agreement is earned
     (Arrays/fill buf (byte 0x5A))
     (= (attempt #(fresh bs n))
        (attempt #(through-buffer r buf bs n))))))

(defspec a-reused-buffer-agrees-on-truncated-input 300
  (testing "the case that actually broke: every prefix of a message must reach
            the same verdict through a dirty reused buffer as through an
            exactly-sized array."
    (prop/for-all
     [v gen-value]
     (let [bs (b/encode v opts)
           n (alength ^bytes bs)
           buf (byte-array (max 1 (* 2 n)))
           r (Reader. (byte-array 1))]
       (Arrays/fill buf (byte 0x5A))
       ;; Sampled rather than exhaustive: gen-value reaches multi-kilobyte
       ;; values and 300 trials x every prefix is minutes, not seconds. The
       ;; step is coprime-ish with nothing in particular, so cut points do not
       ;; land on the same structural boundary every time.
       (every? (fn [k]
                 (= (attempt #(fresh bs k))
                    (attempt #(through-buffer r buf bs k))))
               (range 0 n (max 1 (quot n 40))))))))

;;; source-at

(deftest source-at-navigates-an-item-behind-a-prefix
  (testing "the case it exists for: a format that puts its own bytes in front
            of a CBOR item, so the item does not have to be nested inside a
            container purely to be findable."
    (let [a (b/encode {:aaa 1 :bbb "x"} opts)
          c (b/encode {:ccc [1 2 3] :ddd :e} opts)
          blob (byte-array (+ 5 (alength ^bytes a) (alength ^bytes c)))]
      (System/arraycopy a 0 blob 5 (alength ^bytes a))
      (System/arraycopy c 0 blob (+ 5 (alength ^bytes a)) (alength ^bytes c))
      (is (= 1 (nav/value (get (nav/source-at blob 5 opts) :aaa))))
      (is (= "x" (nav/value (get (nav/source-at blob 5 opts) :bbb))))
      (let [second-off (+ 5 (alength ^bytes a))]
        (is (= [1 2 3] (nav/value (get (nav/source-at blob second-off opts) :ccc))))
        (is (= :e (nav/value (get (nav/source-at blob second-off opts) :ddd)))))
      (testing "and realising the whole item at an offset equals decoding it alone"
        (is (= {:aaa 1 :bbb "x"} (nav/value (nav/source-at blob 5 opts))))))))

(deftest source-at-zero-is-source
  (let [bs (b/encode {:a 1 :b [2 3]} opts)]
    (is (= (nav/value (nav/source bs opts))
           (nav/value (nav/source-at bs 0 opts))))))
