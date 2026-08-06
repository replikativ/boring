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
            [boring.errors :as err]
            [boring.frame :as frame]
            [boring.options :as opt]
            [clojure.zip :as zip])
  (:import (org.replikativ.boring Reader ByteSource)
           (java.util.concurrent.atomic AtomicReferenceArray)))

(set! *warn-on-reflection* true)

(declare ->Cursor cursor-at read-index read-index* tag-view
         nth-item realize lookup-map head-count child-offsets)

;; A shaped array is `39649([keys, [row, row, ...]])`, where each row is an
;; ARRAY of values positionally matching `keys`. It REALISES to a vector of
;; maps, so a cursor on one has to present that shape without building it --
;; see `shaped-view`.
(def ^:private ^:const TAG-SHAPED-ARRAY 39649)

(defn- fail [type msg data]
  (throw (ex-info msg (assoc data :type type))))

;; ---------------------------------------------------------------- the source

(declare slot-at node-sound?)

;; `shapes` caches shaped-array descriptions by tag offset. Separate from
;; `probes` rather than sharing it: `probes` is keyed by USER key values, so a
;; caller whose key happened to equal a shape cache key would get a description
;; map where encoded bytes were expected. A field costs one pointer.
(deftype Nav [^Reader rdr opts probes idx src shapes])

(defn- nav-idx
  "The decoded index, parsed on first use.

  `idx` holds a DELAY, not the index. Parsing the frame is the expensive part
  -- measured 8.5 us of the 11.0 us a per-call indexed lookup took, against
  2.5 us once the source is reused -- and a caller that only touches a
  top-level key never needs it. A store hands out a fresh blob per read and so
  constructs a source per read, which made an index cost more than it saved:
  an indexed binary search ran 11.0 us per call against 2.5 us reused, and a
  shallow `:meta` lookup went from 0.95 us unindexed to 7.5 us indexed.

  Deferring it makes an index free for the lookups that do not consult it, and
  unchanged for the ones that do."
  [^Nav n]
  (when-let [d (.idx n)] @d))

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
  ;; `:containers`, not the index itself. A detected frame whose PAYLOAD is
  ;; unusable now yields `{:data-end ptr}` alone -- the two questions are
  ;; answered separately, see `read-index*` -- so an index can be present and
  ;; carry no nodes at all.
  (let [ix (nav-idx nav)]
    (if-let [^longs cs (:containers ix)]
      (loop [lo 0 hi (dec (alength cs))]
        (if (> lo hi)
          -1
          (let [mid (quot (+ lo hi) 2)
                c (aget cs mid)]
            (cond
              ;; VALIDATED HERE, not at load. The node is checked against the
              ;; data the first time a lookup lands on it, and an unsound one
              ;; reports -1 -- which every caller already treats as "no index,
              ;; walk it". So the fallback path is the one that always existed
              ;; and the failure mode is unchanged; only the moment of
              ;; discovery moved. See `index-payload`.
              (= c off) (if (node-sound? nav ix mid) mid -1)
              (< c off) (recur (inc mid) hi)
              :else (recur lo (dec mid))))))
      -1)))

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

;; A reusable navigation context: resolved options plus the PROBE CACHE, shared
;; across every source opened through it.
;;
;; The probe cache holds `key value -> encoded bytes`, which is a fact about the
;; key and the profile and NOT about any document, so sharing it is sound. It is
;; also where the time goes when a caller opens one source per document and
;; makes a handful of lookups: encoding the four keywords of
;; `[:value :profile :address :city]` costs 0.711 us against 0.169 us for the
;; navigation itself, so a scan over 4000 blobs paid for those four keys 4000
;; times. Reusing one source instead measured 8.9x faster end to end.
;;
;; THE SHAPE CACHE IS DELIBERATELY NOT SHARED. It is keyed by OFFSET, which
;; means something only within one document -- offset 22 is a different
;; container in the next blob -- so a shared one would hand a cursor another
;; document's shape and return wrong values. Each source gets its own.
;; `cfg` is the reader configuration resolved ONCE. `configure-reader!` reads
;; eleven options from a Clojure map, and a scan that opens a source per document
;; repeated that per document -- 44 000 map lookups over 4000 blobs, each one a
;; linear scan of a PersistentArrayMap, for an answer fixed before the scan
;; began. Same shape as the encoded-key cache this type already carries.
(deftype NavContext [opts probes ^objects cfg])

(defn context
  "A reusable navigation context for `opts`, to be passed to `source` in place
  of the options map when opening MANY sources with the same options.

  Sources opened through one context share the encoded-key cache, so a path's
  keys are encoded once for the whole scan rather than once per document. That
  is worth having: on 229-byte blobs, encoding the keys of a four-step path cost
  0.711 us per document against 0.169 us for the navigation it enabled.

    (let [ctx (nav/context {:stringref false})]
      (doseq [blob blobs]
        (nav/value (get-in (nav/source blob ctx) path))))

  A context holds no document state and is safe to reuse for as long as the
  options hold. It is NOT a cache of documents: per-document state, including
  the index and the shape cache, still belongs to each source.

  Thread-safe: the cache is an atom, and the worst a race can do is encode the
  same key twice."
  [opts]
  (let [o (assoc (opt/check-opts opts) :stringref false)]
    (NavContext. o (atom {}) (boring/reader-config o))))

(defn- nav-of ^Nav [src opts]
  ;; VALIDATED like every other decode entry point. This was the one that was
  ;; not: `nav/source` took whatever map it was handed straight to
  ;; `configure-reader!`, so `{:max-depth "5"}` -- a security bound -- was
  ;; accepted here and refused everywhere else.
  ;;
  ;; `check-opts` and not `resolve-opts`, for a reason worth stating: the map
  ;; is STORED and handed to `boring/encode` later, by `probe-for`, which
  ;; resolves it there. Resolving it here too would resolve it twice -- and
  ;; resolution is deliberately not idempotent, because a resolved map has
  ;; `:canonical` in it and re-resolving reads that as the caller trying to
  ;; override what the profile locks.
  (let [ctx? (instance? NavContext opts)
        ;; A context has already been checked and had `:stringref false`
        ;; applied; re-doing it per source would put the per-document cost back.
        probes (if ctx? (.probes ^NavContext opts) (atom {}))
        ^objects cfg (when ctx? (.cfg ^NavContext opts))
        opts (if ctx?
               (.opts ^NavContext opts)
               (assoc (opt/check-opts opts) :stringref false))
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
        ;; A context applies its pre-resolved config; everything else resolves
        ;; the map here, exactly as before.
        _ (if cfg (boring/apply-reader-config! r cfg) (boring/configure-reader! r opts))]
    (when (.hasStringrefRoot r)
      (fail :boring/stringref-not-navigable
            (str "boring.nav: this document opens a stringref namespace, and a "
                 "cursor holding only an offset cannot resolve one. Re-encode "
                 "with {:stringref false} to navigate it, or decode it whole "
                 "with boring/decode.")
            {}))
    ;; ONE Nav, not two. The delay has to close over the Nav it will read from,
    ;; and the Nav has to hold the delay, so this used to build a throwaway Nav
    ;; purely to be captured -- two Navs and two shape atoms per source, on a
    ;; path a document store walks once per blob. A volatile breaks the cycle
    ;; without changing what anything sees: the delay cannot run before the
    ;; vreset!, because only the returned Nav is reachable.
    ;;
    ;; `:trust-index :ignore` skips the index entirely and scans. The scan is
    ;; the reference implementation the indexed paths are checked against, so
    ;; this is the one setting whose correctness needs no separate argument.
    (let [holder (volatile! nil)
          ;; A DELAY. Detection is deferred with the parse: a document with no
          ;; frame costs nothing to find that out, and one with a frame pays
          ;; only if something consults it.
          idx (when-not (= :ignore (:trust-index opts))
                (delay (read-index @holder)))
          nav (Nav. r opts probes idx src (atom {}))]
      (vreset! holder nav)
      nav)))

(defn- fork-nav ^Nav [^Nav n]
  (let [src (.src n)
        ^Reader r (cond (bytes? src) (Reader. ^bytes src)
                        :else (Reader. ^ByteSource src))]
    (boring/configure-reader! r (.opts n))
    ;; The decoded INDEX is shared -- it is immutable once built and it is the
    ;; expensive part: 145 us for a 20 000-item index against 175 ns for a
    ;; Reader. A fresh probe cache rather than a shared one, because it is the
    ;; only other mutable field and contending an atom to save a key encode is
    ;; the wrong trade.
    ;; FORCED HERE, then shared as a realised value. Sharing the delay itself
    ;; would let the forked thread parse the frame through the PARENT's
    ;; Reader, which is the one thing `fork` exists to avoid. Forcing on the
    ;; forking thread keeps the sharing -- 145 us for a 20 000-item index --
    ;; without the aliasing.
    (let [ix (nav-idx n)]
      (Nav. r (.opts n) (atom {}) (when ix (delay ix)) src (atom {})))))

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

(def ^:private ^:const MAJOR-TEXT 3)
(def ^:private ^:const MAJOR-ARRAY 4)
(def ^:private ^:const MAJOR-MAP 5)
(def ^:private ^:const MAJOR-TAG 6)

(defn- major ^long [^Nav nav ^long off] (.majorAt ^Reader (.rdr nav) off))

