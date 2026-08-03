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

(declare ->Cursor cursor-at read-index read-index*)

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
                                {:got (class src)}))
        ;; The Reader was previously left at its defaults, so `opts` reached
        ;; only the ENCODE side (`probe-for`). Every decode option was silently
        ;; dropped: `:registry` was ignored, so a registered record came back as
        ;; a raw `#boring/record` frame instead of the type -- contradicting
        ;; both this namespace's promise that realising goes "through the
        ;; ordinary reader, same registry, same records" and `source`'s that
        ;; `opts` "are the decode options realisation will use". A caller's
        ;; `:max-depth`, which is a security bound, was not enforced here at all.
        _ (boring/configure-reader! r opts)]
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
  "Linear walk of a map's entries from `start`, at most `limit` of them.

  BOUNDED BY THE SOURCE, not only by the entry count. An index anchor is where
  this can start, and validation proves an anchor ascends, sits in range and --
  for the first one -- is the container's real first entry. It cannot prove that
  a MIDDLE anchor is an entry boundary without walking the container, which is
  the work the index exists to avoid. A middle anchor pointing mid-item made
  this read a garbage head and run off the end of the buffer, raising a raw
  ArrayIndexOutOfBoundsException at the caller of `get`.

  So the walk stops at the source's end and reports a miss. A damaged index may
  still give a wrong ANSWER -- that is the trust boundary doc/SHAPES.md
  describes, and it cannot be closed here -- but it may not throw an untyped
  exception out of a lookup."
  ^long [^Reader r ^long start ^long limit ^bytes probe]
  (let [end (.size r)]
    ;; The walk itself is guarded, not just its starting point. A start can be
    ;; in range while the item's DECLARED length runs past the buffer, so the
    ;; throw happens inside `skipFrom` before any check on its result could see
    ;; it -- `Reader.b` reads without bounds checks, which is what makes the
    ;; decoder fast and what makes a damaged offset land here.
    ;;
    ;; An out-of-range walk is reported as a MISS. With a damaged index the
    ;; answer may be wrong either way -- doc/SHAPES.md is explicit that this is
    ;; a trust boundary -- but it may not be an untyped exception out of `get`,
    ;; and the unsorted branch above simply tries the next anchor.
    ;;
    ;; try/catch costs nothing when nothing throws; this is not a hot-path tax.
    (try
      (loop [i 0 p start]
        (if (or (>= i limit) (>= p end) (neg? p))
          -1
          (if (.bytesEqualAt r p probe)
            (.skipFrom r p)
            (let [q (.skipFrom r (.skipFrom r p))]
              (if (or (<= q p) (> q end)) -1 (recur (inc i) q))))))
      (catch IndexOutOfBoundsException _ -1))))

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
    ;; A node with NO anchors means nothing to jump to -- walk instead. Empty
    ;; containers legitimately produce one (`:index-min 0` will index a `{}`),
    ;; and the sorted branch below assumes at least one anchor: with m=0 its
    ;; `(max 0 (min (dec m) hi))` still yields 0 and `aget` threw straight at
    ;; the caller of `get`. Cheaper to notice here than to special-case both
    ;; branches, and it also covers any future node that turns out empty.
    (if (or (neg? ns) (zero? (alength ^ints (nth (:slots idx) ns))))
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
            ;;
            ;; The PROBE is bounds-checked as well as the walk. Validation
            ;; proves the first anchor is a real entry; a middle one that points
            ;; mid-item survives, and `compareItemToBytes` skips from wherever
            ;; it is told -- reading a garbage head and running off the buffer,
            ;; which surfaced as a raw ArrayIndexOutOfBoundsException out of
            ;; `get`. Found by mutating every byte of a real indexed document
            ;; and requiring that no lookup ever throws an untyped exception.
            (let [lim (long (or (:data-end idx) (.size r)))]
              (loop [lo 0 hi (dec m)]
                (if (> lo hi)
                  (let [anchor (max 0 (min (dec m) hi))]
                    (scan-map r (aget slot anchor) (span anchor) probe))
                  (let [mid (quot (+ lo hi) 2)
                        q (long (aget slot mid))]
                    (if (or (neg? q) (>= q lim))
                      -1                       ; a damaged anchor: report a miss
                      (let [c (.compareItemToBytes r q probe)]
                        (cond (zero? c) (.skipFrom r q)
                              (neg? c) (recur (inc mid) hi)
                              :else (recur lo (dec mid)))))))))
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

(def ^:private shorts-class (class (short-array 0)))

(defn- expand-slot
  "A slot's deltas, back to the absolute offsets every lookup path expects.

  `boring.core/delta-slot` writes each slot as differences from the previous
  entry, in the narrowest of a byte string, sint16 or sint32 -- so the element
  type carries the width and nothing else has to.

  Expanding HERE, once per index, is what keeps this free. A prefix sum is the
  obvious cost of delta encoding, and the obvious place to pay it is in the
  binary search, up to a stride of additions per probe. There is no need: the
  index is already materialised off the wire before any lookup runs, so one pass
  at load time leaves `lookup-map`, `nth` and `node-slot` reading a plain
  `int[]` exactly as before. Postgres pays per probe because it reads its
  offsets in place from a TOAST'd datum and never gets a load-time pass at all.

  Bytes are masked because Java's are signed; shorts are not, because the writer
  only narrows to sint16 within the positive range."
  ^ints [s ^long base]
  (cond
    (bytes? s)
    (let [^bytes a s n (alength a) out (int-array n)]
      (loop [k 0 acc base]
        (if (= k n)
          out
          (let [v (+ acc (bit-and (aget a k) 0xFF))]
            (aset-int out k (int v))
            (recur (inc k) v)))))

    (instance? shorts-class s)
    (let [^shorts a s n (alength a) out (int-array n)]
      (loop [k 0 acc base]
        (if (= k n)
          out
          (let [v (+ acc (aget a k))]
            (aset-int out k (int v))
            (recur (inc k) v)))))

    :else
    (let [^ints a s n (alength a) out (int-array n)]
      (loop [k 0 acc base]
        (if (= k n)
          out
          (let [v (+ acc (aget a k))]
            (aset-int out k (int v))
            (recur (inc k) v)))))))

(defn- index-payload
  "The usable index in the tag-27 frame at `ptr`, or nil meaning \"scan\".

  Split out of `read-index` because it is the half that can FAIL. Detection --
  the tail shape, the pointer, the name -- establishes that something intends to
  be an index; this establishes that its payload can actually be used, which is
  a different question and the one a truncated or hand-edited file gets wrong.

  ANY exception here yields nil. Nothing in the index is load-bearing, so the
  honest response to a payload we cannot use is to ignore it and walk, never to
  throw at the caller of `nav/source`. That matters more than it did: slots are
  expanded eagerly below, so without this one malformed slot would take down a
  cursor that never went near the container it belongs to."
  [^Reader r ^long ptr]
  (try
    ;; `frame-payload`, not `record-fields`: an unregistered tag-27 frame
    ;; decodes to an UnknownRecord when its payload is a map and a
    ;; TaggedLiteral otherwise, and this payload is a vector.
    (let [;; The index frame is decoded against ITS OWN depth budget, not the
          ;; caller's. The frame is a fixed nested shape -- tag 27 around
          ;; [name, [stride, containers, counts, slots, sorted, pointer]] -- so
          ;; reading it costs four or five levels regardless of how shallow the
          ;; DATA is. With `:max-depth 1`, an indexed `[1]` therefore failed to
          ;; decode its own index, forgot it, and then walked into the frame as
          ;; data and raised -- so a VALID index made reading fail where no
          ;; index would have succeeded. That is the invariant inverted: the
          ;; optimisation became load-bearing.
          ;;
          ;; The caller's limit is a bound on THEIR data, and it is still
          ;; enforced on every value they realise. It was never a statement
          ;; about boring's own footer.
          saved (.-maxDepth r)
          _ (set! (.-maxDepth r) (int (max saved 32)))
          [stride ^ints containers ^ints counts slots sorted _]
          (try (boring.data/frame-payload (.readFrom r ptr))
               (finally (set! (.-maxDepth r) (int saved))))]
      (when (and (int? stride) (pos? (long stride)) containers
                 (instance? (Class/forName "[I") containers)
                 (instance? (Class/forName "[I") counts)
                 (sequential? slots) (sequential? sorted)
                 ;; STRUCTURE, not just decodability. Detection proves something
                 ;; MEANT to be an index; none of it proves the payload hangs
                 ;; together. A frame that decodes but whose parts disagree used
                 ;; to be trusted, and produced raw IndexOutOfBoundsException at
                 ;; the caller of `get`, or -- worse -- a wrong subtree.
                 (= (alength containers) (alength counts)
                    (count slots) (count sorted))
                 ;; `node-slot` BINARY-SEARCHES containers, so they must ascend.
                 (loop [k 1]
                   (cond (>= k (alength containers)) true
                         (>= (aget containers (dec k)) (aget containers k)) false
                         :else (recur (inc k))))
                 (every? nat-int? (seq counts)))
        ;; One uniform node list. The SEQUENCE is the node at the sentinel
        ;; offset -1: it has no container header on the wire but behaves like
        ;; one, and a sentinel avoids carrying two shapes for the same idea.
        (let [seq-slot (loop [k 0]
                         (cond (>= k (alength containers)) nil
                               (= -1 (aget containers k)) k
                               :else (recur (inc k))))
              ;; Deltas to absolutes, once, against each slot's own base: its
              ;; container's offset, or 0 for the sequence, whose sentinel -1
              ;; is not a position.
              abs-slots (vec (map-indexed
                              (fn [i s]
                                (expand-slot s (max 0 (long (aget containers (int i))))))
                              slots))
              st (long stride)
              ;; Every node, checked against the file it claims to describe.
              ;; Each of these was reachable: a slot shorter than its count made
              ;; `nth` walk off the end, a zero-length slot made the binary
              ;; search index -1, and an anchor pointing outside the data
              ;; section reached `Reader.skipFrom`, which does an unchecked
              ;; array access and throws a raw AIOOBE at the caller.
              ok? (every?
                   (fn [i]
                     (let [c (long (aget containers (int i)))
                           cnt (long (aget counts (int i)))
                           ^ints a (nth abs-slots i)
                           want (if (zero? cnt) 0 (inc (quot (dec cnt) st)))]
                       (and (= (alength a) want)
                            ;; The node must describe a container that IS THERE
                            ;; and has the entry count it claims, and its first
                            ;; anchor must be that container's first entry.
                            ;; All O(1) -- read from the head -- and together
                            ;; they reject an index whose offsets are internally
                            ;; tidy but point at the wrong places, which passed
                            ;; every earlier check and silently returned a
                            ;; neighbouring value.
                            (if (neg? c)
                              ;; the sequence node: its first item is byte 0
                              (or (zero? want) (zero? (aget a 0)))
                              (and (< c ptr)
                                   (#{4 5} (.majorAt r c))
                                   (= cnt (.headArgAt r c))
                                   (or (zero? want)
                                       (= (long (aget a 0)) (.headEndAt r c)))))
                            ;; anchors ascend, sit inside the data section, and
                            ;; for a real container start after its own header
                            (loop [k 0 prev -1]
                              (if (>= k (alength a))
                                true
                                (let [v (long (aget a k))]
                                  (if (and (> v prev) (<= 0 v) (< v ptr)
                                           (or (neg? c) (> v c)))
                                    (recur (inc k) v)
                                    false)))))))
                   (range (alength containers)))]
          (when ok?
            {:stride st
             :containers containers
             :counts counts
             :slots abs-slots
             :sorted (vec sorted)
             :data-end ptr
             :total (when seq-slot (long (aget counts seq-slot)))
             :offsets (when seq-slot (nth abs-slots seq-slot))}))))
    (catch Exception _ nil)))

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
  ;; The WHOLE of detection is inside the try, not just the payload.
  ;;
  ;; The head parser throws on reserved additional-info 28-30, and a corrupted
  ;; back-pointer can land on such a byte -- roughly 3 in 256 of random
  ;; corruptions, and arbitrary binary payloads contain 0xDC routinely. So
  ;; `nav/source` itself threw `boring: reserved additional-info 28` before
  ;; returning anything, on a file whose only fault was a damaged pointer. The
  ;; contract is that a corrupt pointer costs speed, never correctness, and it
  ;; has to cover the probing too.
  (try
    (read-index* nav)
    (catch Exception _ nil)))

(defn- read-index* [^Nav nav]
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
            ;; `>= 0`, not `> 0`. A pointer of zero says the data section is
            ;; empty and the index starts at byte 0 -- which is exactly what
            ;; sealing an EMPTY sequence produces. Requiring it to be positive
            ;; refused that index, and the trailing frame was then yielded as
            ;; though it were data, so `write-seq!` of nothing read back as one
            ;; `boring/index` item instead of none. The name check below is
            ;; what keeps this from widening the false-positive surface.
            (when (and (<= 0 ptr) (< ptr bp)
                       (= 6 (.majorAt r ptr))
                       (= 27 (.headArgAt r ptr))
                       ;; The frame must END EXACTLY AT THE FILE'S END.
                       ;;
                       ;; Without this, concatenating two sealed sequences lost
                       ;; half the data SILENTLY. `write-seq!` counts from 0, so
                       ;; its back-pointer is chunk-relative; append a second
                       ;; sealed batch of the same data length and the trailing
                       ;; pointer lands squarely on the FIRST batch's index
                       ;; frame -- a genuine tag-27 `boring/index`, so every
                       ;; other check passed. `nav/items` then stopped at the
                       ;; first chunk's data-end and reported 100 of 200 items,
                       ;; with no error. A stale index has to be detectable, and
                       ;; "the index is the last thing in the file" is what
                       ;; tells the real one from an earlier one. Checked after
                       ;; the tag probes, which are cheap and reject faster.
                       (= n (.skipFrom r ptr))
                       (let [arr (.headEndAt r ptr)]
                         (and (= 4 (.majorAt r arr))
                              (.bytesEqualAt r (.headEndAt r arr) (name-probe nav)))))
              (index-payload r ptr))))))))

