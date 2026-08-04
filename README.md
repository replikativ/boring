# boring

[![slack](https://img.shields.io/badge/slack-join_chat-brightgreen.svg)](https://clojurians.slack.com/archives/CB7GJAN0L)
[![clojars](https://img.shields.io/clojars/v/org.replikativ/boring.svg)](https://clojars.org/org.replikativ/boring)
[![circleci](https://circleci.com/gh/replikativ/boring.svg?style=shield)](https://circleci.com/gh/replikativ/boring)
[![last-commit](https://img.shields.io/github/last-commit/replikativ/boring/main.svg)](https://github.com/replikativ/boring/commits/main)

**Fast, portable serialization for Clojure and ClojureScript — in a format the
rest of the world can already read.**

Clojure is a hosted language on purpose. Rich Hickey could have built a Lisp
with its own runtime and its own everything, and chose not to, because a
language that only talks to itself is a silo no matter how good it is. That
decision is why Clojure has libraries, deployment stories and a job market.

The community made the opposite decision about serialization, and mostly did
not notice. [nippy][] and [hako][] are fast and JVM-only. [fressian][] is
portable across Clojure and speaks to no other language. [transit][] was
explicitly designed for reach — that part is not a criticism — but in practice
its reach is Clojure, ClojureScript and a short list of ports, several of them
unmaintained, against a specification still at 0.8.

**The argument for reach is stronger for data than it ever was for code**,
because code runs in a world you control and data does not. When you write
bytes to IO you are writing to an *open* world: the consumer may be rewritten
in another language, or be another team, or be a cache that some later service
reads, or be nobody at all for five years. This is the same argument Clojure
already makes about maps — open, extensible, not closing over what you happen
to know today, not encoding constraints into the data that the data does not
need. A format that can only be read by re-running your code encodes the
biggest constraint of all.

And data outlives code. It outlives the application, usually the platform, and
often the ability to run the program that wrote it. A durable format is a bet
that someone can still read your bytes when your build no longer resolves. That
is a reason not to invent a format lightly — and to assume your format's reach
will exceed what you can currently imagine for it, even when every consumer is
internal and known today.

[transit][]'s own README — in the [Java][transit-java], [Clojure][transit-clj]
and [ClojureScript][transit-cljs] implementations alike — is honest about which
bet it is making:

> Transit is intended primarily as a wire protocol for transferring data
> between applications. If storing Transit data durably, readers and writers
> are expected to use the same version of Transit and you are responsible for
> migrating/transforming/re-storing that data when and if the transit format
> changes.

That is a reasonable position for a wire protocol and a poor one for an
archive — and an archive is what [datahike][] needed, which is why boring
exists.

boring takes the reach: it is [CBOR][rfc8949] — **IETF STD 94**, a full
Internet Standard, with implementations in 26 languages, its own IANA tag
registry and a standard diagnostic notation. It is the format with the widest
reach that can still carry edn faithfully: keywords, symbols, sets, ratios,
records, metadata. A foreign reader gets your data as ordinary CBOR whether or
not it knows what a keyword is.

**The usual trade is that reach costs speed. Here it does not.** On the JVM
boring beats nippy on every payload we measure and trades wins with [hako][].
On ClojureScript it is always smaller on the wire than transit, faster on the
datom-shaped data it was built for, and slower on generic data — see
[Performance](doc/PERFORMANCE.md), which says exactly where.

Getting there did not require changing a single byte of CBOR. Where boring
needed more, it grew *inside* the format rather than around it: string
deduplication is [stringref](doc/COMPATIBILITY.md), a registered CBOR extension; the offset index
that makes a memory-mapped file navigable is an ordinary tagged item at the end
of the file, which every other CBOR reader simply skips. A file boring writes
stays a file `cbor2` and `cbor.me` can read.

So: use CBOR by default, and reach for something else only when you have a
reason you would still defend in five years, to whoever is holding your data
then.

```clojure
(require '[boring.core :as boring])

(boring/encode {:user/name "Ada" :scores [99 100] :tags #{:x :y}})
;; => #object["[B" ...]  49 bytes

(boring/decode *1)
;; => {:user/name "Ada", :scores [99 100], :tags #{:x :y}}
```

The same code runs on the JVM and in the browser. A Python, Rust or Go program
reads the bytes with its own CBOR library — [cbor2][], [ciborium][],
[fxamacker][]. [Interop](doc/INTEROP.md) has an executable reader in Python and
Rust, both run in CI against a committed fixture; the Go and JavaScript
sections are worked guidance, not running code.

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

**Read that table with its tier in mind.** It calls a fresh codec per message,
which is matched across all four libraries but is *not* how hako is meant to be
used: `hako/encode` builds a Writer and a confined Arena per call, and its
author's intended path is the reused `encode-into!`. Reuse both sides and hako
gains considerably more than boring does — several of the small-payload results
above **reverse**. `clojure -M:bench -m hako-ab` prints the tier-matched table,
in time and allocation; quote that one for a like-for-like comparison of the
two libraries as they are meant to be called.

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
on the two small payloads' decode. With both codecs reused it is further ahead
still — roughly 1.3–1.8× on map-heavy encode and ~2× on map-heavy decode —
while boring keeps the vector-heavy decode and allocates less on every payload
measured. fressian+zstd stays 6% smaller than
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
(boring/encode v {:profile :archival})   ; stable bytes AND host types — dumps
(boring/encode v {:profile :canonical})  ; RFC 8949 §4.2 bytewise key order
```

If you need to agree byte-for-byte with a specific peer, check *which* rule it
implements before picking. `:canonical` is RFC 8949 §4.2.1 — bytewise
lexicographic — which is what fxamacker's `SortCoreDeterministic` and ciborium
produce. Python's **cbor2 `canonical=True` is the older length-first rule** (RFC
7049 §3.9), which is boring's `:canonical-rfc7049`, not its `:canonical`:

```
{1000 "x", "a" "y"}   cbor2 canonical=True   a2 6161 6179 1903e8 6178
                      :canonical-rfc7049     a2 6161 6179 1903e8 6178   ← same
                      :canonical             a2 1903e8 6178 6161 6179   ← differs
```

The two coincide for keys sharing a major type, which is nearly all Clojure
data — so this bites exactly the mixed-key-type case, and only ever at a
signature boundary. See [COMPATIBILITY.md](doc/COMPATIBILITY.md#two-canonical-profiles).

`:archival` and `:canonical` both sort map keys; they differ on floats.
`:canonical` implements RFC 8949 §4.2.2 shortest-form, so a `Double` may come
back a `Float` — correct for interchange, wrong for a database dump.
`:archival` keeps the width. Pick by whether you need to agree with *other
encoders* (`:canonical`) or to get *your own types back* (`:archival`).

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

## Reading without decoding

`boring.nav` walks encoded CBOR and builds only what you ask for — and because
a cursor implements `ILookup`, `clojure.core/get-in` works on it directly:

```clojure
(require '[boring.nav :as nav] '[boring.mmap :as mmap])

(def c (nav/source bs {:stringref false}))
(nav/value (get-in c ["customer-137" "name"]))   ; the other 199 are never built

(mmap/with-mmap [c "events.cbor"]                ; JDK 22+; the file need not fit in heap
  (nav/value (get-in c ["customer-137" "name"])))
```

Against decode-then-`get-in`: **21×** for one leaf, **~1400×** for `count`
(O(1) — the element count is in the head), and **~290×** to locate a 1 MiB
blob rather than materialise it. Memory-mapped, a random lookup beats one
`pread` per item by 2.3×.

This does not change CBOR. The wins come from *not decoding*, and CBOR's own
design bounds them: byte strings are length-prefixed, so skipping one is a
jump, but arrays and maps carry an element count rather than a byte length, so
seeking a field is a scan. Zero-copy on payloads, lazy scanning on structure —
not the O(1) field access of a format built for it, and still ordinary CBOR
that `cbor2` and `ciborium` read.

It is also not a free win everywhere: taking only the first item of a log is
*slower* than `decode-seq`, which is already lazy.

[Storage](doc/STORAGE.md) covers the model, memory mapping, writing logs,
compression, and what can be updated in place.

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
next commit), RFC 8949 Appendix A 81/81 and Appendix F.1 94/94, the CBOR
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
- [Storage](doc/STORAGE.md) — navigation, memory mapping, log writing,
  compression, and in-place update
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
[fxamacker]: https://github.com/fxamacker/cbor
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