(defmacro ^:private skip
  "`Reader.skipFrom` behind the decode error boundary.

  The SECOND choke point, and the one `realize` does not cover. `skipStructural`
  recurses per container level, so stepping over a deeply nested sibling
  overflows the stack without ever building a value -- `nav/children` on a
  1000-deep array chain raised a bare `java.lang.StackOverflowError` on a
  256 KiB stack while `nav/value` on the same bytes, wrapped, gave
  `:boring/max-depth-exceeded`.

  Wrapped HERE rather than around the loops that call it, so that a deliberate
  `IndexOutOfBoundsException` -- `nth`'s two-arity out-of-range contract -- is
  raised outside the boundary and stays what `Indexed` promises.

  A macro, not a function: this sits in the inner loop of every walk, and a
  `try` block costs nothing when nothing is thrown while a var call does. As a
  function it cost ~40 ns on the 300 ns `locate the blob` row -- measurable on
  exactly the operation doc/PERFORMANCE.md leads with."
  [r p]
  ;; The `long` is load-bearing: a `try` expression is typed Object, so without
  ;; it every `(recur (skip ...))` boxes and the loop locals stop being
  ;; primitive -- which the compiler reports and which is a real cost in the
  ;; walks this sits inside.
  `(let [^Reader r# ~r]
     (long (err/with-decode-errors (.skipFrom r# ~p)))))

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
  ;; THE choke point: `value`, `Items.nth`, `Items.reduce`, `children`, `get`
  ;; and `zipper` all reach bytes through here. Unwrapped, `nav/value` on a
  ;; 3000-deep tag chain raised a bare `java.lang.StackOverflowError` while
  ;; `decode` on the same bytes gave `:boring/max-depth-exceeded` -- the one
  ;; documented-as-impossible escape in doc/SECURITY.md, on the API whose whole
  ;; premise is reading documents somebody else wrote.
  (err/with-decode-errors (.readFrom ^Reader (.rdr nav) off)))

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
            (skip r p)
            (let [q (skip r (skip r p))]
              (if (or (<= q p) (> q end)) -1 (recur (inc i) q))))))
      (catch IndexOutOfBoundsException _ -1))))

(defn- lookup-map
  "Offset of the value for `k` in the map at `off`, or -1. Decodes no keys.

  Three paths, fastest first:

    indexed + sorted   binary search the node's anchors comparing ENCODED key
                       bytes, then walk at most `stride`-1 entries. O(log n).
    indexed            jump anchor to anchor, walking only within one stride.
                       Still never touches a VALUE -- but measured, it does
                       the same total work as the scan, because without key
                       order you must try each anchor's stride until the key
                       turns up. The index does not accelerate an unsorted map
                       lookup and cannot: 200 keys under the default profile
                       cost 40 000 skips indexed and 40 000 unindexed, at every
                       stride from 1 to 1000. It buys file layout, not speed,
                       here. Sorted keys (`:canonical`, `:archival`) are what
                       make the branch above reachable, and arrays index
                       positionally under any profile.
    unindexed          walk every entry, which is what this always did.

  All three return the same offset. The index only decides how much is walked."
  ^long [^Nav nav ^long off k]
  (let [^Reader r (.rdr nav)
        n (head-count nav off)
        ^bytes probe (probe-for nav k)
        idx (nav-idx nav)
        ns (node-slot nav off)]
    ;; A node with NO anchors means nothing to jump to -- walk instead. Empty
    ;; containers legitimately produce one (`:index-min 0` will index a `{}`),
    ;; and the sorted branch below assumes at least one anchor: with m=0 its
    ;; `(max 0 (min (dec m) hi))` still yields 0 and `aget` threw straight at
    ;; the caller of `get`. Cheaper to notice here than to special-case both
    ;; branches, and it also covers any future node that turns out empty.
    (if (or (neg? ns) (zero? (alength ^longs (slot-at idx ns))))
      (scan-map r (.headEndAt r off) n probe)
      (let [^longs slot (slot-at idx ns)
            stride (long (:stride idx))
            m (alength slot)]
        ;; Entries after anchor a, which is NOT always `stride`: the last
        ;; anchor covers the remainder. Walking a full stride from it ran off
        ;; the end of the container and into whatever followed -- found by the
        ;; missing-key case, where the search lands past the final anchor.
        (letfn [(span [^long a] (min stride (- n (* a stride))))
                ;; A MISS FROM THE INDEX IS NOT AN ANSWER, it is a hint that
                ;; did not pay off.
                ;;
                ;; Validation proves the FIRST anchor is a real entry and that
                ;; the anchors ascend and sit in range; it cannot prove a
                ;; middle one is an entry boundary without walking the
                ;; container, which is the work the index exists to avoid. A
                ;; middle anchor off by one byte therefore made the bounded
                ;; walk start mid-item and report a present key as absent:
                ;; measured, eight of forty present keys came back `nil` from a
                ;; single changed byte, while `decode` of the same bytes
                ;; returned the true forty-entry map.
                ;;
                ;; So a negative answer is re-derived by the honest walk. That
                ;; makes this namespace's promise -- "a missing or stale index
                ;; falls back to walking and returns the same answer" -- true
                ;; for a DAMAGED one as well, which it was not. The cost lands
                ;; only on genuine misses, where an honest answer requires the
                ;; walk anyway: trusting an index for a NEGATIVE is exactly
                ;; what damage makes unsound.
                (confirm [^long hit]
                  (if (neg? hit) (scan-map r (.headEndAt r off) n probe) hit))]
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
              (confirm
               (loop [lo 0 hi (dec m)]
                 (if (> lo hi)
                   (let [anchor (max 0 (min (dec m) hi))]
                     (scan-map r (aget slot anchor) (span anchor) probe))
                   (let [mid (quot (+ lo hi) 2)
                         q (long (aget slot mid))]
                     (if (or (neg? q) (>= q lim))
                       -1                      ; a damaged anchor: report a miss
                       (let [c (.compareItemToBytes r q probe)]
                         (cond (zero? c) (skip r q)
                               (neg? c) (recur (inc mid) hi)
                               :else (recur lo (dec mid))))))))))
            ;; Unsorted: still jump anchor to anchor rather than entry to entry.
            (confirm
             (loop [a 0]
               (if (>= a m)
                 -1
                 (let [hit (scan-map r (aget slot a) (span a) probe)]
                   (if (>= hit 0) hit (recur (inc a)))))))))))))

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
      (let [ix (nav-idx nav)
            ns (node-slot nav off)]
        (if (neg? ns)
          (loop [i 0 p (.headEndAt r off)]
            (if (= i idx) p (recur (inc i) (skip r p))))
          (let [^longs slot (slot-at ix ns)
                stride (long (:stride ix))
                anchor (quot idx stride)]
            (loop [i (* anchor stride) p (long (aget slot anchor))]
              (if (= i idx) p (recur (inc i) (skip r p))))))))))

(defn- child-offsets
  "Offsets of the children of the container at `off`, in wire order. For a map
  these alternate key, value."
  [^Nav nav ^long off]
  (let [^Reader r (.rdr nav)
        mj (major nav off)
        n (head-count nav off)
        ;; `(* 2 n)` overflowed to a negative long on a map head declaring 2^62
        ;; entries, so `seq` and `zipper` threw "long overflow" where `reduce`
        ;; on the same cursor reports :boring/truncated-input. One byte flipped
        ;; to 0xBB reaches it. Bounded by the bytes that actually follow: an
        ;; entry is at least one byte.
        room (- (.size r) (.headEndAt r off))
        _ (when (or (neg? n) (> n room))
            (fail :boring/bad-count
                  (str "boring.nav: a container declaring " n
                       " entries cannot fit in the " room " bytes that follow")
                  {:offset off :declared n}))
        n (if (= mj MAJOR-MAP) (* 2 n) n)]
    (loop [i 0 p (.headEndAt r off) acc (transient [])]
      (if (= i n)
        (persistent! acc)
        (recur (inc i) (skip r p) (conj! acc p))))))

;; `shape` is nil for every ordinary cursor. It is non-nil only on a cursor
;; standing on a ROW of a shaped array, where the bytes are an array of values
;; but the logical value is a MAP -- the keys live once in the shape header.
;;
;; Carried on the cursor rather than looked up from the row's offset because a
;; row does not know it is a row: nothing in its own bytes distinguishes it
;; from any other array. The parent hands it down.
(deftype Cursor [^Nav nav ^long off shape]
  ;; Associative, not merely ILookup: `contains?` and `find` are what a caller
  ;; reaches for after `get`, and both threw "not supported on type" -- an
  ;; untyped IllegalArgumentException on undamaged data. `assoc` is refused,
  ;; because a cursor is a read-only view of bytes; the zipper already refuses
  ;; mutation the same way.
  clojure.lang.Associative
  (containsKey [this k] (not (identical? ::none (.valAt this k ::none))))
  (entryAt [this k]
    (let [v (.valAt this k ::none)]
      (when-not (identical? v ::none) (clojure.lang.MapEntry/create k v))))
  (assoc [_ _ _]
    (fail :boring/read-only
          "boring.nav: a cursor is a read-only view over bytes; assoc is not supported"
          {:offset off}))

  ;; `Associative` EXTENDS `IPersistentCollection`, so declaring it obliges
  ;; `equiv`, `cons` and `empty` as well as the `count` and `seq` below. Only
  ;; those two were implemented, so `(= cursor x)` threw
  ;; `java.lang.AbstractMethodError` on UNDAMAGED data -- and an `Error`, so a
  ;; caller's `catch Exception` does not see it. Third instance of that family
  ;; in this file: `count` threw the same way before `Counted` was added, and
  ;; `reduce` before `IReduce`. Declaring an interface is a promise about every
  ;; method on it, including the ones inherited.
  (equiv [this o]
    ;; IDENTITY, not the value. A cursor is a view over bytes and realising one
    ;; to answer `=` would do arbitrary decode work behind an operation that
    ;; reads as free -- the same argument that keeps `Cursor` out of `IDeref`.
    ;; Compare `(nav/value c)` when you mean the value.
    (identical? this o))
  (cons [_ _]
    (fail :boring/read-only
          "boring.nav: a cursor is a read-only view over bytes; conj is not supported"
          {}))
  (empty [_]
    (fail :boring/read-only
          "boring.nav: a cursor is a read-only view over bytes; empty is not supported"
          {}))

  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [_ k nf]
    (if shape
      ;; A ROW of a shaped array. Its bytes are an array, but it IS a map, so
      ;; the key is resolved through the shape to a position and the row is
      ;; then indexed like any other array. A key the shape does not carry is
      ;; absent -- `writeShapedArray` only emits rows whose key sets match.
      (if-some [i (get (:pos shape) k)]
        (let [p (nth-item nav off (long i))]
          (if (neg? p) nf (cursor-at nav p)))
        nf)
      (let [mj (major nav off)]
        (cond
          (= mj MAJOR-MAP) (let [p (lookup-map nav off k)]
                             (if (neg? p) nf (cursor-at nav p)))
        ;; AN INTEGER KEY ON AN ARRAY IS AN INDEX, as it is for a Clojure
        ;; vector. This fell through to not-found, so `get` reported a PRESENT
        ;; element absent and `contains?` -- which is defined in terms of
        ;; `valAt` two forms up -- reported false for a valid index, while
        ;; `nth` returned the element. Undamaged data, no error.
        ;;
        ;; It survived because it depends on the ENCODING rather than the data:
        ;; under `:shapes` an array is a tag cursor, so the MAJOR-TAG branch
        ;; below realised it and clojure.core answered correctly. A consumer
        ;; writing `get-in` over a path with an index therefore worked or
        ;; silently returned nil according to how the document happened to be
        ;; written.
        ;; `(long k)`, NOT `(int k)`. The checked int cast threw
        ;; ArithmeticException -- untyped, out of `get` -- for any index past
        ;; 2^31, where `clojure.core/get` on the realised vector is total and
        ;; simply answers not-found. `nth-item` already range-checks against the
        ;; container's own count, so the long reaches it and comes back -1.
        (and (= mj MAJOR-ARRAY) (integer? k))
        (let [p (nth-item nav off (long k))]
          (if (neg? p) nf (cursor-at nav p)))
        ;; A tag's reader is arbitrary, so structure does not imply semantics.
        ;; Realise and let clojure.core decide -- correct for every registry.
        ;; `clojure.core/get` is total EXCEPT on a sorted collection, where an
        ;; incomparable key throws a raw ClassCastException; a lookup that was
        ;; handed a not-found value must not throw.
        ;;
        ;; THIS IS O(CONTAINER), AND IT IS THE ONE PLACE NAVIGATION IS NOT.
        ;; Realising defeats the index completely -- not because the index is
        ;; missing but because the cursor never reaches the container it
        ;; describes. A tag-27 record puts its payload 22 bytes in, so the root
        ;; cursor is the TAG, `node-slot` is asked about the tag's offset, and
        ;; no node matches it.
        ;;
        ;; Measured on 2000 keys, reaching the last one: a bare map is 0.26 us
        ;; against 13.47 us walking (52x, and FLAT in size), while the same
        ;; pairs as a `sorted-map` -- which round-trips as a tag-27 record --
        ;; cost 550 us with the index and 541 us without it. Position does not
        ;; matter either: the FIRST key costs the same as the last, which is the
        ;; signature of realisation rather than a scan that short-circuits.
        ;;
        ;; DESCENTS NOW EXIST FOR THREE OF THESE, each measured reaching the
        ;; last element and each FLAT in size where realising grew:
        ;;
        ;;   shaped arrays   5.60 us against 206.83   (5000 rows)   36.9x
        ;;   typed arrays    1.77 us against  70.90   (100k longs)    40x
        ;;   records         7.91 us against 577.18   (2000 keys)     73x
        ;;
        ;; `:shapes` used to cost projection roughly 4x for its ~2x size win,
        ;; so the fork users had to choose between -- small or navigable -- is
        ;; gone for that shape.
        ;;
        ;; SETS ARE NOT AMONG THEM, and a registered tag-27 name is not either:
        ;; `get` on a set means MEMBERSHIP, which no structural descent
        ;; reproduces, and a registered ctor is an arbitrary `IFn`. Both still
        ;; realise, which is what already worked.
        ;;
        ;; Descending into a tag in general means knowing its reader preserves
        ;; structure, which is a registry question rather than a format one, and
        ;; guessing wrong returns a wrong value rather than a slow one. A shaped
        ;; array is the case where boring knows, because boring wrote it.
          (= mj MAJOR-TAG)
          (let [v (tag-view nav off)
                ;; `clojure.core/get` is total EXCEPT on a sorted collection,
                ;; where an incomparable key throws; a lookup handed a
                ;; not-found value must not throw.
                fallback #(try (get (realize nav off) k nf)
                               (catch ClassCastException _ nf))]
            (case (:kind v)
              ;; WHICH KEYS A VECTOR-KINDED TAG ANSWERS IS THE VIEW'S BUSINESS,
              ;; because the two of them realise to different host types and
              ;; `clojure.core/get` treats those differently. A shaped array
              ;; realises to a Clojure vector, which uses `Util.isInteger` and
              ;; answers not-found for 0.0; a typed array realises to a JAVA
              ;; ARRAY, and `RT.getFrom` tests `instanceof Number` there, so
              ;; `(get (long-array [10]) 0.0)` is 10. Hard-coding `integer?`
              ;; here was stricter than the thing it mirrors.
              :vector (if ((:key-pred v) k)
                        (let [r ((:nth v) (long k))]
                          (if (identical? ::miss r) nf r))
                        nf)
              :map (let [r ((:lookup v) k)]
                     (cond (identical? ::miss r) nf
                           (identical? ::realise r) (fallback)
                           :else r))
              (fallback)))
          :else nf))))

  clojure.lang.Indexed
  ;; THROWS out of range, as `Indexed.nth(int)` is specified to and as every
  ;; other Indexed does. Returning nil turned an off-by-one in caller code into
  ;; a NullPointerException somewhere else entirely. The 3-arity is the one that
  ;; answers with a not-found value.
  (nth [this i]
    (let [v (.nth this i ::none)]
      (if (identical? v ::none)
        (throw (IndexOutOfBoundsException. (str "boring.nav: index " i " out of bounds")))
        v)))
  (nth [_ i nf]
    (if shape
      ;; A row realises to a MAP, and `clojure.core/nth` on a map answers with
      ;; the not-found value rather than an element. Matching that is what keeps
      ;; `(nth (get c 0) 0 x)` the same before and after this descent existed.
      nf
      (let [mj (major nav off)]
        (cond
          (= mj MAJOR-ARRAY) (let [p (nth-item nav off i)]
                               (if (neg? p) nf (cursor-at nav p)))
        ;; A tag's reader is arbitrary, so the realised value decides -- but
        ;; `clojure.core/nth` throws "nth not supported on this type" for a
        ;; realised keyword or set EVEN with a not-found argument, which leaked
        ;; an untyped error out of the arity whose whole point is not to throw.
          (= mj MAJOR-TAG)
          (let [v (tag-view nav off)]
            (case (:kind v)
              :vector (let [r ((:nth v) i)] (if (identical? ::miss r) nf r))
              ;; A map-kinded tag realises to a map or an UnknownRecord,
              ;; neither of which is Indexed, so this was already not-found --
              ;; but it realised the whole value to discover that.
              :map nf
              (let [rv (realize nav off)]
                (if (or (nil? rv) (instance? clojure.lang.Indexed rv)
                        (instance? java.util.List rv) (string? rv)
                        (and (some? rv) (.isArray (class rv))))
                  (nth rv i nf)
                  nf))))
          :else nf))))

  ;; Honest O(1): the count is in the head, and head-count refuses the
  ;; indefinite-length case where it would not be.
  clojure.lang.Counted
  (count [_]
    (if shape
      ;; A row counts its KEYS. The bytes say the same number -- one value per
      ;; key -- but going through the shape says why.
      (count (:ks shape))
      ;; A navigable tag counts what the value it realises to counts: rows for
      ;; a shaped array, elements for a typed one, fields for a record.
      ;; `count` used to refuse every tag while `nth` on a typed array worked
      ;; through the realising path -- countable by nobody, indexable by anyone.
      (if-let [v (and (= MAJOR-TAG (major nav off)) (tag-view nav off))]
        ;; CHECKED, exactly as the array and map branch below is. This branch
        ;; did not check, which reproduced verbatim the defect that comment
        ;; describes -- "an IMPOSSIBLE number, 1048576 entries from a five-byte
        ;; document" -- simply by wrapping the same map in a tag-27 frame. And
        ;; above 2^31 the `(int ...)` threw ArithmeticException, untyped, where
        ;; `decode` reports :boring/bad-count.
        ;;
        ;; `(quot room 2)` rather than `(* 2 n)`: the multiplication is what
        ;; overflows on the counts worth rejecting.
        (let [n (long (:n v))
              room (- (.size ^Reader (.rdr nav)) off)
              too-many? (if (= :map (:kind v)) (> n (quot room 2)) (> n room))]
          (when (or (neg? n) too-many?)
            (fail :boring/bad-count
                  (str "boring.nav: a tagged container declaring " n
                       " entries cannot fit in the " room " bytes that follow")
                  {:offset off :declared n}))
          (int n))
        (let [mj (major nav off)]
        (if (or (= mj MAJOR-ARRAY) (= mj MAJOR-MAP))
        ;; CHECKED against the data, which this was the one entry point not to
        ;; do. `(int n)` on a head declaring 2^31 entries threw an untyped
        ;; ArithmeticException, and below that it returned an IMPOSSIBLE number
        ;; -- 1048576 entries from a five-byte document -- while `decode`,
        ;; `seq`, `byte-span`, `value` and `(reduce f coll)` on the same bytes
        ;; all report :boring/bad-count. Reachable by flipping byte 0 to 0x9B
        ;; or 0xBB.
        ;;
        ;; NOT all of them, and this comment used to say so. Measured on a 0xBB
        ;; head declaring 2^20 pairs over nine bytes:
        ;; `(reduce f init coll)` gives :boring/truncated-input,
        ;; because IReduceInit walks `child-offsets` without the `room` guard
        ;; this arity and `child-offsets` share; `nth` and `get` return their
        ;; not-found value and report nothing, which is the contract of the
        ;; arity the caller chose. Typed either way -- the guarantee is that no
        ;; untyped throwable escapes, not that one keyword covers every entry
        ;; point.
        ;;
        ;; One entry is at least one byte, so a count larger than the bytes
        ;; that follow cannot be honest.
        (let [n (head-count nav off)
              room (- (.size ^Reader (.rdr nav)) (.headEndAt ^Reader (.rdr nav) off))
              need (if (= mj MAJOR-MAP) (* 2 n) n)]
          (when (or (neg? n) (> need room))
            (fail :boring/bad-count
                  (str "boring.nav: a container declaring " n
                       " entries cannot fit in the " room " bytes that follow")
                  {:offset off :declared n}))
          (int n))
        (fail :boring/not-a-container
              "boring.nav: count is only defined for arrays and maps"
              {:offset off :major mj}))))))

  clojure.lang.Seqable
  (seq [_]
    (if shape
      ;; Entries, because a row IS a map: key from the shape, value from the
      ;; matching position. `child-offsets` gives the row's values in order,
      ;; which is exactly shape order.
      (seq (mapv (fn [k p] (clojure.lang.MapEntry. k (cursor-at nav p)))
                 (:ks shape)
                 (child-offsets nav off)))
      (let [mj (major nav off)]
        (cond
          (= mj MAJOR-ARRAY) (seq (mapv #(cursor-at nav %) (child-offsets nav off)))
          (= mj MAJOR-MAP)
          (seq (mapv (fn [[kp vp]]
                       (clojure.lang.MapEntry. (realize nav kp) (cursor-at nav vp)))
                     (partition 2 (child-offsets nav off))))
          ;; A navigable tag seqs as the collection it realises to: rows for a
          ;; shaped array, elements for a typed one, entries for a record. Wire
          ;; order is already the realised order -- a sorted-map is written in
          ;; its own iteration order, which is sorted.
          (= mj MAJOR-TAG)
          (let [v (tag-view nav off)]
            (case (:kind v)
              :vector (seq (vec ((:items v))))
              :map (seq (vec ((:entries v))))
              nil))
          :else nil))))

  ;; IReduce as well as IReduceInit: `(reduce f coll)` -- the arity everyone
  ;; actually writes -- threw a raw ClassCastException ("cannot be cast to
  ;; clojure.lang.IReduce") on perfectly good data, because every test happened
  ;; to pass an init. Exactly the sibling of the `Counted`/AbstractMethodError
  ;; gap found a round earlier.
  clojure.lang.IReduce
  (reduce [this f]
    (let [s (seq this)]
      (if s (clojure.core/reduce f (first s) (rest s)) (f))))

  ;; The reducers path: no intermediate seq, no child cursors retained beyond
  ;; the step that uses them.
  clojure.lang.IReduceInit
  (reduce [this f init]
    ;; A non-shaped tag must still fall through to the `:else` below, which
    ;; refuses. Testing MAJOR-TAG alone would have turned that typed refusal
    ;; into a silent `init`.
    (if (or shape (and (= MAJOR-TAG (major nav off)) (tag-view nav off)))
      ;; Both shaped forms go through `seq`, which already knows how to present
      ;; them. Duplicating the walk here would be a second place for the
      ;; key-to-position mapping to drift from the first.
      (let [s (seq this)]
        (if s (clojure.core/reduce f init s) init))
      (let [^Reader r (.rdr nav)
            mj (major nav off)
            n (head-count nav off)]
        (cond
          (= mj MAJOR-ARRAY)
        (loop [i 0 p (.headEndAt r off) acc init]
          (if (or (= i n) (reduced? acc))
            (unreduced acc)
            (recur (inc i) (skip r p) (f acc (cursor-at nav p)))))
        (= mj MAJOR-MAP)
        (loop [i 0 p (.headEndAt r off) acc init]
          (if (or (= i n) (reduced? acc))
            (unreduced acc)
            (let [vp (skip r p)]
              (recur (inc i) (skip r vp)
                     (f acc (clojure.lang.MapEntry. (realize nav p)
                                                    (cursor-at nav vp)))))))
          :else (fail :boring/not-a-container
                      "boring.nav: reduce is only defined for arrays and maps"
                      {:offset off :major mj})))))

  Object
  (toString [_] (str "#boring.nav/cursor[" off (when shape " shaped") "]")))

(defn- cursor-at [^Nav nav ^long off] (->Cursor nav off nil))

;; ------------------------------------------------------- navigating a tag
;;
;; A CBOR tag is structurally uniform -- one tag number wrapping one data item
;; -- and semantically anything at all, because the reader maps (tag, payload)
;; to a host value through an arbitrary function. Descending is sound only when
;; that function COMMUTES with the operation:
;;
;;     get(R(payload), k) == R_child(get_payload(payload, translate(k)))
;;
;; for the reader `R`. Which is why there is no single rule, and why the tags
;; below fall into distinct kinds:
;;
;;   WRAPPER            R preserves structure. Navigate the payload; `value`
;;                      still realises at the TAG, so nothing is reconstructed.
;;                      Records are this.
;;   RESTRUCTURING      R rearranges. Needs a key-to-position translation AND
;;                      explicit reconstruction, because realising at the tag
;;                      no longer describes a child. Shaped rows are this, and
;;                      it is why they carry state on the cursor.
;;   SCALAR-DECODING    Elements are packed rather than written as items, so
;;                      access is arithmetic and yields VALUES, never cursors.
;;                      Typed arrays are this.
;;   OPERATION-CHANGING The operation means something else -- `get` on a set is
;;                      MEMBERSHIP. No generic form; these stay opaque.
;;   EXTERNALLY-RESOLVED The value is not a function of the bytes at all, as
;;                      with a reader that resolves storage roots. Never
;;                      navigable, and the reason "tag plus value" is not
;;                      enough on its own.
;;
;; A VIEW is what a navigable tag produces: built once per (nav, offset),
;; cached, and holding closures that have already captured both. Everything
;; unknown yields nil and realises, so this is a pure optimisation with no
;; correctness cliff -- a tag boring has never heard of behaves exactly as it
;; did before any of this existed.
;;
;;   {:kind :map    :n <count> :lookup (fn [k]) :entries (fn [])}
;;   {:kind :vector :n <count> :nth (fn [i])    :items   (fn [])}
;;
;; `:lookup` and `:nth` return a child cursor, a realised value, `::miss` for
;; absent, or `::realise` to fall back for this key alone.

(def ^:private ^:const TAG-RECORD 27)

;; RFC 8746 typed arrays boring WRITES: signed little-endian integers plus f32
;; and f64 LE. `Reader.readTypedArray` can read all 21 with a lossless JVM
;; representation, but descent is implemented only for these five.
;;
;; NOT for lack of ambition. Each of the other tags has its own widening rule --
;; uint8 becomes short[], not byte[], so that 200 does not read back as -56 --
;; and mirroring a rule wrongly here returns a WRONG NUMBER rather than a slow
;; one. The five below are the ones boring's own data uses and the ones whose
;; boxing is pinned by test against `realize`; everything else falls back to
;; realising, which is what already worked.
(defn- le-long ^long [^bytes b ^long n]
  (loop [i 0 acc 0] (if (= i n) acc
                        (recur (inc i)
                               (bit-or acc (bit-shift-left
                                            (bit-and (long (aget b i)) 0xFF)
                                            (* 8 i)))))))

;; UNCHECKED casts, and that is the whole point. `le-long` accumulates bytes as
;; UNSIGNED, so a two-byte element holding -1 arrives as 65535 and a four-byte
;; one as 4294967295. Clojure's `short` and `int` are CHECKED: they threw
;; `IllegalArgumentException` and `ArithmeticException` respectively, untyped,
;; straight out of `get`/`nth`/`seq`/`reduce` -- on undamaged data that `decode`
;; round-trips perfectly. Every negative element of a short[] or int[], and
;; EVERY negative float, since tag 85 goes through the same int.
;;
;; long[] and double[] were never affected: at eight bytes `le-long` wraps into
;; the sign naturally, which is exactly why the defect hid -- two of the five
;; tags are correct by construction.
;;
;; It hid for a second reason too: every fixture in `typed_nav_test` was built
;; from `(range n)`, so the tests pinned the boxing over the sign-free half of
;; the domain and the docstring above claimed the pinning was total.
(def ^:private typed-array-tags
  {77 {:size 2 :read (fn [^bytes b] (Short/valueOf (unchecked-short (le-long b 2))))}
   78 {:size 4 :read (fn [^bytes b] (Integer/valueOf (unchecked-int (le-long b 4))))}
   79 {:size 8 :read (fn [^bytes b] (Long/valueOf (le-long b 8)))}
   85 {:size 4 :read (fn [^bytes b] (Float/valueOf (Float/intBitsToFloat
                                                   (unchecked-int (le-long b 4)))))}
   86 {:size 8 :read (fn [^bytes b] (Double/valueOf (Double/longBitsToDouble
                                                    (le-long b 8))))}})

(defn- typed-view
  "SCALAR-DECODING. Elements are packed into a byte string rather than written
  as CBOR items, so element i is at `data + i*size` -- arithmetic, with no walk
  and no index. Reaching one element used to build the whole primitive array:
  70.90 us on a 100 000-element long[] against 1.766 us, flat in length."
  [^Nav nav ^long off ^long tag]
  (when-let [spec (typed-array-tags tag)]
    (let [^Reader r (.rdr nav)
          bs (.headEndAt r off)]
      ;; must wrap a DEFINITE-LENGTH byte string, as the reader requires; an
      ;; indefinite one has no count on the wire
      (when (and (= 2 (.majorAt r bs)) (not= 31 (.infoAt r bs)))
        (let [blen (.headArgAt r bs)
              size (long (:size spec))
              rd (:read spec)
              data (.headEndAt r bs)]
          (when (and (<= 0 blen) (zero? (rem blen size)))
            (let [n (quot blen size)
                  at (fn [^long i]
                       (if (or (neg? i) (>= i n))
                         ::miss
                         (let [p (+ data (* i size))]
                           (rd (.bytesBetween r p (+ p size))))))]
              ;; `number?`, not `integer?` -- see the :vector branch of valAt.
              {:kind :vector :n n :nth at :key-pred number?
               :items (fn [] (map at (range n)))})))))))

(defn- shaped-view
  "RESTRUCTURING. A shaped array is `39649([keys, [row, ...]])`: the keys are
  hoisted out and each row is an ARRAY of values matching them positionally. It
  realises to a VECTOR OF MAPS, so a row cursor has array bytes and map
  semantics and must carry the shape -- nothing in a row's own bytes says it is
  a row.

  Returns nil for anything that is not exactly what `writeShapedArray` emits."
  [^Nav nav ^long off ^long tag]
  (when (= tag TAG-SHAPED-ARRAY)
    (let [^Reader r (.rdr nav)
          pair (.headEndAt r off)]
      (when (and (= MAJOR-ARRAY (.majorAt r pair)) (= 2 (.headArgAt r pair)))
        (let [ks-off (.headEndAt r pair)]
          (when (= MAJOR-ARRAY (.majorAt r ks-off))
            (let [rows-off (skip r ks-off)]
              (when (= MAJOR-ARRAY (.majorAt r rows-off))
                (let [k (.headArgAt r ks-off)
                      n (.headArgAt r rows-off)]
                  (when (and (<= 0 k) (<= 0 n))
                    (let [ks (loop [i 0 p (.headEndAt r ks-off) acc (transient [])]
                               (if (= i k)
                                 (persistent! acc)
                                 (recur (inc i) (skip r p)
                                        (conj! acc (realize nav p)))))
                          sh {:ks ks
                              :pos (persistent!
                                    (reduce-kv (fn [m i kk] (assoc! m kk i))
                                               (transient {}) ks))}
                          at (fn [^long i]
                               (let [p (nth-item nav rows-off i)]
                                 (if (neg? p) ::miss (->Cursor nav p sh))))]
                      {:kind :vector :n n :nth at :key-pred integer?
                       :items (fn [] (map #(->Cursor nav % sh)
                                          (child-offsets nav rows-off)))})))))))))))

(defn- record-view
  "WRAPPER. A record writes `27([name, {fields}])`, so the field map begins past
  the tag and the name -- 22 bytes for `clojure/sorted-map`. The cursor stays on
  the TAG and only the container operations redirect, which is why `value` still
  realises the record rather than the bare map and `value-type` still says
  `:tag`. No reconstruction, and no cursor state.

  REFUSES A REGISTERED NAME. `TagRegistry.recordCtor` is an arbitrary `IFn`: it
  may rename fields, drop them, or -- as datahike's `reconstruct-db` does --
  resolve storage roots that are not in the bytes at all. Unregistered names
  decode to `UnknownRecord`, whose `valAt`, `count` and `seq` delegate straight
  to the field map, so descent is exactly equivalent there.

  The decision reads the SAME registry the reader will use, so the two cannot
  drift: a blob read without datahike's registry descends, and realises to an
  `UnknownRecord` over that same field map, and both agree."
  [^Nav nav ^long off ^long tag]
  (when (= tag TAG-RECORD)
    (let [^Reader r (.rdr nav)
          pair (.headEndAt r off)]
      (when (and (= MAJOR-ARRAY (.majorAt r pair)) (= 2 (.headArgAt r pair)))
        (let [name-off (.headEndAt r pair)]
          (when (= MAJOR-TEXT (.majorAt r name-off))
            (let [payload (skip r name-off)]
              ;; a POSITIONAL record -- datahike's Datom carries a vector --
              ;; is not a map and gets no descent here
              (when (= MAJOR-MAP (.majorAt r payload))
                (let [nm (realize nav name-off)]
                  (when (and (string? nm)
                             (or (nil? (.recordCtor (.registry r) ^String nm))
                                 ;; ...or its author declared it
                                 ;; structure-preserving. See
                                 ;; `boring/declare-navigable-record`, and
                                 ;; `boring.nav-conformance/check-record` for
                                 ;; testing the claim rather than trusting it.
                                 (.isNavigableRecord (.registry r) ^String nm)))
                    (let [sorted? (= nm "clojure/sorted-map")]
                      {:kind :map
                       :n (head-count nav payload)
                       :lookup
                       (fn [k]
                         ;; A sorted-map looks up by `compare`, not `=`, and the
                         ;; two disagree across numeric types:
                         ;; `(get (sorted-map 1 :a) 1.0)` is `:a` because
                         ;; `(compare 1 1.0)` is 0, while a byte probe for 1.0
                         ;; matches nothing -- and the same trap is reachable
                         ;; inside a vector key, since `(compare [1] [1.0])` is
                         ;; 0 too. So sorted lookups descend only where the two
                         ;; agree, and everything else realises.
                         ;;
                         ;; A custom comparator cannot arrive here at all:
                         ;; `Writer.requireDefaultComparator` refuses to encode
                         ;; one.
                         (if (and sorted?
                                  (not (or (keyword? k) (symbol? k) (string? k))))
                           ::realise
                           (let [p (lookup-map nav payload k)]
                             (if (neg? p) ::miss (cursor-at nav p)))))
                       :entries
                       (fn []
                         (map (fn [[kp vp]]
                                (clojure.lang.MapEntry. (realize nav kp)
                                                        (cursor-at nav vp)))
                              (partition 2 (child-offsets nav payload))))})))))))))))

;; Dispatch by TAG NUMBER, not by trying each in turn. Three sequential probes
;; ran on every tag cursor before this; one lookup replaces them, so unifying
;; is cheaper than the special cases were.
;;
;; This is the extension point. A handler that knows its reader preserves
;; structure -- datahike's TxReport is registered with `identity`, and so
;; qualifies -- belongs here rather than in a growing `cond`.
(def ^:private tag-view-builders
  (merge {TAG-SHAPED-ARRAY shaped-view
          TAG-RECORD       record-view}
         (zipmap (keys typed-array-tags) (repeat typed-view))))

(defn- tag-view
  "The navigation view for the tag at `off`, or nil meaning opaque -- realise,
  exactly as before any descent existed.

  Cached per Nav by offset, because building one realises the shape's keys or
  the record's name and a caller walking rows would otherwise pay per row.

  ANY exception yields nil. Damaged bytes mean \"not a form I recognise\", never
  a throw out of `get`; the realising fallback still answers."
  [^Nav nav ^long off]
  (let [cache (.shapes nav)]
    (if-some [hit (get @cache off)]
      (when-not (identical? ::none hit) hit)
      (let [v (try
                (when (= MAJOR-TAG (major nav off))
                  (let [tag (.headArgAt ^Reader (.rdr nav) off)]
                    (when-let [build (tag-view-builders tag)]
                      (build nav off tag))))
                (catch Exception _ nil))]
        (swap! cache assoc off (if (nil? v) ::none v))
        v))))

;; --------------------------------------------------------------- public API

(defn cursor?
  "True if `x` is a cursor -- something `get`, `nth`, `seq` or `reduce` handed
  back rather than a value they realised.

  Worth having because only `value` is TOTAL. It takes anything and returns
  anything already realised unchanged, which is what makes
  `(value (get c k))` safe when you cannot see from the outside whether `k`
  descended a map (cursor) or a tag (realised value). The rest of this
  namespace is not: `value-type`, `byte-span` and `raw-bytes` are `^Cursor`
  hinted, so a realised value reaches them as a bare ClassCastException, and
  `children` is `(fn [c] c)` and simply hands a non-cursor straight back --
  `(children 5)` is `5`. Ask here when the answer decides which one you call."
  [x] (instance? Cursor x))

(defn ^:no-doc skips
  "Structural skips this cursor's reader has performed, and a setter to zero it.

  Test support, and the reason it exists is worth stating: the index is a PURE
  OPTIMISATION -- same answers, fewer steps -- so no assertion about a returned
  VALUE can tell a live index from a dead one, and timing it is flaky.
  Per-path mutation showed what that costs: the indexed branches of both
  `lookup-map` and `nth-item` could be deleted outright with the whole suite
  still green, and both later turned out to carry defects nothing had caught.

  Counting the walking is the observable that distinguishes them. Reaching
  `k150` in a 200-key map is 17 skips through the index and 301 without."
  (^long [c] (.-skips ^Reader (.rdr ^Nav (.nav ^Cursor c))))
  ([c ^long n] (set! (.-skips ^Reader (.rdr ^Nav (.nav ^Cursor c))) n) c))

(defn value
  "Realise the subtree at the cursor into a Clojure value, through the ordinary
  decoder -- same registry, same records, same everything.

  TOTAL on non-cursors, which is not tidiness but a trap removed. `get` returns
  a CURSOR when it descends a map or array, and the REALISED VALUE when it
  descends a tag -- because a tag's reader is arbitrary, so structure does not
  imply semantics. That is documented, but it means `(value (get c k))` worked
  or threw a ClassCastException depending on the WIRE REPRESENTATION of the
  thing you asked for: a sorted-map is a tag, a plain map is not, and the caller
  cannot see the difference from the outside. Anything already realised is
  returned unchanged."
  [c]
  (if (instance? Cursor c)
    (let [^Cursor c c
          sh (.shape c)]
      (if sh
        ;; A ROW OF A SHAPED ARRAY IS A MAP, and its bytes are an array.
        ;; Realising the offset alone would hand back the values without their
        ;; keys -- a wrong value, not a slow one -- so the shape is reapplied
        ;; here. This is the whole reason the shape rides on the cursor.
        (let [nav (.nav c)]
          (zipmap (:ks sh)
                  (mapv #(realize nav (long %)) (child-offsets nav (.off c)))))
        (realize (.nav c) (.off c))))
    c))

(defn value-type
  "What is at the cursor, without decoding it: :map :array :text :bytes :tag
  :int :float-or-simple."
  [^Cursor c]
  ;; A shaped row's BYTES are an array and its VALUE is a map. Reporting the
  ;; bytes would contradict `value`, `seq` and `count`, all of which say map.
  (if (.shape c)
    :map
    (case (int (major (.nav c) (.off c)))
      0 :int, 1 :int, 2 :bytes, 3 :text, 4 :array, 5 :map, 6 :tag, :float-or-simple)))

(defn byte-span
  "`[start end]` of the value at the cursor. `end` is exclusive. This is what
  lets a caller hand a subtree somewhere else without decoding it."
  [^Cursor c]
  (let [^Nav nav (.nav c) off (.off c)]
    [off (skip ^Reader (.rdr nav) off)]))

(defn raw-bytes
  "The encoded bytes of the subtree at the cursor, copied out. A re-encodable
  slice: `(boring/decode (raw-bytes c) opts)` equals `(value c)`, for the SAME
  `opts` the cursor's `source` was given.

  The options are not optional, and the docstring used to omit them. `value`
  realises through the nav's own reader, which carries the registry `source`
  was handed; `boring/decode` with no options carries none. With a record
  registered under `\"user.Pt\"`, `value` gives back a `Pt` and a bare `decode`
  of the same bytes gives back a `#boring/record` fallback -- equal bytes,
  unequal values, and no error to notice it by. Registry-free documents were
  the only ones the tests covered, and there the claim happens to hold."
  ^bytes [^Cursor c]
  (let [^Nav nav (.nav c)
        [s e] (byte-span c)]
    (.bytesBetween ^Reader (.rdr nav) s e)))

