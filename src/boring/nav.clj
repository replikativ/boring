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

    source       open a document; takes options or a `context`
    context      resolved options plus an encoded-key cache, for a SCAN that
                 opens one source per document -- 2.4x on 4000 blobs
    value        realise what a cursor points at
    container?   whether a cursor can be descended into
    items        top-level items of an RFC 8742 sequence
    fork         a cursor usable from another thread

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

  TAGS ARE OPAQUE BY DEFAULT, WITH THREE EXCEPTIONS. `get` on a tagged value
  realises it through the normal reader and continues with clojure.core/get on
  the result. A tag's reader is an arbitrary function, so there is no general
  relationship between the wire shape and the logical shape of what it returns
  -- descending structurally could disagree with decoding, silently.

  Three forms are descended into anyway, because boring WROTE them and knows
  what they mean: shaped arrays (tag 39649), RFC 8746 typed arrays, and tag-27
  records. Each is worth 36-73x on the shape it covers, and each refuses
  wherever equivalence is not provable -- see the taxonomy above
  `tag-view-builders`. A record's gate is `Reader.recordDescendable`, which
  lives beside the dispatch it must agree with rather than mirroring it here.

  Everything else still realises, and realising is still the reference
  implementation.

  A HANDLER CAN OPT IN. `boring/declare-navigable-record` says a registered
  constructor preserves structure; `boring.nav-conformance` checks that claim
  against your own data, because a wrong one returns wrong values silently.

  THE INVARIANT, stated carefully, because the obvious version is false:

    For a document the configured reader would decode successfully, every nav
    answer equals the realised answer. Where the reader would raise on the
    SUBTREE a nav operation actually touches, nav raises the same typed error.
    Nav may also DECLINE -- `count` on an opaque tag refuses -- which is sound;
    what it may never do is answer differently.

  Nav does NOT charge document-wide resource budgets for a partial read.
  `:max-items` and `:max-depth` bound a whole decode; a lookup that reads three
  items is not charged for the other ten thousand, so `(get c :a)` can succeed
  where `decode` raises `:boring/max-items-exceeded`. That is deliberate, it
  predates every descent, and charging them would defeat this namespace.
  `boring.nav-divergence-test` asserts the rest."
  (:require [boring.core :as boring]
            [boring.data]
            [boring.errors :as err]
            [boring.frame :as frame]
            [boring.options :as opt]
            [clojure.zip :as zip])
  (:import (org.replikativ.boring Reader ByteSource)))

(set! *warn-on-reflection* true)

(declare ->Cursor cursor-at read-index read-index* source-at tag-view
         nth-item realize lookup-map head-count child-offsets)

;; A shaped array is `39649([keys, [row, row, ...]])`, where each row is an
;; ARRAY of values positionally matching `keys`. It REALISES to a vector of
;; maps, so a cursor on one has to present that shape without building it --
;; see `shaped-view`.
(def ^:private ^:const TAG-SHAPED-ARRAY 39649)

(defn- fail [type msg data]
  (throw (ex-info msg (assoc data :type type))))

;; ---------------------------------------------------------------- the source

(declare slot-at sorted-at? container-at count-at)

;; A TYPE, not a fifteen-key map. `index-payload` used to return one, and on a
;; source that is read ONCE -- which is every read of a store handing out a
;; fresh blob -- building it was the single largest cost in a cold lookup.
;;
;; Profiled, 4M cold indexed lookups through a `nav/context` (so option
;; validation is out of the picture): 29.7% of samples were Clojure map and
;; keyword operations, against ~28% doing any CBOR reading at all, and the
;; TOP LEAF in the whole profile was `PersistentHashMap$BitmapIndexedNode.assoc`
;; at 9.4% -- this map being built. Fifteen keys is past the array-map
;; threshold, so it is a bitmap trie: fifteen hashed inserts to construct, and
;; a hash and a trie descent for every read, of which `slot-at` alone does six.
;;
;; Fields are a load. Nothing here ever needed map semantics: it is internal to
;; this namespace, never printed, never `assoc`ed by a caller, never iterated.
;;
;; NIL-PUNNING IS PRESERVED, deliberately. `read-index` returns a DETECTED but
;; REFUSED frame as an index carrying only `data-end` -- `items` still needs to
;; know where the data stops, or it walks into the footer and republishes it as
;; a trailing item. Callers tell the two apart with `(if-let [cs (.containers
;; ix)] ...)`, exactly as they did when this was a map, so that contract is
;; unchanged.
;;
;; `ILookup` so `(:containers ix)` keeps working. Tests reach in here on
;; purpose -- `index_layout_test` and `index_robustness_test` assert on
;; `:slots`, `:sorted`, `:offsets`, `:node-checked` -- and those assertions are
;; the point of those files, not an accident to be rewritten around. The hot
;; paths in this namespace use direct field access; `valAt` is for them.
(deftype Index
         ;; NO READER. The Index carries offsets and arrays and nothing that can
         ;; read them, deliberately: `fork-nav` builds a FRESH `Reader` per fork
         ;; and SHARES the decoded index, justified in its own comment by "it is
         ;; immutable once built". That is true of arrays. It would be false the
         ;; moment this held a Reader, because `byteAt`/`u32At`/`headArgAt`/
         ;; `headEndAt` do not set the `busy` flag -- only `skipFrom` and
         ;; `readFrom` do -- and the last two MUTATE `pos` and restore it in a
         ;; `finally`. Two forked threads interleaving there race on `pos`: A
         ;; sets `pos = p+1`, B sets `pos = q+1`, A's `arg()` reads B's bytes.
         ;; Wrong offset, wrong anchor, wrong subtree, NO exception -- which is
         ;; exactly what `fork` exists to prevent and exactly what the `busy`
         ;; detector was added for.
         ;;
         ;; So every accessor takes its `^Reader` from the calling `Nav`. The
         ;; field was unused already; removing it is what keeps it that way once
         ;; the elements become byte offsets and reading them needs a Reader.
         [^long stride
          ;; CONTAINERS AS AN OFFSET TOO, at whichever width the frame
          ;; declares. `seal-index!` emits tag 78 when every offset fits in
          ;; int32 and tag 79 when one does not -- narrowest-that-fits, and the
          ;; CBOR tag IS the declaration -- so `cw` is 4 or 8 and
          ;; `container-at` sign-extends either. Normalising to a `long[]` used
          ;; to cost 1.21 us per open on a 770-node frame, more than the decode
          ;; it normalised, purely to change the type.
          ^long containers-data
          ^long cw
          ;; COUNTS AS AN OFFSET, not an int[]. `counts` is a tag-78 typed
          ;; array on the wire -- RFC 8746 little-endian -- so entry `i` is four
          ;; bytes at `counts-data + 4i` and reading one is a load rather than a
          ;; decode. Materialising it cost 1.07 us per open on a 770-node frame,
          ;; to serve a lookup that touches one or two nodes.
          ;;
          ;; `n` is the node count, derived once as `byte-length / 4`, because
          ;; there is no `alength` to ask any more and three checks depend on it.
          ^long counts-data
          ;; `nw` is the counts element width, 4 or 8, exactly as `cw` is for
          ;; containers. Nothing writes 8; the reader accepts it so that a
          ;; future writer change does not cost older readers their index.
          ^long nw
          ^long n
          ;; SLOTS AND SORTED AS OFFSETS TOO, which is what stops the OPEN
          ;; scaling with total anchor count: the packed slots were bulk-copied
          ;; on every open, so a finer stride made every open more expensive for
          ;; every lookup in the document. That coupling is what made a
          ;; per-container stride policy incoherent -- see the plan.
          ;;
          ;; `slots-tbase` is ABSOLUTE; the entries it holds are RELATIVE to
          ;; `slots-data`. See `slot-table`.
          ^long slots-data
          ^long slots-len
          ^long slots-tbase
          ^long slots-wstart
          ^long sorted-data
          ^long data-end
          total
          ^longs offsets]
  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [_ k nf]
    (case k
      :stride stride
      ;; `:containers` was the long[]; it is the accepted/refused discriminator
      ;; now, which is all any caller ever used it for -- `(some? (:containers
      ;; ix))`. A refused-but-detected frame carries n = -1.
      :containers (when-not (neg? n) n)
      :containers-data containers-data :cw cw
      :counts-data counts-data :nw nw :n n
      ;; `:counts` was the int[]; it is a node COUNT now. Nothing in the tree
      ;; reads it -- both test reach-ins named `:counts` are on
      ;; `boring.core/build-index`'s map, which is unaffected.
      :counts n
      :slots-data slots-data :slots-len slots-len
      :slots-tbase slots-tbase :slots-wstart slots-wstart
      :sorted-data sorted-data :data-end data-end
      ;; `:slots` and `:sorted` were the byte arrays. Tests used them only to
      ;; ask "is this index usable", which `:containers` answers.
      :slots (when-not (neg? n) slots-data)
      :sorted (when-not (neg? n) sorted-data)
      :total total :offsets offsets
      nf)))

