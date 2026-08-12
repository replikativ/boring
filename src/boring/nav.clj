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

  1. A stringref document is navigable IF ITS INDEX CARRIES THE POINTER TABLE.
     This paragraph used to say the opposite -- that `:stringref false` was
     required and that navigating such a document was refused outright -- which
     was true, and was the first thing anyone read about this namespace, until
     the frame gained the table.

     A stringref is an index into a table built from every preceding string, so
     a cursor holding nothing but an offset cannot rebuild it. The index frame
     therefore carries the DEFINING OFFSET of every slot something actually
     references, and a reference resolves by JUMPING to where the string was
     written. `encode-indexed` and `write-indexed!` emit that table by default;
     what is still refused, by `check-stringref-navigable!`, is a document that
     opens a namespace and carries NO table -- one that was never indexed.

     So `(nav/root (boring/encode-indexed v))` works on the default profile,
     and is 35% smaller than the same document without stringref. A file that
     was only `encode`d, with no index, still cannot be navigated: decode it
     whole, or seal an index onto it.

     A SEQUENCE IS THE EXCEPTION. `write-seq!` forces `:stringref false` at
     every stride, because each top-level item restarts the namespace at index
     0 and one frame carries one table -- see its docstring.

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

(declare ->Cursor cursor cursor-at nav-of-items items? read-index read-index* tag-view
         nth-item realize lookup-map head-count child-offsets)

;; A shaped array is `39649([keys, [row, row, ...]])`, where each row is an
;; ARRAY of values positionally matching `keys`. It REALISES to a vector of
;; maps, so a cursor on one has to present that shape without building it --
;; see `shaped-view`.
(def ^:private ^:const TAG-SHAPED-ARRAY 39649)

(defn- fail [type msg data]
  (throw (ex-info msg (assoc data :type type))))

;; ---------------------------------------------------------------- the source

(declare slot-at anchor-at sorted-at? container-at count-at check-stringref-navigable!
         byte-string-at root-cursor reader-root-offset)

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
;; `ILookup` so `(:containers ix)` keeps working, which is how
;; `index_robustness_test` asks whether a frame was ACCEPTED, and `:offsets`
;; whether a sequence node is present. Those two are the live reach-ins; the
;; list here used to name `:slots`, `:sorted` and `:node-checked` as well, and
;; all three had moved on -- `:node-checked` is not served at all, and the
;; other two are read off the WIRE by those tests now, not off this type.
;;
;; The hot paths use direct field access; `valAt` is for tests and debugging.
(deftype ^:no-doc Index
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
(deftype ^:no-doc Nav [^Reader rdr opts probes idx src views]
  ;; ILookup ONLY IN ORDER TO REFUSE. A source is not a value and implements no
  ;; collection interface -- but `clojure.core/get` answers nil for anything
  ;; that is not ILookup, so `(get (source bs) :k)` would have been SILENTLY
  ;; nil rather than an error. That is precisely the failure the split exists
  ;; to prevent: `source` used to return a root cursor, so that call is what
  ;; every existing caller has written.
  ;;
  ;; Throwing here makes the break loud at the first lookup instead of turning
  ;; a working projection into one that finds nothing. Found by the test that
  ;; asserts the break is loud, which failed on exactly this line.
  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [_ k _]
    (fail :boring/not-a-cursor
          (str "boring.nav: a source is not a cursor -- `get` needs a position "
               "inside the document, not the document. Use (nav/root s), which "
               "is what (nav/source ...) returned before the two were split.")
          {:key k})))

;; `src` is a VOLATILE, not the bytes: `re-point!` swaps what a reusable source
;; reads, and `fork-nav` builds its fresh Reader from `src` -- so a fork taken
;; after a re-point has to see the CURRENT bytes, not the ones the source was
;; created with.

(defn- nav-idx
  "The decoded index, parsed on first use.

  `idx` holds a VOLATILE, not the index -- see below. Parsing the frame is the
  expensive part
  -- measured 8.5 us of the 11.0 us a per-call indexed lookup took, against
  2.5 us once the source is reused -- and a caller that only touches a
  top-level key never needs it. A store hands out a fresh blob per read and so
  constructs a source per read, which made an index cost more than it saved:
  an indexed binary search ran 11.0 us per call against 2.5 us reused, and a
  shallow `:meta` lookup went from 0.95 us unindexed to 7.5 us indexed.

  Deferring it makes an index free for the lookups that do not consult it, and
  unchanged for the ones that do.

  A VOLATILE HOLDING A SENTINEL, not a `delay`. A `Delay`, plus the `volatile`
  holder its closure needed in order to reach the Nav, cost 112 bytes of the
  360 a source allocates -- 27% of it -- and were paid on EVERY source,
  including every document with no index frame at all, which is the common case
  for a store handing out small blobs. This is 16.

  The holder is gone because it was never necessary: `nav-idx` is handed the
  Nav, so nothing has to close over a reference to it.

  THREAD SAFETY IS UNCHANGED IN THE WAY THAT MATTERS, and the difference is
  worth naming. `Delay` is synchronized, so two threads racing here both got
  one parse; now both may parse and one wins. That is wasteful, not wrong --
  the parse is deterministic over immutable bytes and yields an immutable
  Index. And a Nav shared across threads without `fork` is ALREADY the
  documented hazard: `Reader` carries a mutable `pos`, which is a far worse
  race than a duplicated parse. `fork-nav` forces this before sharing, so
  forks are unaffected."
  [^Nav n]
  (when-let [v (.idx n)]
    (let [x @v]
      (if (identical? ::unparsed x)
        (let [parsed (read-index n)]
          (vreset! v parsed)
          parsed)
        x))))

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
              ;; MATCHED, NOT VALIDATED. The node is accepted only on an
              ;; exact offset match, which is what keeps a mis-ordered
              ;; `containers` from handing this container another one's
              ;; anchors: a search that cannot find its node returns -1, and
              ;; -1 means walk. Nothing here checks the node against the DATA
              ;; -- per-node validation was removed, see doc/INDEX.md.
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
  profile -- so byte equality is value equality.

  ALREADY-ENCODED BYTES PASS THROUGH, which is what lets `field-offset` take a
  probe made once by `probe` and reused across a whole scan. The cache lookup
  below is cheap but not free, and on the offset path cheap is the entire
  budget: measured, `field-offset` with a keyword allocates 32 bytes a call and
  with a probe allocates none."
  ^bytes [^Nav nav k]
  (if (bytes? k)
    k
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
            bs)))))

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
(deftype ^:no-doc NavContext [opts probes ^objects cfg])

(defn context
  "A reusable navigation context for `opts`, to be passed to `source` in place
  of the options map when opening MANY sources with the same options.

  Sources opened through one context share the encoded-key cache, so a path's
  keys are encoded once for the whole scan rather than once per document. That
  is worth having: on 229-byte blobs, encoding the keys of a four-step path cost
  0.711 us per document against 0.169 us for the navigation it enabled.

    (let [ctx (nav/context {:stringref false})]
      (doseq [blob blobs]
        (nav/value (get-in (nav/root blob ctx) path))))

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
            (fail :boring/bad-argument
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
    ;; A VOLATILE HOLDING A SENTINEL. Detection is deferred with the parse: a
    ;; document with no frame costs nothing to find that out, and one with a
    ;; frame pays only if something consults it. See `nav-idx` for why this is
    ;; not a `delay` any more -- 96 bytes per source, on every source.
    ;; `:trust-index :ignore` STILL DETECTS THE FRAME. It used to leave `.idx`
    ;; nil, which conflated the two questions `read-index*` is careful to keep
    ;; apart: "where does the data end" and "are these anchors usable". With no
    ;; Index at all there is no `data-end`, so `Items` walked to the end of the
    ;; BUFFER and republished the footer as a data item -- `nav/items` reporting
    ;; 41 where `decode-seq` reported 40, through the very option
    ;; doc/SECURITY.md recommends for untrusted input. `read-index*` refuses the
    ;; nodes instead, which is the shape it already had for a detected-but-
    ;; unusable payload.
    (doto (Nav. r opts probes (volatile! ::unparsed)
                (volatile! src) (volatile! nil))
      (check-stringref-navigable! r))))

(defn- check-stringref-navigable!
  "Refuse a stringref document that carries no pointer table.

  A cursor holds an offset, and a stringref is an index into a table built by
  decoding every preceding string -- so without help it cannot resolve one, and
  this used to be a flat refusal for every document opening a namespace. The
  help is the index frame's pointer table: with it, a reference resolves by
  jumping to where the string was written. So the question is no longer \"does
  this document use stringrefs\" but \"can this one's references be resolved\".

  THE INDEX PARSE IS FORCED HERE, against the laziness `nav-idx` exists for,
  and only for documents that open a namespace. Those were refused outright
  until now, so the open they pay is not a regression on anything -- and a
  document without a namespace, which is every `:index`-written file today,
  never reaches this and stays lazy.

  A NAMESPACE THAT WAS NEVER USED IS NOT A PROBLEM, and this refused one for a
  while. The default profile opens a namespace before it knows the content, so
  `(encode-indexed (vec (range 40)))` -- forty integers and not one string --
  came back unnavigable. The fix is on the WRITER's side, not here: an indexed
  write seals a frame whenever it opens a namespace, and the pointer table is
  emitted even when empty. So `hasStringrefPointers` distinguishes \"no table\"
  from \"a table with no entries\", and the refusal below keeps its exact
  meaning -- this document was never indexed, so nothing can resolve a
  reference in it.

  Deciding it HERE instead, by accepting a missing table and letting the reader
  raise at the reference, was tried and is worse. It cannot tell \"referenced
  nothing\" from \"references something unresolvable\", so it has to accept both
  -- which moves the failure for an unindexed stringref document from
  `nav/source` to somewhere deep in a walk. `mmap-source` over millions of rows
  is the wrong place to learn the file cannot be navigated at all.

  `:trust-index :ignore` has no Index at all, so it refuses -- correctly:
  ignoring the index means ignoring the only thing that could resolve a
  reference."
  [^Nav nav ^Reader r]
  (when (.hasStringrefRoot r)
    (nav-idx nav)
    (when-not (.hasStringrefPointers r)
      ;; TWO CAUSES, TWO REMEDIES. "Seal it with an index" is the right advice
      ;; for a document that simply has no frame, and NONSENSE for a caller who
      ;; asked to ignore the one it has -- which is the case doc/SECURITY.md
      ;; recommends for untrusted input, so it is the one most likely to be hit
      ;; by someone following the documentation.
      (let [ignoring? (= :ignore (:trust-index (.opts nav)))]
        (fail :boring/stringref-not-navigable
              (if ignoring?
                (str "boring.nav: {:trust-index :ignore} cannot navigate a "
                     "stringref document. Ignoring the index means ignoring its "
                     "pointer table, which is the only thing that can resolve a "
                     "reference from an offset. Re-encode with {:stringref "
                     "false} and navigate that, or read it with boring/decode, "
                     "which builds the table itself and consults no index.")
                (str "boring.nav: this document opens a stringref namespace and "
                     "its index carries no stringref pointer table, so a cursor "
                     "holding only an offset cannot resolve a reference. Seal it "
                     "with an index, re-encode with {:stringref false}, or "
                     "decode it whole with boring/decode."))
              {:trust-index (:trust-index (.opts nav))})))))

