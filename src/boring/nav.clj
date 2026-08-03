(ns boring.nav
  "Read-only navigation over encoded CBOR, without decoding what you did not ask
  for.

  `(get-in src [\"customer-137\" \"name\"])` walks the wire format, steps over the
  199 customer records it does not want, and materialises one string. On a 74 KB
  blob that measured 27 µs against 181 µs for decode-then-`get-in`. On a file of
  1 MiB bytestrings, handing back a span instead of the payload is ~1000x.

  THE ONE SURPRISE. `get` returns a CURSOR, not a value. That is the whole
  point -- a cursor costs nothing, so intermediate maps are never built -- but
  it means `get-in` here does not behave like `get-in` on a map. Call `value`
  at the end:

      (-> src (get-in [\"customer-137\" \"name\"]) nav/value)

  WHAT IS IMPLEMENTED, AND WHAT IS DELIBERATELY NOT:

    ILookup      `get`, and therefore clojure.core/get-in for free
    Indexed      `nth` -- O(n), see below
    Counted      `count` -- genuinely O(1), the count is in the head
    Seqable      `seq` of children
    IReduceInit  `reduce` over children with no intermediate seq
    zipper       via `zipper`, read-only: make-node throws

  NOT IDeref. `@cursor` would read as a cheap field access while doing
  arbitrary decode work, and IDeref means a reference type with changing
  identity or a caching pending computation -- a cursor is neither. `value` is
  a function.

  `nth` is O(n) because a CBOR array carries an ELEMENT count, not byte
  offsets: reaching index i means stepping over i values. `Indexed` promises
  no better, but it is worth knowing before looping over indices.

  TWO HARD CONSTRAINTS, both checked rather than documented-and-hoped:

  1. `:stringref false` is required. A stringref is an index into a table built
     from every preceding string, so a cursor holding only an offset cannot
     resolve one -- and skipping a subtree would still have to register the
     strings inside it. Navigating a stringref document is refused, not
     silently wrong. boring writes stringref BY DEFAULT, so files meant to be
     navigated must be written with `{:stringref false}` -- and put that on the
     WRITER, `(boring/writer n {:stringref false})`, rather than passing it to
     every call: resolved per call it costs ~250 heap bytes per item, resolved
     once it costs nothing.

  2. Indefinite-length containers cannot be descended. Their count is not on
     the wire, so `count` could not be O(1) and `Counted` would be a lie.
     boring never emits them -- `Writer.head` only writes definite lengths --
     so this can only arrive from a foreign streaming encoder. Decode such a
     document with `boring/decode`, which handles them fine.

  TAGS ARE OPAQUE. `get` on a tagged value realises it through the normal
  reader and continues with clojure.core/get on the result. A tag's reader is
  an arbitrary function, so there is no general relationship between the wire
  shape and the logical shape of what it returns -- descending structurally
  could disagree with decoding, silently. The slow path IS the reference
  implementation, which is what makes the fast path safe to trust."
  (:require [boring.core :as boring]
            [boring.data]
            [clojure.zip :as zip])
  (:import (org.replikativ.boring Reader ByteSource)))

(set! *warn-on-reflection* true)

(declare ->Cursor cursor-at read-index)

(defn- fail [type msg data]
  (throw (ex-info msg (assoc data :type type))))

;; ---------------------------------------------------------------- the source

(deftype Nav [^Reader rdr opts probes idx])

(defn- node-slot
  "Position of the index node covering the container at `off`, or -1.

  Returns an INT, not a map. An earlier version returned
  `{:slot .. :count .. :sorted ..}` and allocated it on every lookup at every
  level, which made deep paths SLOWER with an index than without: at depth 64
  that is 64 maps per lookup, swamping the search it was meant to accelerate.

  Binary search over the sorted container offsets -- O(log C) per level, which
  is also what lets the index be sparse: an unindexed container simply is not
  found, and the caller walks."
  ^long [^Nav nav ^long off]
  (if-let [idx (.idx nav)]
    (let [^ints cs (:containers idx)]
      (loop [lo 0 hi (dec (alength cs))]
        (if (> lo hi)
          -1
          (let [mid (quot (+ lo hi) 2)
                c (aget cs mid)]
            (cond (= c off) mid
                  (< c off) (recur (inc mid) hi)
                  :else (recur lo (dec mid)))))))
    -1))

(defn- probe-for
  "The encoded bytes of `k`, cached. Key matching compares bytes rather than
  decoding keys, which is sound because encoding is deterministic for a given
  profile -- so byte equality is value equality."
  ^bytes [^Nav nav k]
  (let [p (.probes nav)]
    (or (get @p k)
        (let [bs (boring/encode k (.opts nav))]
          (swap! p assoc k bs)
          bs))))

(defn- nav-of ^Nav [src opts]
  (let [opts (assoc opts :stringref false)
        ^Reader r (cond
                    (bytes? src) (Reader. ^bytes src)
                    (instance? ByteSource src) (Reader. ^ByteSource src)
                    :else (fail :boring/unsupported-source
                                "boring.nav: expected a byte[] or a ByteSource"
                                {:got (class src)}))]
    (when (.hasStringrefRoot r)
      (fail :boring/stringref-not-navigable
            (str "boring.nav: this document opens a stringref namespace, and a "
                 "cursor holding only an offset cannot resolve one. Re-encode "
                 "with {:stringref false} to navigate it, or decode it whole "
                 "with boring/decode.")
            {}))
    (let [nav (Nav. r opts (atom {}) nil)]
      (Nav. r opts (atom {}) (read-index nav)))))

(defn source
  "A navigable view over `src` -- a byte[], or a ByteSource such as
  `boring.mmap/mmap-source` gives. Returns a cursor at the root.

  `opts` are the decode options realisation will use (`:registry` and friends),
  and must describe how the document was WRITTEN. `:stringref false` is forced;
  see the namespace docstring.

  ADDRESSES THE FIRST ITEM ONLY. A log or stream is usually a CBOR sequence
  (RFC 8742) -- many top-level items concatenated, which is what `write-to!` in
  a loop produces -- and a cursor from here would navigate only the first of
  them and silently ignore the rest. Use `items` for that. This is not an error
  case, because a caller may legitimately navigate a value sitting in an
  oversized scratch buffer."
  ([src] (source src nil))
  ([src opts] (cursor-at (nav-of src (or opts {})) 0)))

;; ------------------------------------------------------------- wire queries

(def ^:private ^:const MAJOR-ARRAY 4)
(def ^:private ^:const MAJOR-MAP 5)
(def ^:private ^:const MAJOR-TAG 6)

(defn- major ^long [^Nav nav ^long off] (.majorAt ^Reader (.rdr nav) off))

(defn- head-count
  "Element count for an array, pair count for a map. Refuses indefinite."
  ^long [^Nav nav ^long off]
  (let [n (.headArgAt ^Reader (.rdr nav) off)]
    (if (neg? n)
      (fail :boring/indefinite-length-not-navigable
            (str "boring.nav: cannot descend into an indefinite-length container "
                 "-- its count is not on the wire. boring never writes these; "
                 "decode this document with boring/decode instead.")
            {:offset off})
      n)))

;; ------------------------------------------------------------------ cursor

(defn- realize [^Nav nav ^long off]
  (.readFrom ^Reader (.rdr nav) off))

(defn- scan-map
  "Linear walk of a map's entries from `start`, at most `limit` of them."
  ^long [^Reader r ^long start ^long limit ^bytes probe]
  (loop [i 0 p start]
    (if (>= i limit)
      -1
      (if (.bytesEqualAt r p probe)
        (.skipFrom r p)
        (recur (inc i) (.skipFrom r (.skipFrom r p)))))))

(defn- lookup-map
  "Offset of the value for `k` in the map at `off`, or -1. Decodes no keys.

  Three paths, fastest first:

    indexed + sorted   binary search the node's anchors comparing ENCODED key
                       bytes, then walk at most `stride`-1 entries. O(log n).
    indexed            jump anchor to anchor, walking only within one stride --
                       still never touching a value.
    unindexed          walk every entry, which is what this always did.

  All three return the same offset. The index only decides how much is walked."
  ^long [^Nav nav ^long off k]
  (let [^Reader r (.rdr nav)
        n (head-count nav off)
        ^bytes probe (probe-for nav k)
        idx (.idx nav)
        ns (node-slot nav off)]
    (if (neg? ns)
      (scan-map r (.headEndAt r off) n probe)
      (let [^ints slot (nth (:slots idx) ns)
            stride (long (:stride idx))
            m (alength slot)]
        ;; Entries after anchor a, which is NOT always `stride`: the last
        ;; anchor covers the remainder. Walking a full stride from it ran off
        ;; the end of the container and into whatever followed -- found by the
        ;; missing-key case, where the search lands past the final anchor.
        (letfn [(span [^long a] (min stride (- n (* a stride))))]
          (if (nth (:sorted idx) ns)
            ;; Sorted keys: binary search the anchors, then a bounded walk.
            (loop [lo 0 hi (dec m)]
              (if (> lo hi)
                (let [anchor (max 0 (min (dec m) hi))]
                  (scan-map r (aget slot anchor) (span anchor) probe))
                (let [mid (quot (+ lo hi) 2)
                      q (aget slot mid)
                      c (.compareItemToBytes r q probe)]
                  (cond (zero? c) (.skipFrom r q)
                        (neg? c) (recur (inc mid) hi)
                        :else (recur lo (dec mid))))))
            ;; Unsorted: still jump anchor to anchor rather than entry to entry.
            (loop [a 0]
              (if (>= a m)
                -1
                (let [hit (scan-map r (aget slot a) (span a) probe)]
                  (if (>= hit 0) hit (recur (inc a))))))))))))

(defn- nth-item
  "Offset of element `idx` of the array at `off`, or -1.

  Arrays need no sorting: element i is simply the i-th recorded offset, so an
  indexed array is O(1) to the anchor and then at most `stride`-1 skips. That
  is why the index helps arrays under any profile, while maps need canonical
  key order before binary search is legal."
  ^long [^Nav nav ^long off ^long idx]
  (let [^Reader r (.rdr nav)
        n (head-count nav off)]
    (if (or (neg? idx) (>= idx n))
      -1
      (let [ix (.idx nav)
            ns (node-slot nav off)]
        (if (neg? ns)
          (loop [i 0 p (.headEndAt r off)]
            (if (= i idx) p (recur (inc i) (.skipFrom r p))))
          (let [^ints slot (nth (:slots ix) ns)
                stride (long (:stride ix))
                anchor (quot idx stride)]
            (loop [i (* anchor stride) p (long (aget slot anchor))]
              (if (= i idx) p (recur (inc i) (.skipFrom r p))))))))))

(defn- child-offsets
  "Offsets of the children of the container at `off`, in wire order. For a map
  these alternate key, value."
  [^Nav nav ^long off]
  (let [^Reader r (.rdr nav)
        mj (major nav off)
        n (head-count nav off)
        n (if (= mj MAJOR-MAP) (* 2 n) n)]
    (loop [i 0 p (.headEndAt r off) acc (transient [])]
      (if (= i n)
        (persistent! acc)
        (recur (inc i) (.skipFrom r p) (conj! acc p))))))

(deftype Cursor [^Nav nav ^long off]
  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [_ k nf]
    (let [mj (major nav off)]
      (cond
        (= mj MAJOR-MAP) (let [p (lookup-map nav off k)]
                           (if (neg? p) nf (cursor-at nav p)))
        ;; A tag's reader is arbitrary, so structure does not imply semantics.
        ;; Realise and let clojure.core decide -- correct for every registry.
        (= mj MAJOR-TAG) (get (realize nav off) k nf)
        :else nf)))

  clojure.lang.Indexed
  (nth [this i] (.nth this i nil))
  (nth [_ i nf]
    (let [mj (major nav off)]
      (cond
        (= mj MAJOR-ARRAY) (let [p (nth-item nav off i)]
                             (if (neg? p) nf (cursor-at nav p)))
        (= mj MAJOR-TAG) (nth (realize nav off) i nf)
        :else nf)))

  ;; Honest O(1): the count is in the head, and head-count refuses the
  ;; indefinite-length case where it would not be.
  clojure.lang.Counted
  (count [_]
    (let [mj (major nav off)]
      (if (or (= mj MAJOR-ARRAY) (= mj MAJOR-MAP))
        (int (head-count nav off))
        (fail :boring/not-a-container
              "boring.nav: count is only defined for arrays and maps"
              {:offset off :major mj}))))

  clojure.lang.Seqable
  (seq [_]
    (let [mj (major nav off)]
      (cond
        (= mj MAJOR-ARRAY) (seq (mapv #(cursor-at nav %) (child-offsets nav off)))
        (= mj MAJOR-MAP)
        (seq (mapv (fn [[kp vp]]
                     (clojure.lang.MapEntry. (realize nav kp) (cursor-at nav vp)))
                   (partition 2 (child-offsets nav off))))
        :else nil)))

  ;; The reducers path: no intermediate seq, no child cursors retained beyond
  ;; the step that uses them.
  clojure.lang.IReduceInit
  (reduce [_ f init]
    (let [^Reader r (.rdr nav)
          mj (major nav off)
          n (head-count nav off)]
      (cond
        (= mj MAJOR-ARRAY)
        (loop [i 0 p (.headEndAt r off) acc init]
          (if (or (= i n) (reduced? acc))
            (unreduced acc)
            (recur (inc i) (.skipFrom r p) (f acc (cursor-at nav p)))))
        (= mj MAJOR-MAP)
        (loop [i 0 p (.headEndAt r off) acc init]
          (if (or (= i n) (reduced? acc))
            (unreduced acc)
            (let [vp (.skipFrom r p)]
              (recur (inc i) (.skipFrom r vp)
                     (f acc (clojure.lang.MapEntry. (realize nav p)
                                                    (cursor-at nav vp)))))))
        :else (fail :boring/not-a-container
                    "boring.nav: reduce is only defined for arrays and maps"
                    {:offset off :major mj}))))

  Object
  (toString [_] (str "#boring.nav/cursor[" off "]")))

(defn- cursor-at [^Nav nav ^long off] (->Cursor nav off))

;; --------------------------------------------------------------- public API

(defn cursor? [x] (instance? Cursor x))

(defn value
  "Realise the subtree at the cursor into a Clojure value, through the ordinary
  decoder -- same registry, same records, same everything."
  [^Cursor c]
  (realize (.nav c) (.off c)))

(defn value-type
  "What is at the cursor, without decoding it: :map :array :text :bytes :tag
  :int :float-or-simple."
  [^Cursor c]
  (case (int (major (.nav c) (.off c)))
    0 :int, 1 :int, 2 :bytes, 3 :text, 4 :array, 5 :map, 6 :tag, :float-or-simple))

(defn byte-span
  "`[start end]` of the value at the cursor. `end` is exclusive. This is what
  lets a caller hand a subtree somewhere else without decoding it."
  [^Cursor c]
  (let [^Nav nav (.nav c) off (.off c)]
    [off (.skipFrom ^Reader (.rdr nav) off)]))

(defn raw-bytes
  "The encoded bytes of the subtree at the cursor, copied out. A re-encodable
  slice: `(boring/decode (raw-bytes c))` equals `(value c)`."
  ^bytes [^Cursor c]
  (let [^Nav nav (.nav c)
        [s e] (byte-span c)]
    (.bytesBetween ^Reader (.rdr nav) s e)))

(defn children
  "A reducible/seqable of child cursors (arrays) or MapEntries of realised key
  to value cursor (maps). Prefer `reduce` over `seq` in a hot loop."
  [^Cursor c] c)

(defn- name-probe
  "The encoded tag-27 type name, cached on the Nav."
  ^bytes [^Nav nav]
  (let [p (.probes nav)]
    (or (get @p ::index-name)
        (let [bs (boring/encode boring/index-name {:stringref false})]
          (swap! p assoc ::index-name bs)
          bs))))

(defn- read-index
  "The offset index sealed onto a CBOR sequence by `boring/write-seq!`, or nil.

  CBOR cannot be parsed backwards, so the index is found through its last
  element: an 8-byte byte string, which always encodes as `0x48` plus 8. Read
  the final 9 bytes, take the pointer, seek there.

  Three checks, because a file with no index could in principle end in those
  same bytes: the tail must have that shape, the pointer must be in range, and
  the target must actually be a tag-27 frame carrying the name. A false
  positive needs
  all three; a false negative just means scanning, which is always correct."
  [^Nav nav]
  (let [^Reader r (.rdr nav)
        n (.size r)]
    (when (>= n 9)
      (let [bp (- n 9)]
        (when (and (= 2 (.majorAt r bp)) (= 8 (.infoAt r bp)))
          (let [^bytes ptr-bytes (.readFrom r bp)
                ptr (areduce ptr-bytes i acc 0
                             (bit-or (bit-shift-left acc 8)
                                     (bit-and (aget ptr-bytes i) 0xFF)))]
            ;; Tag 27, then an array whose first element is the name. Checking
            ;; the NAME before decoding is what keeps a stray file that merely
            ;; ends in the right 9 bytes from being decoded as an index: it
            ;; would have to point at a tag-27 frame carrying this exact string.
            (when (and (pos? ptr) (< ptr bp)
                       (= 6 (.majorAt r ptr))
                       (= 27 (.headArgAt r ptr))
                       (let [arr (.headEndAt r ptr)]
                         (and (= 4 (.majorAt r arr))
                              (.bytesEqualAt r (.headEndAt r arr) (name-probe nav)))))
              ;; `frame-payload`, not `record-fields`: an unregistered tag-27
              ;; frame decodes to an UnknownRecord when its payload is a map
              ;; and a TaggedLiteral otherwise, and this payload is a vector.
              (let [rec (.readFrom r ptr)
                    [stride ^ints containers ^ints counts slots sorted _]
                    (boring.data/frame-payload rec)]
                (when (and (int? stride) (pos? (long stride)) containers)
                  ;; One uniform node list. The SEQUENCE is the node at the
                  ;; sentinel offset -1: it has no container header on the wire
                  ;; but behaves like one, and a sentinel avoids carrying two
                  ;; separate shapes for the same idea.
                  (let [seq-slot (loop [k 0]
                                   (cond (>= k (alength containers)) nil
                                         (= -1 (aget containers k)) k
                                         :else (recur (inc k))))]
                    {:stride (long stride)
                     :containers containers
                     :counts counts
                     :slots (vec slots)
                     :sorted (vec sorted)
                     :data-end ptr
                     :total (when seq-slot (long (aget counts seq-slot)))
                     :offsets (when seq-slot (nth (vec slots) seq-slot))}))))))))))

(deftype Items [^Nav nav idx]
  clojure.lang.Seqable
  (seq [this] (seq (into [] this)))

  clojure.lang.Indexed
  (nth [this i] (.nth this i nil))
  (nth [_ i nf]
    (let [^Reader r (.rdr nav)
          end (long (or (:data-end idx) (.size r)))]
      (if-let [{:keys [^long stride ^ints offsets ^long total]} idx]
        ;; O(1) to the anchor, then at most stride-1 skips.
        (if (or (neg? i) (>= i total))
          nf
          (let [anchor (quot (long i) stride)]
            (loop [k (* anchor stride) p (long (aget offsets anchor))]
              (if (= k (long i)) (cursor-at nav p) (recur (inc k) (.skipFrom r p))))))
        ;; No index: skip i times, or run out.
        (loop [k 0 p 0]
          (cond (>= p end) nf
                (= k (long i)) (cursor-at nav p)
                :else (recur (inc k) (.skipFrom r p)))))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (let [^Reader r (.rdr nav)
          ;; Stop at the data section's end, NOT the file's. Without this the
          ;; index item itself would be yielded as if it were data.
          end (long (or (:data-end idx) (.size r)))]
      (loop [p 0 acc init]
        (if (or (>= p end) (reduced? acc))
          (unreduced acc)
          (recur (.skipFrom r p) (f acc (cursor-at nav p)))))))

  Object
  (toString [_] "#boring.nav/items"))

(defn items
  "A reducible/seqable of cursors, one per TOP-LEVEL item, over a CBOR sequence
  (RFC 8742) -- the shape `write-to!` in a loop produces, and the natural frame
  for a log.

  Each item is independently decodable, so this streams: nothing before the
  cursor you are holding stays live, and `reduce` honours `reduced` so you can
  stop early without walking the rest of the file. Reaching item n costs n
  skips -- a skip being a structural walk, not a decode -- so tailing is cheap
  and random access to the middle of a large file wants an offset index built
  alongside the writes.

      (transduce (comp (map nav/value) (filter #(= \"error\" (get % \"lvl\"))))
                 conj [] (nav/items bs opts))

  If the sequence was sealed with an offset index (`boring/write-seq!` with
  `:index N`), `nth` uses it: O(1) to the nearest anchor, then at most N-1
  skips. Without one it skips from the start. Either way the answer is the
  same -- an index is an optimisation, never load-bearing for correctness, and
  a missing, truncated or stale one falls back to scanning.

  Detecting the index is not only about speed. The index is itself a top-level
  item, so without recognising it this would yield it as though it were data.

  The `:stringref false` requirement applies per item, as everywhere in this
  namespace."
  ([src] (items src nil))
  ([src opts]
   (let [nav (nav-of src (or opts {}))]
     (Items. nav (.idx nav)))))

(defn zipper
  "A read-only clojure.zip zipper over the cursor. `down`, `right`, `node` and
  friends work; anything that edits throws, because a change of length would
  cascade through every offset after it."
  [^Cursor c]
  (zip/zipper
   (fn branch? [^Cursor x] (contains? #{:array :map} (value-type x)))
   (fn children* [^Cursor x]
     (seq (map (fn [e] (if (instance? clojure.lang.MapEntry e) (val e) e))
               (seq x))))
   (fn make-node [_ _]
     (fail :boring/read-only
           (str "boring.nav: zippers over encoded CBOR are read-only -- editing "
                "changes lengths, which shifts every offset after the edit. "
                "Decode, edit, re-encode.")
           {}))
   c))
