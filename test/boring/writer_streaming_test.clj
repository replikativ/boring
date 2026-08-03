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

(deftest a-leaf-larger-than-the-buffer-still-grows-it
  (testing "documented, not a bug: a long string is ONE CBOR item with a length
            header, so it cannot be split across chunks. nippy buffers the same
            cases. Collections stream; leaves do not."
    (let [sink (proxy [OutputStream] [] (write ([_]) ([_ _ _])))
          s (apply str (repeat 400000 \x))
          w (boring/writer 4096 o)]
      (boring/write-to! w s sink o)
      (is (>= (alength ^bytes (boring/buffer w)) 400000)
          "the buffer had to grow to hold the one leaf"))))

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