(defn- fork-nav ^Nav [^Nav n]
  (let [src @(.src n)
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
    ;; THE POINTER TABLE IS READER STATE, AND THIS READER IS NEW. Sharing the
    ;; realised Index is what makes a fork cheap, but the stringref table does
    ;; NOT live on the Index -- #12 put it on the Reader deliberately -- so a
    ;; fork that inherited only the Index had no table, and every reference in
    ;; it raised `:boring/bad-stringref` while the parent read the same offsets
    ;; fine. `fork` is the documented way to read one source from several
    ;; threads, so that failed every parallel reader over a stringref document.
    ;;
    ;; Such a fork re-parses instead of inheriting, because parsing is what
    ;; installs the table -- and it is the only way to get one onto this Reader
    ;; without a second copy of the frame-location logic to drift from the
    ;; first. It costs the index parse the sharing exists to save, but only for
    ;; documents that open a namespace; every other fork still inherits.
    (let [ix (if (.hasStringrefRoot r) ::unparsed (nav-idx n))
          fork (Nav. r (.opts n) (atom {}) (volatile! ix) (volatile! src) (volatile! nil))]
      ;; FORCED, not left lazy. Marking the index unparsed is not enough on its
      ;; own: `nav-idx` runs only when something consults the index, and
      ;; `value`/`realize` go straight to `Reader.readFrom` without ever asking
      ;; -- so the fork read references with no table installed and raised
      ;; `stringref outside any tag-256 namespace`. `source` has the same
      ;; problem and solves it the same way.
      (check-stringref-navigable! fork r)
      fork)))

(defn source
  "A SOURCE over `src` -- a byte[], or a ByteSource such as
  `boring.mmap/mmap-source` gives. NOT a cursor: see `root`.

  `opts` are the decode options realisation will use (`:registry` and friends),
  and must describe how the document was WRITTEN.

  A STRINGREF DOCUMENT NEEDS AN INDEX to be navigable -- the pointer table in
  the frame is the only thing that can resolve a reference from an offset -- so
  one without a frame is refused here rather than answered wrongly. That is a
  narrower rule than the `:stringref false` this used to demand, and it pointed
  at a paragraph of the namespace docstring that now exists to say so.

  ADDRESSES THE FIRST ITEM ONLY. A log or stream is usually a CBOR sequence
  (RFC 8742) -- many top-level items concatenated, which is what `write-to!` in
  a loop produces -- and a cursor from here would navigate only the first of
  them and silently ignore the rest. Use `items` for that. This is not an error
  case, because a caller may legitimately navigate a value sitting in an
  oversized scratch buffer.

  BREAKING: THIS RETURNS A SOURCE, NOT A ROOT CURSOR. `(nav/root bs opts)` is
  what this used to be, and is what you want for `get`/`seq`/`walk`. A source
  is the DOCUMENT -- the bytes, the reader over them, the index slot and the
  shape cache -- and it deliberately implements nothing: no `ILookup`, no
  `Seqable`, no `Counted`. It is a handle, not a value, and giving it
  collection interfaces is exactly how it would turn back into a cursor.

  So the break is LOUD: `(get (source bs) :k)` throws rather than quietly
  answering nil.

  The reason to have the name at all is the offset layer. Every offset
  function takes `(source, offset)`, so there is exactly one place a position
  can come from and it is the argument you passed. Before the split those
  functions took a cursor and IGNORED its offset -- `(field-offset
  cursor-at-500 0 probe)` was well-typed and meaningless, because the cursor
  was standing in for a document that had no name."
  ([src] (source src nil))
  ([src opts] (nav-of src (or opts {}))))

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

(def ^:private ^:const MAJOR-SIMPLE 7)
(def ^:private ^:const INFO-UNDEFINED 23)

(defn- undefined-at?
  "Whether the item at `off` is CBOR `undefined` -- simple value 23, the byte
  0xf7.

  ONLY MEANINGFUL INSIDE A SHAPED ROW, where it marks an absent key. Everywhere
  else `undefined` is an ordinary value and realises to
  `boring.data/undefined`; nothing outside the shaped-row paths calls this.

  Two byte reads and no decode, because it sits in the lookup path of every
  shaped-row `get`."
  [^Nav nav ^long off]
  (let [^Reader r (.rdr nav)]
    (and (= MAJOR-SIMPLE (.majorAt r off))
         (= INFO-UNDEFINED (.infoAt r off)))))

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

(defn- stringref-key-matches?
  "Whether the REFERENCE key at `p` names the same bytes as `probe`.

  `bytesEqualAt` is a memcmp against an encoded probe, so `d8 19 05` never
  equals an encoded `:profile` -- and on konserve-shaped data that is 199 key
  occurrences in 200, every one of them a present key reported absent.

  Rather than compile the probe into its reference form, which needs a
  string-to-index pass over the pointer table per document, the comparison
  moves: the table gives the DEFINING LITERAL's offset, and the literal is
  byte-identical to what the probe encodes. Same memcmp, different offset, no
  per-document state and nothing decoded.

  The two shapes differ by what the reference carries that the literal does
  not. A bare `tag 25` stands for the string itself, so the whole probe is
  compared. A repeated keyword is `tag 39` wrapping the reference, and the
  defining literal is the TEXT alone -- `boring.core/pack-stringrefs` records
  where the text starts, not where its tag-39 wrapper does -- so the probe is
  compared past its own two-byte `d8 27`.

  False whenever anything is unavailable: no table, no pointer for that index,
  a damaged offset. A miss here is answered by the ordinary walk, and a key
  that really is absent must not become an exception."
  [^Reader r ^long p ^bytes probe]
  (and (.hasStringrefPointers r)
       (= MAJOR-TAG (.majorAt r p))
       (let [a (.headArgAt r p)]
         (cond
           (= 25 a)
           (let [off (.stringrefOffsetFor r (.headArgAt r (.headEndAt r p)))]
             (and (nat-int? off) (.bytesEqualAtFrom r (long off) probe 0)))

           (= 39 a)
           (let [q (.headEndAt r p)]
             (and (= MAJOR-TAG (.majorAt r q))
                  (= 25 (.headArgAt r q))
                  ;; THE PROBE MUST ITSELF BE A TAG-39 ENCODING. Skipping two
                  ;; bytes of a probe that never had a `d8 27` compares the
                  ;; WRONG SUFFIX, and with a three-byte probe that is a
                  ;; one-byte comparison against the literal's text head -- so
                  ;; the integer key 365, encoded `19 01 6d`, matched every
                  ;; keyword whose head byte is 0x6d and `(get row 365)`
                  ;; returned the value of `:key-number-5`. A sweep of 0..1999
                  ;; against one 40-key map produced 14 such phantom hits.
                  ;;
                  ;; The length test alone did not catch it: `> 2` admits
                  ;; exactly the three-byte probes that are most dangerous here.
                  (> (alength probe) 2)
                  (= 0xd8 (bit-and (aget probe 0) 0xff))
                  (= 0x27 (bit-and (aget probe 1) 0xff))
                  (let [off (.stringrefOffsetFor r (.headArgAt r (.headEndAt r q)))]
                    (and (nat-int? off) (.bytesEqualAtFrom r (long off) probe 2)))))

           :else false))))

(defn- uint-bytes
  "`v` as a CBOR unsigned head -- the argument a tag 25 carries."
  ^bytes [^long v]
  (cond
    (< v 24) (byte-array [(unchecked-byte v)])
    (< v 0x100) (byte-array [(unchecked-byte 0x18) (unchecked-byte v)])
    (< v 0x10000) (byte-array [(unchecked-byte 0x19)
                               (unchecked-byte (bit-shift-right v 8))
                               (unchecked-byte v)])
    :else (byte-array [(unchecked-byte 0x1a)
                       (unchecked-byte (bit-shift-right v 24))
                       (unchecked-byte (bit-shift-right v 16))
                       (unchecked-byte (bit-shift-right v 8))
                       (unchecked-byte v)])))

(defn- reference-probe
  "`probe` re-encoded as the stringref REFERENCE it would have been written as
  in this document, or nil if this document never registered it.

  WHY A SECOND PROBE EXISTS AT ALL. `stringref-key-matches?` compares a
  reference key by moving the comparison to the defining literal, which is
  enough for EQUALITY and is all a linear scan needs. A binary search needs
  ORDER, and order is the one thing that move destroys: reference-keyed anchors
  ascend by reference INDEX -- assigned in first-occurrence order -- and the
  literals they resolve to are in no order at all (measured on a 40-key
  document: 19 of 39 adjacent pairs descend). Searching under the resolved
  comparator is unsound, not merely slow. So the probe moves instead, and the
  comparison stays in the wire space the `sorted` bit is actually about.

  BOTH FORMS ARE TRIED BY THE CALLER, because one container can hold both. A
  key repeated from an earlier row is a reference; a key first seen in THIS row
  is a literal; a row with some of each is still bytewise sorted, since a
  literal's `0x6X` head sorts before a reference's `0xd8`. No single encoding
  compares against all of those anchors -- a single-form search measured 10 of
  20 wrong on such a container. Two searches over one bytewise-sorted array
  are sound and each still O(log m).

  The two shapes mirror `stringref-key?`. A repeated KEYWORD is `tag 39`
  wrapping `tag 25`, and its pointer names the TEXT alone -- so the lookup
  skips the probe's own `d8 27` and the answer puts it back. A repeated string
  is a bare `tag 25` and is compared whole."
  ^bytes [^Reader r ^bytes probe]
  (when (and (.hasStringrefPointers r) (pos? (alength probe)))
    (let [kw? (and (> (alength probe) 2)
                   (= 0xd8 (bit-and (aget probe 0) 0xff))
                   (= 0x27 (bit-and (aget probe 1) 0xff)))
          idx (.stringrefIndexForBytes r probe (if kw? 2 0))]
      (when-not (neg? idx)
        (let [^bytes tail (uint-bytes idx)
              ^bytes head (if kw?
                            (byte-array [(unchecked-byte 0xd8) (unchecked-byte 0x27)
                                         (unchecked-byte 0xd8) (unchecked-byte 0x19)])
                            (byte-array [(unchecked-byte 0xd8) (unchecked-byte 0x19)]))
              hn (alength head)
              tn (alength tail)
              out (byte-array (+ hn tn))]
          (System/arraycopy head 0 out 0 hn)
          (System/arraycopy tail 0 out hn tn)
          out)))))

(def ^:private ^:const jump-min-entries
  "Below this many entries, do not consult the index to skip a container.

  MUST EQUAL `boring.core/default-index-min`, and the reason is that this is
  not a tuning knob at all: it is the reader's copy of the writer's floor, and
  its only job is to skip a binary search over the container table that is
  GUARANTEED to miss because no such container can have earned a node. Two byte
  reads decide it -- the major type and the head argument, which for a
  container is its element count.

  IT SAID 16 AND MEANT `:index-min`'s DEFAULT, and then the default moved to 4
  without this moving with it. For a while the writer placed nodes on 4- to
  15-entry containers and this refused to consult them -- including the
  five-key top-level map whose node was the entire argument for lowering the
  floor. The docstring still claimed the two were the same number, which is how
  it survived review.

  `index-layout-test` pins them equal now, because a comment saying MUST EQUAL
  is what was already here."
  4)

(defn- skip-value
  "Where the value at `vp` ENDS -- by jumping over it through the index when it
  is a container with a node, and by walking it otherwise.

  WHY THIS EXISTS. A CBOR array or map carries an ELEMENT COUNT, not a byte
  length, so `skipFrom` past one is a structural walk of its whole subtree.
  Reaching a field that sits after a big container therefore costs the whole
  container, and the index did nothing about it: it accelerated lookups INSIDE
  a container and left skipping PAST one linear. Measured on a 3000-element
  array of maps, one skip: 139.62 us walking against 0.54 us jumping, 224x, and
  the jump is FLAT in container size while the walk is linear. It already wins
  at 50 entries (5.80 us against 0.83).

  HOW. A node's anchors are entry boundaries -- key positions for a map,
  element positions for an array -- so the LAST anchor is within `stride`
  entries of the end. Walk only that remainder. At stride 1 it is one entry
  (0.11 us); at stride 64 it is up to 56 (3.03 us), which is why the stride is
  the lever here as everywhere else.

  THE ANSWER IS VERIFIED AGAINST THE WALK IN THE TESTS, not trusted from the
  frame: a wrong end is a wrong ANSWER rather than an error, which is the one
  outcome this namespace exists to make hard. The bounds check below rejects an
  end that is not strictly after `vp` and inside the source, so a damaged
  anchor falls back to the honest walk rather than returning a plausible
  offset. Damage that stays inside those bounds remains the trust boundary
  doc/INDEX.md describes and cannot be closed here.

  Falls back to `skipFrom` on anything unexpected -- no node, no index, a slot
  that will not read -- because this is an optimisation and its failure mode
  must be slowness, never a different answer."
  (^long [^Nav nav ^Reader r ^long vp] (skip-value nav r vp 0))
  (^long [^Nav nav ^Reader r ^long vp ^long sdepth]
  (let [m (.majorAt r vp)]
    ;; THE GATE IS PAID ON EVERY VALUE A SCAN STEPS OVER, and it is not free:
    ;; measured over 60 small values at stride 1, 4.23 us against 3.36 for the
    ;; unpatched walk -- about 14 ns per entry for the call, two reads and a
    ;; branch. That is the price of the 135.13 -> 17.53 us on the case this
    ;; exists for, and of 133.45 -> 3.35 at stride 16.
    ;;
    ;; Checking `infoAt` first was tried, on the theory that CBOR's five-bit
    ;; info field IS the count under 24 and would settle a small container
    ;; without `headArgAt`'s possible multi-byte read. It bought nothing --
    ;; 4.27 against 4.23 -- because `headArgAt` on a small container reads
    ;; exactly those same bits. The cost is the call, not the read.
    ;; THE DEPTH CAP IS A HOSTILE-FRAME BOUND, not a data bound. The
    ;; remainder below recurses through skip-value for each entry it walks,
    ;; and the recursion argument comes off the FRAME: a crafted anchor chain
    ;; -- each anchor legally in bounds, each a byte past the last -- nests
    ;; one call per link, so the depth is attacker-chosen up to the file
    ;; size. Honest anchors nest no deeper than the document does, and
    ;; `:max-depth` caps documents at 1024; past that this falls back to the
    ;; structural walk, which is slow and correct.
    (if-not (and (< sdepth 1024)
                 (or (= 4 m) (= 5 m))
                 (>= (.headArgAt r vp) jump-min-entries))
      (.skipFrom r vp)
      (let [ns* (node-slot nav vp)]
        (if (neg? ns*)
          (.skipFrom r vp)
          (try
            (let [idx (nav-idx nav)
                  ;; THE LAST ANCHOR AND THE COUNT, without building the node.
                  ;; This read `slot-at`, which allocates a `long[m]` and
                  ;; prefix-sums every anchor, to use exactly two of its
                  ;; values -- 24 016 B and 9.075 us on a 3000-element array
                  ;; against 32 B and 4.580 here. See `anchor-at`.
                  ^longs la (anchor-at r idx ns* -1)
                  a (aget la 1)
                  ;; A NODE WITH NO ANCHORS HAS TO BE REFUSED HERE. `slot-at`
                  ;; returned an empty array and `(aget anchors -1)` raised an
                  ;; AIOOBE that the `catch` below turned into a walk, so the
                  ;; guard was accidental. `anchor-at` reports the count
                  ;; instead, and without this `a` of 0 makes `span` one stride
                  ;; too long AND starts the walk at the container's own head
                  ;; rather than an entry -- a wrong END, which is a wrong
                  ;; ANSWER. `:index-min 0` legitimately produces such a node.
                  _ (when (zero? a) (throw (ex-info "no anchors" {:node ns*})))
                  n (.headArgAt r vp)
                  ;; Same derivation as `lookup-map`: anchor count equals entry
                  ;; count only at stride 1.
                  stride (long (if (= a (long n)) 1 (.stride ^Index idx)))
                  span (- (long n) (* (dec a) stride))
                  map? (= 5 m)
                  ;; THE REMAINDER WALK SKIPS ITS ENTRIES THROUGH THIS SAME
                  ;; FUNCTION, not through `.skipFrom`. The last anchor is
                  ;; within `stride` entries of the end, and those entries are
                  ;; walked -- but an entry's VALUE can itself be a large
                  ;; indexed container, and `.skipFrom` past one is the full
                  ;; subtree walk this function exists to remove. So the
                  ;; pathological case was "the jump worked, and then we walked
                  ;; a 3000-element array anyway because it happened to be the
                  ;; last entry". Recursing makes the skip O(nesting depth)
                  ;; instead of O(subtree).
                  ;;
                  ;; A MAP'S KEY GOES THROUGH `.skipFrom` and only its VALUE
                  ;; recurses: a key is a string or an integer, never a
                  ;; container worth a node, so recursing on it would pay the
                  ;; gate's two reads for nothing.
                  ;;
                  ;; Terminating: each recursion is on a value strictly inside
                  ;; this container, so the depth is the document's nesting
                  ;; depth and not the entry count.
                  ;;
                  ;; MEASURED on a 20-element array at stride 16 whose entry 18
                  ;; is a 3000-element array with a node of its own -- so the
                  ;; jump lands and the remainder walk meets it: 107.3 us
                  ;; through `.skipFrom` against 3.78 recursing, 28.4x, on a
                  ;; byte-identical blob. `remainder-skip-test` pins it.
                  ;; THE LAST ANCHOR MUST BE PAST THE CONTAINER HEAD. An
                  ;; anchor AT the head recursed into skip-value(vp) forever
                  ;; -- StackOverflowError, which is an Error, straight
                  ;; through the RuntimeException catch below. `anchor-at`
                  ;; bounds anchors to (0, data-end) but nothing tied them to
                  ;; THIS container; a frame is bytes somebody else wrote.
                  _ (when (<= (aget la 0) vp) (throw (ex-info "anchor at or before head" {})))
                  e (loop [q (long (aget la 0)) k (long span)]
                      (if (or (zero? k) (neg? q)) q
                          ;; MONOTONIC OR WALK. Each step must land strictly
                          ;; past where it started, or the loop is riding a
                          ;; crafted cycle; `.skipFrom` consumes at least one
                          ;; byte or throws typed, so the fallback restores
                          ;; progress as well as truth.
                          (let [q' (long (if map?
                                           (skip-value nav r (.skipFrom r q) (inc sdepth))
                                           (skip-value nav r q (inc sdepth))))]
                            (recur (if (> q' q) q' (.skipFrom r q)) (dec k)))))]
              (if (and (> e vp) (<= e (.size r))) e (.skipFrom r vp)))
            (catch RuntimeException _ (.skipFrom r vp)))))))))

