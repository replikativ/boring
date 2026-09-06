# Compatibility policy

This page describes boring's wire-format policy, type mappings, and known
platform differences. For foreign-language readers, see [Interop](INTEROP.md);
for custom types, see [Extending](EXTENDING.md).

## The promise

Released tag meanings are append-only. Newer readers must decode older released
output with the same value semantics. New tags may be
added, but existing tags are not repurposed. The provisional shaped-array tag
is an explicit exception to tag-number stability, described below.

The policy does not require a new encoder to produce byte-identical output
to an older encoder. Such changes need review and documentation. Applications
that use encoded bytes as identifiers should pin their encoding configuration
and account for encoder upgrades.

Equal-value round trips also have limits: CBOR and the host languages do not
distinguish all the same types, and some encodings discard precision or type
information. The following sections describe those cases.

## How the promise is enforced

[The golden corpus](../test/boring/golden_v1.cljc) contains frozen encodings
for portable and JVM-only values. [The tests](../test/boring/golden_test.cljc)
check both that those bytes still decode correctly and that encoding still
produces the frozen bytes.

Add coverage when introducing a wire construct. Regenerate with
`bin/regen-golden` only after reviewing and documenting the byte changes.
The corpus covers examples of each included construct; it is not an exhaustive
proof of compatibility.

## Why there is no version header

Boring emits CBOR without a library-specific envelope. CBOR readers can parse
its structure without knowing which boring version wrote it. Reconstructing
tagged types still requires the appropriate tag support.

Applications can carry their own schema or producer version in an envelope.
This is useful for migrations that concern the meaning of application data
rather than the codec.

## Tag assignments

| Tags | Representation |
| --- | --- |
| 0, 1 | Text and epoch-based instants |
| 2, 3 | Positive and negative bignums |
| 4 | Decimal fraction |
| 25, 256 | String reference and namespace |
| 27 | Named object, including records and reserved type markers |
| 30 | Rational number |
| 32, 35, 37, 39 | URI, regex, UUID, identifier |
| 40 | Multidimensional array |
| 64–87 | RFC 8746 typed-array range, with platform limits below |
| 258 | Set |
| 1002, 1004 | Duration and full date |
| 39649 | Shaped array, provisional |

The JVM emits the five little-endian typed-array tags corresponding to
`short[]`, `int[]`, `long[]`, `float[]`, and `double[]`. It reads 21
typed-array tags. Tag 76 is reserved and the two float128 forms remain tagged.
ClojureScript converts five tags to JS typed arrays and retains the other
supported forms as tagged values, validating their payloads.

### Stringref index space

Stringref deduplicates text and byte strings. A string occupies a slot when its
length reaches the threshold for that reference index: 3 bytes for indices
0–23, 4 for 24–255, 5 for 256–65535, and 7 beyond. Byte strings participate
wherever they occur, including UUIDs, bignum magnitudes, and typed arrays.

Tag 256 opens a namespace. A nested namespace temporarily replaces the
enclosing one, and tag 25 outside a namespace is an error. Encoder and decoder
must register strings in the same order. The interop fixtures check this
against Python and Rust readers.

