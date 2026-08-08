(ns optidx
  "What does an index read cost if NOTHING is decoded and NOTHING is allocated
  beyond the answer?

  The frame already carries everything positionally: `containers` and `counts`
  are fixed-width typed arrays (element k is a computed offset), `sorted` is a
  bitset, and `slots` now carries a sparse start table. So a lookup is:

      binary search containers        O(log N) reads
      read counts[i], width, start    3 reads
      expand m anchors                O(m) reads

  Against today's open, which `.readFrom`s containers and counts into Java
  arrays, normalises int32 to long[], allocates three memo arrays and forces a
  delay -- ~1.9 us for ONE node."
  (:require [boring.core :as b] [boring.nav :as nav] [boring.mmap :as mm]
            [criterium.core :as crit])
  (:import [org.replikativ.boring Reader] [com.sun.management ThreadMXBean]
           [java.io File]))

(set! *warn-on-reflection* true)

(defn mean-us [f] (* 1e6 (first (:mean (crit/quick-benchmark (f) {})))))
(def ^ThreadMXBean tmx (java.lang.management.ManagementFactory/getThreadMXBean))
(defn alloc [f]
  (let [id (.getId (Thread/currentThread))]
    (dotimes [_ 20000] (f))
    (let [a (.getThreadAllocatedBytes tmx id)]
      (dotimes [_ 2000000] (f))
      (long (/ (- (.getThreadAllocatedBytes tmx id) a) 2000000)))))

;; ---------------------------------------------------------------- the reads

(defn le ^long [^Reader r ^long p ^long w]
  (case (int w)
    1 (bit-and (.byteAt r p) 0xFF)
    2 (bit-or (bit-and (.byteAt r p) 0xFF)
              (bit-shift-left (bit-and (.byteAt r (+ p 1)) 0xFF) 8))
    4 (long (unchecked-int
             (bit-or (bit-and (.byteAt r p) 0xFF)
                     (bit-shift-left (bit-and (.byteAt r (+ p 1)) 0xFF) 8)
                     (bit-shift-left (bit-and (.byteAt r (+ p 2)) 0xFF) 16)
                     (bit-shift-left (bit-and (.byteAt r (+ p 3)) 0xFF) 24))))
    (loop [j 0 acc 0]
      (if (= j 8) acc
          (recur (inc j) (bit-or acc (bit-shift-left
                                      (bit-and (.byteAt r (+ p j)) 0xFF) (* 8 j))))))))

;; An `Index` that is offsets and nothing else. No arrays, no memos, no delay.
(deftype Opt [^Reader rdr ^long n ^long stride
              ^long cdata ^long cw          ; containers: data offset, elem width
              ^long ndata                   ; counts: data offset (always 4)
              ^long sdata                   ; slots: data offset
              ^long tbase ^long tw          ; slots start table
              ^long srdata])                ; sorted bitset