;; `views` is a ONE-SLOT cache of the last tag view built, as `[offset view]`.
;;
;; It was an unbounded `atom {}` keyed by offset, and it grew one entry per tag
;; offset TOUCHED -- `::none` for non-navigable ones included. A `reduce` over
;; `nav/items` of 2000 records retained 2000 entries; 2000 sets retained 2000
;; useless ones. That contradicts `items`' own promise that nothing before the
;; cursor you are holding stays live, on exactly the workload the descents were
;; built for.
;;
;; One slot is enough for what the cache is FOR. Every row of one shaped array
;; shares a single tag offset, so a caller walking rows hits the slot every
;; time; a caller walking many different tags gains nothing from memoising each
;; one, and a miss is `majorAt` + `headArgAt` + a map lookup.
;;
;; Separate from `probes` rather than sharing it: `probes` is keyed by USER key
;; values, so a caller whose key happened to equal a cache key would get a view
;; where encoded bytes were expected.
(deftype Nav [^Reader rdr opts probes idx src views])

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
  (let [^Index ix (nav-idx nav)
        ^Reader r (.rdr nav)]
    ;; `n` is -1 for a detected-but-REFUSED frame, which still carries
    ;; `data-end` -- the two questions are answered separately, see
    ;; `read-index*` -- so an index can be present and carry no nodes at all.
    (if (and ix (not (neg? (.n ix))))
      (loop [lo 0 hi (dec (.n ix))]
        (if (> lo hi)
          -1
          (let [mid (quot (+ lo hi) 2)
                c (container-at r ix mid)]
            (cond
              ;; VALIDATED HERE, not at load. The node is checked against the
              ;; data the first time a lookup lands on it, and an unsound one
              ;; reports -1 -- which every caller already treats as "no index,
              ;; walk it". So the fallback path is the one that always existed
              ;; and the failure mode is unchanged; only the moment of
              ;; discovery moved. See `index-payload`.
              (= c off) mid
              (< c off) (recur (inc mid) hi)
              :else (recur lo (dec mid))))))
      -1)))

(def ^:private ^:const max-cached-probes
  "How many encoded keys one context remembers. Generous for the intended use --
  a path is a handful of keys -- and a bound rather than a target."
  1024)

(defn- probe-for
  "The encoded bytes of `k`, cached. Key matching compares bytes rather than
  decoding keys, which is sound because encoding is deterministic for a given
  profile -- so byte equality is value equality."
  ^bytes [^Nav nav k]
  (let [p (.probes nav)
        m @p]
    (or (get m k)
        (let [bs (boring/encode k (.opts nav))]
          ;; BOUNDED. A context is documented as reusable for as long as its
          ;; options hold, and its cache is shared by every source opened
          ;; through it -- so with computed keys (`(str "id-" i)`) this grew for
          ;; the life of the scan: 5000 entries after 5000 distinct keys. Past
          ;; the bound the encoding still happens, it is simply not remembered,
          ;; which costs a re-encode and never an answer.
          ;;
          ;; The cache exists for a PATH's keys, which is a handful repeated per
          ;; document. A working set that large is not a path, it is a leak.
          (when (< (count m) max-cached-probes)
            (swap! p assoc k bs))
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
  (let [_ (when-not (or (map? opts) (nil? opts) (instance? NavContext opts))
            (fail :boring/bad-options
                  (str "boring.nav: expected an options map or a `nav/context`, got "
                       (some-> opts class .getName))
                  {:got (class opts)}))
        ctx? (instance? NavContext opts)
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
          nav (Nav. r opts probes idx src (volatile! nil))]
      (vreset! holder nav)
      nav)))

(defn- fork-nav ^Nav [^Nav n]
  (let [src (.src n)
        ^Reader r (cond (bytes? src) (Reader. ^bytes src)
                        :else (Reader. ^ByteSource src))]
    ;; Resolved once here rather than reading the options map, for the same
    ;; reason `context` does it: `configure-reader!` is eleven `get`s on a
    ;; PersistentArrayMap, whose `get` is a linear scan. Once per fork, so the
    ;; saving is nil -- but a path that forgot is how the option surface drifts,
    ;; and this file has found that shape three times already.
    (boring/apply-reader-config! r (boring/reader-config (.opts n)))
    ;; A FRESH VIEW SLOT, and that is load-bearing rather than tidy: a cached
    ;; view holds closures over the parent's `nav`, hence over the parent's
    ;; Reader. Sharing the slot across a fork would share the Reader, which is
    ;; the one thing `fork` exists to prevent.
    ;;
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
      (Nav. r (.opts n) (atom {}) (when ix (delay ix)) src (volatile! nil)))))

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
  ([src opts] (source-at src 0 opts)))

(defn source-at
  "A navigable view over the item at byte offset `off` in `src`.

  `source` is this with `off` 0.

  THE OFFSET IS FOR FORMATS THAT PREFIX AN ITEM WITH SOMETHING OF THEIR OWN --
  a length, a header, another item. The alternative is to nest the item inside
  a container purely so that it can be found, and then every read pays to skip
  whatever shares that container. konserve-lmdb's store blobs are
  `<header><meta item><value item>` for exactly this reason: both items are
  reachable in constant time, where a two-entry map forces one of them to walk
  past the other on every read. Measured there, one field out of 20k rows:
  0.163 us/row through the map against 0.091 through the offset.

  `off` is TRUSTED, like every other positional entry point in this namespace.
  A wrong offset lands mid-item and reports whatever the bytes there happen to
  encode -- the reader is bounded, so it cannot read past the source, but it
  can very much return a plausible wrong value. Callers derive offsets from the
  format, not from user input.

  Addresses ONE item, the one starting at `off`. See `source` on sequences."
  ([src off] (source-at src off nil))
  ([src off opts] (cursor-at (nav-of src (or opts {})) (long off))))

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

(defn- stringref-key?
  "Whether the map key at `p` is a stringref REFERENCE rather than a literal.

   Both shapes: a bare `tag 25`, and a repeated KEYWORD, which boring writes as
   `tag 39` wrapping the reference."
  [^Reader r ^long p]
  (and (= MAJOR-TAG (.majorAt r p))
       (let [a (.headArgAt r p)]
         (or (= 25 a)
             (and (= 39 a)
                  (let [q (.headEndAt r p)]
                    (and (= MAJOR-TAG (.majorAt r q)) (= 25 (.headArgAt r q)))))))))

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
            ;; A KEY THAT IS A STRINGREF REFERENCE CANNOT BE COMPARED, so a miss
            ;; on one is not an answer. It is the cursor reporting that it
            ;; stands INSIDE a namespace whose table it does not have.
            ;;
            ;; `source`/`source-at` refuse a document that OPENS a namespace,
            ;; but that check reads byte 0 of the BUFFER and knows nothing about
            ;; the offset. An offset landing inside a namespace -- reachable
            ;; whenever a container format puts its own bytes in front, which is
            ;; exactly what `source-at` exists for -- sailed past it and then
            ;; reported every referenced key ABSENT. A present key, a wrong
            ;; answer, no error.
            ;;
            ;; One `majorAt` on the miss path, where a memcmp and two skips have
            ;; already happened.
            (if (stringref-key? r p)
              (fail :boring/stringref-not-navigable
                    (str "boring.nav: the key at offset " p " is a stringref "
                         "reference, so this cursor stands inside a stringref "
                         "namespace and cannot resolve it. Navigating from an "
                         "offset only works outside one -- re-encode with "
                         "{:stringref false}, or decode this item whole.")
                    {:offset p})
            ;; THE LAST ENTRY OF A WALK NEEDS NO ADVANCE, and computing one is
            ;; not free -- `skip` past a VALUE is a structural walk of that
            ;; value's whole subtree. This used to advance unconditionally and
            ;; then discover, on the next iteration, that `i` had reached the
            ;; limit; the offset it worked out was thrown away.
            ;;
            ;; That is invisible on flat data and decisive on nested data,
            ;; because the wasted skip is the size of one SUBTREE. An indexed
            ;; lookup walks a bounded span per anchor, so it paid this on EVERY
            ;; anchor it tried -- and with a stride of 1, on every entry it
            ;; looked at. Measured on a 32 KB document whose first key holds the
            ;; whole tree: scanning that one-entry span cost 26.0 us against
            ;; 0.018 us for the same call one anchor later. The index's jump was
            ;; being taken correctly and then thrown away by this line.
              (if (>= (inc i) limit)
                -1
                (let [q (skip r (skip r p))]
                  (if (or (<= q p) (> q end)) -1 (recur (inc i) q))))))))
      (catch IndexOutOfBoundsException _ -1))))

