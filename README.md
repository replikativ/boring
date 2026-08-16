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

The community made the opposite decision about *data*, and mostly did not
notice: the language bet on an open host it did not control, while serialization
stayed in-house. [nippy][] and [hako][] are fast and JVM-only. [fressian][] is
portable across Clojure and speaks to no other language. [transit][] was
explicitly designed for reach — that part is not a criticism — but in practice
its reach is Clojure, ClojureScript and a short list of ports, several of them
unmaintained, against a specification still at 0.8; and where it *did* ride an
open transport — transit-msgpack — it wrote its own `~#tag` dialect on top
rather than msgpack's native types. Reach in the transport is not reach in the
format.

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
archive — and an archive is what [datahike][] needed. None of this is a charge
of bad judgment: fressian gave Datomic a durable, richly-typed store, transit
gave app-to-app and the browser a fast path, and CBOR only became an Internet
Standard in 2020. The point is not that anyone chose wrong; it is that the open
format now exists — and is worth *joining* rather than wrapping.

boring takes the reach: it is [CBOR][rfc8949] — **IETF STD 94**, a full
Internet Standard since December 2020, with [implementations in 26
languages][cbor-impls], its own IANA tag registry and a standard diagnostic
notation. It is the format with the widest
reach that can still carry edn faithfully: keywords, symbols, sets, ratios,
records, metadata. A foreign reader gets your data as ordinary CBOR whether or
not it knows what a keyword is.

And boring rides no dialect on top of it — it *speaks* CBOR. Where transit wrote
its own tags over an open transport, boring's extensions are CBOR's own, so a
`cbor2` or `ciborium` reader gets idiomatic CBOR, not a Clojure-only encoding it
must special-case.

**And reach did not cost speed.** boring round-trips on par with the fastest
JVM Clojure codecs — `nippy/fast` and [hako][] — decoding faster than nippy,
encoding a little slower. Serialization rarely dominates a real workload
anyway; where it does — a few percent deciding an application — you are past
what *any* dynamic, self-describing format offers and into a fixed-schema,
zero-copy layout like FlatBuffers or Cap'n Proto, with a compiled schema and
no open world. Everywhere else, boring is fast enough that the reach is free.

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
;; => #object["[B" ...]  58 bytes

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

On nippy's own `stress-data` benchmark — its data, its filter, its timing loop,
each codec through its real API — boring round-trips within a whisker of
`nippy/fast`, the fastest JVM Clojure codec, decoding faster and encoding a
little slower:

| codec | round µs | bytes |
|---|---:|---:|
| nippy/fast | 1 659 | 14 017 |
| boring | 1 662 | 15 326 |
| nippy (LZ4) | 2 050 | 7 835 |
| boring + zstd | 3 221 | 4 900 |
| nippy/lzma2 | 22 966 | 3 700 |

Compressed, boring+zstd is 1.6× smaller than nippy's default. Size is really a
compressor choice, not a codec one — lzma2 is smaller and ~7× slower, LZ4
larger and faster, and any codec here can pair with any of them.

Where boring wins outright: **string-heavy data** (stringref deduplication) and
**arrays of same-shaped maps** — the datom shape — with `:shapes`. Where it
loses: [hako][]'s FFM reader is faster on small and deeply nested maps, and
`nippy/fast` encodes flat numeric vectors faster. On ClojureScript boring is
always smaller on the wire than transit and faster on the datom shape, slower on
generic data. [Performance](doc/PERFORMANCE.md) has the per-payload tables and
the methodology.

Handing boring a primitive array instead of a vector is worth more than any of
this: a `short[]` of 1000 elements decodes **33× faster** than the equivalent
vector, as a standard [RFC 8746][rfc8746] typed array other languages read
natively.

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
lexicographic — which is what fxamacker's `SortCoreDeterministic` produces.
**Both of the peers this repo actually checks against are length-first**: Python's
cbor2 `canonical=True` and Rust ciborium's `CanonicalValue` are the older RFC
7049 §3.9 rule, which is boring's `:canonical-rfc7049`, not its `:canonical`:

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

There is also `:canonical-rfc7049`, the length-first key order — [clj-cbor][]'s,
but calling it that understates its reach: [cbor2][] is the most widely deployed
CBOR implementation there is and its canonical mode is this one, as is
[ciborium][]'s. It is a separate profile rather than an option on `:canonical`,
because a signer and a verifier who disagree about a sub-option that does not
appear in the profile name produce a mismatch nobody can see.

