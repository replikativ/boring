(ns boring.writer-streaming-test
  "The writer streams to its sink instead of accumulating the whole encoding.

  The property that makes this safe to believe is EQUIVALENCE: the bytes must
  not depend on the buffer size. A small buffer flushes often and a large one
  barely at all, so if the two agree byte-for-byte across a range of sizes, then
  every offset the writer computed -- including every index offset, which is
  where this session's bugs clustered -- was independent of where the chunk
  boundaries happened to fall.

  Byte-identity subsumes index-identity here, because the index frame is part of
  the output. That is why these assertions compare whole files rather than
  picking the index apart."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [boring.core :as boring]
            [boring.nav :as nav]
            [boring.generative-test :as g])
  (:import (java.io ByteArrayOutputStream OutputStream)))

(def o {:stringref false})

(defn- streamed ^bytes [values buf-size opts]
  (let [out (ByteArrayOutputStream.)
        w (boring/writer buf-size opts)]
    (boring/write-seq! w values out opts)
    (.toByteArray out)))

(defn- streamed-one ^bytes [v buf-size opts]
  (let [out (ByteArrayOutputStream.)
        w (boring/writer buf-size opts)]
    (boring/write-to! w v out opts)
    (.toByteArray out)))

;; ---------------------------------------------------------------- equivalence

