# Changelog

All notable, user-visible changes to boring are documented here.

The **library** is beta and this file records API changes as they happen. The
**format** is not beta — see [doc/COMPATIBILITY.md](doc/COMPATIBILITY.md) for
what is promised about the bytes, which is a stronger promise than anything
here.

## Unreleased

### Added

- **Random access into encoded CBOR**, and the sealed offset index that makes it
  fast. `boring.nav` walks the wire format and materialises only what you ask
  for; `write-seq!` seals an index by default and `boring.nav/items` uses it to
  reach item *n* without stepping over the *n-1* before it — 9.8 ms to 1.6 µs on
  a 200 000-item log, at 0.34% file overhead. The frame is ordinary CBOR (tag 27,
  `boring/index`), so a file carrying one stays readable by any CBOR
  implementation; see [doc/SHAPES.md](doc/SHAPES.md) for the format.
- `encode-indexed`, `build-index` and `seal-index!` — index an already-encoded
  blob rather than capturing while writing. **Available on both platforms**, and
  byte-identical between them, so a browser and a server produce the same file.
  Writing an index *while streaming* (`write-seq!` with `:index`) remains
  JVM-only; reading one works everywhere.
- `write-indexed!` — a single value, streamed to an `OutputStream` with an index
  sealed after it.
- `write-to-buffer!` — encode into a caller-supplied `ByteBuffer`, allocation-free.
- `trim!` — give a reused writer back what one exceptional job grew. Every growth
  in a writer is otherwise one-way, which is what makes reuse allocation-free.
- `boring.mmap` — navigate a memory-mapped file without reading it into the heap.
- `{:trust-index :ignore}` — `boring.nav` skips a sealed index and scans. A
  chosen index can misdirect a lookup within the blob it arrived with, which
  matters when you verify one part of a document and act on another; see
  [doc/SECURITY.md](doc/SECURITY.md).
- `boring.hasch` is now IN THE JAR. It was an alias-only source path, so the
  namespace this changelog already advertised was simply absent from the
  artifact — and its absence is silent: with hasch present and this
  integration missing, two different record types and a plain map all
  content-address to the same uuid.
- `:profile :archival` — sorted map keys AND fixed-width floats. That combination
  was previously unreachable: `:canonical` locks the float width and `:interop`
  locks the key order, so the one thing a portable database dump needs could not
  be asked for.
- `:profile :canonical-rfc7049` — clj-cbor's and Python cbor2's length-first key
  ordering, named rather than left as a knob on `:canonical`. Verified against
  cbor2 and Rust ciborium over 989 values, byte for byte.
- [doc/STORAGE.md](doc/STORAGE.md).

- **`:on-unknown-record`**, a decode option for what a tag-27 name no registry
  resolves should become: `:fallback` (the default, unchanged — the
  `UnknownRecord`/`TaggedLiteral` carrier), `:error` for
  `:boring/unregistered-record`, or `(fn [name payload])` whose return value is
  used.

  The default is lossless passthrough, which is why the carrier exists: a relay
  must be able to carry a type it has no constructor for. `:error` is for the
  opposite need, because that passthrough makes a registry that can *never*
  match completely silent — a record simply arrives as an `UnknownRecord`, with
  no error and no warning. That is not hypothetical: it is how the record wire
  name change below went unnoticed in boring's own nippy suite.

  There is deliberately no `:warn`. boring would have to own an output channel
  (`*err*` versus `js/console.warn`) and the dedupe state to keep a 200 000-item
  log from flooding, and neither is testable without capturing output. The
  function form is that capability without boring choosing a logger for anyone:

      (boring/decode bs {:on-unknown-record
                         (fn [nm payload]
                           (log/warn "unregistered record" nm)
                           (boring.data/frame-for nm payload))})

- **`boring.data/frame-for`**, the rule both readers use to pick a carrier for
  an unresolvable tag-27 frame — `UnknownRecord` for a map payload, a
  `TaggedLiteral` for anything else. Public so an `:on-unknown-record` handler
  that only wants to warn can return the default instead of reimplementing it,
  which is how the two would drift apart.

- **`boring.mmap/mmap-source` and `mmap-items` take `:offset` and `:length`**,
  narrowing the mapping to part of a file. A CBOR document is not always the
  whole file: konserve stores a blob as a 20-byte header, then metadata, then
  the value, so the value begins partway in and mapping from zero addresses
  the header as though it were CBOR.

      (mmap/mmap-source path {:offset (+ 20 meta-size)})

  `:length` defaults to the rest of the file. An offset or length past the end
  is a typed `:boring/bad-argument` naming the file size, rather than an
  `IndexOutOfBoundsException` from `MemorySegment.asSlice`.

  The mechanism already worked — `asSlice` plus `segment-source` — but only by
  reaching past `boring.mmap` into both. This is the way to ask for it.