(defn children
  "A reducible/seqable of child cursors (arrays) or MapEntries of realised key
  to value cursor (maps). Prefer `reduce` over `seq` in a hot loop.

  Identity on anything that is not a cursor -- a tag that `get` already
  realised arrives here as its value and leaves unchanged, so a walk gets the
  scalar back instead of an error. `cursor?` is how you tell the two apart."
  [^Cursor c] c)

(def ^:private shorts-class (class (short-array 0)))
(def ^:private ints-class (class (int-array 0)))
(def ^:private longs-class (class (long-array 0)))

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
  ^longs [s ^long base]
  (cond
    (bytes? s)
    (let [^bytes a s n (alength a) out (long-array n)]
      (loop [k 0 acc base]
        (if (= k n)
          out
          (let [v (+ acc (bit-and (aget a k) 0xFF))]
            (aset out k v)
            (recur (inc k) v)))))

    (instance? shorts-class s)
    (let [^shorts a s n (alength a) out (long-array n)]
      (loop [k 0 acc base]
        (if (= k n)
          out
          (let [v (+ acc (aget a k))]
            (aset out k v)
            (recur (inc k) v)))))

    (instance? ints-class s)
    (let [^ints a s n (alength a) out (long-array n)]
      (loop [k 0 acc base]
        (if (= k n)
          out
          (let [v (+ acc (aget a k))]
            (aset out k v)
            (recur (inc k) v)))))

    ;; sint64 (tag 79), the fourth width. Only written when two anchors are more
    ;; than 2 GiB apart, but a reader must accept what a writer may emit.
    :else
    (let [^longs a s n (alength a) out (long-array n)]
      (loop [k 0 acc base]
        (if (= k n)
          out
          (let [v (+ acc (aget a k))]
            (aset out k v)
            (recur (inc k) v)))))))