(defn open-opt ^Opt [^Reader r ^long ptr ^long stride]
  (let [[_ oc ocn os osr] (#'nav/payload-offsets r ptr)
        ;; tag 78 = sint32, tag 79 = sint64; both little-endian typed arrays.
        ctag (.headArgAt r (long oc))
        cbs (.headEndAt r (long oc))
        cdata (.headEndAt r cbs)
        clen (.headArgAt r cbs)
        cw (if (= ctag 79) 8 4)
        n (quot clen cw)
        nbs (.headEndAt r (long ocn))
        ndata (.headEndAt r nbs)
        sdata (.headEndAt r (long os))
        srdata (.headEndAt r (long osr))
        b0 (bit-and (.byteAt r sdata) 0xFF)
        tw (if (zero? (bit-and (bit-shift-right b0 4) 0x0F)) 2 4)
        tbase (+ sdata 1 (quot (+ n 3) 4))]
    (Opt. r n stride cdata cw ndata sdata tbase tw srdata)))

(defn container-at ^long [^Opt x ^long i]
  (le ^Reader (.rdr x) (+ (.cdata x) (* (.cw x) i)) (.cw x)))
(defn count-at ^long [^Opt x ^long i]
  (le ^Reader (.rdr x) (+ (.ndata x) (* 4 i)) 4))
(defn wcode ^long [^Opt x ^long i]
  (let [^Reader r (.rdr x)]
    (bit-and 3 (bit-shift-right (.byteAt r (+ (.sdata x) 1 (quot i 4))) (* 2 (rem i 4))))))

(defn node-for ^long [^Opt x ^long off]
  (loop [lo 0 hi (dec (.n x))]
    (if (> lo hi) -1
        (let [mid (quot (+ lo hi) 2) c (container-at x mid)]
          (cond (= c off) mid (< c off) (recur (inc mid) hi) :else (recur lo (dec mid)))))))

(defn anchors ^longs [^Opt x ^long i]
  (let [^Reader r (.rdr x)
        cnt (count-at x i) st (.stride x)
        m (if (<= cnt 0) 0 (if (= st 1) cnt (inc (quot (dec cnt) st))))
        w (wcode x i) sz (bit-shift-left 1 w)
        blk (quot i 16)
        ;; the sparse table entry, then at most 15 node segments
        p0 (loop [j (* blk 16) p (+ (.sdata x) (le r (+ (.tbase x) (* blk (.tw x))) (.tw x)))]
             (if (= j i) p
                 (recur (inc j)
                        (+ p (* (let [c (count-at x j)]
                                  (if (<= c 0) 0 (if (= st 1) c (inc (quot (dec c) st)))))
                                (bit-shift-left 1 (wcode x j)))))))
        base (max 0 (container-at x i))
        out (long-array m)]
    (loop [k 0 p p0 acc base]
      (when (< k m)
        (let [v (+ acc (le r p sz))]
          (aset out k v) (recur (inc k) (+ p sz) v))))
    out))

;; ---------------------------------------------------------------- harness

(defn ptr-of ^long [^Reader r]
  (let [sz (.size r) bp (- sz 9) ^bytes pb (.readFrom r bp)]
    (areduce pb i acc 0 (bit-or (bit-shift-left acc 8) (bit-and (aget pb i) 0xFF)))))

(defn report [label ^bytes bi o mk-reader]
  (let [^Reader r (mk-reader)
        ptr (ptr-of r)
        stride (long (:index o))
        x (open-opt r ptr stride)
        a-nav (#'nav/nav-of bi o)
        truth (#'nav/slot-at (#'nav/nav-idx a-nav) 0)
        mine (anchors x 0)]
    (println (format "  %-34s agree=%-5s  CURRENT %8.3f us %8d B   OPT %8.3f us %8d B"
                     label (java.util.Arrays/equals ^longs truth ^longs mine)
                     (mean-us #(let [i (#'nav/read-index (#'nav/nav-of bi o))] (#'nav/slot-at i 0)))
                     (alloc #(let [i (#'nav/read-index (#'nav/nav-of bi o))] (#'nav/slot-at i 0)))
                     (mean-us #(anchors (open-opt r ptr stride) 0))
                     (alloc #(anchors (open-opt r ptr stride) 0))))))

(defn -main [& _]
  (let [O {:stringref false :shapes false :index 16 :index-min 4 :trust-index :trusted}
        flat (apply array-map (mapcat (fn [i] [(format "k%06d" i) i]) (range 128)))
        deep (vec (repeatedly 769 (fn [] (into {} (map (fn [i] [(str "k" i) i])) (range 20)))))
        ^bytes b1 (b/encode-indexed flat O)
        ^bytes bn (b/encode-indexed deep O)
        f (doto (File/createTempFile "optidx" ".cbor") (.deleteOnExit))]
    (with-open [o (java.io.FileOutputStream. f)] (.write o bn))
    (println)
    ;; Where does OPT's ~4 KB come from? Split the prototype in two.
    (let [^Reader r (Reader. bn)
          ptr (ptr-of r)
          x (open-opt r ptr 16)]
      (println (format "  %-34s %8.3f us %8d B" "open-opt alone"
                       (mean-us #(open-opt r ptr 16)) (alloc #(open-opt r ptr 16))))
      (println (format "  %-34s %8.3f us %8d B" "anchors alone (open hoisted)"
                       (mean-us #(anchors x 0)) (alloc #(anchors x 0))))
      (println (format "  %-34s %8.3f us %8d B" "payload-offsets alone"
                       (mean-us #(#'nav/payload-offsets r ptr))
                       (alloc #(#'nav/payload-offsets r ptr))))
      (println (format "  %-34s %8.3f us %8d B" "one .headArgAt"
                       (mean-us #(.headArgAt r (long ptr)))
                       (alloc #(.headArgAt r (long ptr))))))
    ;; SPARSE vs DENSE. Node 0 is block-aligned, so the block walk never runs --
    ;; every number so far was sparse's best case. Node 15 is the worst case in
    ;; a 16-block: one table read plus 15 nodes of counts[j] and wcode[j].
    (let [^Reader r (Reader. bn) ptr (ptr-of r) x (open-opt r ptr 16)]
      (doseq [i [0 1 7 15 16 400 767]]
        (println (format "  anchors node %-4d (block offset %2d)  %8.3f us %8d B"
                         i (rem i 16) (mean-us #(anchors x i)) (alloc #(anchors x i))))))
    (println)
    (report "heap, 1 node (128-entry map)" b1 O #(Reader. b1))
    (report "heap, 770 nodes" bn O #(Reader. bn))
    (with-open [arena (java.lang.foreign.Arena/ofConfined)]
      (let [seg (mm/mmap-segment f arena)]
        (report "MMAP, 770 nodes" bn O #(Reader. (mm/segment-source seg))))))
  (shutdown-agents))
