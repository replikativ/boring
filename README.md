# boring

[![slack](https://img.shields.io/badge/slack-join_chat-brightgreen.svg)](https://clojurians.slack.com/archives/CB7GJAN0L)
[![clojars](https://img.shields.io/clojars/v/org.replikativ/boring.svg)](https://clojars.org/org.replikativ/boring)
[![circleci](https://circleci.com/gh/replikativ/boring.svg?style=shield)](https://circleci.com/gh/replikativ/boring)
[![last-commit](https://img.shields.io/github/last-commit/replikativ/boring/main.svg)](https://github.com/replikativ/boring/commits/main)

boring is a [CBOR](https://www.rfc-editor.org/rfc/rfc8949) serializer for
Clojure and ClojureScript. It preserves Clojure data types, supports readers
in other languages, and lets JVM applications read selected fields from
encoded values without decoding the whole document.

It was built for the stored values and messages used by
[Datahike](https://github.com/replikativ/datahike),
[konserve](https://github.com/replikativ/konserve), and
[kabel](https://github.com/replikativ/kabel). CBOR gives those bytes a documented
format that can be read independently of the application that wrote them.
The name comes from CBOR: the underlying format should be a routine part of
using your data.

## Why CBOR?

Clojure's choice to be a hosted language gives us access to libraries and
tools beyond the Clojure community. We want the same advantage for data:
a Python analysis job, a Rust service, or a future replacement for today's
application should be able to read what a Clojure program stored.

That matters particularly for databases. A message usually has a known
receiver; a stored value may be read years later by a program that does not
exist yet. With CBOR, that reader can start from an established binary format
and its own language's implementation. Boring supplies the Clojure type
mapping within CBOR's tag system.

### Why not Nippy, Fressian, or Transit?

[Nippy](https://github.com/taoensso/nippy) is a mature choice for fast JVM
Clojure serialization, with a documented commitment to reading older data.
For a JVM-only application, it may already provide everything needed.
We also need ClojureScript and readers outside the Clojure ecosystem; CBOR
lets those consumers use their existing parsers and inspection tools.

[Fressian](https://github.com/Datomic/fressian) was designed as an extensible
binary format for data across languages. It has served Clojure storage well,
and our ecosystem has used it extensively. CBOR gives us a broader choice of
[independent implementations](https://cbor.io/impls.html) and a shared tag registry for types such as UUIDs,
decimals, and typed arrays. That reduces the format-specific work required
when another language needs to consume the data.

[Transit](https://github.com/cognitect/transit-clj) is convenient for rich
values moving between applications, especially Clojure and ClojureScript.
Its JSON representation can also benefit from the JavaScript engine's native
parser. Transit carries its type conventions over JSON or MessagePack;
boring uses CBOR's own type and tag mechanisms. Transit also documents a
same-version expectation for durable storage and leaves migration to the
application. We wanted an explicit long-term read-compatibility policy for
our storage format.

CBOR does not remove all integration work. A generic reader can inspect a
record's tagged name and fields, but reconstructing a Clojure record or an
opt-in shaped array needs a handler. The useful starting point is that the
parser, basic values, and many shared types are already available.
See [Interop](doc/INTEROP.md) for the remaining mappings.

The reason to adopt boring is that combination of Clojure data support,
independent readers, and selective access to stored values. The
[benchmarks](doc/PERFORMANCE.md) help establish whether its costs fit your
workload; speed alone does not distinguish it from every existing codec.

## Install

Add boring to `deps.edn`:

```clojure
{:deps {org.replikativ/boring {:mvn/version "RELEASE"}}}
```

Use the [Clojars version](https://clojars.org/org.replikativ/boring) in place of
`RELEASE` to pin a build. The codec runs on JDK 9+ and ClojureScript.
The optional `boring.mmap` namespace requires JDK 22+.

Source dependencies also need the Java classes compiled. After adding a
`:git/url` or `:local/root` dependency, run `clj -X:deps prep`;
use `-Sforce` when recompiling changed Java sources.

## Encode and decode

```clojure
(require '[boring.core :as boring])

(def value {:user/name "Ada" :scores [99 100] :tags #{:x :y}})
(def encoded (boring/encode value))

(= value (boring/decode encoded))
;; => true
```

This example runs on both platforms. The encoded result is a JVM byte array
or a JavaScript `Uint8Array`.

The default encoding supports keywords, symbols, sets, records, metadata,
sorted collections, queues, numbers, dates, UUIDs, and arrays. Some types have
different representations on the two platforms: for example, a JVM
`BigDecimal` becomes a `boring.data/Decimal` in ClojureScript, and a URI
becomes a tagged value. [Compatibility](doc/COMPATIBILITY.md) describes the
type mappings and precision limits.

Records carry a type name and field map. Register a constructor to recover the
record type; otherwise the reader retains an inspectable carrier that can be
encoded again. There is no fallback to Java object deserialization.
See [Extending](doc/EXTENDING.md).

## Choose an encoding profile

| Profile | Use | Behaviour |
| --- | --- | --- |
| `:clojure` (default) | Clojure-to-Clojure values and messages | String deduplication; preserves float width |
| `:interop` | Readers without stringref or shaped-array support | Disables those extensions; preserves float width |
| `:archival` | Reproducible storage encoding and editing | Sorts map keys; preserves float width; no stringref or shapes |
| `:canonical` | Agreement with RFC 8949 deterministic encoders | Bytewise key order; shortest numeric representation |
| `:canonical-rfc7049` | Peers using the older canonical ordering | Length-first key order; shortest numeric representation |

```clojure
(boring/encode value {:profile :archival})
```

All profiles still use tags for types such as keywords and records.
A foreign CBOR library can parse the tagged structure, but needs the relevant
tag handlers to reconstruct those types. [Interop](doc/INTEROP.md) includes
Python and Rust readers exercised in CI.

Canonical encoding can change host numeric types. It also does not mean that
every pair of values Clojure considers equal has the same bytes. Float width,
signed zero, metadata, and platform number types need particular care at a
hashing or signing boundary. See [Determinism](doc/COMPATIBILITY.md#determinism)
and [the two canonical profiles](doc/COMPATIBILITY.md#two-canonical-profiles).

## Reading without decoding

On the JVM, `boring.nav` provides a cursor over encoded data. Looking up a
field locates its bytes; `nav/value` decodes the selected value.

```clojure
(require '[boring.nav :as nav])

(def customers
  (into {} (for [i (range 200)]
             [(str "customer-" i) {"name" (str "name-" i)}])))

(def customer-bytes
  (boring/encode-indexed customers {:profile :archival}))

(nav/value (get-in (nav/root customer-bytes) ["customer-137" "name"]))
;; => "name-137"
```

The other customers are not materialised. The archival profile sorts the map
keys, allowing indexed lookup to use binary search. Arrays use positional
anchors, and eligible unsorted maps get an anchor for each entry so a lookup
can skip their values.

`encode-indexed` adds an offset index when useful. It also supports the
default profile's string references: the index records where referenced
strings were defined. Small values may be emitted without a frame and without
a stringref namespace when that is shorter.

Plain `encode` output with a stringref namespace cannot be navigated without
a suitable index. For an unindexed document you intend to navigate, write it
with `{:stringref false}`.

Cursor `get-in` follows map keys. Use `nth` for an array position, for example
`(nav/value (nth (get cursor :scores) 1))`.
See [the offset index](doc/INDEX.md) for examples, configuration, and the
index trust boundary.

### Memory-mapped files and logs

`boring.mmap/mmap-source` maps a single document; `mmap-items` maps a CBOR
sequence. Both return the view and the arena that owns it. Decode any result
you want to retain before closing the arena.

```clojure
(require '[boring.mmap :as mmap])

(with-open [out (java.io.FileOutputStream. "customers.cbor")]
  (.write out ^bytes customer-bytes))

(let [[cursor arena] (mmap/mmap-source "customers.cbor")]
  (with-open [a arena]
    (nav/value (get-in cursor ["customer-137" "name"]))))
;; => "name-137"
```

A mapped single document can use indexed string references. Navigable
sequences currently require stringref off: `write-seq!` enforces this because
each item would otherwise introduce a separate namespace.

`konserve.mmap/with-mmap-value` uses the same document mapper for filestore
values. Values must be boring-encoded, uncompressed, and unencrypted;
indexed string references are supported for reads. Editing has additional
requirements, described below.

[Storage](doc/STORAGE.md) covers streaming, scoped mappings, compression,
and serving encoded data.

## Editing without decoding

`boring.edit` can replace a field or change its parent container while keeping
the rest of the document encoded. Use the same deterministic, stringref-off
profile that wrote the original value.

```clojure
(require '[boring.edit :as edit])

(def editable
  (boring/encode {:user/name "Ada" :scores [99 100]} {:profile :archival}))

(boring/decode
  (edit/assoc-in-bytes editable [:scores 1] 42 {:profile :archival}))
;; => {:user/name "Ada", :scores [99 42]}
```

The byte-array update functions return new bytes. Explicit poke functions
mutate their input, and the mmap editing functions mutate a file. Same-length
leaf replacements avoid moving the tail; changes in length must move the
following bytes and update any affected index.

In-place file edits need coordination with readers and a recovery policy.
Konserve provides replacement-file, checked in-place, and unchecked in-place
options. [Editing](doc/EDITING.md) explains the API boundaries and durability
requirements.

## Speed

The [benchmark tables](doc/PERFORMANCE.md) cover full encode/decode, wire size,
compression, and selective reads. Results depend on the data and the API used.

In the recorded JVM comparisons, boring performs well on string-heavy data.
Enabling `:shapes` reduces repeated key work for arrays of maps and improves
their decode time. Hako leads several small-map and map-heavy comparisons.
The ClojureScript results generally favour Transit for full decoding, with
exceptions including shaped arrays and numeric vectors.

If your application needs only a few fields, compare navigation with full
decoding as well as comparing codecs. If it reads every field, measure the full
decode path. Include compression and result construction in the comparison
when your application uses them.

## Status

The library is beta; APIs may change. Its compatibility policy is to keep
released data readable and tag meanings stable, subject to the documented
provisional extension. Tests include a frozen byte corpus, shared JVM/CLJS
conformance cases, mutation fuzzing, and independent Python and Rust readers.
These check the covered values and behaviours, rather than proving support
for every legal CBOR document.

Shaped arrays are opt-in with `:shapes true`. Their tag, 39649, remains
provisional and unregistered; enabling it accepts that tag-number risk.
See [Shapes](doc/SHAPES.md). Boring emits canonical bytes but does not validate
that incoming bytes were canonically encoded. Read the
[security guide](doc/SECURITY.md) before accepting untrusted input.

## Documentation

- [Compatibility](doc/COMPATIBILITY.md): guarantees, profiles, and platform differences
- [Interop](doc/INTEROP.md): tag mappings and readers in other languages
- [Extending](doc/EXTENDING.md): custom tags and record types
- [Storage](doc/STORAGE.md): files, logs, mapping, and compression
- [Index](doc/INDEX.md): selective reads and the offset-frame format
- [Editing](doc/EDITING.md): byte-array and mapped-file updates
- [Shapes](doc/SHAPES.md): shared keys in arrays of maps
- [Performance](doc/PERFORMANCE.md): measurements and reproduction commands
- [Security](doc/SECURITY.md): untrusted input and limits
- [Migrate codec](doc/MIGRATE-CODEC.md): Datahike dump-codec evaluation

## License

Copyright © 2026 Christian Weilbach and contributors.

Distributed under the Apache License 2.0. See [LICENSE](LICENSE) and
[NOTICE](NOTICE). Benchmark and test dependencies are not distributed in
boring's jar.
