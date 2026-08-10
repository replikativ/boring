(ns source-shapes
  "Why does a column scan cost 4x more over a k7 payload than over an
  `allocateDirect` buffer holding the same bytes?

  ONE CONFIGURATION PER JVM, and that is the entire point of this file. Every
  accessor in `BufferSource` is `buf.get(...)` on a field typed `ByteBuffer`.
  The JIT inlines that to an unsafe load when the call site has seen ONE
  implementation class, keeps it cheap for two, and falls back to a virtual
  call with no bounds-check elimination at three or more. `ByteBuffer` has four
  that matter here -- HeapByteBuffer, HeapByteBufferR, DirectByteBuffer,
  DirectByteBufferR -- and a benchmark that measures all of them in one process
  measures a megamorphic call site for every one after the second.

  The earlier isolation did exactly that, in one REPL, and its numbers are
  therefore not comparable to each other. This runs one shape and exits.

      clojure -M:k7 -m source-shapes byte-array
      clojure -M:k7 -m source-shapes heap-buffer
      clojure -M:k7 -m source-shapes direct
      clojure -M:k7 -m source-shapes direct-ro
      clojure -M:k7 -m source-shapes mmap
      clojure -M:k7 -m source-shapes mmap-ro        ; what k7 hands back
      clojure -M:k7 -m source-shapes segment        ; FFM, for comparison
      clojure -M:k7 -m source-shapes polluted       ; all of them, then mmap-ro

  `polluted` is the control: if it is much slower than `mmap-ro` alone, the
  effect is call-site shape and not the memory."
  (:require [boring.core :as boring]
            [boring.nav :as nav]
            [criterium.core :as cc])
  (:import (java.io File RandomAccessFile)
           (java.nio ByteBuffer)
           (java.nio.channels FileChannel FileChannel$MapMode)
           (org.replikativ.boring BufferSource)))

(set! *warn-on-reflection* true)

(def ^:private n-rows 1000)

(defn- segment-blob ^bytes []
  (boring/encode-indexed
   (vec (for [i (range n-rows)]
          {:e (+ 100 i) :a :user/name :v (str "person-" i)
           :tx (+ 536870912 i) :added true}))
   {:shapes true :stringref true}))

;; --------------------------------------------------------- the source shapes

(defn- mapped
  "A MappedByteBuffer over a temp file holding `bs` at offset `base`, sliced to
  exactly the payload -- which is what k7 does: duplicate, position, limit,
  slice, and optionally asReadOnlyBuffer."
  ^ByteBuffer [^bytes bs ^long base ro?]
  (let [f (doto (File/createTempFile "boring-src" ".bin") (.deleteOnExit))
        raf (RandomAccessFile. f "rw")
        _ (.setLength raf (+ base (alength bs) 64))
        ch (.getChannel raf)
        m (.map ch FileChannel$MapMode/READ_WRITE 0 (.size ch))]
    (.position m (int base))
    (.put m bs)
    (let [dup (.duplicate m)
          _ (doto dup (.position (int base)) (.limit (int (+ base (alength bs)))))
          slice (.slice dup)]
      (.close ch)
      (if ro? (.asReadOnlyBuffer (.rewind slice)) (.rewind slice)))))

(defn- direct ^ByteBuffer [^bytes bs ro?]
  (let [b (doto (ByteBuffer/allocateDirect (alength bs)) (.put bs) (.flip))]
    (if ro? (.asReadOnlyBuffer b) b)))

(defn- shape-source [what ^bytes bs]
  (case what
    "byte-array"  bs
    "heap-buffer" (BufferSource/of (ByteBuffer/wrap bs))
    "direct"      (BufferSource/of (direct bs false))
    "direct-ro"   (BufferSource/of (direct bs true))
    "mmap"        (BufferSource/of (mapped bs 37 false))
    "mmap-ro"     (BufferSource/of (mapped bs 37 true))
    ;; FFM over the SAME mapping, so the comparison is BufferSource against
    ;; SegmentSource and not one memory kind against another.
    "segment"     ((requiring-resolve 'boring.mmap/segment-source)
                   (java.lang.foreign.MemorySegment/ofBuffer
                    (mapped bs 37 false)))))

;; ------------------------------------------------------------- the operations

(defn- nav-sum ^long [src]
  (let [s (nav/source src nil)
        sh (nav/shape s (nav/root-offset s))
        col (nav/shape-column sh :e)
        rows (nav/shape-rows sh)]
    (long (nav/reduce-at s rows
                         (fn [^long a ^long ro]
                           (+ a (nav/long-at s (nav/nth-offset s ro col))))
                         0))))

(defn- decode-sum ^long [src]
  (reduce (fn [^long a r] (+ a (long (:e r)))) 0 (boring/decode src)))

(defn- point ^long [src]
  (let [s (nav/source src nil)
        sh (nav/shape s (nav/root-offset s))]
    (nav/long-at s (nav/nth-offset s (nav/nth-offset s (nav/shape-rows sh) 613)
                                   (nav/shape-column sh :e)))))

(defn -main [& [what]]
  (let [what (or what "mmap-ro")
        bs (segment-blob)
        expected (reduce + (map #(+ 100 %) (range n-rows)))]

    ;; POLLUTE FIRST, if asked: run every other shape through the same
    ;; BufferSource accessors before measuring the one we care about.
    (when (= what "polluted")
      (doseq [w ["heap-buffer" "direct" "direct-ro" "mmap"]]
        (let [src (shape-source w bs)]
          (dotimes [_ 20000] (nav-sum src) (decode-sum src)))))

    (let [target (if (= what "polluted") "mmap-ro" what)
          src (shape-source target bs)]
      (println (format "\n=== %s ===" what))
      (println (format "source class: %s"
                       (if (bytes? src) "byte[]"
                           (let [b (try (.buffer ^BufferSource src)
                                        (catch Exception _ nil))]
                             (if b (.getName (class b)) (.getName (class src)))))))
      (assert (= expected (nav-sum src)) "nav disagrees")
      (assert (= expected (decode-sum src)) "decode disagrees")
      (print "nav column   ") (flush) (cc/quick-bench (nav-sum src))
      (print "decode column") (flush) (cc/quick-bench (decode-sum src))
      (print "nav point    ") (flush) (cc/quick-bench (point src)))))