- **`decode`, `reader` and `decode-with` accept a `ByteSource`**, not only a
  `byte[]`. The Java `Reader` has taken one since `boring.nav` needed it, but
  the Clojure API did not — so a caller holding off-heap bytes had to copy into
  a `byte[]` to decode them at all. Navigation could read a source and a
  whole-value decode could not, which is backwards: a store's normal case is
  wanting the whole value.

      (boring/decode (mmap/segment-source seg))        ; no byte[] materialised
      (boring/decode-with reader seg-source)           ; one Reader, a segment per value

  Nothing is gated on JDK 22 by this. `ByteSource` is deliberately a JDK-9
  interface naming no FFM type; only a caller who CONSTRUCTS a segment-backed
  source needs the newer JDK.

### Changed

- **BREAKING: `boring.nav/source` returns a *source*, not a root cursor.**
  `(nav/root bs opts)` is what it used to be, and is what you want for `get`,
  `seq`, `walk` and friends.

  The split names a concept the namespace did not have. A **source** is the
  document — the bytes, the reader over them, the index, the shape cache. A
  **cursor** is a position inside one. Without that distinction an
  allocation-free field lookup has to take a cursor and *ignore* its offset,
  which is both meaningless and a trap.

  The rule: **a cursor is a position you can hold; an offset is a position you
  can only use.** Exploring, holding, printing, `get`-ing, `seq`-ing — cursors.
  Inside a loop whose trip count is the size of your data — the source and its
  offsets, where a million-row projection allocates 0.62 GB through cursors and
  none at all through offsets.

  A source implements no collection interface, and `get` on one *throws* rather
  than answering nil — because `clojure.core/get` returns nil for anything that
  is not `ILookup`, and a silently empty projection is exactly what this change
  must not cause.

  `source-at` is deprecated in favour of `cursor`, which is the same function
  under a name that says what it returns. New: `root`, `cursor`, `offset`,
  `source-of`. The bridge is exact — `(cursor (source-of c) (offset c))` is
  `c`, for everything except a shaped row, whose `shape` is cursor state an
  offset cannot carry.

- **`boring.nav/re-point!`** — point an existing source at different bytes and
  get its root cursor back, reusing the reader, the nav, the probe cache and
  the cursor. A scan over many blobs then allocates nothing per row. It is
  sharp on purpose: cursors previously taken from that source now address the
  new bytes, so it is for a loop that finishes with each document before
  starting the next, and it must not be shared across threads even by the
  standards of the rest of the namespace.

- **BREAKING: the index frame is a trust boundary, and a corrupt one may now
  change an answer.** boring previously promised that *a missing, stale,
  truncated or randomly corrupt index may cost speed; it may not change an
  answer and it may not throw at the caller*. That promise required re-deriving
  at read time what the frame asserts — verifying each anchor against its
  predecessor costs O(stride) *skips* per jump, and a skip is O(1) only for a
  scalar, so stepping over 16 twenty-entry maps is ~640 sub-skips. Measured at
  four times the cost of the lookup it guarded.

  The promise is withdrawn rather than quietly falsified. Use the index where
  you are willing to trust the bytes; corruption beneath boring is the storage
  layer's job, and both known consumers have one.

  **What is still promised, and is not negotiable**: no untyped exception ever,
  from any damage to any byte — a wrong answer is inside the boundary, an
  `ArrayIndexOutOfBoundsException` or `OutOfMemoryError` out of `get` is not;
  no read outside the file; a reader that consults no index is never affected
  by frame damage; and undamaged data always reads correctly. See
  [doc/SHAPES.md](doc/SHAPES.md).

- **`:trust-index :trusted` is accepted and does nothing.** There is one path
  now, and it is faster than the old *trusted* one was — the checks it used to
  skip are gone for everyone. The key is kept rather than removed because a
  removed option key silently no-ops. `:trust-index :ignore` is unchanged and
  still skips the index entirely.

- **The index frame is recognised with six *through fifteen* payload
  elements**, where it previously required exactly six. Nothing writes more
  than six. This is forward compatibility, and the reason it matters is that
  the failure mode of *not* recognising a frame is the worst one available: a
  reader that does not recognise it never learns where the data section ends,
  so the frame is republished as a trailing **data** item and a file of N
  records reads back as N+1 — silently, and in both directions. Refusing to
  *use* an index is safe; refusing to *see* one is not.

  Readers from this version on therefore treat a widened frame as a frame. A
  widened payload must insert its new elements **before** the trailing
  back-pointer, which stays last: the trailer the whole scheme is located by is
  the file's final 9 bytes.