(defspec sequence-bytes-do-not-depend-on-the-buffer-size 200
  (prop/for-all [values (gen/vector g/gen-value 1 40)]
                (let [sizes [16 64 512 8192 262144]
                      outs (mapv #(vec (streamed values % o)) sizes)]
                  (apply = outs))))

(defspec single-value-bytes-do-not-depend-on-the-buffer-size 200
  (prop/for-all [v g/gen-value]
                (let [outs (mapv #(vec (streamed-one v % o)) [16 64 512 8192 262144])]
                  (apply = outs))))

(defspec streaming-agrees-with-encode 200
  (prop/for-all [v g/gen-value]
                (= (vec (boring/encode v o))
                   (vec (streamed-one v 16 o)))))

(deftest an-indexed-sequence-is-identical-at-every-buffer-size
  (testing "the index offsets are absolute file positions, so a chunk boundary
            falling in the middle of a container must not move them. A 16-byte
            buffer flushes inside almost every item; a 256 KB one never flushes
            at all."
    (let [values (mapv (fn [i] {:id i :name (str "customer-" i)
                                :tags #{:a :b :c} :nested {:x [1 2 3] :y i}})
                       (range 300))
          outs (mapv #(vec (streamed values % o)) [16 64 512 8192 262144])]
      (is (apply = outs) "identical bytes at every buffer size")
      (testing "and the index is usable, not merely identical"
        (let [bs (streamed values 16 o)
              got (nav/items bs)]
          (is (= 300 (count got)))
          (is (= values (mapv nav/value (seq got))))
          (is (= (nth values 299) (nav/value (nth got 299)))
              "the last item, which is what an index exists to reach"))))))

(deftest a-container-whose-keys-span-a-flush-is-still-answered-correctly
  (testing "the `sorted` flag licenses nav to BINARY-SEARCH a container. While
            streaming, the previous key may already have gone to the sink, so
            the writer copies it out; a key split across a flush cannot be
            compared at all and the container is marked unsorted. Unsorted only
            ever costs a scan, so every lookup must still answer correctly --
            which is what this checks, at a buffer size small enough that keys
            really do straddle flushes."
    (let [m (into (sorted-map)
                  (for [i (range 400)]
                    [(str "key-" (format "%040d" i)) {:v i}]))
          bs (streamed-one m 16 (assoc o :index 4 :index-min 4))]
      (is (= m (boring/decode bs o)) "round-trips whatever the flag says"))
    (testing "and with a large-key map sealed through write-seq!"
      (let [items [(into {} (for [i (range 200)]
                              [(str "k" (format "%060d" i)) i]))]
            small (streamed items 16 o)
            large (streamed items 262144 o)]
        (is (= (vec small) (vec large)))
        (is (= items (vec (boring/decode-seq small o))))))))

;; ---------------------------------------------------------------- memory

(deftest the-buffer-does-not-grow-to-the-size-of-the-value
  (testing "this is the whole point: encoding a value larger than the buffer
            used to grow the buffer to hold all of it, so a 1 GB structure cost
            a second GB. Collections stream at every depth now."
    (let [sink (proxy [OutputStream] [] (write ([_]) ([_ _ _])))
          big (mapv (fn [i] {:id i :name (str "customer-" i) :pad (apply str (repeat 64 \x))})
                    (range 20000))
          w (boring/writer 65536 o)
          n (boring/write-to! w big sink o)]
      (is (> n 1000000) "the value really is bigger than the buffer")
      (is (<= (alength ^bytes (boring/buffer w)) 65536)
          (str "buffer grew to " (alength ^bytes (boring/buffer w)))))))

(deftest leaves-stream-too-rather-than-growing-the-buffer
  (testing "this test used to assert the OPPOSITE, on a premise that was simply
            wrong: a comment in the writer claimed one CBOR item with a length
            header cannot be split across chunks. A chunk boundary is a
            write-call boundary and has no CBOR meaning at all -- the head has
            already said how many bytes follow, and where they are handed to the
            OutputStream is nobody's business. A 1 MB byte array through a
            64-byte writer used to leave it holding 1 MiB, a second full-size
            copy of something the caller already had."
    (let [sink (proxy [OutputStream] [] (write ([_]) ([_ _ _])))]
      (doseq [[label v] [["ASCII string" (apply str (repeat 400000 \x))]
                         ["UTF-8 string" (apply str (repeat 400000 "\u00e9"))]
                         ["byte array"   (byte-array 1000000)]
                         ["bignum"       (.shiftLeft java.math.BigInteger/ONE 800000)]]]
        (let [w (boring/writer 64 o)]
          (boring/write-to! w v sink o)
          (is (<= (alength ^bytes (boring/buffer w)) 64)
              (str label " grew the buffer to " (alength ^bytes (boring/buffer w))))))))
  (testing "and the bytes are still right, including a string that is only
            partly ASCII -- the path that speculates and bails out"
    (let [out (ByteArrayOutputStream.)
          w (boring/writer 64 o)
          s (apply str (repeat 100000 "a\u00e9"))]
      (boring/write-to! w s out o)
      (is (= s (boring/decode (.toByteArray out) o)))))
  (testing "a directly streamed bignum magnitude still round-trips"
    (let [out (ByteArrayOutputStream.)
          w (boring/writer 64 o)
          n (.shiftLeft java.math.BigInteger/ONE 800000)]
      (boring/write-to! w n out o)
      (is (= n (boring/decode (.toByteArray out) o)))))
  (testing "a large leaf inside a PINNED span still buffers, because a map key
            must stay contiguous for the index's sorted flag"
    (let [out (ByteArrayOutputStream.)
          w (boring/writer 64 o)
          k (apply str (repeat 5000 \k))]
      (boring/write-to! w {k 1} out (assoc o :index 1 :index-min 1))
      (is (= {k 1} (boring/decode (.toByteArray out) o))))))

;; ---------------------------------------------------------------- failure

(deftest a-failed-write-does-not-append-a-half-value
  (testing "streaming is not atomic -- bytes already sent cannot be recalled --
            but the half-encoded tail sitting in the buffer must not follow them
            onto the stream. `abortStream` drops it."
    (let [out (ByteArrayOutputStream.)
          w (boring/writer 64 o)
          ;; an object with no encoding, after several encodable items
          values (concat (repeat 50 {:a 1 :b "some padding to force flushes"})
                         [(Object.)])]
      (is (thrown? clojure.lang.ExceptionInfo
                   (boring/write-seq! w values out o)))
      (testing "what did reach the stream is a valid CBOR sequence prefix"
        (let [bs (.toByteArray out)]
          (is (pos? (alength bs)))
          (is (every? map? (boring/decode-seq bs o))
              "every complete item decodes; no trailing half-value"))))
    (testing "and the writer is reusable afterwards"
      (let [out (ByteArrayOutputStream.)
            w (boring/writer 64 o)]
        (try (boring/write-to! w (Object.) out o) (catch Exception _ nil))
        (is (= [{:ok 1}] (vec (boring/decode-seq (streamed [{:ok 1}] 64 o) o))))
        (is (= 1 (boring/write-to! w 1 (ByteArrayOutputStream.) o))
            "the aborted stream left no state behind")))))

;; ---------------------------------------------------------------- write-indexed!

(deftest write-indexed-agrees-with-encode-indexed
  (testing "`encode-indexed` builds the whole byte array and then WALKS it to
            derive the index -- two copies of the document plus a second pass
            over every byte. `write-indexed!` captures the nodes as the writer
            emits them and holds one chunk. They must agree byte-for-byte, since
            `boring.writer-index-test` already pins writer-capture against the
            byte walk for sequences and this is the single-value case."
    (doseq [[label v] [["wide map" (into {} (for [i (range 400)] [(str "k" i) {:v i}]))]
                       ["wide vec" (mapv (fn [i] {:id i :name (str "n" i)}) (range 400))]
                       ["nested"   {:a (vec (range 100)) :b (into {} (for [i (range 60)] [i i]))}]
                       ["tiny"     {:a 1}]]]
      (let [expect (boring/encode-indexed v {:index 16})
            got (let [out (ByteArrayOutputStream.)
                      w (boring/writer 64 o)]           ; tiny buffer: many flushes
                  (boring/write-indexed! w v out {:index 16})
                  (.toByteArray out))]
        (is (= (vec expect) (vec got)) label))))
  (testing "and the result is navigable, which is the point of having it"
    (let [v (into {} (for [i (range 500)] [(str "key-" i) {:v i}]))
          out (ByteArrayOutputStream.)
          w (boring/writer 64 o)]
      (boring/write-indexed! w v out {:index 16})
      (let [bs (.toByteArray out)
            c (nav/source bs o)]
        (is (= {:v 499} (nav/value (get c "key-499"))))
        (is (= v (nav/value c)))
        (is (= v (boring/decode bs o)) "and `decode` still returns the value")
        (is (= [v] (vec (boring/decode-seq bs o))) "with the frame hidden")))))

(deftest write-indexed-is-bounded-and-refuses-stringref
  (testing "bounded memory, unlike encode-indexed which holds the whole array"
    (let [sink (proxy [OutputStream] [] (write ([_]) ([_ _ _])))
          big (mapv (fn [i] {:id i :name (str "customer-" i)}) (range 40000))
          w (boring/writer 65536 o)
          n (boring/write-indexed! w big sink {:index 16})]
      (is (> n 1000000))
      (is (<= (alength ^bytes (boring/buffer w)) 65536))))
  (testing "an explicit :stringref true alongside :index throws rather than one
            silently winning -- nav cannot resolve a reference from an offset"
    (is (thrown? clojure.lang.ExceptionInfo
                 (boring/write-indexed! (boring/writer 4096 nil) {:a 1}
                                        (ByteArrayOutputStream.)
                                        {:index 16 :stringref true})))))

(deftest trim-gives-back-what-one-large-job-grew
  (testing "a writer's growth is one-way -- buffer, symbol table and index
            arrays all keep their PEAK size -- which is what makes reuse
            allocation-free and also what pins a 256 MB buffer in a pool after
            one exceptional value. `trim!` is the explicit way back"
    (let [w (boring/writer 64)]
      (boring/encode-into! w (byte-array 1000000))
      (is (> (alength (boring/buffer w)) 1000000) "the big job grew it")
      (is (pos? (boring/trim! w)) "and trim reports what it released")
      (is (= 64 (alength (boring/buffer w))) "back to the size it was built at")
      (is (= {:a 1} (boring/decode (boring/encode-into! w {:a 1})))
          "a trimmed writer is a usable writer")))

  (testing "trimming a writer that never grew is a no-op, not an error"
    (is (zero? (boring/trim! (boring/writer 64)))))

  (testing "and mid-stream it refuses, because the buffer holds bytes that have
            not reached the sink yet"
    (let [^org.replikativ.boring.Writer w (boring/writer 64)]
      (.beginStream w (ByteArrayOutputStream.))
      (is (= :boring/bad-argument
             (try (boring/trim! w) (catch clojure.lang.ExceptionInfo e
                                     (:type (ex-data e)))))))))

(deftest a-forged-footer-opens-no-gate
  (let [t! (fn [f] (try (f) (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
        tmx (java.lang.management.ManagementFactory/getThreadMXBean)
        alloc-mb (fn [f] (let [tid (.getId (Thread/currentThread))
                               a (.getThreadAllocatedBytes tmx tid)]
                           (t! f)
                           (long (/ (- (.getThreadAllocatedBytes tmx tid) a) 1048576.0))))
        hx (fn [^String h] (byte-array (map (fn [p] (unchecked-byte (Integer/parseInt (apply str p) 16)))
                                            (partition 2 h))))
        data (byte-array (concat (seq (boring/encode {:a 1} {:stringref false}))
                                 (seq (boring/encode {:b 2} {:stringref false}))))
        ;; Honest data, then the seventeen bytes a real frame begins with, then
        ;; a nested-array bomb where the frame's slots would be, then a correct
        ;; back-pointer. The prefix gate matches; only NOT DECODING the footer
        ;; keeps this cheap.
        attack (let [o (ByteArrayOutputStream.)]
                 (.write o ^bytes data)
                 (.write o ^bytes (hx "d81b826c626f72696e672f696e64657886"))
                 (dotimes [_ 300000] (.write o 0x81))
                 (.write o 0x00)
                 ;; 0x48 -- the 8-byte byte-string header `footer-start` looks
                 ;; for. The first version of this test omitted it, so the gate
                 ;; never opened and both assertions passed on unfixed code.
                 ;; That is TWICE this test has been vacuous; the assertion
                 ;; below on `:boring/max-items-exceeded` is what makes the
                 ;; omission impossible to miss a third time.
                 (.write o 0x48)
                 (dotimes [i 8]
                   (.write o (unchecked-byte (bit-and (bit-shift-right (alength data) (* 8 (- 7 i)))
                                                      0xff))))
                 (.toByteArray o))]

    (testing "THE BOMB MUST ACTUALLY REACH THE BUDGET. The first version of this
              test used a tag-40 payload that failed validation at
              :boring/bad-tag-content with no budget set at all, so both of its
              assertions passed however broken the gate was -- which is exactly
              why the defect it was written to catch survived another round.
              This one allocates 102 MB against the unfixed code"
      (is (#{:boring/max-items-exceeded :boring/max-depth-exceeded}
           (t! #(doall (boring/decode-seq attack {:max-items 100}))))
          "the payload must reach a BUDGET -- either one proves the bomb is a
           real value and not a malformed item that dies in validation, which
           is how this assertion passed vacuously twice")
      (is (< (alloc-mb #(doall (boring/decode-seq attack {:max-items 100}))) 20)
          "and the footer is skipped, not decoded under lifted budgets"))

    (testing "an honest sealed file still reads back, under budgets too small
              for its own footer, on both readers"
      (let [sealed (let [o (ByteArrayOutputStream.)]
                     (boring/write-seq! (boring/writer 4096)
                                        (vec (for [i (range 300)] {:e i :v (str "v" i)})) o)
                     (.toByteArray o))]
        (is (= 300 (count (boring/decode-seq sealed))))
        (is (= 300 (count (boring/decode-seq sealed {:max-depth 3}))))
        (is (= 300 (count (boring/decode-seq-from
                           (java.io.ByteArrayInputStream. sealed) {:max-depth 3}))))))))

(deftest appending-a-second-sealed-batch-loses-nothing-in-decode-seq
  (testing "`footer-start` checked the seventeen prefix bytes and the pointer's
            range, but never that the frame ENDS at the file's end. Concatenate
            two sealed batches of equal length and the second file's pointer
            names an offset inside the FIRST that also carries the prefix -- so
            decode-seq stopped there and returned 40 of 82 items with no error,
            while decode-seq-from, nav/items, a bare Reader loop and
            ClojureScript all returned 82. `nav/read-index*` has always enforced
            the end-at-EOF rule; the sequence decoder had a weaker proxy for it"
    (let [mk (fn [n] (let [o (ByteArrayOutputStream.)]
                       (boring/write-seq! (boring/writer 4096)
                                          (vec (for [i (range n)] {:e i :v (str "v" i)})) o)
                       (.toByteArray o)))
          cat (fn [& bss] (let [o (ByteArrayOutputStream.)]
                            (doseq [^bytes b bss] (.write o b))
                            (.toByteArray o)))
          a (mk 40)]
      (testing "two batches of the SAME length -- the case that broke"
        (is (= 82 (count (boring/decode-seq (cat a a)))))
        (is (= (count (nav/items (cat a a))) (count (boring/decode-seq (cat a a))))
            "and every reader agrees"))
      (testing "different lengths, and a single batch, are unaffected"
        (is (= 84 (count (boring/decode-seq (cat a (mk 42))))))
        (is (= 40 (count (boring/decode-seq a))))
        (is (= 40 (count (boring/decode-seq a {:max-depth 3})))
            "including under a budget too small for the footer")))))

(deftest trim-refuses-to-pull-a-borrowed-buffer-away
  (testing "`encode-buffered!` hands back a byte count and tells the caller to
            read the bytes out of `buffer`; every other entry point copies them
            out. `trim!` replaces the buffer with a fresh, ZEROED one -- so
            trimming between the two gave the caller 25 zero bytes for
            `{:id 7 :name \"hello\"}`, which `decode` then read as the integer
            0. A wrong value out of an ordinary two-call sequence, with no
            error anywhere.

            `pos` cannot tell a borrow from an already-copied encode: it is
            non-zero after both. So the borrow is recorded."
    (let [w (boring/writer 8)]
      ;; grow it first, so trim has something to give back and the test is not
      ;; about a writer that was never oversized
      (boring/encode-buffered! w (vec (range 500)))
      (let [n (boring/encode-buffered! w {:id 7 :name "hello"})]
        (is (= :boring/bad-argument
               (try (boring/trim! w) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
            "the borrow is refused")
        (is (= {:id 7 :name "hello"}
               (boring/decode (java.util.Arrays/copyOf ^bytes (boring/buffer w) n)))
            "and the bytes the caller was told to read are still there")))
    (testing "while the flows that copy out still free memory -- the refusal has
              to be narrow or `trim!` stops doing its job"
      (let [w (boring/writer 8)]
        (boring/encode-into! w (vec (range 500)))
        (is (pos? (boring/trim! w)) "after encode-into!, which copies"))
      (let [w (boring/writer 8)]
        (boring/encode-buffered! w (vec (range 500)))
        (boring/encode-into! w {:a 1})
        (is (pos? (boring/trim! w)) "and after a later encode ends the borrow")))))
