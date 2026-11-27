(ns ^:no-doc boring.index-packing)

(def ^:no-doc ^:const slot-layout-v2
  "Version nibble for a DENSE start table -- one entry per node.

  v1 was sparse, one entry every 16 nodes, on the reasoning that a bounded
  block walk was the same win an order of magnitude cheaper in bytes. Measured
  on a 770-node frame, that was wrong: a block-aligned node resolves in
  0.073 us and one at the end of its block in 0.376 -- the walk costs ~0.30 us,
  FIVE TIMES the base, and averages ~0.22 across a block. Every number that
  made sparse look free had been taken on node 0, which is block-aligned, so
  the walk never ran.

  Dense costs 1542 bytes against sparse's 98 on that frame -- but 1.85% of the
  BLOB, which is the denominator that matters, against the +84%-of-`slots`
  figure the sparse decision was anchored on. It is also simpler: `start_i` is
  one read, there is no block loop, and `counts` stops being needed to locate a
  node at all.

  The layout byte is what makes this cost nothing to change, which is the
  reason it exists."
  2)

(defn- slot-width-code
  "The narrowest of four widths that holds every delta in `d`: 0 = u8, 1 = u16,
  2 = i32, 3 = i64.

  On 50 000 ~66-byte log records the per-item deltas span 60..67, which is a
  byte, against int32 absolutes reaching into the millions -- so the width is
  worth choosing, and worth choosing PER NODE. See `pack-slots`."
  ^long [^longs d]
  (let [n (alength d)]
    (loop [i 0 mn Long/MAX_VALUE mx Long/MIN_VALUE]
      (if (= i n)
        (cond
          (or (zero? n) (and (>= mn 0) (<= mx 0xFF))) 0
          (and (>= mn 0) (<= mx 0xFFFF)) 1
          (and (>= mn Integer/MIN_VALUE) (<= mx Integer/MAX_VALUE)) 2
          ;; The fourth tier. Only reachable when two anchors are more than
          ;; 2 GiB apart, which needs items that large -- but it costs nothing
          ;; to have, because a narrower node is still emitted beside it.
          :else 3)
        (let [v (aget d i)]
          (recur (inc i) (min mn v) (max mx v)))))))

(defn- anchor-count
  "How many anchors a container of `n` entries carries at `stride`.

  The reader derives every node's segment length from this rather than reading
  it, which is what keeps `slots` a single flat byte string. It must agree
  EXACTLY with `Writer.anchorCount` and with what the walk emits -- a
  disagreement would silently shift every subsequent node's anchors. Both index
  builders were checked against it across strides 1/4/16 and five document
  shapes before the derivation replaced the stored lengths.

  An EMPTY container needs no anchors: `((0 - 1) / stride) + 1` is 1 under
  truncating division, which would claim a phantom anchor at offset 0."
  ^long [^long n ^long stride]
  (if (<= n 0) 0 (if (= stride 1) n (inc (quot (dec n) stride)))))