(defn- lookup-map
  "Offset of the value for `k` in the map at `off`, or -1. Decodes no keys.

  Three paths, fastest first:

    indexed + sorted   binary search the node's anchors comparing ENCODED key
                       bytes, then walk at most `stride`-1 entries. O(log n).
    indexed            jump anchor to anchor, walking only within one stride,
                       and never touching a VALUE. Without key order you must
                       try each anchor's span until the key turns up, so what
                       this saves is decided entirely by THE STRIDE. Skips to
                       reach the last of 200 unsorted keys:

                         unindexed    161      stride 4     122
                         stride 1       2      stride 16    152
                         stride 2      82      stride 64    160

                       So an unsorted lookup IS accelerated, at stride 1, by
                       80x. This paragraph used to say it does not and cannot,
                       at every stride from 1 to 1000 -- and that was true when
                       it was written, because `scan-map` skipped past the value
                       of the last entry in every span it examined. A stride-1
                       span is one entry, so it paid a full value skip to learn
                       nothing. Fixing that (see `scan-map`) is what made the
                       stride the lever.

                       Sorted keys (`:canonical`, `:archival`) reach the branch
                       above and work at ANY stride; measured on a document
                       whose first key holds a deep subtree, canonical at stride
                       16 and unsorted at stride 1 both land at ~7.3 us against
                       34.4 unindexed. Unsorted at the DEFAULT stride is a net
                       LOSS -- 43.7 us -- because the frame is paid for and the
                       span containing the big entry is walked anyway. Arrays
                       index positionally under any profile.
    unindexed          walk every entry, which is what this always did.

  All three return the same offset. The index only decides how much is walked."
  ^long [^Nav nav ^long off k]
  (let [^Reader r (.rdr nav)
        n (head-count nav off)
        ^bytes probe (probe-for nav k)
        ^Index idx (nav-idx nav)
        ns (node-slot nav off)]
    ;; A node with NO anchors means nothing to jump to -- walk instead. Empty
    ;; containers legitimately produce one (`:index-min 0` will index a `{}`),
    ;; and the sorted branch below assumes at least one anchor: with m=0 its
    ;; `(max 0 (min (dec m) hi))` still yields 0 and `aget` threw straight at
    ;; the caller of `get`. Cheaper to notice here than to special-case both
    ;; branches, and it also covers any future node that turns out empty.
    ;; AN UNSORTED MAP AT STRIDE > 1 CANNOT BE ACCELERATED AT ALL, so do not
    ;; pay to try. The unsorted branch below loops anchor to anchor calling
    ;; `scan-map` for `span` entries each, which visits every entry of the
    ;; container -- exactly what one `scan-map` from the head does, plus a jump
    ;; and a call per anchor. Measured on a map of 2000 scalars: 16.81 us
    ;; indexed against 11.29 scanning, 0.67x, i.e. the anchors cost 50% and
    ;; bought nothing.
    ;;
    ;; At stride 1 it INVERTS and the index is the whole point: every entry has
    ;; an anchor, so moving from one to the next JUMPS OVER the value instead of
    ;; walking it. Measured 2.72x on a 20-key hash map of 1000-int vectors --
    ;; and Clojure maps are unsorted by default, so that is the ordinary case
    ;; rather than a corner. See `boring.core/pack-sorted` and commit 825657f.
    (if (or (neg? ns)
            (zero? (alength ^longs (slot-at r idx ns)))
            (and (> (.stride idx) 1) (not (sorted-at? r (.sorted-data idx) ns))))
      (scan-map r (.headEndAt r off) n probe)
      (let [^longs slot (slot-at r idx ns)
            stride (.stride idx)
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
                ;; WHY `confirm` IS NOT REDUNDANT, beyond the damaged-anchor
                ;; case argued above. A `:sorted` node over a map the WRITER
                ;; ordered by something other than canonical CBOR bytes -- a
                ;; `clojure/sorted-map` payload is ordered by Clojure `compare`
                ;; -- leaves this binary-searching an array that is not sorted
                ;; by the function the search compares with. It stays correct
                ;; only because a negative is re-derived by an honest scan and a
                ;; positive requires an exact byte match. Deleting `confirm` as
                ;; a redundant optimisation would break those lookups silently.
                (confirm [^long hit]
                  (if (neg? hit) (scan-map r (.headEndAt r off) n probe) hit))]
          (if (sorted-at? r (.sorted-data idx) ns)
            ;; Sorted keys: binary search the anchors, then a bounded walk.
            ;;
            ;; The PROBE is bounds-checked as well as the walk. Validation
            ;; proves the first anchor is a real entry; a middle one that points
            ;; mid-item survives, and `compareItemToBytes` skips from wherever
            ;; it is told -- reading a garbage head and running off the buffer,
            ;; which surfaced as a raw ArrayIndexOutOfBoundsException out of
            ;; `get`. Found by mutating every byte of a real indexed document
            ;; and requiring that no lookup ever throws an untyped exception.
            ;; `.data-end` is a primitive field and so never nil; the `or`
            ;; against `(.size r)` dated from the fifteen-key map.
            (let [lim (.data-end idx)]
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
  key order before binary search is legal.

  THE ANCHOR IS TRUSTED. It was briefly verified against its predecessor here --
  stepping `stride` items from anchor k-1 and requiring it to land on anchor k,
  the same local rule sequences used -- because a damaged anchor reaches the
  caller through `nth` with nothing in between, unlike `lookup-map`, whose
  misses `confirm` re-derives.

  That check cost more than it was worth once the index became a trust boundary
  outright. It is O(stride) SKIPS, and a skip is O(1) only for a scalar:
  stepping over 16 twenty-entry maps is ~640 sub-skips, measured at warm 1.14 ->
  4.90 us on 769 maps of 20. An index we have decided to trust does not earn
  that, and validating one jump while leaving the rest of the frame trusted was
  never a coherent position anyway. See this namespace's docstring for what a
  trusted index does and does not promise."
  ^long [^Nav nav ^long off ^long idx]
  (let [^Reader r (.rdr nav)
        n (head-count nav off)]
    (if (or (neg? idx) (>= idx n))
      -1
      ;; ELEMENT 0 NEVER NEEDS THE INDEX, and does not even need it OPEN.
      ;; `anchor` is `(quot idx stride)`, which is 0 for idx 0 whatever the
      ;; stride is, and anchor 0 is the container's own first entry -- so the
      ;; indexed route walks from exactly where this does, after paying a binary
      ;; search, an anchor expansion, and possibly the whole index open.
      ;;
      ;; Measured on a 2000-element vector: `nth 0` cost 0.18x of scanning warm
      ;; and 0.24x cold, and on a nested path `[0 "k7"]` was SLOWER than
      ;; `[400 "k7"]` -- the index doing work that cannot pay.
      (if (zero? idx)
        (.headEndAt r off)
        (let [ix (nav-idx nav)
              ;; `nav-idx` is nil when the file carries no usable index at all.
              stride0 (if ix (.stride ^Index ix) 0)
              ;; The same argument once the stride is known: any `idx` inside
              ;; the first stride lands on anchor 0, so there is nothing to jump
              ;; to. Checked before `node-slot`, which is a binary search over
              ;; every node in the document.
              ns (if (or (nil? ix) (< idx stride0)) -1 (node-slot nav off))
              ^longs slot (when-not (neg? ns) (slot-at r ix ns))
              stride (when slot stride0)
              anchor (when slot (quot idx (long stride)))]
        ;; THE ANCHOR MUST BE THERE, which `lookup-map` has always checked and
        ;; this never did -- `node-sound?` was rejecting such a node before it
        ;; got here, and with that gone `(aget slot anchor)` on a node whose
        ;; count says zero anchors is a raw AIOOBE out of `nth`. Empty
        ;; containers legitimately produce a zero-anchor node (`:index-min 0`
        ;; will index a `[]`), so this is reachable without any damage at all.
          (if (or (nil? slot) (zero? (alength slot)) (>= (long anchor) (alength slot)))
            (loop [i 0 p (.headEndAt r off)]
              (if (= i idx) p (recur (inc i) (skip r p))))
            (loop [i (* (long anchor) (long stride)) p (long (aget slot (long anchor)))]
              (if (= i idx) p (recur (inc i) (skip r p))))))))))

;; NOTE: this compares the declared count against `room` BEFORE doubling for a
;; map, while `Cursor.count` compares `2n`. Both are typed refusals, so the
;; difference is which one fires -- a map declaring exactly `room` pairs passes
;; here and fails in `skip`. Cosmetic, but the two guards should say the same
;; thing if either is ever touched.
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
                          (cond (identical? ::miss r) nf
                                ;; `::realise` is part of the view contract for
                                ;; BOTH kinds, and only `:map` honoured it -- so
                                ;; a vector-kinded builder returning it would
                                ;; have handed the caller the keyword
                                ;; `:boring.nav/realise`. No builder does today;
                                ;; the next one would have found out the hard
                                ;; way.
                                (identical? ::realise r) (fallback)
                                :else r))
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
              :vector (let [r ((:nth v) i)]
                        (cond (identical? ::miss r) nf
                              (identical? ::realise r)
                              (let [rv (realize nav off)]
                                (if (or (nil? rv) (instance? clojure.lang.Indexed rv)
                                        (instance? java.util.List rv) (string? rv)
                                        (and (some? rv) (.isArray (class rv))))
                                  (nth rv i nf)
                                  nf))
                              :else r))
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
      ;; `major` read ONCE. It was called here and again in the `let` below.
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
              ;; NOT `(seq (vec ...))`. The `:items`/`:entries` closures already
              ;; return lazy seqs, and pouring them through a vector first
              ;; boxed every element of a 100k typed array before `reduce` saw
              ;; one -- against this namespace's own promise to reduce over
              ;; children with no intermediate seq.
              :vector (seq ((:items v)))
              :map (seq ((:entries v)))
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

(defn walk
  "The cursor at `path` from `cursor`, or nil if any step is absent.

  `(walk c [:profile :address :city])` is `(get-in c [...])` with two
  differences that matter on a hot path:

    * NO INTERMEDIATE CURSOR. `get-in` allocates one per step to throw away;
      this resolves the whole path against one `Nav`, carrying an offset.
    * EVERY STEP CONSULTS THE INDEX, because every step goes through the same
      `lookup-map`/`nth-item` that `get` does.

  That second point is the reason this is public rather than an internal
  shortcut. A caller that wants a compiled access path -- probe bytes encoded
  once, no per-row re-encoding -- would otherwise write its own walker over
  `Reader`'s positional primitives, and such a walker CANNOT reach the index:
  the index lives on the Nav, and `Reader.skipFrom` knows nothing about it.
  konserve-lmdb wrote exactly that walker, and it disagreed with this namespace
  twice -- reporting a present integer key absent, and returning the first
  VALUE of a map for the path `[1]` where the truth is the value under the key
  `1`. One walker, one set of answers.

  Probes are cached on the Nav's context, so a path's keys are encoded once per
  context rather than once per document -- which is the other half of what a
  compiled path buys.

  An integer step is a POSITION on an array and a KEY on a map, decided from
  the container. That is `get`'s rule and it is the correct one."
  [cursor path]
  (when cursor
    (let [^Cursor c cursor
          ^Nav nav (.nav c)]
      ;; A shaped-array ROW resolves its keys through the shape rather than the
      ;; wire, so it cannot be walked by offset. Falling back to `get` per step
      ;; keeps the answer right; such rows are small and rare.
      (if (.shape c)
        (reduce (fn [cur k] (when cur (get cur k))) cursor path)
        (loop [off (.off c) ks (seq path)]
          (if-not ks
            (cursor-at nav off)
            (let [k (first ks)
                  mj (major nav off)]
              (if (or (= mj MAJOR-ARRAY) (= mj MAJOR-MAP))
                (let [p (if (and (= mj MAJOR-ARRAY) (integer? k))
                          (nth-item nav off (long k))
                          (lookup-map nav off k))]
                  (if (neg? p) nil (recur p (next ks))))
                ;; ANYTHING ELSE GOES THROUGH `get`, which is the only place
                ;; that knows how to descend a TAG -- a record, a shaped array,
                ;; a set, or a stringref namespace, which has no view and falls
                ;; back to realising. Walking by offset cannot do that, and
                ;; skipping the fallback made `walk` answer nil where `get-in`
                ;; answers correctly. Rare, so the cursor allocation here costs
                ;; nothing on the paths this function exists for.
                (when-let [c2 (get (cursor-at nav off) k)]
                  (reduce (fn [cur kk] (when cur (get cur kk))) c2 (next ks)))))))))))

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
          ;; THE PAYLOAD MUST ACTUALLY BE THERE. `Reader.readTypedArray` bounds
          ;; the declared byte-string length against what remains
          ;; (`checkCount`); this did not, so a tag-79 header claiming 1 MiB
          ;; over eight real bytes reported `count` 131072 and FABRICATED an
          ;; element from `nth 0` -- a value `decode` refuses with
          ;; :boring/bad-count. Not an out-of-bounds read, since `bytesBetween`
          ;; is range-checked, but a wrong answer, which is worse.
          (when (and (<= 0 blen) (zero? (rem blen size))
                     (<= (+ data blen) (.size r)))
            (let [n (quot blen size)
                  at (fn [^long i]
                       (if (or (neg? i) (>= i n))
                         ::miss
                         (let [p (+ data (* i size))]
                           (rd (.bytesBetween r p (+ p size))))))]
              ;; `number?`, not `integer?` -- see the :vector branch of valAt.
              {:kind :vector :n n :nth at :key-pred number?
               :items (fn [] (map at (range n)))})))))))