(defn- node-valid?
  "Does index node `i` describe the data it claims to?

  Split out of `index-payload` so it can be run PER NODE, on demand, instead of
  over every node at load. That is the whole cost of an index on a short-lived
  source: the checks below read the file -- `skip` walks up to a stride of
  items to pin the far end -- so validating a 12 000-container frame cost
  ~1 ms before a single lookup ran, and a caller that touches one key paid all
  of it. Deferring makes the price proportional to what is actually navigated.

  The checks themselves are unchanged, and each was reachable: a slot shorter
  than its count made `nth` walk off the end, a zero-length slot made the binary
  search index -1, and an anchor outside the data section reached
  `Reader.skipFrom`, which does an unchecked array access and throws a raw
  AIOOBE at the caller."
  [^Reader r ptr c cnt st ^longs a]
  (let [ptr (long ptr) c (long c) cnt (long cnt) st (long st)]
(let [want (if (zero? cnt) 0 (inc (quot (dec cnt) st)))]
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
                         false))))
                 ;; AND THE FAR END, which nothing pinned.
                 ;;
                 ;; Every check above is about the START of the node
                 ;; and about the anchors being tidy among
                 ;; themselves. None of them says the node describes
                 ;; THIS data. Two measured consequences:
                 ;;
                 ;;   Splice one sealed file's data section under
                 ;;   another's footer of the same byte length but
                 ;;   different item boundaries -- an interrupted
                 ;;   append and a retry, a restore taking the body
                 ;;   from one snapshot and the tail from another.
                 ;;   Every gate passed. `nth items 16` returned the
                 ;;   string "gaaaa", `nth 32` returned the record
                 ;;   written at 32-9, and `decode-seq` over the same
                 ;;   bytes was correct throughout, so two code paths
                 ;;   in one application disagreed about the file.
                 ;;
                 ;;   The item total was never compared with the
                 ;;   data at all -- the slot-length rule
                 ;;   `want = 1 + (cnt-1)/stride` cannot see a change
                 ;;   anywhere inside a whole stride. One flipped bit
                 ;;   made `count` report 501 for a 500-item file;
                 ;;   deflating it left ten written records present
                 ;;   in the file, returned by `decode-seq`, and
                 ;;   unreachable through `count`/`nth`.
                 ;;
                 ;; The check is exact and costs at most one stride
                 ;; of skips per node, never a walk of the container:
                 ;; the slot-length rule above already forces
                 ;; `remaining` into [1, stride]. From the last
                 ;; anchor, stepping over the items it covers must
                 ;; land EXACTLY on the node's end -- the data
                 ;; section's end for the sequence, the container's
                 ;; own end otherwise.
                 (let [m (alength a)]
                   (or (if (neg? c)
                         ;; A SEQUENCE NODE CLAIMING ZERO ITEMS has
                         ;; no anchors, so the far-end check below
                         ;; short-circuits and never looks at the
                         ;; data -- and `count` then returned 0 and
                         ;; `nth` nil over a file holding all 60 of
                         ;; its records. Zero items is only honest
                         ;; if the data section is empty, which is
                         ;; one comparison.
                         (and (zero? m) (zero? ptr))
                         (zero? m))
                       (let [covered (* st (dec m))
                             remaining (- cnt covered)
                             per (if (and (not (neg? c)) (= MAJOR-MAP (.majorAt r c)))
                                   2 1)
                             n (* per remaining)
                             want-end (if (neg? c) ptr (skip r c))]
                         (and (pos? remaining)
                              (= want-end
                                 (loop [k 0 p (long (aget a (dec m)))]
                                   (if (= k n) p (recur (inc k) (skip r p)))))))))))))