(defn pack-slots
  "Every node's anchor deltas, as ONE byte string.

      [ layout byte | 2-bit width code per node | dense start table |
        node 0's deltas | node 1's deltas | ... ]

  The width codes come first, `(quot (+ n 3) 4)` bytes of them, node `i` at bit
  `(* 2 (rem i 4))` of byte `(+ 1 (quot i 4))` -- the layout byte is byte 0: 0 = u8, 1 = u16, 2 = i32, 3 = i64,
  little-endian. Then each node's deltas back to back at its own width. A node's
  segment LENGTH is not stored -- it is `anchor-count` of that node's entry
  count, which the frame already carries.

  This replaced one typed array PER NODE. That shape cost three ways and this
  costs none of them: every small array carried its own CBOR head (a byte a
  node, 33% of the slots on small deltas), decoding them all materialised a Java
  array per container to serve a lookup that visits one or two, and locating
  them positionally instead meant a head-read and a jump per node before any
  lookup could start. Measured on a 767-node frame, the two together were 53.5
  of the 92 ns per node it cost to open an index; this is 5.3.

  A SINGLE FLAT TYPED ARRAY was the obvious alternative and is the wrong one.
  It is faster still -- 0.53 ns/node, since it needs no width dispatch -- but
  one array has one width, so a single container with 32 KiB between anchors
  promotes every other node to int32. Measured: 33% SMALLER than the old shape
  when all deltas are small, 166% LARGER when one node among 767 is wide. That
  is not a corner: a map of small values beside one large blob is the ordinary
  shape of scraped data. Per-node widths keep the narrow case narrow and make
  the wide case cost only the node that is wide.

  Unsigned u8 and u16 rather than the byte-string/sint16 pair the typed arrays
  forced. The old second tier was SIGNED and so capped at 0x7FFF, sending
  32 KiB..64 KiB deltas to int32; nothing here is a CBOR typed array, so the
  full 16 bits are usable and that band stays two bytes wide."
  ^bytes [slots ^longs containers ^ints counts ^long stride]
  (let [n (count slots)
        ds (object-array n)
        ws (byte-array n)]
    (dotimes [i n]
      (let [^longs offs (nth slots i)
            m (alength offs)
            d (long-array m)]
        ;; THE INVARIANT THE WHOLE LAYOUT RESTS ON, checked where it is
        ;; established rather than assumed where it is used. No segment length
        ;; goes on the wire: the reader slices `packed` by `anchor-count` of
        ;; each node's entry count, so a walk that ever emitted a different
        ;; number of anchors would not corrupt one node, it would shift every
        ;; node after it -- silently, into other nodes' deltas. Cheap: one
        ;; integer per node, on the writing side, which is the expensive side.
        ;; EITHER OF THE TWO LEGAL STRIDES. Stride is per node now: an unsorted
        ;; map is only usable at 1, everything else takes the file's. So a
        ;; node's anchor count must match `anchor-count` at ONE of them, and
        ;; which one is not stored -- the reader derives it, because a node
        ;; whose anchors equal its entries can only have been written at 1.
        ;;
        ;; Still the invariant the layout rests on, just widened by one case:
        ;; no segment length goes on the wire, so a walk that emitted some
        ;; other number would shift every node after it.
        (when-not (or (= m (anchor-count (aget counts i) stride))
                      (= m (anchor-count (aget counts i) 1)))
          (throw (ex-info (str "boring: index node " i " has " m " anchors but "
                               (aget counts i) " entries implies "
                               (anchor-count (aget counts i) stride) " at stride "
                               stride " or " (anchor-count (aget counts i) 1)
                               " at stride 1."
                               " The index walk and `anchor-count` disagree.")
                          {:type :boring/bad-index :node i :anchors m
                           :entries (aget counts i) :stride stride})))
        ;; Offsets inside a container ascend, so consecutive differences are
        ;; small and nearly uniform while the absolutes are large and
        ;; unbounded. That is the whole saving. `base` is the container's own
        ;; offset, or 0 for the sequence node, whose sentinel offset is -1 --
        ;; so slot[0] is a header width rather than a file position, which
        ;; keeps the first delta as narrow as the rest.
        (loop [k 0 prev (max 0 (aget containers i))]
          (when (< k m)
            (let [v (aget offs k)]
              (aset d k (- v prev))
              (recur (inc k) v))))
        (aset ds i d)
        ;; A negative delta cannot arise from a walk -- entries ascend and no
        ;; CBOR item is zero bytes -- but u8 and u16 cannot represent one, so
        ;; `slot-width-code` promotes to a signed tier rather than wrapping.
        ;; That makes it an assumption about the walk, not about the file.
        (aset-byte ws i (byte (slot-width-code d)))))
    (let [wbytes (quot (+ n 3) 4)
          seg (fn ^long [^long i] (* (alength ^longs (aget ds i))
                                     (bit-shift-left 1 (aget ws i))))
          deltas (loop [i 0 t 0] (if (= i n) t (recur (inc i) (+ (long t) (long (seg i))))))
          entries (inc n)          ; DENSE: start_0 .. start_n
          ;; W depends on the total, and the total depends on W. Two iterations
          ;; settle it: widening the table can only push the total up by
          ;; `entries * 2`, never back down.
          w-start (loop [sw 2]
                    (let [tot (+ 1 wbytes (* entries sw) deltas)]
                      (if (or (= sw 4) (< tot 0x10000)) sw (recur 4))))
          header (+ 1 wbytes (* entries w-start))
          total (+ header deltas)
          out (byte-array total)
          put! (fn [^long o ^long v ^long sz]
                 (dotimes [j sz]
                   (aset-byte out (+ o j) (unchecked-byte (bit-shift-right v (* 8 j))))))]
      ;; The layout byte: version in the low nibble, the start table's entry
      ;; width in the high nibble. It is what makes a frame in the PREVIOUS
      ;; shape refusable exactly rather than probabilistically -- a reader that
      ;; expects this layout and meets the old one reads a width code where a
      ;; version should be, and says so, instead of computing nonsense offsets
      ;; and hoping the length check catches them.
      (aset-byte out 0 (unchecked-byte (bit-or slot-layout-v2
                                               (bit-shift-left (if (= w-start 4) 1 0) 4))))
      (dotimes [i n]
        (let [b (+ 1 (quot i 4))]
          (aset-byte out b (unchecked-byte
                            (bit-or (bit-and 0xFF (aget out b))
                                    (bit-shift-left (aget ws i) (* 2 (rem i 4))))))))
      ;; THE DENSE START TABLE, one entry per node plus a final entry holding
      ;; the total. Node i's deltas begin at entry i -- ONE READ, where this
      ;; was a prefix sum over every earlier node, which is what let the reader
      ;; stop materialising `slot-starts` at all.
      ;;
      ;; The final entry is also the structural gate: it must equal the byte
      ;; string's own length, which is one read instead of a sum over N.
      (let [tbase (+ 1 wbytes)]
        (loop [i 0 p (long header)]
          (put! (+ tbase (* i w-start)) p w-start)
          (when (< i n) (recur (inc i) (+ (long p) (long (seg i)))))))
      (loop [i 0 p (long header)]
        (if (= i n)
          out
          (let [^longs d (aget ds i)
                sz (bit-shift-left 1 (aget ws i))
                m (alength d)]
            (dotimes [k m] (put! (+ p (* k sz)) (aget d k) sz))
            (recur (inc i) (+ p (* m sz)))))))))