(defn- tag-pair
  "The two element offsets of a tag whose payload is a definite 2-element array,
  or nil. `39649([keys, rows])` and `27([name, fields])` are both this shape, and
  both builders opened by checking it by hand."
  [^Reader r ^long off]
  (let [pair (.headEndAt r off)]
    (when (and (= MAJOR-ARRAY (.majorAt r pair)) (= 2 (.headArgAt r pair)))
      (let [a (.headEndAt r pair)]
        [a (skip r a)]))))

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
          pr (tag-pair r off)]
      (when pr
        (let [ks-off (nth pr 0)]
          (when (= MAJOR-ARRAY (.majorAt r ks-off))
            (let [rows-off (nth pr 1)]
              (when (= MAJOR-ARRAY (.majorAt r rows-off))
                (let [k (.headArgAt r ks-off)
                      n (.headArgAt r rows-off)]
                  ;; `(pos? k)`: the Reader requires at least one key
                  ;; (`Reader.java`), and a zero-key shape navigated to `[{}]`
                  ;; against a `:boring/bad-tag-content` from `decode`.
                  (when (and (pos? k) (<= 0 n))
                    (let [ks (loop [i 0 p (.headEndAt r ks-off) acc (transient [])]
                               (if (= i k)
                                 (persistent! acc)
                                 (recur (inc i) (skip r p)
                                        (conj! acc (realize nav p)))))
                          ;; DUPLICATE KEYS, which the Reader rejects
                          ;; unconditionally in `checkShapeKeys`. `:pos` is built
                          ;; with `assoc!` so the last wins, while `:ks` keeps
                          ;; both -- one cursor then reported `count` 2 with
                          ;; `(count (value c))` 1. O(K) once per view, against
                          ;; keys already realised on the line above.
                          dup? (not= (count ks) (count (set ks)))
                          sh {:ks ks
                              :pos (persistent!
                                    (reduce-kv (fn [m i kk] (assoc! m kk i))
                                               (transient {}) ks))}
                          ;; EVERY ROW IS CHECKED WHEN IT IS REACHED, not when
                          ;; the view is built. The Reader enforces
                          ;; `row length == key count`; a 3-value row against 2
                          ;; keys navigated fine here.
                          ;;
                          ;; Deliberately NOT validated up front. Checking every
                          ;; row eagerly takes shaped `count` from 644 ns to
                          ;; 10.4 us -- 16x on well-formed data -- and it would
                          ;; also be WRONG: `count` reads the rows-array header
                          ;; and touches no row, so a bad row 5 is not in the
                          ;; subtree it examined. Charging it for that is the
                          ;; same mistake as charging a partial read for
                          ;; `:max-items`. On the paths that DO walk rows --
                          ;; `seq`, `reduce`, `nth i` -- the check comes for
                          ;; free, 0.68 ns/row, because they are there already.
                          row-ok? (fn [^long p]
                                    (and (= MAJOR-ARRAY (.majorAt r p))
                                         (= k (.headArgAt r p))))
                          at (fn [^long i]
                               (let [p (nth-item nav rows-off i)]
                                 (cond (neg? p) ::miss
                                       (not (row-ok? p))
                                       (fail :boring/bad-tag-content
                                             (str "boring.nav: shaped row " i
                                                  " does not carry exactly " k
                                                  " values, which is what its shape declares")
                                             {:offset p :row i :expected k})
                                       :else (->Cursor nav p sh))))]
                      (when-not dup?
                        {:kind :vector :n n :nth at :key-pred integer?
                         ;; through `at`, so every row is checked here too
                         :items (fn [] (map at (range n)))}))))))))))))

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
          pr (tag-pair r off)]
      (when pr
        (let [name-off (nth pr 0)]
          (when (= MAJOR-TEXT (.majorAt r name-off))
            (let [payload (nth pr 1)]
              ;; a POSITIONAL record -- datahike's Datom carries a vector --
              ;; is not a map and gets no descent here
              (when (= MAJOR-MAP (.majorAt r payload))
                (let [nm (realize nav name-off)]
                  ;; THE READER DECIDES. This used to be `recordCtor == nil` here
                  ;; in Clojure, with a docstring claiming the two could not
                  ;; drift because both read the same registry. True of the
                  ;; registry, false of the DECISION: it also depends on the
                  ;; reserved-name table and on `:on-unknown-record` and
                  ;; `:auto-construct-records?`, and the mirror was wrong about
                  ;; all three the day it was written -- `:on-unknown-record` had
                  ;; been added the day before. Three documented configurations
                  ;; produced silently wrong values, and a fourth let nav answer
                  ;; where the reader raises, bypassing a policy gate.
                  ;;
                  ;; `Reader.recordDescendable` sits beside the dispatch it has
                  ;; to agree with and reads the same fields. Nothing here
                  ;; mirrors anything.
                  (when (and (string? nm)
                             (.recordDescendable r ^String nm))
                    ;; NO SORTED-MAP SPECIAL CASE ANY MORE. `clojure/sorted-map`
                    ;; is a reserved name and `recordDescendable` refuses it, so
                    ;; the compare-versus-equals dance that used to live here is
                    ;; gone with it.
                    ;;
                    ;; That gives up a measured 73x on sorted-map lookups, which
                    ;; is a real loss and worth stating plainly. The reason is
                    ;; that descent was only sound for sorted-maps BORING WROTE.
                    ;; Clojure cannot build a sorted-map whose keys are not
                    ;; mutually comparable, and the writer refuses a custom
                    ;; comparator -- but a hand-crafted document can claim the
                    ;; name over any keys at all, and then `decode` raises while
                    ;; `count` and `seq` answer. Establishing comparability means
                    ;; realising every key when the view is built, which is O(K)
                    ;; on the operation the descent exists to make O(log K).
                    ;;
                    ;; Revisitable: the shaped-row watermark shows how to verify
                    ;; incrementally instead. Not worth it until someone stores
                    ;; sorted-maps hot.
                    {:kind :map
                     :n (head-count nav payload)
                     :lookup
                     (fn [k]
                       (let [p (lookup-map nav payload k)]
                         (if (neg? p) ::miss (cursor-at nav p))))
                     :entries
                     (fn []
                       (map (fn [[kp vp]]
                              (clojure.lang.MapEntry. (realize nav kp)
                                                      (cursor-at nav vp)))
                            (partition 2 (child-offsets nav payload))))}))))))))))