ciborium was listed here under the bytewise rule, and that was wrong. It was
corrected in [COMPATIBILITY.md](doc/COMPATIBILITY.md#two-canonical-profiles)
when `interop/rust/src/canonical.rs` compared ciborium's output against ours
over 989 values, and the correction did not reach this page — so a reader who
followed the README picked `:canonical` to match a ciborium peer and got a
verification failure with nothing to point at. Two documents, one measurement,
one of them updated.

`:canonical` follows RFC 8949 §4.2 and the deterministic rules in
[draft-ietf-cbor-serialization][cde]. It is deliberately **lossy** — a bignum
that fits becomes a plain integer, floats narrow to their shortest form. You
cannot both sign a document and preserve a host type the wire has no room for.
[Compatibility](doc/COMPATIBILITY.md) spells out the consequences.

## Reading without decoding

`boring.nav` walks encoded CBOR and builds only what you ask for — and because
a cursor implements `ILookup`, `clojure.core/get-in` works on it directly for
**map keys**:

```clojure
(require '[boring.nav :as nav] '[boring.mmap :as mmap])

(def customers (into {} (for [i (range 200)]
                          [(str "customer-" i) {"name" (str "name-" i)}])))

;; :stringref false at WRITE time — see below; it is not optional
(def bs (boring/encode customers {:stringref false}))

(def c (nav/root bs))
(nav/value (get-in c ["customer-137" "name"]))   ; => "name-137"
                                                 ; the other 199 are never built
```

That walks the container to find the key. **An offset index turns the walk into
a jump** — opt-in, and a pure optimisation: the answers are identical, and a
missing, truncated or stale index falls back to scanning.

```clojure
;; ONE large value you will navigate into. `encode-indexed` seals an index onto
;; it and KEEPS stringref -- see below; the index frame is what makes a
;; stringref document navigable at all.
(def ibs (boring/encode-indexed customers {:profile :archival}))

(boring/decode ibs)                              ; => the map, unchanged
(nav/value (get-in (nav/root ibs) ["customer-137" "name"]))   ; => "name-137"
```

The result is a two-item CBOR sequence — the value, then the index — so
`decode` still returns the value and any CBOR reader in any language consumes
both. Here it cost **1.17%** on the wire, for one container node.

**`:archival` is doing real work in that call, and the default would not.**
Anchors only pay where a lookup can use them, so `keep-node?` refuses an
*unsorted* map at a stride above 1 — without key order you must try every
anchor's span anyway, which is the scan you were avoiding, plus the jumps.
`customers` is a hash map, so under the default profile this frame comes back
with **zero container nodes** and that `get-in` scans exactly like the
unindexed line above it. `:archival` sorts the keys canonically, which is what
makes the binary search legal.

An earlier version of this example omitted the profile and quoted the saving
from a differently-shaped payload. It was measuring a frame that carried
nothing but the stringref pointer table. See [INDEX.md](doc/INDEX.md) for
when a node is kept.

For a log, `write-seq!` indexes by default and `nav/items` uses it:

```clojure
(def events (vec (for [i (range 200000)] {:id i :s (str "event-" i)})))

(with-open [o (java.io.FileOutputStream. "events.cbor")]
  (boring/write-seq! (boring/writer 65536) events o))

(def items (nav/items (java.nio.file.Files/readAllBytes
                       (.toPath (java.io.File. "events.cbor")))))
(nav/value (nth items 199999))                   ; O(1) to the nearest anchor,
                                                 ; then at most stride-1 skips

;; the same file, mapped — `mmap-items`, because a log is a SEQUENCE.
;; `mmap-source` is the single-value shape and hands back a cursor at the root.
(let [[items arena] (mmap/mmap-items "events.cbor")]   ; JDK 22+; need not fit in heap
  (with-open [a arena]
    (nav/value (nth items 199999))))
```

On 200 000 small items, reaching the **last** one is a jump of about a
microsecond instead of a scan of about twelve milliseconds, for **0.34%** more
file at the default stride. The table with every stride, and the harness that
produces it, live in [doc/INDEX.md](doc/INDEX.md#stride-is-a-parameter-not-a-constant) —
`clojure -M:bench -m nav index`. This paragraph used to restate its own set of
figures for the same corpus, and three of the four disagreed with that table.

Build `items` once and reuse it; constructing it reads the index frame, so
folding that into every lookup measures setup rather than the jump.

Two things the **first** example is carrying, both of which used to be silent.
Neither applies to the indexed forms above — not because those turn stringref
off, which they no longer do, but because the index frame resolves it:

**Index it, and stringref stops being a problem.** boring writes stringref by
default, and a stringref is an index into a table built from every preceding
string, so a cursor holding only an offset cannot rebuild it. The index frame
carries the defining offset of every referenced slot, so a reference resolves by
jumping — which is why `encode-indexed` keeps stringref and is *smaller* for it.

What `nav/source` still refuses, with `:boring/stringref-not-navigable`, is a
document that opens a namespace and was never indexed: plain `encode` output.
For that, either seal an index onto it or write it `{:stringref false}` — and
put that on the WRITER, `(boring/writer n {:stringref false})`, rather than
passing it per call, where it costs ~250 heap bytes per item.

**`get-in` descends maps, not arrays.** `Cursor`'s `valAt` handles map keys and
realises tags; an array position falls through to the not-found value, so
`(get-in c ["p" 1])` is `nil` where `(get-in (boring/decode bs) ["p" 1])` is
`20`. No error — the arity does not have one to give. Use `nth` on the array
cursor: `(nav/value (nth (get c "p") 1))`.

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

### One representation, disk to socket

This is where the reach argument stops being philosophical. A format the rest
of the world reads is a format you never have to *convert out of* — so the
value can stay bytes at every boundary it crosses.

A mapped `MemorySegment` answers `.asByteBuffer()` for free, and http-kit
passes a `ByteBuffer` body through untouched, writing it with a gathering
`writev`:

```clojure
(defonce blob (mmap/mmap-segment "customers.cbor" arena))   ; mapped once

(defn handler [_]
  {:status 200
   :headers {"content-type" "application/cbor"}
   :body (.duplicate (.asByteBuffer ^MemorySegment blob))})
```

Measured on a 731 KB blob: **64 bytes** of heap allocated per request, against
731 705 for `Files/readAllBytes`. Never parsed, never re-encoded, never a JVM
object — and the consumer can be Python, Rust or a browser, because what went
out is ordinary CBOR. The index frame rides along as a second item any
conformant reader consumes and ignores.

Stated honestly, because the phrase is overused: this is **not** sendfile. The
kernel still copies page cache to socket buffer. What it removes is everything
on the JVM side. And for a whole static file with no logic, nginx does it
better — the case that is boring's is serving a **slice** of a larger file,
found by walking the index, without materialising the rest.

[Storage](doc/STORAGE.md) covers the model, memory mapping, serving a mapping
to a socket, writing logs, compression, and what can be updated in place.

## Editing without decoding

The mirror image of reading without decoding: change one leaf of a large value
without materialising the rest. `boring.edit` navigates to the target and
**splices only the changed bytes back in** — an ancestor header never moves,
because a CBOR map/array head carries an element *count*, not a byte length, so
growing a nested value does not shift the bytes that enclose it.

```clojure
(require '[boring.edit :as edit])

(def bs (boring/encode {:user/name "Ada" :scores [99 100]} {:profile :archival}))

(boring/decode (edit/assoc-in-bytes bs [:scores 1] 42 {:profile :archival}))
;; => {:user/name "Ada", :scores [99 42]}   -- only that element was re-encoded
```

Paths resolve like `clojure.core/get-in` — a key into a map (keyword, string,
symbol, even an integer key), an index into a vector — dispatching on the
container, not on the shape of the key.

`update-in-bytes`, `assoc-in-bytes` and `dissoc-in-bytes` are **byte-for-byte
indistinguishable from decode → `clojure.core/update-in` → encode** — a
generative property test asserts exactly that over thousands of random nested
values and paths. A same-length change is a **poke** — overwrite the value's
bytes in place, leaving any [offset index](doc/INDEX.md) valid. A size-changing
change is a **splice**; when the value carries an index, `:index :maintain`
*shifts* the frame's offsets instead of rebuilding it, byte-identical to a
rebuild and measurably cheaper.

Memory-mapped, this becomes an edit with **no copy and no full re-encode**.
`boring.mmap/poke!` overwrites a same-length value in a mapped file in place;
`boring.mmap/splice!` grows or shrinks the file and memmoves only the tail after
the edit within the mapping. Updating a field deep in a gigabyte-sized value
costs a page write, not a re-serialization.

Two honest constraints. Editing needs a **deterministic, stringref-off profile**
(`:archival` or `:canonical`) — the same profiles you would use for storage — so
that a value's bytes do not depend on what was encoded before it. And an
in-place edit **mutates live bytes**, so it is not crash-safe on its own; it is
for a single writer, or paired with a torn-write detector. [Editing](doc/EDITING.md)
has the full API, the semantics, the numbers, and the durability story;
[konserve][]'s filestore wires it into `update-in!`/`assoc-in!` with a
crash-safe default.

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
- [Index](doc/INDEX.md) — the offset index: frame layout, how navigation turns
  a node into a jump, and where it does not pay
- [Editing](doc/EDITING.md) — splice/poke `update-in` on encoded bytes and
  memory-mapped files, index maintenance, and the durability story
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
[cbor-impls]: https://cbor.io/impls.html
[rfc8746]: https://www.rfc-editor.org/rfc/rfc8746
[cde]: https://datatracker.ietf.org/doc/draft-ietf-cbor-serialization/