(defn- slot-at
  "Absolute anchors for node `i`, expanded on first use and memoized.

  `boring.core/delta-slot` writes anchors as deltas, so this is the prefix sum
  back to the offsets every lookup path expects. Doing it here rather than at
  load is what keeps an index off the critical path of a source that is read
  once."
  ^longs [ix ^long i]
  (let [^AtomicReferenceArray cache (:slot-cache ix)
        hit (.get cache (int i))]
    (or hit
        (let [^longs cs (:containers ix)
              a (expand-slot (nth (:raw-slots ix) i)
                             (max 0 (long (aget cs (int i)))))]
          ;; CAS so every thread observes the same array; a loser discards its
          ;; own copy rather than publishing a second one.
          (.compareAndSet cache (int i) nil a)
          (.get cache (int i))))))

(defn- node-sound?
  "Is index node `i` usable? Checked once, then remembered.

  An unsound node is not an error: `node-slot` reports -1 and the caller walks
  the container, which is the path taken whenever a container is simply not
  indexed. So a malformed node costs speed, never correctness -- the same
  contract `read-index` keeps for a corrupt back-pointer.

  `:trust-index :trusted` skips the check; `:ignore` skips the index altogether.
  Two ends of one dial, and `:trusted` was previously accepted by the option
  validator while having no effect anywhere -- an option taken and ignored."
  [^Nav nav ix ^long i]
  (if (= :trusted (:trust-index (.opts nav)))
    ;; TRUSTED: skip the check entirely.
    ;;
    ;; This is the single lever that decides whether an index pays. Measured on
    ;; a 1000-entry map: decoding the frame costs 3-7 us and expanding a node's
    ;; deltas 0.13 us, but validating one node costs 47 us -- because the
    ;; far-end check SKIPS THE WHOLE CONTAINER to pin where it ends. That is
    ;; exactly the walk the index exists to avoid, so a source read once pays a
    ;; full container walk for the privilege of not walking the container.
    ;;
    ;; The check is not pointless -- it catches a data section spliced under a
    ;; foreign footer, which every cheaper gate passes (see `node-valid?`). But
    ;; that threat is about files of unknown provenance. A store that owns its
    ;; own bytes, and sits on a page-checksummed engine, is paying for a
    ;; guarantee it already has.
    true
    (let [^booleans done (:node-checked ix)
          ^booleans okv (:node-ok ix)]
      (if (aget done (int i))
        (aget okv (int i))
        (let [^Reader r (.rdr nav)
              ^longs cs (:containers ix)
              ^ints cnts (:counts ix)
              v (boolean
                 (try
                   (node-valid? r (long (:data-end ix))
                                (long (aget cs (int i)))
                                (long (aget cnts (int i)))
                                (long (:stride ix))
                                (slot-at ix i))
                   ;; Any failure reading a malformed node means "unusable",
                   ;; not "throw at the caller of `get`".
                   (catch Exception _ false)))]
          (aset okv (int i) v)
          (aset done (int i) true)
          v)))))

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
  cursor that never went near the container it belongs to.

  `trusted?` is `:trust-index :trusted`, and it skips the two checks below that
  are O(NODE COUNT). It had been honoured only per node, by `node-sound?`; the
  FRAME-level structure was re-verified on every `nav/source` regardless, which
  is the same defect one level up -- an option accepted and then ignored.

  That cost is not marginal, because it is paid per SOURCE and a document store
  opens one source per blob and does one lookup. Measured on 4096 scalars: the
  indexed lookup itself is 0.22 us against 24.97 us scanning, but opening the
  source and doing that one lookup came to 8.67 us -- so nearly all of what the
  index saved was spent proving the index was well-formed."
  [^Reader r ^long ptr trusted?]
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
          ;; AND ITS OWN ITEM BUDGET, for exactly the same reason the depth
          ;; budget above is isolated -- the argument was written out once and
          ;; then applied to only one of the two limits.
          ;;
          ;; The frame's item cost scales with the NODE COUNT, so a 500-record
          ;; log written with default `write-seq!` options overran a budget that
          ;; was generous for its data: `.readFrom` threw, the catch-all below
          ;; returned nil, `:data-end` was lost, and `items` then walked past the
          ;; data section into the frame and reported 501 items for 500 records.
          ;; A silently wrong count out of a default-written file.
          ;;
          ;; The counter is restored too, not just the limit: the frame must not
          ;; spend the caller's budget on its way past.
          savedMax (.-maxItems r)
          savedN (.-items r)
          _ (set! (.-maxItems r) 0)
          [stride raw-containers ^ints counts slots sorted _]
          (try (boring.data/frame-payload (.readFrom r ptr))
               (finally (set! (.-maxDepth r) (int saved))
                        (set! (.-maxItems r) savedMax)
                        (set! (.-items r) savedN)))
          ;; CONTAINERS ARRIVE AT EITHER WIDTH. `seal-index!` emits int32 when
          ;; every offset fits and sint64 when one does not, and the CBOR tag is
          ;; the declaration -- the same narrowest-that-fits rule the slot
          ;; deltas have always used. Normalising to long here means one shape
          ;; downstream and no width test in the binary search.
          ^longs containers (cond
                              (instance? (Class/forName "[J") raw-containers)
                              raw-containers
                              (instance? (Class/forName "[I") raw-containers)
                              (let [^ints a raw-containers
                                    out (long-array (alength a))]
                                (dotimes [i (alength a)] (aset out i (long (aget a i))))
                                out)
                              :else nil)]
      (when (and (int? stride) (pos? (long stride)) containers
                 (instance? (Class/forName "[I") counts)
                 (sequential? slots) (sequential? sorted)
                 ;; STRUCTURE, not just decodability. Detection proves something
                 ;; MEANT to be an index; none of it proves the payload hangs
                 ;; together. A frame that decodes but whose parts disagree used
                 ;; to be trusted, and produced raw IndexOutOfBoundsException at
                 ;; the caller of `get`, or -- worse -- a wrong subtree.
                 ;; STAYS UNCONDITIONAL even when trusted. Disagreeing lengths
                 ;; are the one frame fault that costs O(1) to detect and would
                 ;; otherwise surface as a raw IndexOutOfBoundsException from
                 ;; inside `get` rather than as "no usable index, scan instead".
                 (= (alength containers) (alength counts)
                    (count slots) (count sorted))
                 ;; AND SO DOES THIS, for exactly the same reason -- which the
                 ;; comment above stated and then did not carry through to its
                 ;; sibling. `expand-slot` dispatches on the four widths
                 ;; `delta-slot` writes and hints the fall-through `^longs`, so a
                 ;; slot that decoded as anything else -- a TaggedValue, from one
                 ;; flipped byte -- became a raw ClassCastException. Under the
                 ;; default path `node-sound?` forces `slot-at` inside a `try`
                 ;; and swallows it; `:trusted` skips that, and `lookup-map` and
                 ;; `nth-item` then call `slot-at` unguarded. Six of 222
                 ;; single-byte footer mutants escaped untyped this way.
                 ;;
                 ;; O(node count) rather than O(1), so it is not free -- but it
                 ;; is one `instance?` per node against a walk of the whole
                 ;; container, and the alternative is an untyped throwable the
                 ;; contract says cannot happen.
                 (every? (fn [sl]
                           (or (bytes? sl)
                               (instance? shorts-class sl)
                               (instance? ints-class sl)
                               (instance? longs-class sl)))
                         slots)
                 (or
                  trusted?
                  (and
                   ;; `node-slot` BINARY-SEARCHES containers, so they must ascend.
                   (loop [k 1]
                     (cond (>= k (alength containers)) true
                           (>= (aget containers (dec k)) (aget containers k)) false
                           :else (recur (inc k))))
                   ;; `(every? nat-int? (seq counts))` walked an int[] as a
                   ;; boxed seq -- one Integer per node, on the path taken for
                   ;; every source opened. Same predicate, no allocation.
                   (loop [k 0]
                     (cond (>= k (alength counts)) true
                           (neg? (aget counts k)) false
                           :else (recur (inc k)))))))
        ;; One uniform node list. The SEQUENCE is the node at the sentinel
        ;; offset -1: it has no container header on the wire but behaves like
        ;; one, and a sentinel avoids carrying two shapes for the same idea.
        (let [seq-slot (loop [k 0]
                         (cond (>= k (alength containers)) nil
                               (= -1 (aget containers k)) k
                               :else (recur (inc k))))
              n (alength containers)
              st (long stride)
              raw (vec slots)
              ;; LAZY, memoized per node. `expand-slot` is a prefix sum over a
              ;; node's anchors and `node-valid?` reads the file, so doing both
              ;; for every node at load made an index cost more than it saved on
              ;; a source used for ONE lookup -- which is every read of a
              ;; document store. Measured on a 1.37 MB nested map: 1140 us
              ;; indexed against 129 us unindexed, the gap widening with size
              ;; because both passes scale with container count.
              ;;
              ;; `expand-slot`'s docstring argues the eager pass is free because
              ;; the index is materialised once per source. That is true when
              ;; the source is REUSED and exactly inverted when it is not.
              ;;
              ;; AtomicReferenceArray rather than a plain object array: a forked
              ;; Nav shares this realised index across threads, and publishing a
              ;; freshly built long[] through a non-volatile write would be a
              ;; data race. The boolean memos below tolerate their race -- the
              ;; worst case is recomputing a verdict -- which is the same trade
              ;; `anchor-checked` already makes.
              cache (AtomicReferenceArray. n)
              ix {:stride st
                  :containers containers
                  :counts counts
                  :raw-slots raw
                  :slot-cache cache
                  :node-checked (boolean-array n)
                  :node-ok (boolean-array n)
                  :sorted (vec sorted)
                  :data-end ptr}
              ;; THE SEQUENCE NODE STAYS EAGER, alone. `count` and `nth` consult
              ;; it immediately, a wrong `:total` is the silent-corruption case
              ;; documented in `node-valid?`, and it is a single node rather
              ;; than one per container. A sequence is also the shape whose
              ;; source is reused, so there is nothing to amortise away.
              seq-anchors (when seq-slot (slot-at ix (long seq-slot)))
              ;; Trusted skips this for the same reason `node-sound?` does: it
              ;; walks the file to prove the sequence node honest, and a caller
              ;; who wrote the bytes has already answered that question.
              seq-ok? (or trusted?
                          (nil? seq-slot)
                          (node-valid? r ptr
                                       (long (aget containers (int seq-slot)))
                                       (long (aget counts (int seq-slot)))
                                       st seq-anchors))]
          (when seq-ok?
            (assoc ix
                   :total (when seq-slot (long (aget counts (int seq-slot))))
                   :offsets seq-anchors
                   ;; The per-anchor verdict cache -- see `anchor-sound?`.
                   :anchor-checked (when seq-slot
                                     (boolean-array (alength ^longs seq-anchors)))
                   :anchor-ok (when seq-slot
                                (boolean-array (alength ^longs seq-anchors))))))))
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
                       ;; THE WHOLE 17-BYTE PREFIX, which is tag 27, the
                       ;; two-element array, the name `boring/index` AND the
                       ;; six-element payload header, in one comparison against
                       ;; the same constant `boring.frame` compares.
                       ;;
                       ;; This checked tag 27, then that the payload was an
                       ;; array, then the name -- and never the array's element
                       ;; COUNT. So widening a genuine frame's payload from six
                       ;; elements to seven left `nav` using it as an index
                       ;; while `decode-seq`, `footer-start` and `index-frame?`
                       ;; all refused it: one file, two logical contents, and
                       ;; the disagreement decided by which API you called.
                       (.bytesEqualAt r ptr frame/prefix-array)
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
                       (= n (skip r ptr)))
              ;; TWO QUESTIONS, TWO ANSWERS. "Where does the data end" and "are
              ;; these anchors usable" are different, and returning one `nil`
              ;; for both conflated them: a file with a genuine, byte-verified
              ;; footer whose PAYLOAD failed validation lost its `:data-end`
              ;; too, so `items` walked past the data section and republished
              ;; the footer as a trailing data item -- `nav/items` reporting 41
              ;; where `decode-seq` reported 40 on the same bytes.
              ;;
              ;; Detection is what establishes `:data-end`, and detection has
              ;; already succeeded by the time we are here: the prefix matched,
              ;; the pointer is in range, and the frame ends exactly at EOF.
              ;; An unusable payload means SCAN, and scanning still has to stop
              ;; in the right place.
              (merge {:data-end ptr}
                     (index-payload r ptr
                                    (= :trusted (:trust-index (.opts nav))))))))))))