;; Dispatch by TAG NUMBER, not by trying each in turn. Three sequential probes
;; ran on every tag cursor before this; one lookup replaces them, so unifying
;; is cheaper than the special cases were.
;;
;; THIS IS WHERE A NEW DESCENT GOES, which is not the same as an extension
;; point: the table is private, the sentinels are private, and a builder would
;; have to return a `Cursor`, which nothing outside this namespace can construct.
;; `declare-navigable-record` is the extension point users actually have.
;;
;; A handler that knows its reader preserves
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
  (let [slot (.views nav)
        hit @slot]
    (if (and hit (= (nth hit 0) off))
      (let [v (nth hit 1)] (when-not (identical? ::none v) v))
      (let [v (try
                (when (= MAJOR-TAG (major nav off))
                  (let [tag (.headArgAt ^Reader (.rdr nav) off)]
                    (when-let [build (tag-view-builders tag)]
                      (build nav off tag))))
                (catch Exception _ nil))]
        (vreset! slot [off (if (nil? v) ::none v)])
        v))))

;; --------------------------------------------------------------- public API

(defn container?
  "Whether `x` is a cursor something can be descended INTO -- an array, a map,
  or a tag with a descent.

  Exists so `zipper` and the Cursor cannot disagree about that. They did:
  `zipper`'s `branch?` tested `value-type`, which answers `:tag` for a shaped
  array, a record and a typed array, so `zip/down` was nil on exactly the values
  `count`, `seq`, `get`, `nth` and `reduce` had learned to enter. Before
  descents existed the two agreed, because every tag was opaque everywhere."
  [x]
  (and (instance? Cursor x)
       (let [^Cursor c x
             nav (.nav c) off (.off c)]
         (or (some? (.shape c))
             (let [mj (major nav off)]
               (or (= mj MAJOR-ARRAY) (= mj MAJOR-MAP)
                   (and (= mj MAJOR-TAG) (some? (tag-view nav off)))))))))

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

(defn- seq-node-ok?
  "Whether the SEQUENCE node's anchors describe this data section.

  All that survives of `node-valid?`, and the reason it survives is that it is
  not the same kind of check as the rest. The per-node and per-anchor
  validation policed byte-level corruption of an index we have now decided to
  trust; this catches a FILE-level mixup, which trusting the index says nothing
  about:

    Splice one sealed file's data section under another's footer of the same
    byte length but different item boundaries -- an interrupted append and a
    retry, a restore taking the body from one snapshot and the tail from
    another. Every other gate passes. `nth items 16` returned the string
    \"gaaaa\", `nth 32` returned the record written at 32-9, and `decode-seq`
    over the same bytes was correct throughout, so two code paths in one
    application disagreed about the file.

  And it is CHEAP, which the container version never was. `want-end` is
  `data-end`, a constant already in the frame, and the walk from the last
  anchor is bounded by one stride because `m = 1 + (cnt-1)/stride` forces
  `remaining` into [1, stride]. For a container it was `(skip r c)` -- a walk
  of every item, the work the node exists to avoid, ~190 us on 769 maps of 20.
  That is the half that was expensive and the half that is gone.

  Zero anchors is honest only when the data section is empty: a node claiming
  no items short-circuits every other check, and `count` then returned 0 and
  `nth` nil over a file holding all 60 of its records."
  [^Reader r ptr cnt st ^longs a]
  (let [ptr (long ptr) cnt (long cnt) st (long st) m (alength a)]
    (and (or (zero? m) (zero? (aget a 0)))       ; a sequence starts at byte 0
         (loop [k 0 prev -1]                     ; anchors ascend, inside the data
           (if (>= k m)
             true
             (let [v (long (aget a k))]
               (if (and (> v prev) (<= 0 v) (< v ptr)) (recur (inc k) v) false))))
         (if (zero? m)
           (zero? ptr)
           (let [remaining (- cnt (* st (dec m)))]
             (and (pos? remaining)
                  (= ptr (loop [k 0 p (long (aget a (dec m)))]
                           (if (= k remaining) p (recur (inc k) (skip r p)))))))))))

(defn- payload-offsets
  "Byte offset of each of the frame payload's first five elements, decoding none
   of them.

   The frame is `27([\"boring/index\", [stride containers counts slots sorted
   end]])`, so this walks: past the tag, into the two-element array, past the
   name, into the payload, then element to element.

   Everything here is `headEndAt`/`skipFrom`: a head read and a jump per step,
   never a decode. It USED TO descend into `slots` as well and record where each
   of its elements began, because `slots` was one typed array per node and
   decoding them all to serve a lookup that visits one or two was half the cost
   of opening an index. `slots` is now a single byte string (`core/pack-slots`),
   so there is nothing to locate -- one bulk read gets all of it."
  [^Reader r ^long ptr]
  (let [pair (.headEndAt r ptr)                 ; ["boring/index", payload]
        nm (.headEndAt r pair)                  ; the name
        payload (.skipFrom r nm)                ; the six-element payload
        e0 (.headEndAt r payload)               ; stride
        e1 (.skipFrom r e0)                     ; containers
        e2 (.skipFrom r e1)                     ; counts
        e3 (.skipFrom r e2)                     ; slots
        e4 (.skipFrom r e3)]                    ; sorted
    [e0 e1 e2 e3 e4]))

(defn- anchor-count
  "How many anchors a container of `n` entries carries at `stride`. Must agree
  exactly with `boring.core/anchor-count` and `Writer.anchorCount` -- this is
  what replaces a stored per-node segment length."
  ^long [^long n ^long stride]
  (if (<= n 0) 0 (if (= stride 1) n (inc (quot (dec n) stride)))))

(defn- slot-le
  "The `w`-byte little-endian value at ABSOLUTE offset `p`.

  `byteAt` masks to 0-255 and bounds-checks against the Reader's limit, raising
  a TYPED `:boring/truncated-input` -- so every read in this family is inside
  the file or a typed error, never an unchecked access."
  ^long [^Reader r ^long p ^long w]
  (if (= w 2)
    (bit-or (.byteAt r p)
            (bit-shift-left (.byteAt r (+ p 1)) 8))
    (bit-or (.byteAt r p)
            (bit-shift-left (.byteAt r (+ p 1)) 8)
            (bit-shift-left (.byteAt r (+ p 2)) 16)
            (bit-shift-left (.byteAt r (+ p 3)) 24))))

(defn- width-code
  "Node `i`'s 2-bit width code, out of the table after the LAYOUT BYTE."
  ^long [^Reader r ^long slots-data ^long i]
  (bit-and 3 (bit-shift-right (.byteAt r (+ slots-data 1 (quot i 4)))
                              (* 2 (rem i 4)))))

(defn- slot-table
  "`[table-base entry-width]` for the packed slots at `slots-data`, or nil if it
  is not a layout this reader knows or its final entry disagrees with its own
  length. TABLE-BASE IS ABSOLUTE; the entries it holds are RELATIVE to
  `slots-data`, because that is what the writer put there.

  That mixture is the one place this file can go wrong by a whole byte string,
  so it is stated rather than inferred: `slot-start` returns a RELATIVE offset,
  the segment bound in `slot-at` compares it against `slots-len`, and only the
  delta reads add `slots-data` back.

  TWO READS, WHERE THIS USED TO BE A PREFIX SUM OVER EVERY NODE. `slots`
  carries a dense table of byte offsets -- one entry per node, plus a final
  entry holding the total -- so a node is reachable without walking to it, and
  the structural gate is that final entry against the byte string's own length
  rather than a sum that had to be run to be checked.

  The LAYOUT BYTE makes the previous shape refusable exactly. Without it a
  reader meeting a frame with no table would read a width code where the
  version is, compute nonsense offsets, and depend on the length check to
  notice -- which it would, about 65535 times in 65536."
  [^Reader r ^long slots-data ^long slots-len ^long n]
  (when (pos? slots-len)
    (let [b0 (.byteAt r slots-data)
          w (if (zero? (bit-and (bit-shift-right b0 4) 0x0F)) 2 4)
          tbase (+ 1 (quot (+ n 3) 4))
          entries (inc n)]
      (when (and (= (bit-and b0 0x0F) boring/slot-layout-v2)
                 (>= slots-len (+ tbase (* entries w)))
                 (= slots-len
                    (slot-le r (+ slots-data tbase (* (dec entries) w)) w)))
        [(+ slots-data tbase) w]))))

(defn- slot-start
  "Byte offset where node `i`'s deltas begin, RELATIVE to the packed slots.
  ONE READ.

  This walked up to 15 nodes from a sparse block start until the measurement
  said otherwise: on a 770-node frame a block-aligned node resolved in 0.073 us
  and one at the end of its block in 0.376 -- the walk cost ~0.30 us, five
  times the base. Every earlier number that made sparse look free had been
  taken on node 0, which is block-aligned, so the walk never ran.
  See `boring.core/slot-layout-v2`."
  ^long [^Reader r ^long tbase ^long w ^long i]
  (slot-le r (+ tbase (* i w)) w))