(deftype Items [^Nav nav idx]
  clojure.lang.Seqable
  (seq [this] (seq (into [] this)))

  clojure.lang.Indexed
  (nth [this i] (.nth this i nil))
  (nth [_ i nf]
    (let [^Reader r (.rdr nav)
          end (long (or (:data-end idx) (.size r)))]
      ;; `:offsets`, not `idx`. An index can exist and carry NO sequence node:
      ;; only `write-seq!` emits the sentinel -1 node, so `encode-indexed`, and
      ;; `build-index` + `seal-index!` over a file somebody else wrote, both
      ;; produce one where `:offsets` and `:total` are nil. Testing `idx`
      ;; destructured `total` as nil and compared it, so `nth` threw an NPE --
      ;; on the 3-arity not-found form too, leaving no safe way to call it,
      ;; while `seq` and `reduce` on the same object worked.
      (if-let [^ints offsets (:offsets idx)]
        ;; O(1) to the anchor, then at most stride-1 skips.
        (let [stride (long (:stride idx))
              total (long (:total idx))]
          (if (or (neg? i) (>= i total))
            nf
            (let [anchor (quot (long i) stride)]
              (loop [k (* anchor stride) p (long (aget offsets anchor))]
                (if (= k (long i)) (cursor-at nav p) (recur (inc k) (.skipFrom r p)))))))
        ;; No sequence index: skip i times, or run out.
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
  same -- an index is an optimisation, not load-bearing for correctness (see
  doc/SHAPES.md for where that stops: damage leaving the payload structurally
  CONSISTENT, bit rot included, can still misdirect), and
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
