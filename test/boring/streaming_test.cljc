(ns boring.streaming-test
  "`decode-seq` and `decode-seq-from` — the sequence readers (RFC 8742).

   Neither had a single test on either platform before this namespace, which is
   how the ClojureScript side came to have no streaming reader at all: the write
   side has streamed since the beginning (`write-to!`, `write-seq!`) while the
   read side took only a byte array, and nothing was checking the pair.

   The two platforms take different SOURCES and must not take different
   semantics. The JVM reads an `InputStream`; ClojureScript takes a pull
   function `(fn [] -> Uint8Array | nil)`, because a Node file reads
   synchronously (`fs.readSync`) and so composes with a lazy seq exactly as an
   InputStream does. Everything below is written once and runs on both."
  (:require [clojure.test :refer [deftest testing is]]
            [boring.core :as boring]))

;; ---------------------------------------------------------------------------

(defn- bytes-of
  "One CBOR sequence holding `vs`, as platform bytes."
  [vs opts]
  (let [encs (map #(boring/encode % opts) vs)]
    #?(:clj
       (let [bos (java.io.ByteArrayOutputStream.)]
         (doseq [^bytes e encs] (.write bos e))
         (.toByteArray bos))
       :cljs
       (let [total (reduce + (map #(.-length %) encs))
             out (js/Uint8Array. total)]
         (loop [es (seq encs) off 0]
           (if es
             (let [e (first es)]
               (.set out e off)
               (recur (next es) (+ off (.-length e))))
             out))))))

(defn- stream-decode
  "`decode-seq-from` over `bs`, whatever the platform calls a source."
  ([bs opts] (stream-decode bs opts 65536))
  ([bs opts chunk-size]
   (let [o (assoc (or opts {}) :chunk-size chunk-size)]
     #?(:clj
        (boring/decode-seq-from (java.io.ByteArrayInputStream. bs) o)
        :cljs
        ;; A pull function over the same bytes, handing back at most
        ;; `chunk-size` at a time — the shape a Node `fs.readSync` loop has.
        (let [pos (atom 0)]
          (boring/decode-seq-from
           (fn []
             (let [p @pos
                   n (min chunk-size (- (.-length bs) p))]
               (when (pos? n)
                 (swap! pos + n)
                 (.subarray bs p (+ p n)))))
           o))))))

(def ^:private corpus
  "Deliberately mixed sizes: small items that pack several to a chunk, and one
   long string that cannot fit in the small chunk sizes tested below, so the
   grow-and-retry path is exercised rather than just the compaction path."
  [1 -1 "a" :kw [1 2 3] {:a 1 :b "two"} true nil
   (apply str (repeat 300 "x"))
   [[1 2] [3 4]] {:nested {:deep [1 2 3]}} 1000000])

;; ---------------------------------------------------------------------------

(deftest streaming-equals-whole-buffer-decoding
  (testing "the streaming reader is not a different reader — for the same bytes
            it must produce exactly what `decode-seq` produces, which is the
            only definition of correct that does not drift."
    (let [bs (bytes-of corpus nil)]
      (is (= corpus (vec (boring/decode-seq bs))))
      (is (= corpus (vec (stream-decode bs nil)))))))

(deftest chunk-size-does-not-change-the-result
  (testing "every item boundary lands somewhere different at each chunk size,
            so this walks the refill/compaction logic across every alignment
            rather than trusting one lucky value.

            `1` is the important one: it forces a refill for literally every
            byte, so an item is reassembled from as many partial reads as it has
            bytes."
    (let [bs (bytes-of corpus nil)]
      (doseq [cs [1 2 3 7 16 64 4096]]
        (is (= corpus (vec (stream-decode bs nil cs)))
            (str "chunk-size " cs))))))

(deftest an-item-larger-than-the-chunk-still-decodes
  (testing "the buffer has to GROW, not just compact: a 10k string cannot be
            assembled by moving bytes down inside a 64-byte window."
    (let [big [(apply str (repeat 10000 "y"))
               (vec (range 2000))]
          bs (bytes-of big nil)]
      (is (= big (vec (stream-decode bs nil 64)))))))

(deftest empty-input-is-an-empty-sequence
  (testing "not nil, not an error — a dump with no records is a valid dump."
    (let [bs (bytes-of [] nil)]
      (is (= [] (vec (stream-decode bs nil))))
      (is (= [] (vec (boring/decode-seq bs)))))))

(deftest a-single-item-needs-no-refill-at-all
  (testing "the degenerate case, where the first read already holds everything."
    (let [bs (bytes-of [{:only "item"}] nil)]
      (is (= [{:only "item"}] (vec (stream-decode bs nil)))))))

;; ---------------------------------------------------------------------------
;; the failure modes, which is where a refill-and-retry reader gets it wrong

(deftest truncated-input-is-refused-not-silently-dropped
  (testing "bytes that end mid-item must raise at the point the seq is realised.

            This is the failure that a refill loop turns into silence if it
            treats 'no more data' as 'end of sequence': the last, incomplete
            item simply vanishes and the caller gets a short dump that looks
            clean."
    (let [full (bytes-of [{:a 1} {:b 2}] nil)
          cut #?(:clj (java.util.Arrays/copyOf full (dec (alength full)))
                 :cljs (.subarray full 0 (dec (.-length full))))]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (doall (stream-decode cut nil))))
      (testing "and at every chunk size — the bug would hide at sizes where the
                truncation happens to land on a refill boundary"
        (doseq [cs [1 3 64]]
          (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                       (doall (stream-decode cut nil cs)))
              (str "chunk-size " cs)))))))