(defn- anchor-sound?
  "Whether sequence anchor `k` is where it claims to be. Cached per anchor.

  VALIDATED LOCALLY, against the PREVIOUS anchor rather than against the start
  of the file: stepping `stride` items from anchor k-1 must land exactly on
  anchor k. That is O(stride) --
  the same order as the walk `nth` already does from the anchor it lands on --
  and it needs no earlier anchor to be trusted, so it costs no more for anchor
  5000 than for anchor 1.

  Cached because the alternative is paying it per lookup: a full scan through
  `nth` then verifies each anchor once rather than once per item, which is one
  extra pass over the data across the whole scan. The cache is a plain
  boolean pair per anchor; two threads racing to compute the same verdict
  write the same value.

  This is the last of the three levels. `footer-start` pins the frame,
  `anchor[0] = 0` and the end-of-node check pin the two ends, and this pins the
  middle -- but only for the anchors actually used, which is what keeps it
  affordable."
  ;; No primitive hints: five args with three of them primitive is one past
  ;; what Clojure allows, and the boxing here is once per anchor, not per item.
  [^Nav nav ^longs offsets stride k]
  (let [ix (nav-idx nav)
        ^booleans done (:anchor-checked ix)
        ^booleans okv (:anchor-ok ix)]
    (if (or (nil? done) (>= (long k) (alength done)))
      true                                    ; no cache: nothing to verify against
      (if (aget done (int k))
        (aget okv (int k))
        (let [^Reader r (.rdr nav)
              ;; Against the PREDECESSOR, not the successor. Checking forward
              ;; leaves the LAST anchor with only the data section's end to
              ;; compare against -- which is what the end-of-node check already
              ;; does, and shares its blind spot: a uniform shift moves the last
              ;; anchor and the walk from its new position can still land
              ;; exactly on the end. Measured, that left `nth 48` wrong and
              ;; every other item right. Checking backward gives every anchor
              ;; but the zeroth a reference that damage has to fake separately,
              ;; and the zeroth is pinned to offset 0 at load.
              ok (if (zero? (long k))
                   (zero? (aget offsets 0))
                   (let [prev (aget offsets (int (dec (long k))))
                         span (long stride)]
                     (try
                       (= (aget offsets (int k))
                          (loop [j 0 p prev]
                            (if (= j span) p (recur (inc j) (skip r p)))))
                       (catch clojure.lang.ExceptionInfo _ false))))]
          (aset done (int k) true)
          (aset okv (int k) (boolean ok))
          ok)))))