(defn- delta-at
  "The delta at ABSOLUTE offset `p`, at width code `w`, little-endian.

  u8 and u16 are UNSIGNED -- nothing here is a CBOR typed array, so the second
  tier is not forced signed the way tag 77 was, and 32 KiB..64 KiB deltas stay
  two bytes. i32 and i64 are signed, which is what makes a negative delta a
  representable absurdity rather than a silent wrap."
  ^long [^Reader r ^long p ^long w]
  (case (int w)
    0 (.byteAt r p)
    1 (bit-or (.byteAt r p)
              (bit-shift-left (.byteAt r (+ p 1)) 8))
    2 (long (unchecked-int
             (bit-or (.byteAt r p)
                     (bit-shift-left (.byteAt r (+ p 1)) 8)
                     (bit-shift-left (.byteAt r (+ p 2)) 16)
                     (bit-shift-left (.byteAt r (+ p 3)) 24))))
    (loop [j 0 acc 0]
      (if (= j 8)
        acc
        (recur (inc j)
               (bit-or acc (bit-shift-left (.byteAt r (+ p j)) (* 8 j))))))))

(defn- sorted-at?
  "Whether node `i`'s keys ascend, out of the `sorted` bitset.

  THE LENGTH EQUALITY IN `index-payload` IS WHAT MAKES THIS SAFE, and it became
  load-bearing when `sorted` stopped being a `byte[]`. An index one past the
  end used to be an AIOOBE the caller turned into a refusal; it is now a byte
  of the frame's own back-pointer, in the file, plausible, and wrong. A false
  TRUE sends `lookup-map` down the binary-search branch on unsorted keys, and
  `confirm` only re-derives negatives."
  [^Reader r ^long sorted-data ^long i]
  (not (zero? (bit-and (.byteAt r (+ sorted-data (quot i 8)))
                       (bit-shift-left 1 (rem i 8))))))

(defn- byte-string-at
  "Data offset and length of the DEFINITE-length byte string at `off`, or nil.

  The major check refuses the pre-v2 shape exactly: `slots` and `sorted` were a
  CBOR array of typed arrays and a CBOR array of booleans, and an array head
  answers `headArgAt`/`headEndAt` perfectly happily with an element count and a
  first-element offset."
  [^Reader r ^long off]
  (when (and (= 2 (.majorAt r off)) (not= 31 (.infoAt r off)))
    [(.headEndAt r off) (.headArgAt r off)]))

(defn- le-array-at
  "Data offset and byte length of the RFC 8746 little-endian typed array at
  `off`, or nil.

  Requires the tag AND that its payload is a DEFINITE-length byte string. The
  major check is not optional: without it the previous frame shape -- where this
  element was a CBOR array -- gets a `headEndAt` and a `headArgAt` that both
  succeed, returning the element COUNT and the first element's offset, and the
  reader goes on to compute node offsets from them. The refusal has to be exact,
  which is the whole compatibility argument for changing the layout.

  WHAT KEEPS THE RESULTING OFFSETS INSIDE THE FILE is not here, and is worth
  naming because nothing near the accessors says it. Neither length returned
  here is checked against the file. `count-at` and `container-at` are
  nevertheless in-file for every `i < n` because of TWO things elsewhere:
  `read-index*`'s `(= n (skip r ptr))` forces the whole frame to tile exactly
  to EOF, and `Reader.checkCount` refuses any byte-string length exceeding
  `remaining()`. That EOF check is documented where it lives purely as the
  stale-index defence -- the concatenated-sealed-batches bug -- and is now also
  the sole bound on two byte-offset accessors. Relaxing it would silently
  un-bound both."
  [^Reader r ^long off ^long tag]
  (when (and (= 6 (.majorAt r off)) (= tag (.headArgAt r off)))
    (let [bs (.headEndAt r off)]
      (when (and (= 2 (.majorAt r bs)) (not= 31 (.infoAt r bs)))
        [(.headEndAt r bs) (.headArgAt r bs)]))))

(defn- le-signed-at
  "The signed little-endian value of width `w` (4 or 8) at absolute offset `p`.

  RFC 8746's sint32 (tag 78) and sint64 (tag 79) are the two widths any of this
  frame's offset arrays can arrive at, and the CBOR tag IS the declaration, so
  this is a branch on a value that is constant for a whole document -- free
  after the first prediction.

  `Integer/reverseBytes` of the big-endian word, NOT four `byteAt` calls: fewer
  and wider accesses is the whole lever on this path, and on a memory-mapped
  source every `byteAt` is also an interface call into the segment. The 64-bit
  case needs two loads because `Reader` exposes no 64-bit accessor; masking the
  LOW word is what makes the assembly correct for negatives."
  ^long [^Reader r ^long p ^long w]
  (if (= 4 w)
    (long (Integer/reverseBytes (unchecked-int (.u32At r p))))
    (bit-or (bit-shift-left
             (long (Integer/reverseBytes (unchecked-int (.u32At r (+ p 4))))) 32)
            (bit-and (long (Integer/reverseBytes (unchecked-int (.u32At r p))))
                     0xFFFFFFFF))))

(defn- container-at
  "Byte offset of node `i`'s container, at whichever width the frame declared.

  The sign matters and is not incidental: the SEQUENCE node is stored at the
  sentinel offset -1, so a zero-extending read would turn it into 4294967295 at
  width 4 and the node would never be found at all."
  ^long [^Reader r ^Index ix ^long i]
  (le-signed-at r (+ (.containers-data ix) (* (.cw ix) i)) (.cw ix)))

(defn- count-at
  "Entry count of node `i`, at whichever width the frame declared.

  ACCEPTS SINT64 THOUGH NOTHING WRITES IT. `seal-index!` emits tag 78, and an
  entry count needs 64 bits only for a container of more than two billion
  entries -- which for the sequence node means a log of that many records, far
  off but not absurd. Teaching the reader the wider shape costs one predictable
  branch now; NOT teaching it means that when a writer eventually emits tag 79,
  every reader older than that change loses the index on those files. Refusing
  an index is safe, so this is compatibility rather than correctness -- but it
  is only free before the fact."
  ^long [^Reader r ^Index ix ^long i]
  (le-signed-at r (+ (.counts-data ix) (* (.nw ix) i)) (.nw ix)))