(defn- scan-map
  "Linear walk of a map's entries from `start`, at most `limit` of them.

  BOUNDED BY THE SOURCE, not only by the entry count. An index anchor is where
  this can start, and `slot-at` proves only that an anchor lies inside the data
  section -- nothing proves it is an ENTRY BOUNDARY, which would mean walking
  the container, which is the work the index exists to avoid. An anchor
  pointing mid-item made this read a garbage head and run off the end of the
  buffer, raising a raw ArrayIndexOutOfBoundsException at the caller of `get`.

  So the walk stops at the source's end and reports a miss. A damaged index may
  still give a wrong ANSWER -- that is the trust boundary doc/INDEX.md
  describes, and it cannot be closed here -- but it may not throw an untyped
  exception out of a lookup."
  ;; `r` IS DERIVED, not passed. Clojure caps a primitive-returning fn at four
  ;; arguments -- the same rule `write-indexed-resolved!` records -- and losing
  ;; the `^long` return here would box on the hot path.
  ^long [^Nav nav ^long start ^long limit ^bytes probe]
  (let [^Reader r (.rdr nav)
        end (.size r)]
    ;; The walk itself is guarded, not just its starting point. A start can be
    ;; in range while the item's DECLARED length runs past the buffer, so the
    ;; throw happens inside `skipFrom` before any check on its result could see
    ;; it -- `Reader.b` reads without bounds checks, which is what makes the
    ;; decoder fast and what makes a damaged offset land here.
    ;;
    ;; An out-of-range walk is reported as a MISS. With a damaged index the
    ;; answer may be wrong either way -- doc/INDEX.md is explicit that this is
    ;; a trust boundary -- but it may not be an untyped exception out of `get`,
    ;; and the unsorted branch above simply tries the next anchor.
    ;;
    ;; try/catch costs nothing when nothing throws; this is not a hot-path tax.
    (try
      (loop [i 0 p start]
        (if (or (>= i limit) (>= p end) (neg? p))
          -1
          (if (or (.bytesEqualAt r p probe)
                  (stringref-key-matches? r p probe))
            (skip r p)
            ;; A KEY THAT IS A STRINGREF REFERENCE CANNOT BE COMPARED, so a miss
            ;; on one is not an answer. It is the cursor reporting that it
            ;; stands INSIDE a namespace whose table it does not have.
            ;;
            ;; `source` refuses a document that opens a namespace WITHOUT an
            ;; index, but that check reads byte 0 of the BUFFER and knows
            ;; nothing about the offset. An offset landing inside a namespace --
            ;; reachable whenever a container format puts its own bytes in
            ;; front, which is exactly what `cursor`'s three-argument form
            ;; exists for -- sailed past it and then reported every referenced
            ;; key ABSENT. A present key, a wrong answer, no error.
            ;;
            ;; One `majorAt` on the miss path, where a memcmp and two skips have
            ;; already happened.
            ;; ONLY WHEN IT CANNOT BE RESOLVED. With a pointer table a
            ;; reference key compares fine (see `stringref-key-matches?`), so a
            ;; non-match is an ordinary MISS and the walk goes on to the next
            ;; entry -- refusing here would turn "this is not the key you asked
            ;; for" into an exception, and every map with more than one
            ;; referenced key would raise on the first entry that is not the
            ;; one sought.
            (if (and (stringref-key? r p) (not (.hasStringrefPointers r)))
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
                ;; THE VALUE IS SKIPPED THROUGH THE INDEX where it is a big
                ;; container -- see `skip-value`. Reaching a field that sits
                ;; after one used to cost the whole container, because a CBOR
                ;; container carries an element count rather than a byte
                ;; length.
                (let [q (skip-value nav r (skip r p))]
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
        ns (node-slot nav off)
        ;; THE NEXT THREE were a nested `let`. Nothing separated the two
        ;; scopes, so clj-kondo called it redundant and it was; the commentary
        ;; that stood between them follows the whole binding vector.
        ^longs slot (when-not (neg? ns) (slot-at r idx ns))
        ;; `long`, for the same reason as `stride`: this feeds the binary
        ;; search's bounds, and an `if` expression is an Object.
        m (long (if slot (alength slot) 0))
        ;; GUARDED ON `slot`, because `idx` is nil whenever this container has
        ;; no node -- the old guard tested `ns` first and never reached a field
        ;; read. With `slot` nil the value is unused (the branch below scans),
        ;; but it is still evaluated, and `(.stride nil)` is an NPE out of
        ;; `get` rather than a scan.
        stride (long (if slot (if (= m (long n)) 1 (.stride idx)) 1))]
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
    ;; THE STRIDE IS PER NODE, and the reader is not told which one -- it
    ;; derives it. A node's anchor count equals its entry count only when it was
    ;; written at stride 1, because `anchor-count(n, k) = ((n-1)/k)+1` reaches
    ;; `n` for no other `k`. So `m == n` IS "stride 1", exactly, and costs a
    ;; comparison rather than a payload element.
    ;;
    ;; That is what lets one file hold both: a large array at the file's stride,
    ;; where anchors are cheap and a binary search is legal, beside an unsorted
    ;; map at stride 1, where they are the only thing that helps.
    (if (or (nil? slot) (zero? m)
            (and (> (long stride) 1) (not (sorted-at? r (.sorted-data idx) ns))))
      (scan-map nav (.headEndAt r off) n probe)
      ;; Entries after anchor a, which is NOT always `stride`: the last
      ;; anchor covers the remainder. Walking a full stride from it ran off
      ;; the end of the container and into whatever followed -- found by the
      ;; missing-key case, where the search lands past the final anchor.
      (let [;; Hoisted so `bsearch` takes one argument, which is where a probe
            ;; belongs -- the bound is a property of the INDEX, not of the key.
            ;; It was tried as a fix for the miss regression below and is not
            ;; one: measured either way, a 40-key miss stays at ~8.4 us. The
            ;; boxing theory it was based on did not survive the measurement.
            lim (long (if slot (.data-end idx) 0))]
        (letfn [(span [^long a] (min stride (- n (* a stride))))
              ;; A MISS FROM THE INDEX IS NOT AN ANSWER, it is a hint that
              ;; did not pay off.
              ;;
              ;; NOTHING PROVES AN ANCHOR IS AN ENTRY BOUNDARY. `slot-at`
              ;; proves only that one lies inside the data section; proving
              ;; more means walking the container, which is the work the
              ;; index exists to avoid. An
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
              ;; WHAT `confirm` IS FOR, now that it is only that. The
              ;; damaged-anchor case argued above, and nothing else.
              ;;
              ;; This used to claim a second job: a `:sorted` node over a map
              ;; the WRITER ordered by something other than CBOR bytes -- a
              ;; `clojure/sorted-map`, ordered by Clojure `compare`. That is
              ;; not a case. `sorted` is computed FROM THE EMITTED KEY BYTES by
              ;; every builder, so it cannot claim an order the file is not in:
              ;; a `sorted-map` whose Clojure order genuinely diverges from
              ;; byte order is written `sorted: false` and never reaches here.
              ;;
              ;; The case that really did depend on `confirm` was stringref,
              ;; and it depended on it for EVERY LOOKUP rather than for a
              ;; corner -- see the two searches below, which is where that
              ;; belongs. A negative is still re-derived, because a damaged
              ;; anchor can still start a bounded walk mid-item.
                (confirm [^long hit]
                  (if (neg? hit) (scan-map nav (.headEndAt r off) n probe) hit))
              ;; The binary search itself, over ONE encoding of the key. Taken
              ;; twice on a stringref document -- see `reference-probe`.
                (bsearch [^bytes p]
                  (loop [lo 0 hi (dec m)]
                    (if (> lo hi)
                      (let [anchor (max 0 (min (dec m) hi))]
                        (scan-map nav (aget slot anchor) (span anchor) p))
                      (let [mid (quot (+ lo hi) 2)
                            q (long (aget slot mid))]
                        (if (or (neg? q) (>= q lim))
                          -1                     ; a damaged anchor: report a miss
                          (let [c (.compareItemToBytes r q p)]
                            (cond (zero? c) (skip r q)
                                  (neg? c) (recur (inc mid) hi)
                                  :else (recur lo (dec mid)))))))))]
          (if (sorted-at? r (.sorted-data idx) ns)
          ;; Sorted keys: binary search the anchors, then a bounded walk.
          ;;
          ;; The PROBE is bounds-checked as well as the walk. An anchor
          ;; that points mid-item is inside the data section and so survives
          ;; `slot-at`, and `compareItemToBytes` skips from wherever
          ;; it is told -- reading a garbage head and running off the buffer,
          ;; which surfaced as a raw ArrayIndexOutOfBoundsException out of
          ;; `get`. Found by mutating every byte of a real indexed document
          ;; and requiring that no lookup ever throws an untyped exception.
          ;; `.data-end` is a primitive field and so never nil; the `or`
          ;; against `(.size r)` dated from the fifteen-key map.
          ;; TWO SEARCHES, LITERAL FIRST. The probe is always built as a
          ;; literal, and on a document without stringrefs that is the only
          ;; encoding any key has -- so the second search is not reached and
          ;; costs nothing. On a stringref document a repeated key is a
          ;; REFERENCE and the literal search cannot match it: measured, 19 of
          ;; 20 present keys came back -1, and every one of them was then
          ;; re-derived by `confirm`'s full scan. That is why an indexed miss
          ;; on a 2000-key map cost 4041 skips against 3998 UNINDEXED -- the
          ;; index was not merely failing to help, it was pure overhead on top
          ;; of the walk it was supposed to replace.
            (let [hit (bsearch probe)]
              (if-not (neg? hit)
                hit
                (let [rp (reference-probe r probe)]
                  (confirm (if rp (bsearch rp) hit)))))
          ;; Unsorted: still jump anchor to anchor rather than entry to entry.
            (confirm
             (loop [a 0]
               (if (>= a m)
                 -1
                 (let [hit (scan-map nav (aget slot a) (span a) probe)]
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
  never a coherent position anyway. See doc/INDEX.md for what a trusted
  index does and does not promise."
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
              ;; PER-NODE STRIDE, derived -- see `lookup-map`. An array written
              ;; at the file's stride keeps it; one whose anchors equal its
              ;; elements was written at 1.
              stride (when slot (if (= (alength slot) (long n)) 1 stride0))
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

(defn- shaped-row-entries
  "`[key offset]` for each key the row at `off` actually carries.

  ABSENCE HAS TWO SPELLINGS and this is the one place both are resolved. A
  SHORT row stops early -- `map` over two collections ends with the shorter, so
  keys past the values are simply not produced. An `undefined` in an interior
  position is filtered. Both are ordinary, because the shape's keys are the
  UNION of every row's; see `Writer.unionShape`.

  O(values), which `count` on a shaped row did not used to be -- it read the
  key count and returned it. That was O(1) and, once rows could be ragged,
  wrong: it counted the shape rather than the row."
  [^Nav nav ^long off ks]
  (into [] (comp (remove (fn [[_ p]] (undefined-at? nav (long p))))
                 (map (fn [[k p]] [k (long p)])))
        (map vector ks (child-offsets nav off))))

;; `shape` is nil for every ordinary cursor. It is non-nil only on a cursor
;; standing on a ROW of a shaped array, where the bytes are an array of values
;; but the logical value is a MAP -- the keys live once in the shape header.
;;
;; Carried on the cursor rather than looked up from the row's offset because a
;; row does not know it is a row: nothing in its own bytes distinguishes it
;; from any other array. The parent hands it down.
(deftype ^:no-doc Cursor [^Nav nav ^long off shape]
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
      ;; then indexed like any other array.
      ;;
      ;; ABSENCE HAS THREE SPELLINGS NOW, and all three land on `nf`:
      ;; a key the shape does not carry at all; a position past the end of a
      ;; SHORT row, which `nth-item` already answers -1 for; and `undefined`
      ;; (0xf7) sitting in an interior position. The shape's key vector is the
      ;; UNION of every row's keys, so a row that lacks one is normal rather
      ;; than damaged -- see `Writer.unionShape`.
      (if-some [i (get (:pos shape) k)]
        (let [p (nth-item nav off (long i))]
          (if (or (neg? p) (undefined-at? nav p)) nf (cursor-at nav p)))
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
      ;; A row counts the keys it CARRIES, which since shapes became a union is
      ;; not the same as the keys the shape declares: a short row stops early
      ;; and an interior `undefined` marks an absent key. This used to return
      ;; `(count (:ks shape))` in O(1), and that is now a count of the table
      ;; rather than of the row -- it would report 5 for `{:a 1}` in a 5-key
      ;; shape, disagreeing with `(count (value c))`.
      (count (shaped-row-entries nav off (:ks shape)))
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
      ;; matching position. Absent keys are not entries -- a short row and an
      ;; interior `undefined` both drop out in `shaped-row-entries`.
      (seq (mapv (fn [[k p]] (clojure.lang.MapEntry. k (cursor-at nav (long p))))
                 (shaped-row-entries nav off (:ks shape))))
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

(defn- reader-root-offset
  "Where a document's root VALUE begins: 0, or past a stringref namespace head.

  A tag 256 at offset 0 is an ENVELOPE, not a value -- it says \"references
  inside here are numbered from zero\" -- and `boring/decode` accordingly
  returns the item inside it, never a tagged wrapper. `root` has to agree, or
  `count` on the root cursor of a stringref document fails with \"count is only
  defined for arrays and maps\".

  AT OFFSET 0 ONLY, and that restriction is the whole point. This began as a
  skip applied inside `cursor-at`, to EVERY offset, and that was wrong twice:
  a nested tag 256 in the middle of a document is genuine user data whose
  namespace the reader must enter, and stepping past it made `realize` read the
  inner item without one -- turning `[1 [\"hello\" \"hello\"] 3]` into a
  `:boring/bad-stringref` throw. An explicit offset now means exactly the byte
  it names, which is what `cursor`'s docstring has always promised."
  ^long [^Reader r]
  (try
    (if (and (.hasStringrefRoot r) (= MAJOR-TAG (.majorAt r 0)))
      (.headEndAt r 0)
      0)
    (catch Exception _ 0)))

(defn- cursor-at [^Nav nav ^long off] (->Cursor nav off nil))

;; ------------------------------------------------ the two layers, and the
;;                                                   bridge between them
;;
;; A CURSOR IS A POSITION YOU CAN HOLD. AN OFFSET IS A POSITION YOU CAN ONLY
;; USE. If you are exploring, holding, printing, `get`-ing, `seq`-ing or
;; handing a subtree to someone else, you want cursors. If you are inside a
;; loop whose trip count is the size of your data, you want offsets: they
;; allocate nothing, and a scan is where that stops being a detail -- a
;; million-row projection through cursors allocates 0.62 GB and through
;; offsets 0.
;;
;; The two are bridged exactly, and `source` is what they share: the DOCUMENT
;; -- the bytes, the reader over them, the index slot and the shape cache. A
;; cursor is a position inside one. Naming it is what lets an offset function
;; take `(source, offset)` rather than a cursor whose own offset it ignores.

(defn source-of
  "The source a cursor addresses into: the bytes, the reader, the index.

  `(cursor (source-of c) (offset c))` is `c`, up to the `shape` field -- which
  is the one thing an offset cannot carry, and which is exactly why a shaped
  row cannot be addressed by offset alone. Stated here rather than discovered.

  Accepts an `items` too -- it holds the same `nav`. That branch goes through
  `nav-of-items`, declared above and defined beside `Items` itself, because
  `Items` is a deftype further down the file and a hint here cannot name a
  class the compiler has not seen yet.

  IDEMPOTENT ON A SOURCE, and that is load-bearing rather than a convenience:
  the identity in the docstring above is `(cursor (source-of c) (offset c))`,
  and `cursor` takes `source-of` of its own first argument. Without this the
  bridge would not compose with itself -- which is exactly what the test that
  pins the identity found.

  TAKES RAW BYTES TOO, because `root` and `cursor` do and the offset layer is
  supposed to be the same API without the allocation. It did not: every
  function below routes through here, and the `:else` branch was an unchecked
  cast, so `(nav/root-offset bs)` -- the documented way into the offset layer --
  died with `ClassCastException: [B cannot be cast to boring.nav.Items`, naming
  a type the caller has never heard of. `root-offset`, `probe`, `walk-from`,
  `field-offset`, `nth-offset`, `container-count`, `value-at`, `long-at`,
  `reduce-at` and `reduce-kv-at` were all affected.

  Constructing a source per call is NOT what you want in a loop -- that is the
  whole reason the layer takes one -- so this is a convenience for the first
  call, not a licence. Hold the source."
  [c]
  (cond (instance? Cursor c) (.nav ^Cursor c)
        (instance? Nav c) c
        (or (bytes? c) (instance? ByteSource c)) (source c nil)
        ;; ANYTHING ELSE IS AN `Items`, and if it is not, say so in the
        ;; vocabulary of this namespace rather than in the JVM's.
        (items? c) (nav-of-items c)
        :else (fail :boring/unsupported-source
                    (str "boring.nav: not a source, cursor, items, byte[] or "
                         "ByteSource: " (some-> c class .getName))
                    {:type-of (some-> c class .getName)})))

(defn offset
  "The byte offset `c` addresses. The inverse of `cursor`."
  ^long [c] (.off ^Cursor c))

(defn cursor
  "A cursor at byte offset `off`.

      (nav/cursor s off)                  ; of a source, or of any cursor's
      (nav/cursor bs off opts)            ; the same, in one step

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

  The three-argument form is what the removed `source-at` was, and the
  two-argument form takes raw bytes as well as a source, for the same reason
  `root` does."
  ([s ^long off] (if (or (bytes? s) (instance? ByteSource s))
                   (cursor-at (source s nil) off)
                   (cursor-at (source-of s) off)))
  ([src ^long off opts] (cursor-at (source src opts) off)))

(defn root
  "A cursor at the root of a document.

      (nav/root bs opts)                  ; what `source` used to be
      (nav/root (nav/root bs opts))     ; the same, in two steps
      (nav/root c)                        ; back to the top from anywhere

  THE ORDINARY ENTRY POINT. If you are exploring, holding, printing, `get`-ing
  or `seq`-ing, you want this. If you are inside a loop whose trip count is the
  size of your data, you want `source` and the offset layer.

  The one-argument form takes RAW BYTES as well as a source or a cursor,
  because `(root bs)` is what a caller writes first and making them spell
  `(root (source bs))` buys nothing."
  ([s] (if (or (bytes? s) (instance? ByteSource s))
         (root-cursor (source s nil))
         (root-cursor (source-of s))))
  ([src opts] (root-cursor (source src opts))))

(defn- root-cursor
  "A cursor at the document's root VALUE -- past a stringref envelope if there
  is one. See `reader-root-offset` for why only `root` does this and `cursor` does
  not."
  [^Nav nav]
  (cursor-at nav (reader-root-offset ^Reader (.rdr nav))))

(defn root-offset
  "Where the document's root VALUE begins, as an offset. Allocates nothing.

  The offset layer's entry point, and what `root` uses internally. It is 0 for
  almost every document and past the head of a tag-256 stringref envelope for
  the rest -- which is exactly the distinction a caller should not have to know
  about, and the reason entering the offset layer used to require building a
  cursor (`(nav/offset (nav/root bs))`) purely to throw it away."
  ^long [s] (reader-root-offset ^Reader (.rdr ^Nav (source-of s))))

;; --- the offset-layer sentinels, named -----------------------------------
;;
;; The offset layer returns a `long`: a real byte offset, or one of two
;; sentinels. Naming them here means the contract lives in ONE place -- a
;; future change to the values cannot silently desync a downstream `cond`, the
;; way konserve-lmdb, kabel and datahike would each hardcode -1/-2 otherwise.
;; Primitive `^long` in, boolean out, inlined so there is no boxing on scans.

(def ^:no-doc ^:const absent-offset -1)
(def ^:no-doc ^:const unnameable-offset -2)

(defn absent?
  "True when an offset-layer return means the path led to nothing -- no such
  key, or an index out of range. See `walk-from`."
  {:inline (fn [o] `(== (long ~o) -1))}
  [^long o] (== o -1))

(defn no-offset?
  "True when the value is PRESENT but no byte offset can name it, so a caller
  should fall back to the cursor `walk`/`value`. Tag structures and
  shaped-array rows are the two cases. See `walk-from`."
  {:inline (fn [o] `(== (long ~o) -2))}
  [^long o] (== o -2))

(defn found?
  "True when the return is a real offset -- neither sentinel -- so
  `value-at`/`long-at` will read it rather than raise `:boring/absent`."
  {:inline (fn [o] `(not (neg? (long ~o))))}
  [^long o] (not (neg? o)))

;; ------------------------------------------------------- the offset layer
;;
;; Everything below takes `(source, offset)` and returns a `long` or a scalar.
;; Nothing allocates. The internals have always worked in offsets -- `lookup-map`
;; and `nth-item` return one, `realize` takes one -- so this is a skin over what
;; was already there, and the reason to have it is that the alternative is every
;; caller writing its own walker. konserve-lmdb wrote two, and both disagreed
;; with this namespace.
;;
;; TWO SENTINELS, both negative, both refused by `value-at`/`long-at` as a
;; typed `:boring/absent` rather than a wrong answer:
;;
;;   -1  ABSENT -- the path led nowhere (no such key, index out of range).
;;   -2  PRESENT BUT UNNAMEABLE -- the value is there, but no offset can name
;;       it, so fall back to the cursor `walk`. A tag structure and a shaped-
;;       array row are the two cases: `walk-from` cannot descend a tag, and a
;;       shaped row is synthesised, not a byte range.
;;
;; `walk-from`'s docstring is canonical for both; the predicates `absent?`,
;; `no-offset?` and `found?` below name them so no caller hardcodes the
;; numbers. This comment said "-1 is the only sentinel" through three releases
;; that returned -2 from `walk-from`, `field-offset` and `nth-offset`.

(defn probe
  "`k` encoded once, to hand to `field-offset` in a loop.

  A key is matched by comparing BYTES, not by decoding the stored key, so a
  lookup needs the key's encoding. `field-offset` will make one per call from
  a cache on the source; making it yourself is worth 32 bytes a call, which on
  a scan over a million rows is 32 MB of garbage for a value that never
  changes.

  Tied to the OPTIONS, not to the document: the encoding depends on the
  profile, so a probe made under one set of options is meaningless under
  another. Make it from the same source or context you will use it with."
  ^bytes [s k] (probe-for (source-of s) k))

;; `walk` is defined further down, with the cursor layer it belongs to.
;; `walk-from` hands it the tail of a path that reaches a tag view.
(declare walk)

(defn walk-from
  "The OFFSET at `path` from `off`, without building a cursor for any step.

  The offset-layer twin of `walk`, and the primitive a projection over many
  rows wants: `walk` takes and returns a Cursor, so a scan pays two
  allocations per row for objects it throws away. Measured on a four-step path,
  same answer both ways: 304.6 B/row through cursors against 80.6 through
  offsets, the remainder being the returned value itself.

  Answers exactly what `walk` answers -- it is the same loop over the same
  `lookup-map`/`nth-item`, so an integer step is a POSITION on an array and a
  KEY on a map, decided from the container, and every step CONSULTS THE INDEX.
  That last point is why this is here rather than in a caller: the index lives
  on the source, and a walker written over `Reader`'s positional primitives
  cannot reach it. konserve-lmdb wrote such a walker twice and it disagreed
  with this namespace twice.

  Returns:

    >= 0  the offset
      -1  a step was absent
      -2  the path ends somewhere NO OFFSET CAN NAME, and the caller should
          fall back to `walk`. Two shapes reach it. A ROW OF A SHAPED ARRAY,
          whose bytes are an array while its value is a map -- the array's
          offset would answer a vector where the truth is a map. And anything
          reached THROUGH A TAG THIS NAMESPACE HAS NO VIEW FOR, which is
          REALISED rather than pointed at, so there is no offset to give: a
          stringref document is the case that matters in practice, and it is
          how konserve-lmdb's older blobs read.

          `value-at` and the other offset readers reject both negatives, so a
          caller that only checks `neg?` gets not-found rather than a wrong
          answer -- but for -2 the value is THERE, and `walk` will hand it
          over.

  A PATH MAY BE COMPILED, and for a scan it should be. `probe-for` passes
  already-encoded bytes straight through, so substituting `probe` for each key
  step gives a path that costs no cache lookup per step per row:

      (def p (mapv #(if (integer? %) % (nav/probe src %)) [\"a\" \"b\" 2]))
      (nav/walk-from src 0 p)

  Measured on a four-step path: 0.658 us against 0.535, at the SAME
  allocation -- a cache hit does not allocate, it simply is not free. Integer
  steps survive compilation unchanged and still mean POSITION on an array and
  KEY on a map, decided from the container.

  That is the whole of \"compiled path\": no second API to keep in step with
  this one, and nothing a caller can compile wrongly except by encoding under
  different options, which `probe` ties to the source."
  ^long [s ^long off path]
  (let [^Nav nav (source-of s)
        ;; INDEXED, NOT SEQ-WALKED, and that is most of what this function
        ;; saves. `(seq path)` and a `next` per step allocate a cell each --
        ;; on a four-step path that measured 160 of the 224 bytes a row was
        ;; spending, more than the two Cursors this exists to avoid. A vector
        ;; is `nth`-able in O(1) with no allocation at all, so the path is
        ;; coerced ONCE and then indexed.
        ^clojure.lang.IPersistentVector kv (if (vector? path) path (vec path))
        n (.count kv)]
    (if (neg? off)
      -1
      (loop [o (long off) i 0]
        (if (= i (long n))
          o
          (let [k (.nth kv i)
                mj (major nav o)]
            (if (or (= mj MAJOR-ARRAY) (= mj MAJOR-MAP))
              (let [p (if (= mj MAJOR-ARRAY)
                        ;; A NON-INTEGER KEY ON AN ARRAY IS ABSENT, as `get`
                        ;; says and as `clojure.core/get-in` says. See `walk`.
                        (if (integer? k) (nth-item nav o (long k)) -1)
                        (lookup-map nav o k))]
                (if (neg? (long p)) -1 (recur (long p) (inc (long i)))))
              ;; A TAG OR A SCALAR. The views -- records, shaped arrays, typed
              ;; arrays -- live on the cursor layer, so the tail is handed to
              ;; `walk` rather than reimplemented. One walker, one set of
              ;; answers, which is the whole reason `walk` is public.
              (if-let [c2 (get (cursor-at nav o) k)]
                ;; `get` FOR THE TAIL, and no cast. A tag this namespace has
                ;; no view for REALISES: `(get tag-cursor k)` hands back a
                ;; plain value, not a Cursor, and `walk` simply carries on with
                ;; `get` on it. Casting it was a ClassCastException on the
                ;; first stringref blob konserve handed this.
                (let [fin (reduce (fn [cur kk] (when cur (get cur kk)))
                                  c2 (subvec kv (inc (long i))))]
                  (cond (nil? fin) -1
                        ;; A realised value has no offset that names it -- the
                        ;; same answer as a shaped row, for the same reason.
                        (not (instance? Cursor fin)) -2
                        (.shape ^Cursor fin) -2
                        :else (.off ^Cursor fin)))
                -1))))))))

(defn field-offset
  "Byte offset of the value for key `k` in the map at `off`, or -1.

  `k` may be a key or an already-encoded `probe`. The probe is the hot-loop
  form: measured, a keyword costs 32 bytes a call and a probe costs nothing.

  CONSULTS THE INDEX exactly as `get` does -- same `lookup-map`, so an indexed
  container is a binary search and an unindexed one is the linear walk a
  hand-rolled reader would do. That is the whole point of this existing: a
  caller who writes their own walker over `Reader` CANNOT reach the index,
  because the index lives on the source and `Reader.skipFrom` knows nothing
  about it.

  RETURNS -2 WHEN `off` IS NOT A MAP. Two shapes reach it and both are ordinary
  rather than damage: a TAG, which may still hold a map underneath (a record's
  fields, a shaped array's rows) but is not one itself, and a ROW OF A SHAPED
  ARRAY, whose bytes are an array while its value is a map -- its keys live once
  in the shape header, so no key comparison against these bytes can find them.
  Use `shape`/`shape-column` for the second and `walk`/`walk-from` for the
  first. See `walk-from`, which has always spelled -2 this way."
  ^long [s ^long off k]
  (let [^Nav nav (source-of s)]
    (if (= MAJOR-MAP (major nav off)) (lookup-map nav off k) -2)))

(defn nth-offset
  "Byte offset of element `i` of the array at `off`, or -1.

  RETURNS -2 WHEN `off` IS NOT AN ARRAY, for the reason `field-offset` gives.
  A shaped array is the case that matters: it is a TAG, and `(nth-offset s
  tag-off i)` cannot mean row `i` because the row's offset would answer a
  vector where the truth is a map. `(shape-rows (shape s off))` is the array
  this wants, and indexing THAT is exactly what the shaped scan does."
  ^long [s ^long off ^long i]
  (let [^Nav nav (source-of s)]
    (if (= MAJOR-ARRAY (major nav off)) (nth-item nav off i) -2)))

(defn container-count
  "Element count of the array, or pair count of the map, at `off`.

  O(1) -- it is read from the container's head, not counted.

  NOT `count-at`, which is taken by the index reader's per-node accessor
  further down this file. Two definitions of the same name in one namespace do
  not collide loudly -- the later one simply wins, and the earlier calls go to
  it -- which is exactly how a public function can end up silently rebound to
  a private one that answers a different question.

  REFUSES A NON-CONTAINER, rather than joining the -1/-2 convention its
  neighbours use: a count has no spare value. Every negative long is a
  plausible count to arithmetic downstream, and the failure this replaces was
  exactly that -- on a shaped array's tag this returned 39649, the TAG NUMBER,
  because `headArgAt` answers a tag's argument as readily as a container's
  length and nothing here looked at the major type. A silently wrong count out
  of a public function is the one outcome this namespace's trust boundary is
  supposed to make impossible. For a shaped array the count you want is
  `shape-count`."
  ^long [s ^long off]
  (let [^Nav nav (source-of s)
        mj (major nav off)]
    (if (or (= mj MAJOR-ARRAY) (= mj MAJOR-MAP))
      (head-count nav off)
      (fail :boring/not-a-container
            (str "boring.nav: container-count is only defined for arrays and "
                 "maps. Major type " mj " at offset " off
                 (when (= mj MAJOR-TAG)
                   " is a tag -- if it is a shaped array, use `shape-count`."))
            {:offset off :major mj}))))

(defn value-at
  "The value at `off`, realised.

  The general reader, and the one that allocates: use `long-at` for an integer
  in a hot loop. Refuses -1 rather than reading whatever sits at the front of
  the document, because -1 is what every function above returns for ABSENT and
  passing it on unchecked is how a miss becomes a wrong answer."
  [s ^long off]
  (if (neg? off)
    (fail :boring/absent
          "boring.nav: no value at offset -1 -- that is what `field-offset` and
           `nth-offset` return for a key or index that is not there."
          {:offset off})
    (realize (source-of s) off)))

(defn long-at
  "The integer at `off`, as a primitive long. Allocates nothing.

  `value-at` boxes: summing one integer field over 2000 children allocates a
  `Long` per child, and `Long.valueOf`'s cache only covers -128..127. This
  needs no decode at all -- for major 0 the value IS the head argument, and for
  major 1 it is `-1 - arg`, which is CBOR's negative encoding by definition.

  Refuses anything that is not an integer, rather than coercing: a caller
  reaching for this in a loop wants to know that the field it named is a float
  or a string, not to get a plausible number out of one.

  REFUSES -1 AND -2 AS `value-at` DOES, and it did not. Those are what
  `field-offset` and `nth-offset` return for absent and for `there, but no
  offset names it`, so `(long-at s (field-offset s off k))` is the natural
  pairing and the one a scan writes -- and on a miss it reported
  `:boring/truncated-input`, which reads as a damaged document rather than as
  a key that was not there. Two functions taking the same offsets have to
  refuse the same sentinels, or one of them turns a lookup miss into a
  corruption report."
  ^long [s ^long off]
  (when (neg? off)
    (fail :boring/absent
          "boring.nav: no integer at offset -1 -- that is what `field-offset`
           and `nth-offset` return for a key or index that is not there."
          {:offset off}))
  (let [^Nav nav (source-of s)
        ^Reader r (.rdr nav)
        mj (major nav off)]
    (case (int mj)
      0 (.headArgAt r off)
      1 (- -1 (.headArgAt r off))
      (fail :boring/not-an-int
            "boring.nav: long-at needs a CBOR integer (major 0 or 1)"
            {:offset off :major mj}))))

(defn reduce-at
  "Reduce over the OFFSETS of the array at `off`. Allocates nothing.

      (nav/reduce-at s off (fn [acc o] (+ acc (nav/long-at s o))) 0)

  `f` is `(fn [acc offset] acc)` and may return `reduced`. Compare
  `(reduce f init (children c))`, which allocates a cursor per child --
  measured at 24 bytes each, and 134 when the body reaches a field inside one.
  That is the difference between a within-document aggregate a scan can afford
  and one it cannot.

  HINT `f`'S SECOND ARGUMENT and the offset is never boxed:
  `(fn [acc ^long o] ...)` compiles to `IFn$OLO`, and the `instanceof` below
  picks that path. Unhinted it costs one `Long` per child -- slow, never wrong,
  which is the right way round.

  THE TEST IS OUTSIDE THE LOOP, which is the only reason it is worth doing: one
  `instanceof` per reduction, not per child. Writing it without the test and
  hoping the JIT sees through `(f acc p)` does not work, because `f` is an
  untyped local -- measured, the offset boxed on every child and the whole
  point of the layer was lost."
  [s ^long off f init]
  (let [^Nav nav (source-of s)
        ^Reader r (.rdr nav)
        n (head-count nav off)]
    (when-not (= MAJOR-ARRAY (major nav off))
      (fail :boring/not-an-array
            "boring.nav: reduce-at is for arrays; use reduce-kv-at for a map"
            {:offset off :major (major nav off)}))
    (if (instance? clojure.lang.IFn$OLO f)
      (let [^clojure.lang.IFn$OLO g f]
        (loop [i 0 p (.headEndAt r off) acc init]
          (if (or (= i n) (reduced? acc))
            (unreduced acc)
            (recur (inc i) (skip r p) (.invokePrim g acc p)))))
      (loop [i 0 p (.headEndAt r off) acc init]
        (if (or (= i n) (reduced? acc))
          (unreduced acc)
          (recur (inc i) (skip r p) (f acc p)))))))

(defn reduce-kv-at
  "Reduce over the KEY and VALUE offsets of the map at `off`. Allocates nothing.

      (nav/reduce-kv-at s off (fn [acc ko vo] ...) init)

  `f` is `(fn [acc key-offset value-offset] acc)` and may return `reduced`.

  A SEPARATE NAME rather than an arity of `reduce-at`, mirroring
  `reduce`/`reduce-kv`. One name taking either would dispatch on the container
  and so would work on array-shaped data and throw `ArityException` on
  map-shaped data -- the exact `works on my documents` failure this layer
  exists to close, and one that only shows up on the row where the shape
  changes.

  The KEY arrives as an offset, not a value, so a caller that only needs to
  compare it can use `value-at` on the ones it cares about and never realise
  the rest.

  BOTH OFFSETS STAY PRIMITIVE when `f` is hinted `(fn [acc ^long k ^long v] ...)`,
  which compiles to `IFn$OLLO`. Same one-test-outside-the-loop as `reduce-at`."
  [s ^long off f init]
  (let [^Nav nav (source-of s)
        ^Reader r (.rdr nav)
        n (head-count nav off)]
    (when-not (= MAJOR-MAP (major nav off))
      (fail :boring/not-a-map
            "boring.nav: reduce-kv-at is for maps; use reduce-at for an array"
            {:offset off :major (major nav off)}))
    (if (instance? clojure.lang.IFn$OLLO f)
      (let [^clojure.lang.IFn$OLLO g f]
        (loop [i 0 p (.headEndAt r off) acc init]
          (if (or (= i n) (reduced? acc))
            (unreduced acc)
            (let [vp (skip r p)]
              (recur (inc i) (skip r vp) (.invokePrim g acc p vp))))))
      (loop [i 0 p (.headEndAt r off) acc init]
        (if (or (= i n) (reduced? acc))
          (unreduced acc)
          (let [vp (skip r p)]
            (recur (inc i) (skip r vp) (f acc p vp))))))))

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
                (let [p (if (= mj MAJOR-ARRAY)
                          ;; A NON-INTEGER KEY ON AN ARRAY IS ABSENT.
                          ;;
                          ;; It used to fall through to `lookup-map`, which
                          ;; reads the container as a MAP -- `n` pairs where
                          ;; there are `n` elements -- and that is not a slow
                          ;; path or a typed refusal, it is a WRONG ANSWER:
                          ;;
                          ;;   (get-in {"arr" ["k1" "v1"]} ["arr" "k1"])  ; nil
                          ;;   (walk    cursor             ["arr" "k1"])  ; "v1"
                          ;;
                          ;; nav's own `get` chain answers nil, so `walk`
                          ;; disagreed with `get-in`, with `get`, and with the
                          ;; rule its own docstring states. On a short document
                          ;; the over-read runs off the end and raises
                          ;; `:boring/truncated-input` instead; which of the
                          ;; two you get depends on what follows the array.
                          ;;
                          ;; Found by property-testing `walk-from` against
                          ;; `walk` -- neither walker was wrong relative to the
                          ;; other, so only comparing both to `get` could show
                          ;; it.
                          (if (integer? k) (nth-item nav off (long k)) -1)
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

;; A PARSED SHAPE HEADER, and the one thing both layers read a shaped array
;; through. `ks` are the keys in column order, `pos` maps a key to its column,
;; `rows-off` is the rows ARRAY -- an ordinary CBOR array that `nth-item` and
;; `reduce-at` handle with no knowledge of shapes at all -- and `n` is the row
;; count.
;;
;; WHY IT IS A VALUE AND NOT A CLOSURE. `shaped-view` used to parse this inline
;; and close over it, which left the cursor layer able to read shaped data and
;; the offset layer unable to reach it: the key-to-column map, which is the
;; whole reason shapes are fast, was not addressable from outside. Hoisting the
;; resolution OUT OF THE ROW LOOP is what a scan wants -- one map lookup per
;; TABLE rather than a key comparison per row -- and that is only expressible
;; if the caller can hold the shape. See `shape`.
(deftype ^:no-doc Shape [ks pos ^long rows-off ^long n])

(defn- shape-at
  "The parsed header of the shaped array at `off`, or nil.

  Nil for anything that is not exactly what `writeShapedArray` emits, including
  a duplicate key -- which the Reader rejects outright in `checkShapeKeys`, and
  which `pos` cannot represent (`assoc!` keeps the last, `ks` keeps both, and
  one cursor then reported `count` 2 against `(count (value c))` 1).

  ROWS ARE NOT VALIDATED HERE. The Reader enforces `row length == key count`,
  but checking every row eagerly takes shaped `count` from 644 ns to 10.4 us --
  16x on well-formed data -- and would also be WRONG: `count` reads the
  rows-array header and touches no row, so a bad row 5 is not in the subtree it
  examined. Charging it for that is the same mistake as charging a partial read
  for `:max-items`. The paths that DO walk rows check as they go, for 0.68
  ns/row, because they are there already."
  ^Shape [^Nav nav ^long off]
  (let [^Reader r (.rdr nav)]
    (when (and (= MAJOR-TAG (.majorAt r off))
               (= TAG-SHAPED-ARRAY (.headArgAt r off)))
      (when-let [pr (tag-pair r off)]
        (let [ks-off (long (nth pr 0))
              rows-off (long (nth pr 1))]
          (when (and (= MAJOR-ARRAY (.majorAt r ks-off))
                     (= MAJOR-ARRAY (.majorAt r rows-off)))
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
                                    (conj! acc (realize nav p)))))]
                  (when (= (count ks) (count (set ks)))
                    (->Shape ks
                             (persistent!
                              (reduce-kv (fn [m i kk] (assoc! m kk i))
                                         (transient {}) ks))
                             rows-off n)))))))))))

(defn- shaped-view
  "RESTRUCTURING. A shaped array is `39649([keys, [row, ...]])`: the keys are
  hoisted out and each row is an ARRAY of values matching them positionally. It
  realises to a VECTOR OF MAPS, so a row cursor has array bytes and map
  semantics and must carry the shape -- nothing in a row's own bytes says it is
  a row.

  The header comes from `shape-at`, so this view and the offset layer's
  `shape` cannot disagree about what a shaped array is."
  [^Nav nav ^long off ^long tag]
  (when (= tag TAG-SHAPED-ARRAY)
    (when-let [^Shape shp (shape-at nav off)]
      (let [^Reader r (.rdr nav)
            ks (.ks shp)
            k (count ks)
            n (.n shp)
            rows-off (.rows-off shp)
            sh {:ks ks :pos (.pos shp)}
            ;; EVERY ROW IS CHECKED WHEN IT IS REACHED -- see `shape-at` for why
            ;; not up front. On the paths that DO walk rows (`seq`, `reduce`,
            ;; `nth i`) the check costs 0.68 ns/row, because they are there
            ;; already.
            ;;
            ;; `<=`, NOT `=`. The shape's keys are the UNION of every row's, so
            ;; a row that lacks the trailing ones is simply shorter -- that is
            ;; how `Writer.writeShapedArray` truncates trailing absences, and
            ;; the Reader accepts it for the same reason. Longer than the shape
            ;; is still malformed: there is no key for the extra value to land
            ;; on.
            row-ok? (fn [^long p]
                      (and (= MAJOR-ARRAY (.majorAt r p))
                           (<= (.headArgAt r p) k)))
            at (fn [^long i]
                 (let [p (nth-item nav rows-off i)]
                   (cond (neg? p) ::miss
                         (not (row-ok? p))
                         (fail :boring/bad-tag-content
                               (str "boring.nav: shaped row " i " carries more than "
                                    k " values, which is what its shape declares")
                               {:offset p :row i :expected k})
                         :else (->Cursor nav p sh))))]
        {:kind :vector :n n :nth at :key-pred integer?
         ;; through `at`, so every row is checked here too
         :items (fn [] (map at (range n)))}))))

;; ------------------------------------------------ shapes, on the offset layer
;;
;; A shaped array is the densest thing boring writes and the fastest thing it
;; can read, and until these four functions existed the second half was not
;; reachable from the offset layer at all -- `nth-offset` on the tag threw and
;; `container-count` answered 39649.
;;
;; THE POINT IS THAT THE KEY LOOKUP LEAVES THE LOOP. A row of an ordinary array
;; of maps costs a key comparison per row per field; a shaped row costs an array
;; index, because the key was resolved to a column ONCE for the whole table.
;; That is the entire reason the encoding exists, and the API has to let a
;; caller hoist it or the density is all anyone can use.
;;
;;     (let [sh  (nav/shape s (nav/root-offset s))
;;           col (nav/shape-column sh :amount)]
;;       (nav/reduce-at s (nav/shape-rows sh)
;;                      (fn [acc row]
;;                        (+ acc (nav/long-at s (nav/nth-offset s row col))))
;;                      0))
;;
;; Measured over 5000 rows of five fields, summing one column: 152 us here,
;; against 367 us for hako and 1414 us for nippy, both of which must decode the
;; whole table to reach one field of it. This allocates nothing at all.

(defn- need-shape
  "`sh`, or a typed error naming the accessor that was reached with nil.

  `shape` returns NIL for anything that is not a shaped array, and says so --
  \"a caller asking whether a document is shaped is asking a question, not
  asserting an answer\". The four accessors are `^Shape` field reads, so that
  documented nil arrived as `NullPointerException: Cannot read field
  \"rows_off\" because \"sh\" is null`, which names a private deftype field and
  tells the caller nothing. The nil is the API working; the message was not."
  ^Shape [sh who]
  (or sh
      (fail :boring/not-a-shape
            (str "boring.nav/" who " needs a shape, got nil -- which is what "
                 "`shape` returns when the offset does not hold a shaped array. "
                 "Branch on it: (if-let [sh (nav/shape s off)] ...)")
            {:accessor who})))

(defn shape
  "The shape of the shaped array at `off`, or nil if there is not one there.

      (nav/shape s off)                   ; of a source, at an offset
      (nav/shape c)                       ; of a cursor, at its own offset

  Nil rather than a throw for ANYTHING else -- a plain array, a map, a scalar,
  another tag, or a damaged shape header. A caller asking whether a document is
  shaped is asking a question, not asserting an answer, and the branch is
  `if-let`.

  Hold it across the whole scan. Building one realises the key vector, which is
  O(keys) and wasted if it happens per row."
  ([c] (shape (source-of c) (offset c)))
  ([s ^long off] (shape-at (source-of s) off)))

(defn shape-rows
  "The offset of a shape's ROWS ARRAY -- an ordinary CBOR array of ordinary CBOR
  arrays, which `nth-offset`, `reduce-at` and `container-count` handle knowing
  nothing about shapes.

  This is the bridge: everything below it is plain, and the tag stops mattering.

  REFUSES NIL, which is what `shape` returns for anything that is not a shaped
  array. A `^Shape` field read on nil is a NullPointerException naming a
  private deftype field, which tells a caller nothing about what they did."
  ^long [^Shape sh] (.rows-off (need-shape sh 'shape-rows)))

(defn shape-count
  "How many rows the shape has. O(1) -- read from the rows-array head.

  What `container-count` cannot answer, because the shaped array is a TAG and a
  tag has no count. Refuses nil -- see `shape-rows`."
  ^long [^Shape sh] (.n (need-shape sh 'shape-count)))

(defn shape-column
  "The COLUMN INDEX of key `k`, or -1 if the shape does not carry that key.

  Hand it to `nth-offset` on a row. -1 composes: `nth-item` rejects a negative
  index and answers -1 in turn, so a missing key reads as absent rather than as
  column zero -- which is what an unchecked `(get pos k)` would have given, and
  is the difference between not-found and the wrong field's value."
  ^long [^Shape sh k]
  (let [i (get (.pos (need-shape sh 'shape-column)) k)] (if i (long i) -1)))

(defn shape-keys
  "The shape's keys, in column order. The vector `shape-column` indexes into."
  [^Shape sh] (.ks (need-shape sh 'shape-keys)))

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
        ;;
        ;; THROUGH `shaped-row-entries`, which is what makes this agree with
        ;; `count`, `seq` and `get` on the same cursor. Zipping the shape's keys
        ;; against the row's values directly was right while every row carried
        ;; every key; once the shape became a UNION it reported absent keys as
        ;; present with the value `undefined`, so `(nav/value row)` and
        ;; `(boring/decode ...)` disagreed about the same bytes -- exactly the
        ;; divergence between the two readers that this namespace exists to not
        ;; have.
        (let [nav (.nav c)]
          (into {} (map (fn [[k p]] [k (realize nav (long p))]))
                (shaped-row-entries nav (.off c) (:ks sh))))
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

(defn re-point!
  "Point an existing source at different bytes, and return a cursor at its root.

  FOR SCANS, and the measurement is the reason it exists. A store hands out a
  fresh blob per row, so a million-row projection builds a million sources --
  and once the offset API made the lookup itself allocation-free, `source` was
  100% of what a scan still allocated:

      nav/source alone              249.6 B
      source + field-offset         249.6      (the lookup adds nothing)
      REUSED source + field-offset    0.0

  This reuses the `Reader`, the `Nav`, the probe cache and the root cursor,
  and re-points all four. Nothing is allocated.

  THE CURSOR HANDED BACK IS THE ONE YOU PASSED IN, which is what makes it free
  and also what makes it dangerous: every cursor previously derived from this
  source now addresses the NEW bytes at its old offset, and will return
  plausible wrong values rather than fail. So this is for a loop that finishes
  with each document before starting the next -- which is exactly a scan, and
  is not a projection you hold on to.

  NOT SAFE ACROSS THREADS, more sharply than the rest of this namespace: `fork`
  exists because a `Reader` carries a mutable position, and this adds mutable
  BYTES. A forked source has its own Nav and is unaffected by a re-point of its
  parent; a source being re-pointed must not be shared at all.

  `boring.mmap` sources work too -- anything `source` accepts."
  [c src]
  (let [^Nav nav (.nav ^Cursor c)
        ^Reader r (.rdr nav)
        ;; READ BEFORE THE RESET, while the reader still holds the OLD bytes.
        ;; A root cursor's offset is 0 on an ordinary document and past the
        ;; envelope on a stringref one, so a cursor that was at the old root
        ;; must land on the NEW root rather than keep a number that meant
        ;; something about the previous document. Re-pointing a root cursor
        ;; from a stringref document onto a plain one otherwise kept offset 3
        ;; and read from the middle of the new value: `[1 2 [7 8 9]]` came back
        ;; as `[7 8 9]`, no exception. This is the documented zero-allocation
        ;; scan idiom, so a mixed store hit it on every other row.
        at-root? (= (.off ^Cursor c) (reader-root-offset r))]
    (cond
      (bytes? src) (.reset r ^bytes src)
      (instance? ByteSource src) (.reset r ^ByteSource src)
      :else (fail :boring/unsupported-source
                  "boring.nav: expected a byte[] or a ByteSource"
                  {:got (class src)}))
    (vreset! (.src nav) src)
    (when-let [v (.idx nav)] (vreset! v ::unparsed))
    (vreset! (.views nav) nil)
    ;; AFTER the invalidation, not before. The check forces the index parse, and
    ;; parsing is what installs the new document's pointer table -- run it while
    ;; `.idx` still held the PREVIOUS document's Index and the reader would keep
    ;; pointers into bytes that are gone. `.reset` above has already cleared the
    ;; table, so a document that turns out to have none simply refuses.
    (check-stringref-navigable! nav r)
    ;; A NEW CURSOR ONLY WHEN THE ROOT MOVED. `Cursor.off` is final, so the
    ;; offset cannot be corrected in place; allocating here costs one object on
    ;; the rows where the envelope actually changes shape, and nothing on a
    ;; store whose blobs are written with one set of options -- which is every
    ;; store, and why this was invisible until a mixed pair was tried.
    (let [nr (reader-root-offset r)]
      (if (and at-root? (not= nr (.off ^Cursor c)))
        (cursor-at nav nr)
        c))))

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
  "Byte offset of each of the frame payload's first SIX elements, decoding none
   of them.

   The frame is `27([\"boring/index\", [stride containers counts slots sorted
   (stringrefs) end]])`, so this walks: past the tag, into the two-element
   array, past the name, into the payload, then element to element. The sixth
   is the stringref pointer table, present only when the document opens a
   namespace -- which is why the caller gates on the payload count rather than
   reading `e5` positionally and hoping. `data-end` is not returned at all: it
   is the LAST element whatever the count, and the back-pointer already is it.

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
        e4 (.skipFrom r e3)                     ; sorted
        ;; The element AFTER `sorted`: `data-end` in a six-element payload, the
        ;; stringref pointer table in a seven-element one. Which it is cannot be
        ;; decided here -- the payload's element COUNT says so, and the caller
        ;; has already read that off the array head.
        e5 (.skipFrom r e4)]
    ;; A `long[6]`, NOT a vector: this is one allocation per index open, and the
    ;; open is what a scan pays per row. Six boxed Longs in a PersistentVector
    ;; against 64 bytes of primitives.
    (doto (long-array 6)
      (aset 0 e0) (aset 1 e1) (aset 2 e2) (aset 3 e3) (aset 4 e4) (aset 5 e5))))

(def ^:private ^:const stringref-layout-v1 1)

(defn- stringref-table-at
  "The stringref pointer table at `p` as `[base count iw ow]`, or nil.

  Element 5 of a seven-element payload: `(index -> defining offset)` for the
  entries some tag 25 actually references, so a cursor holding an offset can
  resolve one by JUMPING to where the string was written instead of by having
  decoded every string before it. See `boring.core/pack-stringrefs`.

  ONE O(1) GATE, matching the one the slot table gets. The count is not stored,
  so `(dec len)` must divide exactly by the pair width -- a single modulo rather
  than a sum over N. Per-entry validation is deliberately NOT done here: the
  reader bounds-checks every byte it loads through the table and refuses any
  target that is not a string, so a damaged entry costs one lookup, not the
  open. Validating N entries at open would put the cost on every document to
  catch a fault that only matters on the ones that have it.

  nil for any shape this does not recognise, which the caller treats as
  \"no pointer table\" -- the same answer a six-element payload gives."
  [^Reader r ^long p]
  ;; KEEPS `byte-string-at`, where `index-payload` uses the single-long
  ;; `bs-data`/`bs-len`. Those are defined BELOW this function, so using them
  ;; here means going through the `declare` -- which loses the `^long` return
  ;; hint and made the open 0.87 us / 647 B become 10.5 us / 25 370 B, a 39x
  ;; allocation regression from a forward reference. Called once per open, so
  ;; the vector costs less than moving the definitions would risk.
  (when-let [[data len] (byte-string-at r p)]
    (let [len (long len)]
      ;; ONE BYTE IS A VALID TABLE -- the layout byte and no pairs. That is a
      ;; document which opens a namespace and references nothing, and it is
      ;; navigable: there is simply nothing to resolve. Requiring `>= 2` and a
      ;; positive pair count refused every indexed document whose strings did
      ;; not repeat, including ones holding no strings at all, because the
      ;; default profile opens a namespace before it knows the content.
      (when (>= len 1)
        (let [lay (.byteAt r (long data))
              iw (bit-shift-left 1 (bit-and (unsigned-bit-shift-right lay 4) 3))
              ow (bit-shift-left 1 (bit-and (unsigned-bit-shift-right lay 6) 3))
              pw (+ iw ow)
              body (dec len)]
          (when (and (= stringref-layout-v1 (bit-and lay 0xF))
                     (zero? (rem body pw)))
            ;; A `long[4]`, not a vector -- see `slot-table`. [base count iw ow].
            (doto (long-array 4)
              (aset 0 (inc (long data))) (aset 1 (quot body pw))
              (aset 2 iw) (aset 3 ow))))))))

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
        ;; A `long[2]`, not a vector -- one allocation per index open, and the
        ;; open is what a scan pays per row. [table-base entry-width].
        (doto (long-array 2) (aset 0 (+ slots-data tbase)) (aset 1 w))))))

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

;; ONE LONG EACH, because the vector above is per-OPEN allocation on the scan
;; path and the open is now the whole of what a scan allocates per row. Two
;; calls re-read the head, which is a byte and a branch; the vector was ~72
;; bytes and a boxed pair. `-1` for "not a definite-length byte string", which
;; every caller already treats as "no index".
(defn- bs-data ^long [^Reader r ^long off]
  (if (and (= 2 (.majorAt r off)) (not= 31 (.infoAt r off))) (.headEndAt r off) -1))

(defn- bs-len ^long [^Reader r ^long off]
  (if (and (= 2 (.majorAt r off)) (not= 31 (.infoAt r off))) (.headArgAt r off) -1))

;; ONE LONG EACH -- see `bs-data`. `index-payload` reads `containers` and
;; `counts` through these and tries tag 78 before 79, so the vector-returning
;; `le-array-at` these replace allocated up to four per open.
;;
;; THE TAG CHECK IS NOT OPTIONAL, and this is the whole compatibility argument
;; for the v2 layout: without it the PREVIOUS frame shape -- where this element
;; was a CBOR array -- gets a `headEndAt` and a `headArgAt` that both succeed,
;; returning the element COUNT and the first element's offset, and the reader
;; goes on to compute node offsets from them. The refusal has to be exact.
;;
;; NEITHER LENGTH IS CHECKED AGAINST THE FILE HERE. `count-at` and
;; `container-at` are in-file for every `i < n` because of two things
;; elsewhere: `read-index*`'s `(= n (skip r ptr))` forces the frame to tile
;; exactly to EOF, and `Reader.checkCount` refuses a byte-string length past
;; `remaining()`.
(defn- le-data ^long [^Reader r ^long off ^long tag]
  (if (and (= 6 (.majorAt r off)) (= tag (.headArgAt r off)))
    (bs-data r (.headEndAt r off))
    -1))

(defn- le-len ^long [^Reader r ^long off ^long tag]
  (if (and (= 6 (.majorAt r off)) (= tag (.headArgAt r off)))
    (bs-len r (.headEndAt r off))
    -1))

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
        lim (.data-end ix)
        w (width-code r sdata i)
        sz (bit-shift-left 1 w)
        ;; RELATIVE to `slots-data`, so the bound below compares against
        ;; `slots-len` and only the delta reads add the base back.
        start (slot-start r (.slots-tbase ix) (.slots-wstart ix) i)
        ;; THE ANCHOR COUNT COMES FROM THE TABLE, not from `anchor-count` of a
        ;; wire count and the stride.
        ;;
        ;; It has to, now that stride is PER NODE -- there is no single stride
        ;; to derive it from. But it is also the safer of the two, which is why
        ;; the change is a simplification rather than a cost. `count-at` is a
        ;; raw sint32 load off the wire that nothing cross-checks against the
        ;; container, and at stride 1 `anchor-count` returned it verbatim: one
        ;; flipped bit reached 2^31-1 and `(long-array m)` raised
        ;; OutOfMemoryError, an ERROR that walks through every catch here and
        ;; out of `get`. The table is bounded by the O(1) structural gate --
        ;; its last entry must equal the byte string's own length -- so a span
        ;; taken between two of its entries cannot exceed the bytes that exist.
        nxt (slot-start r (.slots-tbase ix) (.slots-wstart ix) (inc i))
        m (quot (- nxt start) sz)]
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
    (when (or (neg? start) (neg? m) (> (+ start (* m sz)) slen))
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

(defn- anchor-at
  "Node `i`'s anchor `k`, and its anchor COUNT, without materialising the node.

  `slot-at` allocates a `long[m]` and prefix-sums every anchor of the node.
  Two of its three callers then read ONE element: `skip-value` takes the last
  anchor, `nth-item` takes the anchor its index lands on. Only `lookup-map`'s
  binary search genuinely needs random access, and it keeps `slot-at`.

  MEASURED on a 3000-element array of two-key maps, one skip per row, min of 7
  runs of 20 000 calls:

      slot-at, stride 1 (m=3000)     9.075 us   24016 B
      this,    stride 1              4.580 us      32 B
      slot-at, stride 16 (m=188)     0.584 us    1520 B
      this,    stride 16             0.352 us      32 B

  The 24 KB is a `long[3000]` built to read its last element, and it was the
  whole of the stride-1 allocation penalty: 24016 + 1290 (the index open) + 11
  (the rest of the per-row path) is exactly the 25 317 B/row a scan measured.

  `k` OF -1 MEANS THE LAST ANCHOR, which is what `skip-value` wants and what it
  would otherwise need the count to ask for.

  RETURNS TWO LONGS IN A FRESH `long[2]` -- [anchor count] -- rather than a
  vector or a map. 32 bytes against the 24 016 a `long[3000]` costs, and it
  cannot take the array from the caller instead: that would be a fifth
  argument, and Clojure caps a fn with primitive hints at four.

  THE BOUNDS ARE `slot-at`'s, deliberately duplicated rather than shared: they
  are what keeps a damaged frame from reaching `Reader.skipFrom`'s unchecked
  array access, and a refactor that let one path keep them while the other
  drifted is the failure this file has had three times. Any change here is a
  change there.

  DO NOT BOX THE ACCUMULATOR. Written first with the result array constructed
  inside the loop's else branch, which made the accumulator an Object for all
  m iterations and ran 3x SLOWER than the allocating version it replaces."
  ^longs [^Reader r ^Index ix ^long i ^long k]
  (let [sdata (.slots-data ix)
        slen (.slots-len ix)
        lim (.data-end ix)
        w (width-code r sdata i)
        sz (bit-shift-left 1 w)
        start (slot-start r (.slots-tbase ix) (.slots-wstart ix) i)
        nxt (slot-start r (.slots-tbase ix) (.slots-wstart ix) (inc i))
        m (quot (- nxt start) sz)]
    (when (or (neg? start) (neg? m) (> (+ start (* m sz)) slen))
      (throw (ex-info "boring: index slot segment outside the packed slots"
                      {:type :boring/bad-index :node i :start start
                       :anchors m :width sz :slots-length slen})))
    (let [want (if (neg? k) (dec m) k)]
      (loop [j 0
             p (+ sdata start)
             acc (max 0 (container-at r ix i))]
        (if (or (>= j m) (> j want))
          (doto (long-array 2) (aset 0 acc) (aset 1 m))
          ;; `unchecked-add` and the range check for the reasons `slot-at`
          ;; gives -- a width-3 delta is an unconstrained signed 64-bit value
          ;; off the wire, and `+` on primitive longs throws on overflow.
          (let [v (unchecked-add acc (delta-at r p w))]
            (when (or (neg? v) (>= v lim))
              (throw (ex-info "boring: index anchor outside the data section"
                              {:type :boring/bad-index :node i :anchor j
                               :offset v :data-end lim})))
            (recur (inc j) (+ p sz) v)))))))

(defn- index-payload
  "The usable index in the tag-27 frame at `ptr`, or nil meaning \"scan\".

  Split out of `read-index` because it is the half that can FAIL. Detection --
  the tail shape, the pointer, the name -- establishes that something intends to
  be an index; this establishes that its payload can actually be used, which is
  a different question and the one a truncated or hand-edited file gets wrong.

  ANY exception here yields nil. Nothing in the index is load-bearing, so the
  honest response to a payload we cannot use is to ignore it and walk, never to
  throw at the caller of `nav/source`.

  ITS REACH IS THE OPEN, NOT THE LOOKUP, and that distinction is worth keeping
  straight. Slots are expanded per call by `slot-at`, not here -- only the
  SEQUENCE node is expanded eagerly. So a malformed slot on a container node is
  not caught by this `try` at all: `slot-at` raises a typed `:boring/bad-index`
  at the lookup that touches it, which is deliberate and documented there.

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
          ^longs offs (try (payload-offsets r ptr) (catch Exception _ nil))
          off-stride (when offs (aget offs 0))
          off-containers (when offs (aget offs 1))
          off-counts (when offs (aget offs 2))
          off-slots (when offs (aget offs 3))
          off-sorted (when offs (aget offs 4))
          off-after (when offs (aget offs 5))
          ;; THE PAYLOAD'S ELEMENT COUNT decides what `off-after` names. Six
          ;; elements and it is `data-end`; seven and it is the stringref
          ;; pointer table, with `data-end` after it. `read-index*` has already
          ;; checked this byte is one of 0x86-0x8f, so the subtraction is safe.
          payload-n (try (- (.byteAt r (+ ptr frame/prefix-head-length)) 0x80)
                         (catch Exception _ 6))
          ;; PUSHED ONTO THE READER, not carried on the Index. #12 took the
          ;; Reader off Index so a forked navigator could not read through a
          ;; stale one; carrying the table the other way would re-tie that knot.
          ;; Set unconditionally -- including the (0 0 0 0) that clears it --
          ;; because a Reader is reused across documents and a table left over
          ;; from the previous one names offsets into bytes that are gone.
          _ (let [t (when (and off-after (>= (long payload-n) 7))
                      (try (stringref-table-at r (long off-after))
                           (catch Exception _ nil)))]
              (if t
                (.setStringrefPointers r (aget ^longs t 0) (int (aget ^longs t 1))
                                       (int (aget ^longs t 2)) (int (aget ^longs t 3)))
                ;; NOT `setStringrefPointers` with four zeros, which is what
                ;; this was. An EMPTY table is now a meaningful state -- a
                ;; document that opens a namespace and references nothing --
                ;; so all-zeros can no longer double as "there is no table".
                (.clearStringrefPointers r)))
          ;; STRIDE POSITIONALLY, which removes the last `.readFrom` from this
          ;; function. A uint under 2^63; a negint is major 1, a bignum major 6,
          ;; a float major 7, and a uint64 above 2^63 comes back negative -- so
          ;; the `pos?` below refuses all of them, exactly as `(int? stride)`
          ;; and `(pos? stride)` did together.
          stride (when (and off-stride
                            (zero? (.majorAt r (long off-stride)))
                            (< (.infoAt r (long off-stride)) 28))
                   (.headArgAt r (long off-stride)))
          slots-data (when off-slots (let [d (bs-data r (long off-slots))]
                                       (when-not (neg? d) d)))
          slots-len (when slots-data (bs-len r (long off-slots)))
          sorted-data (when off-sorted (let [d (bs-data r (long off-sorted))]
                                         (when-not (neg? d) d)))
          sorted-len (when sorted-data (bs-len r (long off-sorted)))
          ;; CONTAINERS AT EITHER WIDTH, chosen by the tag rather than tested
          ;; for. Tag 78 is int32, tag 79 sint64; both are RFC 8746
          ;; little-endian, so entry `i` is `cw` bytes at `containers-data +
          ;; cw*i`. The `long[]` normalisation this replaces cost 1.21 us per
          ;; open on a 770-node frame -- more than the decode it normalised.
          cw (when off-containers
               (cond (not (neg? (le-data r (long off-containers) 78))) 4
                     (not (neg? (le-data r (long off-containers) 79))) 8))
          containers-data (when cw (le-data r (long off-containers) (if (= 4 cw) 78 79)))
          containers-len (when cw (le-len r (long off-containers) (if (= 4 cw) 78 79)))
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
          nw (when off-counts
               (cond (not (neg? (le-data r (long off-counts) 78))) 4
                     (not (neg? (le-data r (long off-counts) 79))) 8))
          counts-data (when nw (le-data r (long off-counts) (if (= 4 nw) 78 79)))
          counts-len (when nw (le-len r (long off-counts) (if (= 4 nw) 78 79)))
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
      ;; AND BOUNDED ABOVE, not merely positive. `lookup-map`'s `span` computes
      ;; `(* a stride)` on longs, and Clojure's `*` on primitive longs THROWS on
      ;; overflow -- so a frame declaring a stride near 2^63 raised a raw
      ;; ArithmeticException out of `get`, which is precisely the untyped
      ;; failure this namespace promises cannot happen.
      ;;
      ;; It was unreachable until `slot-at` began deriving the anchor count from
      ;; the start table instead of from `anchor-count` of the wire count and
      ;; the stride: that derivation CLAMPED `m` to 1 for any huge stride, so
      ;; `span` could only ever compute `0 * stride`. The coupling was
      ;; accidental and load-bearing, and removing it is what exposed this.
      ;;
      ;; `max-index-stride` is what the writer will emit at most (`options`
      ;; refuses more), so nothing legitimate is refused here. With the stride
      ;; under 2^31 and `m` bounded by the file's own bytes, `(* a stride)`
      ;; cannot overflow.
      (when (and stride (pos? (long stride))
                 (<= (long stride) opt/max-index-stride)
                 containers-data n
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
                 (= (long sorted-len) (quot (+ (long n) 7) 8)))
        ;; ONE PROVISIONAL INDEX, built as soon as every offset is known, and
        ;; used for the two things that still read `containers`: the sentinel
        ;; check and the sequence node's anchors.
        ;; It IS the final Index whenever there is no sequence node, which is
        ;; every `encode-indexed` blob, so that case allocates exactly one.
        (let [st (long stride)
              ix0 (Index. st (long containers-data) (long cw)
                          (long counts-data) (long nw) (long n)
                          (long slots-data) (long slots-len)
                          (aget ^longs table 0) (aget ^longs table 1)
                          (long sorted-data) ptr nil nil)]
          ;; NO ASCENDING CHECK, and no counts check either -- a negative
          ;; count cannot reach past `slot-at`, which bounds its own segment
          ;; and its own anchors. It was the last O(NODE COUNT) work at open,
          ;; and on a node-rich document it was not a residual -- it WAS the
          ;; open. Measured, same document and probe:
          ;;
          ;;      nodes    with check    without
          ;;        770      8.25 us      4.25 us
          ;;      16150     71.07 us      4.08 us
          ;;
          ;; The open is now flat in node count. That matters beyond the
          ;; number: it removes the last way one container's presence in the
          ;; index taxes every other lookup in the document, which is what a
          ;; per-container indexing policy needs in order to be decidable
          ;; locally at all.
          ;;
          ;; AND IT WAS NEVER LOAD-BEARING THE WAY IT LOOKED. `node-slot`
          ;; validates its own answer: the binary search accepts a node only on
          ;; `(= c off)`, so it cannot return a node describing a DIFFERENT
          ;; container. On a mis-ordered array the search may fail to find a
          ;; node that exists -- which yields -1, and -1 means walk, which is
          ;; correct. The residue is a crafted frame carrying DUPLICATE
          ;; container offsets, where two nodes both claim `off` and the search
          ;; picks one; `slot-at` still bounds its anchors, so that is a wrong
          ;; answer inside the trust boundary, exactly like every other lie the
          ;; frame is free to tell.
          (when true
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
                          (aget ^longs table 0) (aget ^longs table 1)
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
              (or (when-not (= :ignore (:trust-index (.opts nav)))
                    (index-payload r ptr))
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

(deftype ^:no-doc Items [^Nav nav ^Index idx]
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
            ;; defect. See doc/INDEX.md.
            (let [anchor (quot (long i) stride)]
              ;; THE ANCHOR MUST BE THERE. `total` and the anchor array's
              ;; length are INDEPENDENT now: the count comes off the wire and
              ;; the length is derived from the start table, where it used to
              ;; be `anchor-count` of the one from the other and the two agreed
              ;; by construction. A damaged table therefore yields a short
              ;; array against a large count, which was a raw
              ;; ArrayIndexOutOfBoundsException out of `nth` -- found by the
              ;; every-bit sweep, not by reasoning. Same guard `nth-item`
              ;; already carries, and the same fallback: walk honestly.
              (if (>= anchor (alength offsets))
                (loop [k 0 p 0]
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
  doc/INDEX.md for where that stops: damage leaving the payload structurally
  CONSISTENT, bit rot included, can still misdirect), and
  a missing, truncated or stale one falls back to scanning.

  Detecting the index is not only about speed. The index is itself a top-level
  item, so without recognising it this would yield it as though it were data.

  A SEQUENCE forces `:stringref false`, per item -- `write-seq!` resets the
  namespace for each top-level item, so one frame cannot describe them all.
  That is specific to sequences and NOT true of the rest of this
  namespace."
  ([src] (items src nil))
  ([src opts]
   ;; A SOURCE IS ACCEPTED HERE TOO, as it is by `root` and `cursor`. This took
   ;; `nav-of` directly, which knows only bytes and a ByteSource, so
   ;; `(items (source bs))` raised `:boring/unsupported-source` -- a `source`
   ;; was the universal handle for one half of the namespace and rejected by
   ;; the other.
   ;;
   ;; `opts` is ignored for a source that already exists, because its Reader is
   ;; already configured; passing different options here would silently not
   ;; apply them.
   (let [nav (if (or (bytes? src) (instance? ByteSource src))
               (nav-of src (or opts {}))
               (source-of src))]
     (Items. nav (nav-idx nav)))))

(defn- items?
  "Whether `x` is an `Items`. Declared above and defined here for the same
  reason `nav-of-items` is: `source-of` has to discriminate on the type, and
  naming `Items` up there is a class the compiler has not seen yet -- which is
  a compile error, not a warning, and one the REPL hides because a previous
  load left the class present."
  [x] (instance? Items x))

(defn- nav-of-items
  "The `Nav` behind an `items`. Exists only so `source-of` can reach it: that
  function sits above `Items` in this file, and a `^Items` hint there would
  name a class the compiler has not seen yet."
  [^Items c] (.nav c))

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
