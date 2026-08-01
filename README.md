# boring

[![slack](https://img.shields.io/badge/slack-join_chat-brightgreen.svg)](https://clojurians.slack.com/archives/CB7GJAN0L)
[![clojars](https://img.shields.io/clojars/v/org.replikativ/boring.svg)](https://clojars.org/org.replikativ/boring)
[![circleci](https://circleci.com/gh/replikativ/boring.svg?style=shield)](https://circleci.com/gh/replikativ/boring)
[![last-commit](https://img.shields.io/github/last-commit/replikativ/boring/main.svg)](https://github.com/replikativ/boring/commits/main)

**Fast, portable serialization for Clojure and ClojureScript — in a format the
rest of the world can already read.**

Clojure's serialization story is split three ways. [nippy][] and [hako][] are
fast and JVM-only. [fressian][] is portable across Clojure but speaks to no
other language. [transit][] speaks to other languages, but it is slower, and
its own README — in the [Java][transit-java], [Clojure][transit-clj] and
[ClojureScript][transit-cljs] implementations alike — says what it is for:

> Transit is intended primarily as a wire protocol for transferring data
> between applications. If storing Transit data durably, readers and writers
> are expected to use the same version of Transit and you are responsible for
> migrating/transforming/re-storing that data when and if the transit format
> changes.

The libraries are at 1.x; the [specification][transit-format] is at 0.8. That
is a reasonable position for a wire protocol and a poor one for an archive —
and an archive is what [datahike][] needed, which is why boring exists.

Every one of those options trades reach for speed, or speed for reach, or
durability for either.

boring takes the reach: it is [CBOR][rfc8949], an IETF standard with
implementations in 26 languages. On the JVM it gives up nothing for it — it
beats nippy on every payload we measure and trades wins with [hako][]. On
ClojureScript it is always smaller on the wire than transit, faster on the
datom-shaped data it was built for, and slower on generic data — see
[Performance](doc/PERFORMANCE.md), which says exactly where.

```clojure
(require '[boring.core :as boring])

(boring/encode {:user/name "Ada" :scores [99 100] :tags #{:x :y}})
;; => #object["[B" ...]  49 bytes

(boring/decode *1)
;; => {:user/name "Ada", :scores [99 100], :tags #{:x :y}}
```

The same code runs on the JVM and in the browser. A Python, Rust or Go program
reads the bytes with its own CBOR library — [cbor2][], [ciborium][], fxamacker.
[Interop](doc/INTEROP.md) has worked examples in each.

## Install

```clojure
;; deps.edn
org.replikativ/boring {:mvn/version "RELEASE"}
```

The badge above carries the current version. Every push to `main` that passes
CI deploys to [Clojars](https://clojars.org/org.replikativ/boring).

JDK 9+ (no flags, no `--enable-native-access`) — enforced by `bin/check-artifact`,
which builds the jar and refuses a class-file version above Java 9's.
ClojureScript needs nothing.

## Speed

JVM, µs/op, quiet machine, everyone's public API (a fresh writer and reader per
call, as `nippy/fast-freeze` and `hako/encode` do). Lower is better; **bold**
is the winner. Regenerate with `clojure -M:bench -m published`.

| payload | op | boring | boring `:shapes` | hako | nippy |
|---|---|---:|---:|---:|---:|
| small-map | encode | **0.14** | 0.22 | 0.20 | 0.24 |
| small-map | decode | 0.31 | 0.31 | **0.25** | 0.29 |
| mixed | encode | **0.13** | 0.21 | 0.18 | 0.27 |
| mixed | decode | 0.21 | 0.21 | **0.18** | 0.22 |
| nested-map-50 | encode | 5.29 | 5.60 | **4.08** | 7.01 |
| nested-map-50 | decode | 7.08 | 7.11 | **4.34** | 8.06 |
| datom-maps-200 | encode | 26.91 | 15.48 | **15.05** | 45.02 |
| datom-maps-200 | decode | 21.01 | **11.41** | 11.54 | 49.97 |
| long-vec-1k | encode | 6.74 | 6.76 | **6.65** | 10.91 |
| long-vec-1k | decode | 11.05 | 11.07 | **4.65** | 10.23 |
| str-maps-200 | encode | **23.17** | 25.48 | 23.31 | 36.50 |
| str-maps-200 | decode | 22.63 | **13.77** | 23.20 | 38.86 |

Against nippy that is a win on all twelve cells. Against [hako][] — an
experimental codec built for speed, and the fastest thing in this table — it is
mixed.

On nippy's *own* benchmark (`clojure -M:nippy-bench` — nippy's `stress-data`,
nippy's filter, nippy's timing loop), round-trip µs and bytes:

| codec | round µs | bytes |
|---|---:|---:|
| **boring** | **595** | 15 326 |
| nippy/fast | 932 | 17 105 |
| nippy (LZ4) | 1 102 | 8 518 |
| **boring + zstd** | **1 102** | **4 900** |
| nippy/encrypted | 1 164 | 8 546 |
| fressian | 2 860 | 12 222 |
| fressian + zstd | 3 124 | 4 600 |
| `pr-str` + `read-string` | 5 369 | 15 880 |
| nippy/lzma2 | 9 079 | 3 888 |

Raw, boring is 1.6× nippy/fast. Compressed — which is what a storage layer
actually writes — boring+zstd matches nippy's default round-trip time at
**1.7× smaller**. Only nippy/lzma2 goes smaller, at 8× the time.

**Where it loses.** hako is faster on deeply nested maps (and 1.4× smaller
there), 2.4× faster decoding a plain vector of integers, and marginally ahead
on the two small payloads' decode. fressian+zstd stays 6% smaller than
boring+zstd.

On **ClojureScript** the split is sharper. transit-cljs writes JSON text, so
`JSON.parse` does its whole byte-to-structure walk in native C++ — 57% of
boring's decode time is work V8 does for transit for free. On generic data
transit is 1.5–2.7× faster. But with `:shapes` on an array of same-shaped maps
— the datom shape — boring decodes **53 µs against transit's 91, at 2.9×
smaller**, and encodes 125 µs against 128. boring is smaller on the wire on
every payload.
[Performance](doc/PERFORMANCE.md) has the tables and the profile.

Handing boring a primitive array instead of a vector is worth far more than any
of the above: a `short[]` of 1000 elements decodes **33× faster** than the
equivalent vector, as a standard [RFC 8746][rfc8746] typed array that other
languages read natively.

## What it handles

Everything Clojure has, without a schema and without registering anything:

keywords and symbols (tag 39) · records (tag 27) · sets (tag 258) · bignums ·
`BigDecimal` with its scale intact · ratios · characters · metadata · sorted
maps and sets · queues · instants, durations, periods, local dates · UUIDs ·
URIs (tag 32) · regexes (tag 35) · byte arrays · primitive arrays as
[RFC 8746][rfc8746] typed arrays (JVM emits 5, reads 21; CLJS reads 5) · CBOR's own simple values and `undefined`

Records round-trip as themselves rather than flattening to maps — the problem
[incognito][] exists to solve for fressian — and an *unregistered* record
decodes to an inert value that re-encodes to the same tag and content (in
boring's preferred form — see [Extending](doc/EXTENDING.md)).

**46 of the 49 keys in nippy's own `stress-data` round-trip to an equal
value**, asserted in CI against the live `taoensso.nippy` dependency. The three
that do not are decisions, and the test states each with its reason — a
`deftype` is refused because
nippy carries it through Java serialization, the mechanism behind the Java
deserialization CVE family, and `Instant`/`java.sql.Date` lose their JVM class
to CBOR's date tags, with `:instant-type` and `:date-type` as the escape
hatches.

Metadata is preserved **by default**, matching nippy. `=` ignores metadata in
Clojure, so dropping it passes every value-equality test — which is exactly
why it is worth stating. `{:incl-metadata? false}` opts out.

## Profiles

```clojure
(boring/encode v {:profile :clojure})    ; default — compact, Clojure-native
(boring/encode v {:profile :interop})    ; no extensions; maximally portable
(boring/encode v {:profile :canonical})  ; deterministic bytes, for signing
```

There is also `:canonical-rfc7049`, which uses [clj-cbor][]'s length-first key
order instead of RFC 8949's bytewise one. It is a separate profile rather than
an option on `:canonical`, because a signer and a verifier who disagree about a
sub-option that does not appear in the profile name produce a mismatch nobody
can see.

`:canonical` follows RFC 8949 §4.2 and the deterministic rules in
[draft-ietf-cbor-serialization][cde]. It is deliberately **lossy** — a bignum
that fits becomes a plain integer, floats narrow to their shortest form. You
cannot both sign a document and preserve a host type the wire has no room for.
[Compatibility](doc/COMPATIBILITY.md) spells out the consequences.

## Status

**The library is beta. The format is not.**

boring is beta so that bugs can be fixed without ceremony — expect API
adjustments and fixes, and please report anything that surprises you.

What you write is [CBOR][rfc8949], an IETF standard, and that is the part you
are trusting with your data. The bytes boring emits are checked on every push
against independent implementations — Python's [cbor2][] and Rust's
[ciborium][] read a committed fixture, and boring's encoding is asserted
byte-for-byte identical to cbor2's own — plus a frozen [golden
corpus](doc/COMPATIBILITY.md) that fails if any encoding changes. So do not
expect the format to move under you: expect bug fixes.

Data written today stays readable by any complete, conformant CBOR
implementation. Two of boring's constructs are extensions rather than core
CBOR — [stringref](doc/COMPATIBILITY.md) (tags 25/256) and
[shaped arrays](doc/SHAPES.md) (tag 39649, off by default) — and both are
documented with the ~20 lines a foreign reader needs, worked through for Python
in [Interop](doc/INTEROP.md). `{:profile :interop}` emits neither.

The one genuinely open item is that tag 39649 is not yet registered with IANA;
it is off unless you ask for it, and enabling it opts into a number that may
move. Two smaller gaps are stated where they belong rather than in a list:
boring cannot yet *verify* that incoming bytes were canonical
([Security](doc/SECURITY.md)), and no production datahike store has been
round-tripped through it ([Migrate codec](doc/MIGRATE-CODEC.md) §6).

What exists today: one shared `.cljc` conformance suite run on both platforms
(`bin/ci` prints the counts; pinning them here only means they are wrong by the
next commit), RFC 8949 Appendix A 82/82, the CBOR
working group's not-well-formed corpus 46/47, coverage measured against
nippy's `stress-data`, a frozen [golden corpus](doc/COMPATIBILITY.md) asserted
in both directions, mutation fuzzing gated in CI on both platforms, and
reference readers in Python and Rust run against a committed fixture on every
push.

boring is the format under [konserve][]'s CBOR serializer and [kabel][]'s CBOR
wire, which is where it is exercised on real data.

## Documentation

- [Interop](doc/INTEROP.md) — every tag we emit, what other languages see, and
  runnable readers
- [Performance](doc/PERFORMANCE.md) — the numbers, the methodology, the losses
- [Extending](doc/EXTENDING.md) — your own tags and record types
- [Compatibility](doc/COMPATIBILITY.md) — the format promise and how it is enforced
- [Security](doc/SECURITY.md) — threat model, and what is explicitly not guaranteed
- [Shapes](doc/SHAPES.md) — the key-stripping extension, shipped and proposed
- [Migrate codec](doc/MIGRATE-CODEC.md) — boring vs clj-cbor vs EDN-lines as a
  datahike dump format: type exactness, determinism, 5M datoms under a 64 MiB heap

## License

Copyright © 2026 Christian Weilbach and contributors.

Distributed under the **Apache License 2.0**. See [LICENSE](LICENSE), and
[NOTICE](NOTICE) for the third-party terms — nippy and the other libraries
boring is measured against are EPL-1.0, declared in test and bench aliases
only, and are not distributed in boring's jar.

[datahike]: https://github.com/replikativ/datahike
[konserve]: https://github.com/replikativ/konserve
[kabel]: https://github.com/replikativ/kabel
[clj-cbor]: https://github.com/greglook/clj-cbor
[cbor2]: https://github.com/agronholm/cbor2
[ciborium]: https://github.com/enarx/ciborium
[nippy]: https://github.com/taoensso/nippy
[hako]: https://github.com/mpenet/hako
[fressian]: https://github.com/clojure/data.fressian
[transit]: https://github.com/cognitect/transit-clj
[transit-java]: https://github.com/cognitect/transit-java
[transit-clj]: https://github.com/cognitect/transit-clj
[transit-cljs]: https://github.com/cognitect/transit-cljs
[transit-format]: https://github.com/cognitect/transit-format
[incognito]: https://github.com/replikativ/incognito
[rfc8949]: https://www.rfc-editor.org/rfc/rfc8949
[rfc8746]: https://www.rfc-editor.org/rfc/rfc8746
[cde]: https://datatracker.ietf.org/doc/draft-ietf-cbor-serialization/