Full decoding builds the table in stream order. Navigation instead needs an
[index containing the defining offsets](INDEX.md#resolving-a-stringref-through-the-index).
This works for single documents, including mapped documents. Navigable
sequences use stringref-off items because the current frame holds only one
pointer table.

### Exceptions travel as data

Exceptions use these tag-27 frames:

```text
27(["clojure/ex-info", ["boom", {:a 1}, <cause or null>]])
27(["java/throwable", ["java.lang.Exception", "plain", <cause or null>]])
```

Both decode to `ExceptionInfo`. A general JVM throwable retains its original
class name under `:boring/throwable-class`; the decoder does not instantiate
that class. Stack traces are omitted. A decoded `java/throwable` re-encodes
as `clojure/ex-info`, so this conversion preserves error information rather
than the original frame.

### Decimals, and the two shapes of "decimal"

Tag 4 is `[exponent, mantissa]`, with value
`mantissa * 10^exponent`. JVM `BigDecimal` uses the equivalent
`unscaled * 10^-scale`, so `scale = -exponent`.

On ClojureScript, a decimal's mantissa may be a JS number or `BigInt`.
The accessors provide a consistent interface:

```clojure
(require '[boring.data :as data])

(def d (data/decimal-from-unscaled (bigint 150) 2)) ; JVM example
(data/decimal-scale d)                            ; => 2
(data/decimal-unscaled d)                          ; => 150 as BigInteger
```

On ClojureScript, use `js/BigInt` for an exact unscaled integer;
`decimal-unscaled` returns a `js/BigInt`. Scale is retained, so
`1.50M` and `1.5M` have distinct encodings.

### What an unregistered tag-27 frame decodes to

Tag 27 carries a type name and constructor arguments. For boring's
`[name, payload]` form, an unregistered name produces:

| Payload | Carrier |
| --- | --- |
| Map | `boring.data/UnknownRecord`, exposing the fields as a map |
| Other value | `TaggedLiteral`, exposing `:tag` and `:form` |

`boring.data/frame-name` and `frame-payload` work on both;
`tagged-frame?` recognises them. Both retain the name and payload for
re-encoding in boring's preferred CBOR form. This does not promise to preserve
every alternative legal byte spelling of the original input.

### Reserved tag-27 names

These markers retain distinctions that plain CBOR collections or strings
would lose:

| Name | Argument |
| --- | --- |
| `clojure/sorted-map` | Map |
| `clojure/sorted-set` | Array |
| `clojure/queue` | Array |
| `clojure/with-meta` | `[metadata, value]` |
| `clojure/char` | One-character string |
| `java/period` | Canonical ISO-8601 period |
| `clojure/ex-info`, `java/throwable` | Error data, as above |

Metadata is included by default; `{:incl-metadata? false}` disables it.
A caller's registry takes precedence over built-in markers, so avoid these
names unless deliberately overriding their meaning.

#### What a marker decodes to on ClojureScript

Sorted collections, queues, metadata, and `ex-info` reconstruct natively on
both platforms. Types missing from ClojureScript retain their tag-27 frame:

| Marker | JVM | ClojureScript |
| --- | --- | --- |
| `clojure/char` | `Character` | `TaggedLiteral` |
| `java/period` | `java.time.Period` | `TaggedLiteral` |
| `java/char-array` | `char[]` | `TaggedLiteral` |
| `java/boolean-array`, `java/string-array`, `java/object-array` | JVM array | `TaggedLiteral` |

Both platforms can originate these frames using `tagged-literal`, even
when they lack the corresponding native type.

`java/period` accepts the form emitted by `Period.toString()`.
Alternative spellings such as `P1W` or `+P1D` are refused because the JVM
would re-emit them as `P7D` or `P1D`. A character marker must fit the JVM's
single UTF-16 code unit; a non-BMP code point is refused.

### Types with a registered tag but no counterpart on both platforms

| Value | Tag | JVM | ClojureScript |
| --- | --- | --- | --- |
| URI | 32 | `java.net.URI` | `TaggedValue` |
| Regex | 35 | `java.util.regex.Pattern` | `js/RegExp` |
| Duration | 1002 | `java.time.Duration` | `TaggedValue` |
| Full date | 1004 | `java.time.LocalDate` | `TaggedValue` |
| Decimal | 4 | `java.math.BigDecimal` | `boring.data/Decimal` |
| Rational | 30 | `clojure.lang.Ratio` | `boring.data/Rational` |

Tagged carriers retain information that would be lost by returning a plain
string or map. Supported tag payloads are validated even when returned as
carriers. Cross-platform conformance tests cover these rules, with the
following limits:

- Tag 0 fractions are truncated to milliseconds by default on both platforms,
  since the default result is `java.util.Date` or `js/Date`.
  `{:instant-type :instant}` retains nanoseconds on the JVM. Both reject
  fractions longer than nine digits.
- Both platforms reject RFC 3339 offsets beyond ±18:00, matching the JVM
  `ZoneOffset` limit even though RFC 3339 permits a wider range.
- Tag 1's accepted epoch range differs: `js/Date` spans ±8.64e12 seconds,
  while JVM `Instant` supports a wider range. A value such as `1(1e13)`
  is accepted on the JVM and rejected on ClojureScript.
- JavaScript does not distinguish an integral float from an integer.
  Consequently, validation of a numeric tag field can differ after parsing
  when the wire type carried that distinction.
- Tag 40's typed-array payload support follows the platform's native
  typed-array support.
- Regex syntax differs between Java and JavaScript. A source accepted by one
  may fail on the other. Tag 35 carries source only, so separately supplied
  flags are not retained.
- `java.sql.Date` uses tag 1004 and decodes to `LocalDate` by default.
  `{:date-type :sql-date}` restores the legacy class, but cannot restore
  time-of-day information absent from that tag.

These are reasons to test your actual cross-platform values. The conformance
suite is not a guarantee that every accepted CBOR document has identical
behaviour on both hosts.

### Types with no registered tag

Characters and periods use the named frames above. Arbitrary JVM classes,
including `deftype` and POJOs, need explicit support.

### Why not a Java serialization fallback

Boring does not instantiate arbitrary classes named by input bytes or embed
Java-serialized objects as a fallback. A record's name and field map remain
inspectable by other languages; custom reconstruction is an explicit registry
decision.

### 39649: shaped array, provisional

`:shapes true` enables arrays of maps whose keys are stored once. Rows may
omit keys. The wire format is described in [Shapes](SHAPES.md).

Tag 39649 remains unregistered in the
[IANA registry](https://www.iana.org/assignments/cbor-tags/) checked on
2026-09-06. Its number could change or be assigned elsewhere. Keep shapes off
when that risk is unacceptable. A foreign decoder needs a handler to restore
the maps; an unknown-tag carrier alone does not interpret the extension.
The registration draft is in [IANA registration](IANA-REGISTRATION.md).

## The archival profile

`:archival` locks these settings:

```clojure
{:stringref false
 :float-policy :preserve-width
 :canonical true
 :shapes false
 :canonical-order :rfc8949}
```

It sorts map keys and preserves float width, which is useful for database dumps
and for the [editing API](EDITING.md). It does not implement RFC 8949's
shortest-float requirement. Use `:canonical` when agreeing with an encoder
that requires that rule.

| Property | `:interop` | `:archival` | `:canonical` |
| --- | --- | --- | --- |
| Stringref and shapes | Off | Off | Off |
| Map keys | Unsorted | Bytewise sorted | Bytewise sorted |
| Float width | Preserved | Preserved | Shortest representation |
| Type tags | Retained | Retained | Retained, subject to numeric narrowing |

Stable encoding depends on the encoded types, metadata, options, and custom
handlers as well as map order. It is stronger than sorting keys, and is not
equivalent to Clojure `=`.

## Two canonical profiles

`:canonical` uses RFC 8949 §4.2.1 bytewise lexicographic key order.
`:canonical-rfc7049` uses the older length-first, then lexicographic rule.

| Rule | Peer configuration |
| --- | --- |
| Bytewise | fxamacker/cbor `SortCoreDeterministic` |
| Length-first | Python cbor2 `canonical=True`, Rust ciborium `CanonicalValue`, clj-cbor |

The [Rust canonical fixture](../interop/rust/src/canonical.rs) checks the
length-first comparison. For mixed key types, the difference is visible:

```text
{1000 "x", "a" "y"}
:canonical-rfc7049   a2 6161 6179 1903e8 6178
:canonical          a2 1903e8 6178 6161 6179
```

Agree on the ordering rule and numeric conventions before signing re-encoded
values across implementations.

## Packed CBOR: evaluation

Boring does not implement Packed CBOR. The experiments in
[the benchmark directory](../bench/) explored repeated-item tables,
including their interaction with stringref.

A packer must assign stringref indices in wire order. Writing the packed body
before a table that appears earlier on the wire produces inconsistent
indices; a two-pass encoder can avoid that at additional encode cost.
The experiments did not establish a benefit sufficient to adopt the framework.

Shaped arrays borrow its use of `undefined` for an absent field.
They do not implement its packing tables or use its tag numbers.
See [the Packed CBOR draft](https://datatracker.ietf.org/doc/draft-ietf-cbor-packed/)
for that separate proposal.

## Which APIs exist on which platform

| API | JVM | ClojureScript |
| --- | --- | --- |
| `encode` / `decode` | Yes | Yes |
| `decode-seq` | Yes | Yes |
| `decode-seq-from` | `InputStream` | Pull function |
| `write-seq!` | Streams within an item | Buffers each item |
| `write-to!` / `write-to-buffer!` | Yes | No |
| `encode-indexed` / `build-index` / `seal-index!` | Yes | Yes |
| `write-indexed!` | Yes | No |
| `boring.nav` / `boring.edit` | Yes | No |
| `boring.mmap` | JDK 22+ | No |

Both platforms write indexes and recognise a valid trailing frame when
decoding a sequence. ClojureScript does not currently expose the navigation
API; an index written there can be used by a JVM reader.

## Determinism

Boring's `:canonical` profile targets RFC 8949 deterministic encoding.
The following choices and host differences matter for hashes, signatures, and
round trips:

- NaNs encode as `f9 7e00`, discarding their sign and payload. Another
  deterministic encoder may choose a different NaN convention.
- Signed zero is retained. Clojure can consider positive and negative zero
  equal while their CBOR encodings differ.
- Shortest numeric encoding narrows floats and reduces bignums that fit to
  basic integers. This follows `:float-policy :shortest`, including when
  selected under the default profile; it is not exclusive to `:canonical`.
- An integral JS number encodes as an integer. A JVM `Double` such as
  `1.0` encodes as a float, even under `:canonical`. Re-encoding across
  platforms can therefore change signed bytes.
- `:archival` preserves float width but does not remove those host-level
  differences.

Boring produces canonical output; it does not verify canonical encoding of
incoming bytes. See [Security](SECURITY.md).