(defn pack-sorted
  "One bit per node -- does this container's keys ascend -- as a byte string,
  node `i` at bit `(rem i 8)` of byte `(quot i 8)`.

  This was an array of CBOR booleans, one item each. Measured on a 767-node
  frame: 770 bytes and 8.1 us to decode, against 98 bytes and 0.04 us here.
  Two hundred times faster and eight times smaller, for a bit vector that CBOR
  had no compact form for. The byte length is exact -- `(quot (+ n 7) 8)` --
  which is also what lets the reader check it against the node count."
  ^bytes [sorted]
  (let [v (vec sorted)
        n (count v)
        out (byte-array (quot (+ n 7) 8))]
    (dotimes [i n]
      (when (nth v i)
        (let [b (quot i 8)]
          (aset-byte out b (unchecked-byte
                            (bit-or (bit-and 0xFF (aget out b))
                                    (bit-shift-left 1 (rem i 8))))))))
    out))

(def ^:no-doc ^:const stringref-layout-v1
  "Version nibble for the stringref pointer table -- payload element 6.

  Its own version rather than the frame's, for the reason every other element
  carries one: elements are optional and independently extensible, so a reader
  that meets a shape it does not know must be able to say which element it
  failed on."
  1)

(defn- unsigned-width-code
  "The narrowest unsigned width holding `mx`: 0 = u8, 1 = u16, 2 = u32, 3 = u64.

  UNSIGNED, unlike `slot-width-code`, whose upper two tiers are signed because
  a delta can in principle be negative. Neither a stringref index nor a file
  offset can be, so the sign bit is a byte-eighth this can spend on range --
  and it is the difference between a 64 KiB and a 4 GiB document keeping
  two-byte offsets."
  ^long [^long mx]
  (cond (< mx 0x100)        0
        (< mx 0x10000)      1
        (< mx 0x100000000)  2
        :else               3))