(deftype Items [^Nav nav idx]
  clojure.lang.Seqable
  (seq [this] (seq (into [] this)))

  ;; `count` on the items of a sequence threw AbstractMethodError -- on ordinary,
  ;; undamaged data. `Items` implemented Seqable, Indexed and IReduceInit but
  ;; not Counted, so `clojure.core/count` fell through to an abstract method.
  ;; Nothing caught it because every test reaches for `seq`, `nth` or `reduce`.
  ;;
  ;; O(1) when the sequence was sealed with an index -- the item total is the
  ;; sentinel node's count -- and a walk otherwise, which is what counting a
  ;; CBOR sequence costs when nothing recorded the total. `Cursor` can promise
  ;; O(1) unconditionally because a container's count is in its head; a
  ;; sequence has no head at all.
  clojure.lang.Counted
  (count [this]
    (if-let [t (:total idx)]
      (long t)
      (reduce (fn [^long n _] (inc n)) 0 this)))

  clojure.lang.Indexed
  ;; THROWS out of range, as `Indexed` specifies and as `Cursor.nth` already
  ;; does. It returned nil here, so the two `nth`s on the two navigable types
  ;; disagreed -- and `Cursor`'s own comment claimed both threw. The 3-arity
  ;; not-found form is the way to ask without an exception, on both.
  (nth [this i]
    (let [v (.nth this i ::none)]
      (if (identical? ::none v)
        (throw (IndexOutOfBoundsException.
                (str "boring.nav: index " i " out of bounds for this sequence")))
        v)))
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
      (if-let [^longs offsets (:offsets idx)]
        ;; O(1) to the anchor, then at most stride-1 skips.
        (let [stride (long (:stride idx))
              total (long (:total idx))]
          (if (or (neg? i) (>= i total))
            nf
            (let [anchor (quot (long i) stride)]
              (if-not (anchor-sound? nav offsets stride anchor)
                ;; A middle anchor cannot be validated at load without walking
                ;; to it, which is the work the index exists to avoid -- so it
                ;; is validated HERE, against its neighbour, the first time
                ;; anybody jumps to it, and the verdict is cached.
                ;;
                ;; Without it, one changed delta byte inside the frame moved
                ;; anchors 2 and 3 of a 60-item file and `nth 32` returned
                ;; -1768167461 where `decode-seq` returned the record. The
                ;; end-of-node check does not see it: the last anchor moved
                ;; too, and the walk from its new position still landed
                ;; exactly on the data section's end.
                (loop [k 0 p 0]                     ; fall back to the walk
                  (cond (>= p end) nf
                        (= k (long i)) (cursor-at nav p)
                        :else (recur (inc k) (skip r p))))
                (loop [k (* anchor stride) p (long (aget offsets anchor))]
                  (if (= k (long i)) (cursor-at nav p) (recur (inc k) (skip r p))))))))
        ;; No sequence index: skip i times, or run out.
        (loop [k 0 p 0]
          (cond (>= p end) nf
                (= k (long i)) (cursor-at nav p)
                :else (recur (inc k) (skip r p)))))))

  ;; IReduce as well as IReduceInit: `(reduce f coll)` -- the arity everyone
  ;; actually writes -- threw a raw ClassCastException ("cannot be cast to
  ;; clojure.lang.IReduce") on perfectly good data, because every test happened
  ;; to pass an init. Exactly the sibling of the `Counted`/AbstractMethodError
  ;; gap found a round earlier.
  clojure.lang.IReduce
  (reduce [this f]
    (let [s (seq this)]
      (if s (clojure.core/reduce f (first s) (rest s)) (f))))

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (let [^Reader r (.rdr nav)
          ;; Stop at the data section's end, NOT the file's. Without this the
          ;; index item itself would be yielded as if it were data.
          end (long (or (:data-end idx) (.size r)))]
      (loop [p 0 acc init]
        (if (or (>= p end) (reduced? acc))
          (unreduced acc)
          (recur (skip r p) (f acc (cursor-at nav p)))))))

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
     (Items. nav (nav-idx nav)))))