(defn- slot-at
  "Absolute anchors for node `i`, as arithmetic over the packed bytes.

  ONE EXPANDER. This delegated to an `expand-anchors` taking the eight pieces
  separately, because `index-payload` needed the SEQUENCE node's anchors while
  it was still assembling the `Index` that `slot-at` reads them from -- an old
  map could be built in two steps with `assoc`, a type cannot. It builds a
  PROVISIONAL `Index` instead now, so there is one entry point and the two
  cannot drift. They could: the offsets prototype written against this layout
  implemented a second expander that read the wrong node's segment for every
  node past 15, and agreed with this one on the fixture only because every
  node's delta bytes happened to be identical.

  NO CACHE. This memoized into an `AtomicReferenceArray` allocated per open,
  one slot per node in the document, to serve a lookup that touches one or two
  -- and the source it was protecting is read ONCE, which is every read of a
  store handing out a fresh blob. Expanding is a prefix sum over `m` deltas
  read in place; at the default stride `m` is 2.

  THE ANCHORS AND THEIR SEGMENT ARE BOUNDED HERE, and this is the only place
  left that checks anything about them. The index is trusted for whether its
  anchors are the RIGHT offsets; it is not trusted to keep them inside the
  file, because `Reader.skipFrom` does an unchecked array access and an anchor
  past the end reaches it as a raw AIOOBE at the caller of `get`.

  A typed throw rather than a nil, because there is no longer a validation
  layer above this to turn a bad node into `node-slot` -1 and a scan. The
  caller sees `:boring/bad-index`, which is what every other malformed-input
  path in this library raises."
  ^longs [^Reader r ^Index ix ^long i]
  (let [sdata (.slots-data ix)
        slen (.slots-len ix)
        stride (.stride ix)
        lim (.data-end ix)
        m (anchor-count (count-at r ix i) (long stride))
        w (width-code r sdata i)
        sz (bit-shift-left 1 w)
        ;; RELATIVE to `slots-data`, so the bound below compares against
        ;; `slots-len` and only the delta reads add the base back.
        start (slot-start r (.slots-tbase ix) (.slots-wstart ix) i)]
    ;; AND THE DELTA RUN ITSELF IS BOUNDED, which nothing states elsewhere.
    ;; `slot-table` checks the start table's LAST entry against the byte
    ;; string's length; it never checks that the entries ascend, so one damaged
    ;; start entry sends `delta-at` anywhere. That was survivable only by
    ;; accident: `node-sound?` wrapped the expansion in `(catch Exception _
    ;; false)`, so the AIOOBE became "unusable, scan". With the per-node
    ;; validation gone the exception escapes -- measured, a raw
    ;; ArrayIndexOutOfBoundsException reading index 65286 of an 11-byte array,
    ;; straight out of `get`.
    ;;
    ;; Stating it here makes the bound explicit and O(1) instead of implicit in
    ;; somebody else's catch, and it is the same check an offsets-based reader
    ;; will need when the JVM stops providing one for free.
    ;; CHECKED BEFORE ALLOCATED, and the order is the whole point. `m` comes
    ;; from `count-at`, which is a raw sint32 load off the wire that nothing
    ;; cross-checks against the container -- at stride 1 `anchor-count` returns
    ;; it verbatim, so one flipped bit in the counts array reaches 2^31-1 here.
    ;; Allocating first meant `(long-array m)` raised OutOfMemoryError, which is
    ;; an ERROR: it walks straight through `index-payload`'s and `read-index`'s
    ;; catches and out of `get`, `nth`, `count` and `items`. Reproduced from a
    ;; single bit flip in a blob boring itself wrote, and heap-independently
    ;; with a crafted `Integer/MAX_VALUE` count.
    ;;
    ;; After the check `m * sz <= alength packed`, and `sz >= 1`, so the array
    ;; is bounded by bytes that actually exist in the file.
    (when (or (neg? start) (> (+ start (* m sz)) slen))
      (throw (ex-info "boring: index slot segment outside the packed slots"
                      {:type :boring/bad-index :node i :start start
                       :anchors m :width sz :slots-length slen})))
    (let [a (long-array m)]
      (loop [k 0
             p (+ sdata start)
             acc (max 0 (container-at r ix i))]
        (when (< k m)
          ;; UNCHECKED, because Clojure's `+` on primitive longs THROWS on
          ;; overflow and `delta-at` at width code 3 returns an unconstrained
          ;; signed 64-bit value straight off the wire. A container base of 4
          ;; plus a stored delta of Long/MAX_VALUE raised `ArithmeticException:
          ;; long overflow` out of `get` -- untyped, on a frame that passed
          ;; every acceptance gate. The segment bound above says where the
          ;; bytes are, not what they say.
          ;;
          ;; Wrapping is exactly right here: the wrapped value is either
          ;; negative or beyond `data-end`, so the range check on the next line
          ;; -- which already existed -- turns it into `:boring/bad-index`.
          (let [v (unchecked-add acc (delta-at r p w))]
            (when (or (neg? v) (>= v lim))
              (throw (ex-info "boring: index anchor outside the data section"
                              {:type :boring/bad-index :node i :anchor k
                               :offset v :data-end lim})))
            (aset a k v)
            (recur (inc k) (+ p sz) v))))
      a)))

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

  THERE IS ONE PATH. This took a `trusted?` flag -- `:trust-index :trusted` --
  that skipped the O(NODE COUNT) checks, because they were not marginal: paid
  per SOURCE, and a document store opens one source per blob to do one lookup.
  Measured on 4096 scalars, the indexed lookup itself was 0.22 us against 24.97
  scanning, while opening the source and doing that one lookup came to 8.67 --
  nearly all of what the index saved, spent proving the index was well-formed.

  The dial is gone because the answer is the same at both ends now. The index
  is a TRUST BOUNDARY: we use it where we are willing to trust it, so nothing
  here re-derives whether its offsets are RIGHT. What is left is only what
  keeps reads inside the file, which is not a matter of trust --
  `Reader.skipFrom` does an unchecked array access, so an offset we never
  bounded arrives as a raw AIOOBE at the caller of `get`.

  `:trust-index :ignore` still means what it always did: do not use the index
  at all. `:trusted` is accepted and does nothing, which is deliberate -- a
  REMOVED option key silently no-ops (`boring.options/near-miss` passes unknown
  keys), and konserve-lmdb sets it."
  [^Reader r ^long ptr]
  (try
    ;; `frame-payload`, not `record-fields`: an unregistered tag-27 frame
    ;; decodes to an UnknownRecord when its payload is a map and a
    ;; TaggedLiteral otherwise, and this payload is a vector.
    (let [;; NO BUDGET DANCE. This saved and overrode the Reader's `maxDepth`
          ;; and `maxItems` around the reads below, and restored them in a
          ;; `finally`, because the frame is a fixed nested shape whose cost is
          ;; not a statement about the caller's data. Two real defects came from
          ;; not doing it: with `:max-depth 1` an indexed `[1]` failed to decode
          ;; its own index, forgot it, walked into the frame as data and raised;
          ;; and a 500-record log overran an item budget generous for its data,
          ;; lost `:data-end` with the payload, and reported 501 items for 500.
          ;;
          ;; BOTH OVERRIDES ARE NOW DEAD, and structurally rather than by luck.
          ;; `Reader.readFrom` saves and CLEARS `items` on entry, so each
          ;; element below is read against a fresh budget; and since the v2
          ;; layout every element is ONE item -- `sorted` used to be a CBOR
          ;; array of one boolean per node, and 770 booleans inside a single
          ;; `readFrom` is what overran the budget. Depth likewise: each element
          ;; is read AT ITS OWN OFFSET, so the frame's own nesting is never
          ;; charged, and the deepest element is a tag around a byte string.
          ;;
          ;; Verified by disabling both overrides and running the whole suite,
          ;; which stayed green including `a-valid-index-never-makes-reading-fail`
          ;; at `:max-depth` 1. `a-valid-index-never-makes-counting-wrong` pins
          ;; the item half independently of whether the override exists.
          ;;
          ;; The `finally` was also attached to the wrong form: it wrapped only
          ;; the `.readFrom` vector, so `payload-offsets` ran BEFORE the override
          ;; and `slot-table` ran AFTER the restore. Invisible, and a trap to
          ;; preserve blindly.
          [off-stride off-containers off-counts off-slots off-sorted]
          (try (payload-offsets r ptr)
               (catch Exception _ [nil nil nil nil nil]))
          ;; STRIDE POSITIONALLY, which removes the last `.readFrom` from this
          ;; function. A uint under 2^63; a negint is major 1, a bignum major 6,
          ;; a float major 7, and a uint64 above 2^63 comes back negative -- so
          ;; the `pos?` below refuses all of them, exactly as `(int? stride)`
          ;; and `(pos? stride)` did together.
          stride (when (and off-stride
                            (zero? (.majorAt r (long off-stride)))
                            (< (.infoAt r (long off-stride)) 28))
                   (.headArgAt r (long off-stride)))
          [slots-data slots-len] (when off-slots (byte-string-at r (long off-slots)))
          [sorted-data sorted-len] (when off-sorted (byte-string-at r (long off-sorted)))
          ;; CONTAINERS AT EITHER WIDTH, chosen by the tag rather than tested
          ;; for. Tag 78 is int32, tag 79 sint64; both are RFC 8746
          ;; little-endian, so entry `i` is `cw` bytes at `containers-data +
          ;; cw*i`. The `long[]` normalisation this replaces cost 1.21 us per
          ;; open on a 770-node frame -- more than the decode it normalised.
          [containers-data containers-len cw]
          (when off-containers
            (or (when-let [[d l] (le-array-at r (long off-containers) 78)] [d l 4])
                (when-let [[d l] (le-array-at r (long off-containers) 79)] [d l 8])))
          ;; COUNTS IS NOT DECODED. It is a tag-78 typed array, so its entries
          ;; are four little-endian bytes each and `count-at` loads one on
          ;; demand. Materialising it cost 1.07 us per open on a 770-node frame.
          ;;
          ;; Tag 78 OR 79, and `n` follows from whichever width was declared.
          ;; Nothing writes 79 -- see `count-at` for why the reader knows it
          ;; anyway. Still narrower than `.readFrom` was: that also
          ;; yielded int arrays for tags 66, 70, 71, 74 and 75, which are now
          ;; refused -- safe, since refused means scan, but a real change rather
          ;; than a tidy-up. Note 70 and 71 are LITTLE-endian uint32/uint64
          ;; (RFC 8746); only 66, 74 and 75 are big-endian. So tag 70 has the
          ;; IDENTICAL byte layout to 78 and differs only in signedness, which
          ;; makes it the cheapest widening available if one is ever wanted.
          [counts-data counts-len nw]
          (when off-counts
            (or (when-let [[d l] (le-array-at r (long off-counts) 78)] [d l 4])
                (when-let [[d l] (le-array-at r (long off-counts) 79)] [d l 8])))
          ;; `readTypedArray` used to throw on a byte length that is not a
          ;; multiple of the element width; `quot` truncates instead. Without
          ;; this a 3081-byte counts string would yield n=770, agree with a
          ;; 770-entry `containers`, and be ACCEPTED where it is refused today.
          n (when (and counts-len (zero? (rem (long counts-len) (long nw))))
              (quot (long counts-len) (long nw)))
          ;; THE FINAL TABLE ENTRY IS THE STRUCTURE CHECK, and it is now one
          ;; read rather than a prefix sum over every node. `slot-table` returns
          ;; nil unless the layout byte is one this reader writes AND the last
          ;; entry equals the byte string's own length -- so a frame that
          ;; decodes but whose parts disagree is refused here rather than at
          ;; whichever node a lookup happens to visit. Guarded on the types it
          ;; indexes with, because it runs BEFORE the checks below.
          table (when (and slots-data n)
                  (try (slot-table r (long slots-data) (long slots-len) (long n))
                       (catch Exception _ nil)))]
      (when (and stride (pos? (long stride)) containers-data n
                 ;; A BYTE STRING WHERE AN ARRAY USED TO BE. This is what makes
                 ;; the layout change safe in both directions: a frame written
                 ;; by an older writer arrives as a Clojure vector of typed
                 ;; arrays, fails here, and the caller scans. It cannot be read
                 ;; wrongly, only not read.
                 slots-data sorted-data table
                 ;; STRUCTURE, not just decodability. Detection proves something
                 ;; MEANT to be an index; none of it proves the payload hangs
                 ;; together. A frame that decodes but whose parts disagree used
                 ;; to be trusted, and produced raw IndexOutOfBoundsException at
                 ;; the caller of `get`, or -- worse -- a wrong subtree.
                 ;; STAYS UNCONDITIONAL even when trusted. Disagreeing lengths
                 ;; are the one frame fault that costs O(1) to detect and would
                 ;; otherwise surface as a raw IndexOutOfBoundsException from
                 ;; inside `get` rather than as "no usable index, scan instead".
                 ;; Same ragged-length trap as `counts`: `quot` truncates.
                 (zero? (rem (long containers-len) (long cw)))
                 (= (quot (long containers-len) (long cw)) (long n))
                 ;; `sorted` is a bit per node, so its byte length is exact --
                 ;; `(quot (+ n 7) 8)` -- and an equality rather than a bound.
                 ;; AND THIS ONE IS NOW LOAD-BEARING, not merely tidy. While
                 ;; `sorted` was a `byte[]`, an index one past its end was an
                 ;; AIOOBE the caller turned into "unusable". As an offset it is
                 ;; a byte of the frame's own back-pointer: in the file,
                 ;; plausible, and wrong. See `sorted-at?`.
                 (= (long sorted-len) (quot (+ (long n) 7) 8))
                 )
        ;; ONE PROVISIONAL INDEX, built as soon as every offset is known, and
        ;; used for the three things that still need to read `containers`: the
        ;; ascending check, the sentinel scan, and the sequence node's anchors.
        ;; It IS the final Index whenever there is no sequence node, which is
        ;; every `encode-indexed` blob, so that case allocates exactly one.
        (let [st (long stride)
              ix0 (Index. st (long containers-data) (long cw)
                          (long counts-data) (long nw) (long n)
                          (long slots-data) (long slots-len)
                          (long (nth table 0)) (long (nth table 1))
                          (long sorted-data) ptr nil nil)]
          ;; `node-slot` BINARY-SEARCHES containers, so they must ascend.
          ;; O(NODE COUNT), and the only per-node work left at open -- kept
          ;; because a search over an unordered array does not merely miss, it
          ;; can settle on a node belonging to a different container and hand
          ;; its anchors to this one. The counts check that used to sit beside
          ;; it is gone: a negative count cannot reach past `slot-at`, which
          ;; bounds its own segment and its own anchors.
          ;; ONE read per node, carrying the predecessor. Reading both ends of
          ;; each comparison doubled it, and off the wire that is not free the
          ;; way it was off a `long[]`: this is now the only O(NODE COUNT) work
          ;; left at open, measured at ~0.8 us on 770 nodes against 0.13 when
          ;; `containers` was materialised.
          ;; `(pos? n)` guards the loop INIT, not just its body: a zero-node
          ;; index is accepted, and `(container-at r ix0 0)` on one would read
          ;; `cw` bytes at `containers-data` -- which for a zero-length
          ;; containers array is the NEXT payload element, not containers. It
          ;; cannot leave the file and cannot throw, but it is a read the array
          ;; does not own.
          (when (or (zero? (long n))
                    (loop [k 1 prev (container-at r ix0 0)]
                      (if (>= k (long n))
                        true
                        (let [c (container-at r ix0 k)]
                          (if (>= prev c) false (recur (inc k) c))))))
            ;; One uniform node list. The SEQUENCE is the node at the sentinel
            ;; offset -1: it has no container header on the wire but behaves
            ;; like one, and a sentinel avoids carrying two shapes for the same
            ;; idea. Containers ascend strictly and -1 is the minimum, so if it
            ;; is present at all it is node 0 -- which the scan this replaces
            ;; established by walking every node.
            (let [seq-slot (when (and (pos? (long n)) (neg? (container-at r ix0 0))) 0)
                  ;; THE SEQUENCE NODE STAYS EAGER, alone. `count` and `nth`
                  ;; consult it immediately, a wrong `:total` is the
                  ;; silent-corruption case, and it is a single node rather than
                  ;; one per container. A sequence is also the shape whose
                  ;; source is reused, so there is nothing to amortise away.
                  seq-anchors (when seq-slot (slot-at r ix0 (long seq-slot)))
                  ;; The one validation left, and it is about which FILE this
                  ;; is, not about whether the bytes were corrupted -- see
                  ;; `seq-node-ok?`. O(stride), on one node.
                  seq-ok? (or (nil? seq-slot)
                              (seq-node-ok? r ptr (count-at r ix0 (long seq-slot))
                                            st seq-anchors))]
              (when seq-ok?
                (if seq-slot
                  (Index. st (long containers-data) (long cw)
                          (long counts-data) (long nw) (long n)
                          (long slots-data) (long slots-len)
                          (long (nth table 0)) (long (nth table 1))
                          (long sorted-data) ptr
                          (count-at r ix0 (long seq-slot))
                          seq-anchors)
                  ix0)))))))
    ;; THROWABLE, not Exception. This is the one place the reader promises
    ;; "any failure parsing the frame means scan", and an `Error` walked
    ;; straight through it: `slot-at` allocating from an unvalidated on-wire
    ;; count raised OutOfMemoryError out of `get`. That specific case is fixed
    ;; at its source, but the promise here should not depend on having found
    ;; every way to raise an Error while parsing bytes somebody else wrote.
    (catch Throwable _ nil)))

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
    ;; Throwable for the same reason as `index-payload` -- see there.
    (catch Throwable _ nil)))

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
                       (.bytesEqualAt r ptr frame/prefix-head-array)
                       ;; The payload's array head separately, because six
                       ;; through fifteen elements all count as a frame -- see
                       ;; `frame/payload-count-bytes`. Only the first six are
                       ;; read; the rest are skipped by `payload-offsets`,
                       ;; which never visits them.
                       (contains? frame/payload-count-bytes
                                  (.byteAt r (+ ptr frame/prefix-head-length)))
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
              ;; `or`, not `merge`. A usable payload ALREADY carries
              ;; `:data-end ptr` -- it is set on `ix` and there is one non-nil
              ;; return path -- so the merge rebuilt a 15-key map to overwrite
              ;; one key with the value it already held. Measured at 1.70 us
              ;; against the 1.36 that parsing the whole payload cost: the most
              ;; expensive single step in opening an index was a no-op.
              ;;
              ;; The nil branch is the one that meant anything, and it still
              ;; does: detection has succeeded by here, so an UNUSABLE payload
              ;; must still yield `:data-end` or `items` walks past the data
              ;; section and republishes the footer as a data item.
              (or (index-payload r ptr)
                  ;; DETECTED BUT REFUSED: an Index carrying only `data-end`.
                  ;; `n` is -1, the "no nodes" sentinel, and every other
                  ;; primitive is 0. THERE IS NO NIL IN THIS CALL AND THERE
                  ;; CANNOT BE: the fields are primitive, so a nil here is an
                  ;; NPE that the try in `read-index` swallows -- which loses
                  ;; `:data-end` and makes `items` republish the footer as a
                  ;; data item, N becoming N+1. That is exactly the defect this
                  ;; branch exists to prevent, reintroduced by its own
                  ;; constructor.
                  (Index. 0 0 0 0 0 -1 0 0 0 0 0 ptr nil nil)))))))))