(defn pack-stringrefs
  "The referenced stringref slots, as ONE byte string.

      [ layout byte | (index, offset) | (index, offset) | ... ]

  The layout byte is version in the low nibble, the index width code in bits
  4-5 and the offset width code in bits 6-7. Pairs follow back to back,
  ascending by index, each field little-endian at its own width. THE COUNT IS
  NOT STORED: it is `(quot (dec len) (+ iw ow))`, and the structural gate is
  that the division is exact -- one modulo, no sum over N, exactly as the slot
  table's final entry gates that layout.

  WHY THIS EXISTS. A stringref is an index into a table built by decoding every
  preceding string, so a reader holding nothing but an offset cannot resolve
  one -- which is why `:stringref` and `:index` were mutually exclusive. Given
  (index -> defining offset) for the entries something actually references, the
  reader resolves by jumping instead of by remembering, and the two compose.

  ONLY REFERENCED ENTRIES. A registered-but-unreferenced slot is one nothing
  will ever ask for, and on konserve-shaped data most slots are exactly that:
  200 records carry ~256 registered strings and ~56 referenced ones. Dense
  offsets would be 768 bytes where these are 224.

  TWO WIDTHS, NOT ONE. Indices are small and dense from zero while offsets span
  the document, so a single width would promote every index to the offset's
  tier -- the same mistake `pack-slots` records for a single flat typed array,
  in miniature.

  EMPTY IS STILL AN ELEMENT -- one layout byte and no pairs. It would be
  cheaper to omit it, and that is what this did, but the two states are not the
  same to a reader: \"the frame carries no table\" is a document nothing can
  navigate, while \"the frame carries an empty table\" is a document that opens
  a namespace and references nothing, which is perfectly navigable. Collapsing
  them made `(nav/root (encode-indexed (vec (range 40))))` refuse a file with
  no strings in it at all, because the default profile opens a namespace
  whatever the content turns out to be.

  `pairs` is the interleaved `long[]` from `Writer.stringrefPointers`, already
  ascending. nil in means nil out, and the element is then omitted entirely --
  which is every document that opens no namespace."
  ^bytes [^longs pairs]
  (when pairs
    (let [n (quot (alength pairs) 2)
          mx-i (loop [k 0 m 0] (if (= k n) m (recur (inc k) (max m (aget pairs (* 2 k))))))
          mx-o (loop [k 0 m 0] (if (= k n) m (recur (inc k) (max m (aget pairs (inc (* 2 k)))))))
          ic (unsigned-width-code mx-i)
          oc (unsigned-width-code mx-o)
          iw (bit-shift-left 1 ic)
          ow (bit-shift-left 1 oc)
          out (byte-array (+ 1 (* n (+ iw ow))))
          put! (fn [^long o ^long v ^long sz]
                 (dotimes [j sz]
                   (aset-byte out (+ o j) (unchecked-byte (bit-shift-right v (* 8 j))))))]
      (aset-byte out 0 (unchecked-byte (bit-or stringref-layout-v1
                                               (bit-shift-left ic 4)
                                               (bit-shift-left oc 6))))
      (dotimes [k n]
        (let [p (+ 1 (* k (+ iw ow)))]
          (put! p (aget pairs (* 2 k)) iw)
          (put! (+ p iw) (aget pairs (inc (* 2 k))) ow)))
      out)))

(defn min-len-for-index
  "The shortest encoded length that earns stringref index `idx`.

  Must agree EXACTLY with `Writer.minLenForIndex` and `Reader.minLenForIndex`:
  all three decide independently whether a given string took an index at all,
  and a disagreement shifts every later index rather than one."
  ^long [^long idx]
  (cond (< idx 24)    3
        (< idx 256)   4
        (< idx 65536) 5
        :else         7))