(deftest a-hostile-count-is-reported-as-itself-not-as-truncation
  (testing "the subtle one, and the reason the retry keeps the ORIGINAL
            exception rather than raising a fresh one when it gives up.

            From inside the reader, 'the buffer ends mid-item' and 'the document
            declares an impossible count' are indistinguishable — a declared
            count is checked against the bytes REMAINING, which on a partial
            buffer is exactly what a hostile count looks like. So both must be
            retried while more data can still arrive. If the reader then
            reported the failure as a truncation, every malformed document would
            be misdiagnosed as a short read, and an operator would go looking
            for a broken disk instead of a bad file.

            `0x9a ff ff ff ff` is an array declaring 4294967295 elements and
            supplying none."
    (let [bad #?(:clj (byte-array (map unchecked-byte [0x9a 0xff 0xff 0xff 0xff]))
                 :cljs (js/Uint8Array.from #js [0x9a 0xff 0xff 0xff 0xff]))
          e (try (doall (stream-decode bad nil)) nil
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e e))]
      (is (some? e) "a count that cannot be satisfied is an error")
      (is (= :boring/bad-count (:type (ex-data e)))
          "and it is named as a bad count, not as truncated input"))))

(deftest options-reach-the-streaming-reader
  (testing "`decode-seq-from` configures its reader like every other entry
            point. An unknown tag under the default (tolerant) setting decodes;
            the same bytes with tolerance off must not.

            Worth pinning because the streaming reader builds its Reader by hand
            and resets it on every refill — a `reset!` that cleared the
            configuration would leave the first chunk strict and the rest
            tolerant, or vice versa."
    (let [bs (bytes-of [{:a 1} {:b 2} {:c 3}] nil)]
      (is (= 3 (count (stream-decode bs {:tolerate-unknown-tags false} 4)))
          "configuration survives repeated refills"))))

;; --------------------------------------------------------- the pull contract
;;
;; ClojureScript only: the JVM arity takes an InputStream, whose read contract
;; already distinguishes "0 bytes now" from "-1, end of input". A pull function
;; has to say so itself, and boring's docstring names `nil` -- only `nil` -- as
;; end of input.

#?(:cljs
   (deftest an-empty-block-is-not-end-of-input
     (testing "an empty Uint8Array used to become n = -1 and latch EOF, so a
               source that returned one -- a short read at a boundary, an fd
               with nothing ready yet -- silently DROPPED every byte after it
               and the sequence ended early with no error at all"
       (let [blocks (atom [(js/Uint8Array. 0) (boring/encode 1)
                           (js/Uint8Array. 0) (boring/encode 2) nil])]
         (is (= [1 2] (vec (boring/decode-seq-from
                            #(let [b (first @blocks)] (swap! blocks rest) b)))))))

     (testing "and a source that only ever returns empty is a typed error rather
               than an infinite loop"
       (is (= :boring/stalled-source
              (try (doall (boring/decode-seq-from (fn [] (js/Uint8Array. 0))))
                   (catch :default e (:type (ex-data e)))))))))

#?(:cljs
   (deftest a-block-larger-than-the-chunk-size-is-accepted
     (testing "`pull` is handed no requested size, so returning more than
               :chunk-size is legal. The buffer was grown from the CONFIGURED
               size before the block had even arrived, and the copy then threw a
               raw RangeError out of .set"
       (let [blocks (atom [(bytes-of [1 2 3] nil) nil])]
         (is (= [1 2 3] (vec (boring/decode-seq-from
                              #(let [b (first @blocks)] (swap! blocks rest) b)
                              {:chunk-size 1}))))))))

#?(:cljs
   (deftest chunk-size-is-validated-not-trusted
     (testing ":chunk-size 0 sized the buffer at zero and then copied into it,
               for a raw RangeError; a negative one threw out of the Uint8Array
               constructor. Both are option mistakes, and both now say so"
       (doseq [bad [0 -1 1.5 "64" nil]]
         (is (= :boring/bad-option
                (try (doall (boring/decode-seq-from (fn [] nil) {:chunk-size bad}))
                     (catch :default e (:type (ex-data e)))))
             (str ":chunk-size " (pr-str bad)))))))