(deftype Items [^Nav nav ^Index idx]
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
    (if-let [t (when idx (.total idx))]
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
          ;; `idx` is nil for a sequence with no usable index at all, which is
          ;; why this is a guard and not a field read.
          end (if idx (.data-end idx) (.size r))]
      ;; `:offsets`, not `idx`. An index can exist and carry NO sequence node:
      ;; only `write-seq!` emits the sentinel -1 node, so `encode-indexed`, and
      ;; `build-index` + `seal-index!` over a file somebody else wrote, both
      ;; produce one where `:offsets` and `:total` are nil. Testing `idx`
      ;; destructured `total` as nil and compared it, so `nth` threw an NPE --
      ;; on the 3-arity not-found form too, leaving no safe way to call it,
      ;; while `seq` and `reduce` on the same object worked.
      (if-let [^longs offsets (when idx (.offsets idx))]
        ;; O(1) to the anchor, then at most stride-1 skips.
        (let [stride (.stride idx)
              total (long (.total idx))]
          (if (or (neg? i) (>= i total))
            nf
            ;; THE ANCHOR IS TRUSTED, as everywhere else in this namespace. It
            ;; was verified here against its neighbour, cached per anchor,
            ;; because one changed delta byte moved anchors 2 and 3 of a
            ;; 60-item file and `nth 32` returned -1768167461 where `decode-seq`
            ;; returned the record. That is still true; it is now the
            ;; documented consequence of trusting the frame rather than a
            ;; defect. See this namespace's docstring.
            (let [anchor (quot (long i) stride)]
              (loop [k (* anchor stride) p (long (aget offsets anchor))]
                (if (= k (long i)) (cursor-at nav p) (recur (inc k) (skip r p)))))))
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
          end (if idx (.data-end idx) (.size r))]
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
   ;; `container?`, not a `value-type` test. `value-type` reports `:tag` for a
   ;; shaped array, a record and a typed array -- deliberately, since that is
   ;; what they ARE -- so a zipper could not descend into any of them while
   ;; `count`, `seq`, `get`, `nth` and `reduce` all could. Before descents
   ;; existed the two agreed, because every tag was opaque everywhere.
   (fn branch? [^Cursor x] (container? x))
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