- **The index reader no longer materialises any of the frame.** `containers`,
  `counts`, `slots` and `sorted` are read in place as byte offsets. Opening an
  index on a 769-node document went from 17 840 to 1 664 bytes allocated, and
  the open is now flat in both node count and anchor count — a 16 150-node
  document opens in the same time as a 770-node one, and stride 1 costs no more
  than stride 16. Neither the format nor any API changes.

- **BREAKING, ClojureScript: a reserved tag-27 marker naming a type
  ClojureScript does not have now decodes to a frame-preserving carrier**
  instead of to its bare payload. `27(["java/period", "P1D"])` was `"P1D"` and
  is now `#java/period "P1D"`, read with `boring.data/frame-payload`; the same
  applies to `clojure/char`, `java/char-array`, `java/boolean-array`,
  `java/string-array` and `java/object-array`. The bare payload was not
  round-trip safe — re-encoding it emitted a plain text string or array, the
  tag-27 frame was gone, and a JVM peer received a `String` where it had sent a
  `java.time.Period`. All eleven markers now re-encode to the bytes they
  decoded from, on both platforms and under every profile. The four whose type
  ClojureScript has (`sorted-map`, `sorted-set`, `queue`, `ex-info`) are
  unchanged.

  Both platforms can also now *originate* every marker, since both writers
  encode a `TaggedLiteral` to its frame:
  `(boring/encode (tagged-literal 'clojure/char "a"))` gives identical bytes on
  either platform and decodes to `\a` on the JVM.

- **BREAKING: `java/period` accepts only the canonical ISO-8601 form** — what
  `java.time.Period.toString()` emits. `Period.parse` accepts lower case, a
  leading sign, per-component signs, weeks and leading zeros, but a `Period`
  stores years, months and days and no spelling, so those cannot be stored
  faithfully: `27(["java/period", "P1W"])` used to decode and re-encode as
  `P7D`. Two parser differentials closed with it, one in each direction: `p1d`
  was accepted on the JVM and refused on ClojureScript, and `P2147483648D` was
  accepted on ClojureScript and refused on the JVM.

- **BREAKING, wire format: a record's type name is now `namespace/Name` as
  WRITTEN**, where it was the munged JVM class name (`my_ns.MyRecord`). A
  record written by 0.1.10 or earlier decodes as an `UnknownRecord` under this
  release — silently, since an unrecognised tag-27 name is a legitimate value
  rather than an error. Re-register the old name alongside the new one if you
  have such data:

      (-> (boring/tag-registry)
          (boring/register-record "my_ns.MyRecord" map->MyRecord)   ; old
          (boring/register-record "my-ns/MyRecord" map->MyRecord))  ; new

  Two flaws are fixed by this, and neither was ClojureScript's. Clojure munges
  `-` to `_` when it builds a record's class name, so the JVM had LOST the
  namespace as written while ClojureScript's `pr-str` still had it — and boring
  munged ClojureScript down to match, discarding information one platform still
  had so it could agree with the one that had lost it. The munge is invertible
  by looking the namespace up rather than guessing, so both platforms now carry
  the true name and agree, which they never did. And a dot cannot say where a
  namespace ends (`a.b.c.D` splits two ways), while a slash is legal in
  neither part — and is what boring's own reserved names already use:
  `clojure/sorted-map`, `java/period`.

  A record whose type name reached the wire through a `write-fn` you supplied
  is unaffected; only the derived name changed.

- **An unknown option key within one edit of a real one is now refused**, with
  the suggestion: `{:max-item 5}` raises `:boring/bad-option` naming
  `:max-items`. Keys unlike any option — `:konserve/version` — still pass
  untouched, so threading your own map through keeps working.

- `Items.nth`'s 2-arity throws out of range, as `Indexed` specifies and as
  `Cursor.nth` already did. The 3-arity not-found form is unchanged.

- ClojureScript `write-seq!` writes with `:stringref false`, matching the JVM's
  indexed default, so its output is navigable by `boring.nav`. Use
  `encode-into!` in a loop if you want the compression instead.

- ClojureScript `:instant-type` takes a FUNCTION of epoch milliseconds — plug
  in `cljc.java-time`, `tick`, or your own — where it was previously accepted
  and ignored. Omitted, a `js/Date` comes back as before.

- `write-seq!` **indexes by default** (stride 16). Files it wrote before this
  release remain readable; files it writes now carry a trailing index item that
  older boring versions will surface as an extra `boring/index` value at the end
  of the sequence rather than as an error. Pass `{:index 0}` for the old output.

### Fixed

- **ClojureScript wrote index offsets past 2 GiB as negative numbers.**
  `seal-index!` emitted a 32-bit typed array unconditionally, and
  `Int32Array.from` wraps rather than refusing — so an offset at or above 2^31
  went out negative, which is precisely the case 64-bit offsets exist for. The
  JVM writer had always promoted to 64-bit; ClojureScript now does too.