(defn fork
  "A view of the same source for ANOTHER THREAD.

  **`boring.nav` is not thread-safe, and sharing one source silently returns
  WRONG ANSWERS.** A source owns one `Reader`, and a Reader carries mutable
  position, depth and scratch state that every cursor derived from it shares.
  Measured, 200 parallel passes over one `items` returned 6 plausible but
  wrong documents with no exception at all, alongside a spread of typed errors.
  (This said 10, against the 6 that `doc/SECURITY.md` and `Reader.java`'s
  detector comment both record for the same run. One experiment, three
  write-ups, one of them drifted.)

  Nothing about the surface warns you: the namespace is read-only navigation,
  `Items` is reducible, and `boring.mmap` picks a shared arena precisely so the
  mapping is not pinned to one thread. So this exists, and it is cheap.

      (let [src (nav/items bs opts)]
        (pmap (fn [part] (reduce f (nav/fork src))) parts))

  Rebuilding with `items`/`source` instead would work, and costs far more: the
  index has to be decoded and every delta slot expanded, measured at 145 us for
  a 20 000-item index against 175 ns for a Reader. A fork shares that work --
  it is immutable once built -- and replaces only the mutable part.

  What is shared: the bytes, the decoded index, the options. What is fresh: the
  Reader, and the key-probe cache."
  [x]
  (cond
    (instance? Cursor x) (let [^Cursor c x] (cursor-at (fork-nav (.nav c)) (.off c)))
    (instance? Items x)  (let [^Items i x
                               n (fork-nav (.nav i))]
                           (Items. n (nav-idx n)))
    :else (fail :boring/unsupported-source
                "boring.nav/fork: expected a cursor or the result of `items`"
                {:got (class x)})))

(defn zipper
  "A read-only clojure.zip zipper over the cursor. `down`, `right`, `node` and
  friends work; an edit throws `:boring/read-only`, because a change of length
  would cascade through every offset after it.

  WHEN it throws is worth stating, because \"anything that edits throws\" was
  too strong. The guard is `make-node`, which `clojure.zip` calls only when an
  edit has to be rebuilt into its parent -- so `zip/insert-child`, `zip/remove`
  and `zip/edit` throw where you wrote them, but a `zip/replace` is ACCEPTED
  and `zip/node` reads the replacement straight back. The failure arrives at
  the first `zip/up` or `zip/root` after it. A caller who only replaces and
  reads gets no error at all; nothing escapes, because the value cannot leave
  the zipper without passing through `up`, but you may be several steps past
  the line that was wrong. Decode, edit, re-encode.

  AT THE ROOT it never arrives, because there is no `up` to take:
  `(-> z (zip/replace 5) zip/root)` returns 5 and `zip/up` returns nil, while
  the same replace one level down raises `:boring/read-only` on `root`. That is
  not a hole in the guard -- a root replacement discards the document rather
  than editing it, so there are no offsets left to be inconsistent with -- but
  \"anything that edits throws\" was false there, and it was the one spelling a
  reader would reach for first."
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
