# Changelog

All notable, user-visible changes to boring are documented here.

The **library** is beta and this file records API changes as they happen. The
**format** is not beta — see [doc/COMPATIBILITY.md](doc/COMPATIBILITY.md) for
what is promised about the bytes, which is a stronger promise than anything
here.

## Unreleased

### Added

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
