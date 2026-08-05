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

### Changed

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