- **A damaged index could raise an untyped `OutOfMemoryError` out of `get`.**
  The anchor array was allocated from an entry count read off the wire *before*
  the bound check that refuses it, so one flipped bit reached 2^31−1 — and
  `Error` walks through every catch between the frame parser and the caller.
  Reachable from a single bit flip in a file boring itself wrote.

- **A crafted index could raise an untyped `ArithmeticException` out of
  `get`.** Anchors are a prefix sum, and a 64-bit delta near `Long/MAX_VALUE`
  overflowed the sum before the range check that refuses it. Clojure's `+` on
  primitive longs is checked; the sum is now unchecked, so the wrapped value
  fails the range check that already existed.


- Cross-platform: `encode-indexed` produced different bytes on ClojureScript
  than on the JVM, because the index's `sorted` flag was hardcoded rather than
  computed. Content-addressed stores would have seen two hashes for one value.
- ClojureScript: 27 bytes could hang `decode-seq` indefinitely — the structural
  skip validated neither bounds nor declared counts.
- A declared count could size an index array before being checked against the
  bytes present, so six bytes were an `OutOfMemoryError`.
- Every read path now reports a typed error for a `nil` input, a stack overflow,
  and a damaged index frame, on both platforms.

- First public release. `boring.core` — `encode`, `decode`, `write-to!`,
  `write-seq!`, `decode-seq`, `decode-seq-from`, `writer`, `tag-registry`,
  `register-tag`, `register-record`.
- **Complete Clojure type coverage without a schema**: keywords and symbols
  (tag 39), records (tag 27), sets (tag 258), bignums, `BigDecimal` with its
  scale intact, ratios, characters, metadata, sorted maps and sets, queues,
  instants, durations ([RFC 9581][rfc9581]), periods, local dates
  ([RFC 8943][rfc8943]), UUIDs, URIs (tag 32), regexes (tag 35), byte arrays,
  primitive arrays as [RFC 8746][rfc8746] typed arrays, and CBOR's own simple
  values and `undefined`. 46 of the 49 keys in nippy's `stress-data` round-trip
  to an equal value, checked in CI by calling nippy rather than copying it.
- **ClojureScript**, from the same `.cljc` conformance suite as the JVM, with
  31/31 byte-identical cross-platform vectors asserted.
- **Profiles** — `:clojure` (default), `:interop`, `:canonical`,
  `:canonical-rfc7049`. `:canonical` follows RFC 8949 §4.2 and
  draft-ietf-cbor-serialization, and produces byte-identical output for equal
  values so dumps can be signed.
- **Streaming** in bounded memory both ways: `write-seq!` and `decode-seq-from`
  over [RFC 8742][rfc8742] CBOR sequences. Measured at 5M datoms — a 199 MB
  dump written and streamed back under a 64 MiB heap.
- **Shaped arrays** (`{:shapes true}`, tag 39649, off by default) — key
  stripping for arrays of same-shaped maps. Halves the wire size and nearly
  doubles decode speed on datom-shaped data; see
  [doc/SHAPES.md](doc/SHAPES.md), including the case where it *costs* you.
- **Stringref** (tags 25/256, on by default), scoped per top-level item.
- **Metadata preserved by default**, matching nippy. `{:incl-metadata? false}`
  opts out.
- Optional [hasch][] integration (`boring.hasch`), auto-loaded when hasch is on
  the classpath, so an unregistered record and its instantiated form
  content-address alike.
- Reference readers in Python ([cbor2][]) and Rust ([ciborium][]) executed in
  CI rather than pasted into the documentation — see
  [doc/INTEROP.md](doc/INTEROP.md).

### Notes

- Requires **JDK 9+** with no flags, enforced by `bin/check-artifact`, which
  builds the jar and refuses a class-file version above Java 9's.
- Tag **39649** (shaped arrays) is provisional and not yet IANA-registered. It
  is off by default; enabling it opts into a number that may move. Tag 39650
  (shaped maps) is specified but deliberately not shipped.
- Known untested: no production datahike store has been round-tripped through
  boring yet, and there is no decode-side canonical *validation* — boring can
  produce canonical bytes but cannot verify that incoming bytes were canonical.
  See [doc/MIGRATE-CODEC.md](doc/MIGRATE-CODEC.md) §6 and
  [doc/SECURITY.md](doc/SECURITY.md).

[rfc8746]: https://www.rfc-editor.org/rfc/rfc8746
[rfc8742]: https://www.rfc-editor.org/rfc/rfc8742
[rfc8943]: https://www.rfc-editor.org/rfc/rfc8943
[rfc9581]: https://www.rfc-editor.org/rfc/rfc9581
[hasch]: https://github.com/replikativ/hasch
[cbor2]: https://github.com/agronholm/cbor2
[ciborium]: https://github.com/enarx/ciborium
