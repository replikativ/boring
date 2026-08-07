# Compatibility policy

A serialization bug loses data quietly and is found long after the fact. This
file states what boring promises about the wire format, so the promise can be
held to rather than assumed.

## The promise

**Tag assignments are append-only. The meaning of a tag is fixed once
released.**

Concretely, across any two boring versions:

1. Bytes written by an older boring **decode** on a newer boring, to an equal
   value.
2. A tag that meant X in version N means X in version N+1. It is never
   repurposed, narrowed, or widened.
3. New capabilities arrive as **new tags**, never as a new meaning for an
   existing one.

What is *not* promised: that a newer boring produces byte-identical output to an
older one for the same value. Encoder improvements are permitted; they must be
deliberate, and the golden corpus makes them visible (see below).

## How the promise is enforced

Not by intention — by a test that fails.

`test/boring/golden_v1.cljc` holds frozen bytes for 60 portable and 30 JVM-only
values. `boring.golden-test` asserts both directions on every CI run:

- the frozen bytes still **decode** to the original value — this is promise 1,
  and it must never break;
- the value still **encodes** to the frozen bytes — this is what makes a format
  change impossible to ship by accident, because it fails in a diff a reviewer
  cannot miss.

Regenerate only with `bin/regen-golden`, and read the diff line by line.
Every changed line is a wire-format change: deliberate and documented, or a
bug. There is no third case.

The corpus already earned this on its first run, catching that JVM and
ClojureScript emitted record fields in different orders.

It has also demonstrated the other failure mode, which is worth stating because
it is the one nobody looks for: **a corpus only catches what it covers.** It
held no metadata, no sorted collection, no queue and no character, so two
deliberate wire changes passed it without a single line moving. Both are
covered now. When adding a construct to the wire, add it here in the same
commit — the diff should be additions only, and if an existing line changes,
that is the format break the file exists to surface.

## Why there is no version header

boring emits bare CBOR with no envelope. hako has a magic+version header; boring
deliberately does not, for two reasons.

First, CBOR is self-describing, so a document stays *parseable* by any CBOR
reader regardless of which boring wrote it. A header would not add parseability.

Second, a header only helps if the reader changes behaviour based on it, and
behaviour that varies by version is precisely what the append-only tag policy
exists to forbid. A version field would invite exactly the drift it appears to
protect against.

Applications that need to gate on a producer version should carry it in their
own envelope — datahike's `manifest.edn` already has a `format-version`, so
the dump path has that line of defence independently of the codec.

## Tag assignments

Standard tags used as specified: 0/1 (time), 2/3 (bignum), 4 (decimal
fraction), 25/256 (stringref), 27 (generic object → records), 30 (rational),
**32 (URI)**, **35 (regular expression)**, 37 (UUID), 39 (identifier →
keywords and symbols), 40 (multi-dimensional array), 64–86 (RFC 8746 typed
arrays — the JVM emits the five its primitive array types map to, s16/s32/s64/
f32/f64 little-endian, and reads 21 of the 24 tag numbers; 76 is reserved and
the two f128 tags stay TaggedValues. ClojureScript reads the five it can map to
a JS typed array and leaves the rest tagged — but **validates all 21 either
way**, so the two platforms accept and reject the same documents and differ only
in what they hand back. They did not: the 16 ClojureScript does not read fell
through to the unknown-tag path unchecked, so `64("nope")` decoded there and was
refused on the JVM), 258 (set), **1002 (duration, RFC 9581)**, **1004 (full-date,
RFC 8943)**.

### Stringref index space

Both text strings and **byte strings** take an index, and the threshold is the
stringref spec's: a string earns one when it is at least as long as the
reference would be — 3 octets for index 0–23, 4 for 24–255, 5 for 256–65535, 7
beyond. Byte strings count wherever they appear, including a bignum magnitude,
a UUID's 16 bytes and a typed-array payload.

The encoder and decoder must apply that rule identically or their index spaces
drift and a later reference resolves to the wrong entry. So must any other
implementation: this matches cbor2 byte-for-byte, which is the property
`interop/test_read_boring.py` and `interop/rust` check on every push.

Tag 256 opens a namespace and the table lives and dies with it. A nested
namespace shadows the enclosing one rather than extending it, and a reference
outside any namespace is an error, not an index into whatever happens to be
around.

### Exceptions travel as data

An `ex-info` in a message, or stored as an error value, is ordinary — and had
no encoding at all until recently. nippy carries a `Throwable` through Java
serialization, which is the mechanism behind the deserialization CVE family and
produces bytes no non-JVM reader can interpret.

boring carries the three fields that survive a process boundary meaningfully:

```
27(["clojure/ex-info", ["boom", {:a 1}, <cause or null>]])
27(["java/throwable",  ["java.lang.Exception", "plain", <cause or null>]])
```

The **stack trace is deliberately dropped**: it is large, it is meaningless in
the receiving process, and it leaks absolute source paths and internal class
names to whoever receives the message.

An `ex-info` reconstructs as a real `ExceptionInfo`. Any other `Throwable`
reconstructs as an `ExceptionInfo` too, carrying the original class under
`:boring/throwable-class` — reinstantiating a type named on the wire is the one
thing the reader does not do. ClojureScript has only `ExceptionInfo`, so both
arrive as one there.

### Decimals, and the two shapes of "decimal"

CBOR tag 4 is `[exponent, mantissa]` — value = `mantissa * 10^exponent`. That is
what `boring.data/Decimal` mirrors on ClojureScript, and what the JVM writes
from a `java.math.BigDecimal`.

`java.math.BigDecimal` itself, and fress's `fress.impl.bigdec/Bigdec` (the
ClojureScript decimal that datahike's `:db.type/bigdec` uses in the browser),
mirror the **other** convention: `unscaled * 10^-scale`. Converting between them
has two traps, and both are silent:

1. **The sign of the power is inverted.** `scale = -exponent`. Getting it
   backwards on a scale-2 money amount is a factor of 10,000 with no symptom.
2. **The mantissa is not one type.** On ClojureScript the reader yields an
   ordinary number below 2^53 and a `js/BigInt` above it, because making every
   small decimal a BigInt would allocate for nothing. Code doing exact BigInt
   arithmetic over it — which is the point of having a decimal — then throws
   `Cannot mix BigInt and other types` on the **small** case and works on the
   large one. A test suite full of interesting numbers passes; `1.50` fails in
   production.

Use the accessors rather than the fields. They work on whatever the platform's
decoder produced, and `decimal-unscaled` is always a big integer:

```clojure
(data/decimal-scale d)      ; => 2        (BigDecimal-style, negated exponent)
(data/decimal-unscaled d)   ; => 150N     (BigInteger / js/BigInt, always)
(data/decimal-from-unscaled (data/decimal-unscaled d) (data/decimal-scale d))
```

Scale is preserved exactly, which is the point: `1.50M` and `1.5M` stay
distinct on the wire, and every case above re-encodes byte-identically on both
platforms.

### What an unregistered tag-27 frame decodes to

Tag 27's content is `[type-name, *constructor-arguments]`. When the reader has
no registration for the name, it falls back by the shape of the **payload** —
never by any claim about the sender, because there is none to make: tag 27
carries no record/non-record bit, an extra array element would read as another
constructor argument to every foreign decoder, and a frame written by Python
carries no origin information at all.

| payload | decodes to | why |
|---|---|---|
| a map | `boring.data/UnknownRecord` | presents the fields directly, so `(:x v)` and `(into {} v)` work without a registration |
| anything else | `clojure.lang.TaggedLiteral` | offers `:tag` and `:form`, and never promises map-ness |

`boring.data/frame-name` and `frame-payload` read either without branching, and
`tagged-frame?` recognises both.

Both re-encode to the identical frame, so passthrough holds whatever the
payload shape. That mattered: returning `UnknownRecord` for a positional
payload produced a value that claimed `IPersistentMap` and then threw a raw
`ClassCastException` from `keys` and `IllegalArgumentException` from
`assoc`/`into`, while `(get v :e)` answered `nil` — a broken contract rather
than a caller error.

### Reserved tag-27 names

Tag 27 carries `[type-name, argument]`. A record uses its own name —
`namespace/Name`, as written — and these names are reserved for types CBOR
cannot otherwise distinguish. The prefix names the runtime that owns the type.

Both now carry a **slash**, so the old guarantee here — that a slash made
collision impossible, because a JVM class name never contained one — no longer
holds and has been removed rather than quietly left standing. What remains
true is weaker and still sufficient: colliding needs a record named
`sorted-map` in a namespace named `clojure`, and a caller's registry is
consulted before the built-in markers in both directions, so taking one of
these names deliberately works.

| name | argument | without it |
|---|---|---|
| `clojure/sorted-map` | a map | returns unsorted |
| `clojure/sorted-set` | an array | returns unsorted |
| `clojure/queue` | an array | returns a vector |
| `clojure/with-meta` | `[meta, value]` | metadata is dropped |
| `clojure/char` | a 1-character string | returns a `String`, and `(= \a "a")` is FALSE |
| `java/period` | a canonical ISO-8601 period | throws |

They are **frozen** and pinned in the golden corpus. Renaming one is a silent
break rather than a loud one: an unrecognised name decodes to an ordinary
`UnknownRecord` value, so nothing raises.

#### What a marker decodes to on ClojureScript

Seven of these name a type ClojureScript does not have. They decode there to
the same carrier any unregistered tag-27 frame decodes to — a `TaggedLiteral`,
read with `boring.data/frame-name` and `frame-payload` — which **re-encodes to
the identical frame**:

| marker | JVM | ClojureScript |
|---|---|---|
| `clojure/sorted-map`, `sorted-set`, `queue`, `with-meta`, `ex-info` | native | native |
| `java/throwable` | `ExceptionInfo` | `ExceptionInfo` (both re-emit as `clojure/ex-info`) |
| `clojure/char` | `Character` | `#clojure/char "a"` |
| `java/period` | `java.time.Period` | `#java/period "P1D"` |
| `java/char-array` | `char[]` | `#java/char-array "ab"` |
| `java/boolean-array`, `string-array`, `object-array` | arrays | `#java/boolean-array [true false]` |

This is the same rule as URI below, for the same reason. These used to decode
to the bare payload — `"P1D"` rather than a carrier — which reads as more
convenient and is not: re-encoding a bare string emits a plain text string, so
the frame was gone and a JVM peer on the far side received a `String` where it
had sent a `Period`. A ClojureScript relay downgraded every such value it
passed through, permanently.

**Both platforms can originate every marker**, which is what makes this
symmetric rather than merely lossless. Both writers encode a `TaggedLiteral` to
its frame, so

```clojure
(boring/encode (tagged-literal 'clojure/char "a"))
```

produces identical bytes on either platform, and a JVM peer decodes it to `\a`.
A browser can send a `char[]` or a `Period` it has no type for.

**`java/period` accepts only the canonical form** — exactly what
`Period.toString()` emits. `java.time.Period.parse` accepts much more (lower
case, a leading sign, per-component signs, weeks, leading zeros), but a
`Period` holds years, months and days and no spelling, so the rest cannot be
stored faithfully: `P1W` re-encodes as `P7D`, `+P1D` as `P1D`, `P1Y0M` as
`P1Y`. Accepting only what can be stored is what makes the two platforms agree
on legality *and* keeps every accepted document byte-stable, which matters
because boring keys content by bytes (see `boring.hasch`). Nothing legitimate
is refused — boring's writer emits `Period.toString()`, and `java/period` is
boring's own reserved name. Same trade as refusing RFC 3339 offsets past
±18:00.

The registry is consulted BEFORE these names, so a caller who wants one of them
for their own type can take it.

### Types with a registered tag but no counterpart on both platforms

| value | tag | JVM | ClojureScript |
|---|---|---|---|
| URI | 32 | `java.net.URI` | `TaggedValue` |
| regex | 35 | `java.util.regex.Pattern` | `js/RegExp` |
| duration | 1002 | `java.time.Duration` | `TaggedValue` |
| full date | 1004 | `java.time.LocalDate` | `TaggedValue` |
| decimal fraction | 4 | `java.math.BigDecimal` | `boring.data/Decimal` |
| rational | 30 | `clojure.lang.Ratio` | `boring.data/Rational` |

The bottom four rows were missing, and their absence read as a promise the
library does not keep: the section below asserts that `Duration` and
`LocalDate` "are carried" under tags 1002 and 1004, which is true of the wire
format and of the JVM, but a ClojureScript peer gets an inert `TaggedValue`.
Tags 4 and 30 do decode to a value on both platforms, just not the same one —
JavaScript has no `BigDecimal` or `Ratio`, so boring supplies its own carriers.
All six re-encode to identical bytes, so a round trip through ClojureScript
never corrupts a document; it just hands back less than the JVM would.

**A `TaggedValue` is still validated to the JVM's standard.** Preserving a tag
rather than converting it is not a licence to accept a shape the other platform
refuses — a reader that admits an unknown *critical* key where the other errors
is a parser differential whichever value the two sides go on to produce. So
ClojureScript enforces RFC 9581's tag-1002 map rules (unsigned keys are
critical, at most one scaled-fraction key, unsigned fraction, nothing finer than
a nanosecond) even though it hands back the map unconverted, and RFC 8943's
full-date grammar for tag 1004.

That claim has been tested against — twice, adversarially — so it is worth
saying what it does and does not cover. The intent is that the two platforms
**accept and reject the same documents**, and it does not mean they build the
same value from them; the whole point of these rows is that they cannot.

Treat it as a goal the suite enforces for the shapes it covers, **not as a
proved invariant**. Three rounds of adversarial audit found it broken each time
— most recently 198 documents across six classes, three of which the commit
before had claimed to close. Each round's cases are now pinned by tests. Assume
the next round would find more, and do not build a security property on this
sentence.

Two deliberate exceptions are named below: tag 1's epoch range, and RFC 3339
offsets beyond ±18:00.

**Offsets beyond ±18:00 are refused on both platforms**, though RFC 3339 §5.6
permits up to ±23:59. `java.time.ZoneOffset` stops at ±18:00, so honouring the
full range would mean a document a browser accepts and a server rejects. Every
real-world offset is within ±14:00. This refuses conforming input, deliberately,
in exchange for the two platforms agreeing.

Residuals, all of them platform limits rather than decisions:

- JavaScript has one number type, so `{1: 5.0, -9: 1}` is indistinguishable from
  `{1: 5, -9: 1}` in ClojureScript and only the JVM refuses the float base. A
  base with an actual fractional part is caught on both.
- A tag-0 fraction longer than **three digits** is truncated to milliseconds —
  **on both platforms by default**, not only on ClojureScript. `js/Date` and
  `java.util.Date` both hold milliseconds, and `:instant-type` defaults to
  `:date`. `0("2020-01-01T00:00:00.123456789Z")` decodes to `.123` and
  *re-encodes as a different document* on either side. `{:instant-type
  :instant}` keeps all nine digits on the JVM; ClojureScript has no equivalent.
  Both refuse more than nine digits, so they agree on which documents are legal.
- **Tag 1's accepted epoch range differs**, and this is the one known place the
  two platforms disagree about legality. A `js/Date` spans ±8.64e12 seconds; a
  `java.time.Instant` spans ±3.1e16. An epoch second between those bounds — say
  `1(1e13)` — decodes to a year-318857 instant on the JVM and is refused on
  ClojureScript. Neither is wrong about CBOR; the hosts simply differ.
- Tag 40 accepts a payload written under any of the 21 typed-array tags the JVM
  reads, and only the 5 ClojureScript reads. The same asymmetry as the row
  below, reached through a different tag.

A regex is symmetric in its TYPE — both platforms have one — but not in its
LANGUAGE, and the sentence here used to claim otherwise. Tag 35 carries a
pattern *source*, and `java.util.regex.Pattern` and `js/RegExp` do not accept
the same sources. Measured: `35("(?i)abc")` compiles on the JVM and is
`:boring/bad-tag-content` on ClojureScript, because JavaScript has no inline
flag syntax. The same applies to possessive quantifiers, atomic groups,
`\p{...}` outside the `u` flag, and `\A`/`\z`. Nothing in boring can close
this — translating one regex dialect into another is not a codec's job — so a
pattern that must cross platforms should be written in the intersection, and a
reader that may receive foreign patterns should expect a typed refusal.

URI is not: ClojureScript has no URI type, so tag 32 stays a `TaggedValue`
there. That is deliberate. Decoding it to a plain string looks lossless — a URI
*is* its string form — but it is not round-trip safe: re-encoding the string
emits a plain text string, the tag is gone, and a JVM peer on the far side
receives a `String` where it sent a `URI`. A `TaggedValue` re-encodes to the
identical bytes.

Tag 35 carries the pattern SOURCE only, not flags. A `Pattern` compiled with
`CASE_INSENSITIVE` round-trips as case-sensitive. The alternative was a private
tag for flags; tag 35 is what a foreign reader understands.

### Types with NO registered tag

Carried under a reserved tag-27 name (see above), because the untagged encoding
would be value-**wrong** rather than merely type-wide:

`Character` was written as a one-character text string and came back a
`String`. That reads like the harmless widening `Byte` → `Long` is, and it is
not: `(= \a "a")` is **false** in Clojure, so `{:c \a}` silently became a
different map. Now `27(["clojure/char", "a"])`. A non-BMP codepoint is refused
rather than truncated to its high surrogate — a `char` is a UTF-16 code unit,
and truncating decodes a different character than was written. ClojureScript
has no character type, so it reads the marker back as the one-character string,
which is what `\a` already is there.

`java.time.Period` is a date-based amount of years, months and days. RFC 9581
tag 1002 (duration) carries *seconds*, and that RFC's tag 1003, confusingly
called "period", means a time *interval* between two instants. Neither fits, so
it travels as `27(["java/period", "P1Y1M1D"])` — the ISO-8601 form `java.time`
itself parses.

`java.time.Duration` and `java.time.LocalDate` **are** carried, under the
registered RFC 9581 tag 1002 and RFC 8943 tag 1004 — on the JVM. ClojureScript
has neither type and hands back a `TaggedValue` for both; see the gap table
above. `java.sql.Date` is written
as tag 1004 too and reads back as a `LocalDate` by default, which is what the
value means; `{:date-type :sql-date}` gets the legacy class back, though not
the time-of-day, which tag 1004 has no room for and which a `java.sql.Date` is
not supposed to carry anyway.

Arbitrary JVM classes (`deftype`, POJOs) are **not** carried. See below for why
not by way of Java serialization.

### Why not a Java serialization fallback

nippy can freeze any `Serializable` object without a handler. Two things follow
that make it the wrong choice here.

The bytes stop being CBOR in any useful sense: a Java-serialized blob is opaque
to every non-JVM reader, so the values that most need portability are exactly
the ones that lose it. And reading it is a known RCE vector — nippy manages
this with two allowlists, and by default `thaw` returns a *quarantined stub*
rather than your object, so the capability is not even symmetric.

boring's equivalent is tag 27: a record travels as its type NAME plus a field
map. Any reader in any language gets an inspectable value; a reader with the
constructor registered gets the record back.

### 39649 — shaped array — PROVISIONAL

An array whose elements are all maps sharing one key set, encoded as
`[keys, [values-per-row...]]` so the keys appear once.

**Status: provisional, not yet registered with IANA.** Registration is
First Come First Served for tags ≥ 32768, so until it is filed the number is
not reserved and anyone could take it.

**Action required before publishing:** file the registration. The request is
written out ready to send in [IANA-REGISTRATION.md](IANA-REGISTRATION.md); it
needs one thing filled in, the permanent public URL of `doc/SHAPES.md`.

This was originally 40000. A check of the registry found 40000 **already
assigned**, to `ur:known-value` (Blockchain Commons) — a live collision, not a
hypothetical one. Re-verified 2026-07-31: still assigned, and 39649 is still
free, with nothing assigned anywhere in 38000–39999. 39649 was picked to be hard to collide with rather than
tidy: odd and non-round, because registrants take round numbers and contiguous
blocks, and mid-gap in 39000–39999, the one clean FCFS span below the dense
`ur:` cluster occupying most of 40000–40918.

Until the registration lands, a decoder that does not know the tag surfaces a
`TaggedValue` rather than misreading anything, so a collision degrades to
"unrecognised extension" and never to silent corruption. Shaped arrays are also
off by default (`:shapes true` opts in), so nothing writes this tag unasked.

## The archival profile

`:archival` is sorted keys **and** fixed-width floats: `{:stringref false
:float-policy :preserve-width :canonical true :shapes false :canonical-order
:rfc8949}`. Every bit of it is locked, because both halves are what the name
means.

It exists because determinism and type identity are separate axes that RFC
8949's deterministic profile happens to pin together. Before it, the
combination was unreachable — `:canonical` locks `:float-policy` and `:interop`
locks `:canonical` — so a caller who needed reproducible bytes *and* a `Double`
that stays a `Double` had no way to say so.

Reach for it when the artifact must outlive the process that wrote it: a
database dump, an archive, an audit trail. Two exports of the same data are
byte-identical, so one can be signed and a re-export diffs clean, while
`java.lang.Double` and `java.lang.Float` still survive the round trip.

What it does **not** claim is RFC 8949 deterministic conformance. That name
belongs to `:canonical` alone, and the difference is exactly the float rule:
§4.2.2 requires the shortest form that round-trips, which discards the width
distinction. If you need to agree octet-for-octet with a foreign canonical
encoder, use `:canonical` and accept the narrowing; if you need your own types
back, use `:archival` and accept that the bytes are boring's own convention.

| | `:interop` | `:archival` | `:canonical` |
|---|---|---|---|
| extensions emitted | none | none | none |
| map key order | insertion | sorted | sorted |
| `(double 2.0)` decodes as | `Double` | `Double` | `Float` |
| two equal maps ⇒ same bytes | no | yes | yes |
| RFC 8949 §4.2 conformant | no | no | yes |

## Two canonical profiles

`:canonical` follows RFC 8949 §4.2: bytewise lexicographic key order.
`:canonical-rfc7049` follows the older length-first-then-lexicographic rule
(RFC 7049 §3.9).

**Which peers implement which**, because the profile name is not enough to
predict agreement and this only ever surfaces at a signature boundary:

| rule | implementations |
|---|---|
| bytewise (RFC 8949 §4.2.1) = `:canonical` | fxamacker/cbor `SortCoreDeterministic`, `draft-ietf-cbor-serialization` |
| length-first (RFC 7049 §3.9) = `:canonical-rfc7049` | **Python cbor2 `canonical=True`**, clj-cbor, Rust ciborium |

`ciborium` was listed under the bytewise rule here and that was wrong: its only
ordering helper, `CanonicalValue`, is length-first. Corrected after
`interop/rust/src/canonical.rs` compared its output against ours over 989
values — which is the point of the encode-side gates, and is the kind of claim
that cannot be checked by reading a README.

Calling `:canonical-rfc7049` "clj-cbor's older rule" understated its reach —
cbor2 is the most widely deployed CBOR implementation there is, and its
canonical mode is the length-first one. Verified against cbor2 6.1.4:
`{1000 "x", "a" "y"}` gives `a2616161791903e86178` there and under
`:canonical-rfc7049`, against `a21903e8617861616179` under `:canonical`.

They are separate **profiles**, not one profile with a switch. The order used
to be a free option, so `{:profile :canonical :canonical-order :rfc7049}`
produced bytes that are not RFC 8949 canonical under the name this document
gives the signing profile — `{1000 :x, "a" :y}` begins `a219…` under one rule
and `a261…` under the other. A signer and a verifier who disagree about a
sub-option that does not appear in the profile name produce a verification
failure with nothing to point at.

For keys that share a major type the two orders coincide, which is essentially
all Clojure data; they diverge only when a longer-encoded key has a lower
leading byte, which requires mixed major types.

## Packed CBOR — measured, not adopted

`doc/IANA-REGISTRATION.md` says shaped arrays stand alone because Packed CBOR
([draft-ietf-cbor-packed][packed]) was "deliberately not adopted — see
doc/COMPATIBILITY.md". This is that section; it did not exist, so the argument
lived only in three bench scripts.

Packed CBOR generalises what shaped arrays do: a table of repeated items,
referenced by index. It is strictly more expressive. boring does not use it:

- **The numbers are provisional.** The draft says so; tags 113/114 and 128–143
  are not registered, and the draft expires. Shipping durable bytes under a
  number that may be reassigned is the exact failure mode the tag-39649 move
  was made to avoid.
- **Measured, it did not pay on our payloads.** Against shaped arrays and
  stringref on datom-shaped data: on heterogeneous maps the packer must choose
  one table that suits all of them, and the win collapses.
- **It composes badly with stringref, and the reason is structural.** Stringref
  indices are assigned by order of appearance in the decoded byte stream. A
  *fused* packer writes the rump first — assigning indices in rump order — while
  the table appears BEFORE the rump on the wire, so a decoder reading left to
  right registers the table's literals first and the two index spaces diverge.
  Silent corruption, not an error; the same shape as the byte-string index bug.
  A fused packer must therefore run with stringref off, paying that cost on
  every document whose shapes do not repeat. A two-pass packer keeps stringref
  by writing table-then-rump in stream order, at the cost of an analysis
  traversal that doubled encode time.
- **Tag 39649 cannot express one of its rules, and that is a feature here.**
  Packed's `simple(23)` as a value means the key is ABSENT from the
  reconstructed map, so "same shape" stops meaning "same key set". Shaped arrays
  deliberately require an identical key set, which is what makes the decoder a
  zip with no per-row branching.

Revisit when the draft becomes an RFC and the numbers are assigned.

[packed]: https://datatracker.ietf.org/doc/draft-ietf-cbor-packed/

## Which APIs exist on which platform

Reading is symmetric and is meant to stay that way: **every document one
platform accepts, the other accepts, and to an equal value** — modulo the type
substitutions tabled above. That is the promise this file is about, and the
conformance suite is where it is enforced.

Writing is not symmetric, and pretending otherwise would be the more expensive
mistake.

| | JVM | ClojureScript |
|---|---|---|
| `encode` / `decode` | ✔ | ✔ |
| `decode-seq` (RFC 8742) | ✔ | ✔ |
| `decode-seq-from` (streaming in) | `InputStream` | pull function |
| `write-seq!` (streaming out) | ✔, streams within an item | ✔, buffers each whole item |
| `write-to!` / `write-to-buffer!` | ✔ | — |
| `encode-indexed` / `build-index` / `seal-index!` | ✔ | ✔ |
| `write-indexed!` (streaming, index captured while encoding) | ✔ | — |
| `boring.nav` (offset navigation) | ✔ | — |
| `boring.mmap` (memory mapping) | ✔ | — |

The index is **written on both**, and this table said otherwise for longer than
it was true: `encode-indexed`, `build-index` and `seal-index!` are all in
`boring.core` on ClojureScript. Only `write-indexed!` is JVM-only, because it
captures nodes from the writer as it encodes and there is no stream to capture
from on the other side; ClojureScript reaches the same bytes by the byte walk,
which is why the two must agree and why `indexed-bytes-are-identical-on-both-platforms`
exists.

It is **read past** on both: a ClojureScript `decode-seq` recognises a genuine
trailing `boring/index` frame and does not hand it back as a phantom final item,
so the library's own default JVM output yields the same N items on either side.
It used to yield N there and N+1 here, which is the kind of asymmetry that is
worth a table.

Navigation and memory mapping are absent from ClojureScript because the
capabilities are: there is no `mmap` in a browser, and offset navigation without
one buys nothing over decoding the sequence.

So ClojureScript writes an index it cannot itself use. That is deliberate --
a blob written in the browser or on Node stays navigable to a JVM reader -- but
no consumer is known to rely on it today, and it costs a third of `core.cljs`
kept in byte-for-byte agreement with the JVM.

## Determinism

The `:canonical` profile targets **RFC 8949 §4.2** and the deterministic
serialization rules in `draft-ietf-cbor-serialization`, which is the CBOR
working group's live determinism work and is in WG Last Call. It is *not*
targeting `draft-ietf-cbor-cde`, which is a parked WG document.

Two consequences worth stating plainly, because both surprise people:

- **Every NaN encodes as `f9 7e00`**, whatever bits went in. A NaN carries a
  sign bit and a 51-bit payload, and boring keeps neither: `-NaN` and a
  signalling NaN both come back as the one quiet NaN. RFC 8949 §4.2.2 permits
  picking a single representation and §4.1's zero-padding rule would preserve
  the sign, so this is a choice rather than a requirement — Python cbor2 makes
  the same one, and Rust ciborium does not (`-NaN` → `f9 fe00`). It surfaced
  only once a THIRD independent encoder was compared, because the second shared
  our choice.

- **`-0.0` has no ClojureScript counterpart, and that is visible in the
  bytes.** JavaScript has one number type, and in it `-0` is a `Number` that
  every Clojure operation reports as equal to `0`: `(= 0 (- 0))`,
  `(identical? 0 (- 0))`, and `{0 :a (- 0) :b}` has one entry. boring encodes
  it as the IEEE half `f9 8000` regardless, so two values ClojureScript cannot
  tell apart encode differently — under `:canonical` as well, where `0` is
  `00`. It is reachable from ordinary arithmetic: `(* -1 0)`, `(- 0)`,
  `(reduce * 1 [-1 0])`.

  This is deliberate. Encoding it as `00` would make a ClojureScript producer
  silently drop the sign for a JVM or browser consumer that can represent it,
  and the round trip through `decode` does give `-0.0` back on the JVM. But if
  you rely on `:canonical` meaning *equal values encode identically*, know that
  ClojureScript's notion of equal is coarser than the wire's here. The JVM has
  the same seam one step further out: `(= 0.0 -0.0)` is true in Clojure while
  the two encode as `f9 0000` and `f9 8000`.

- **Canonical is lossy.** It reduces a bignum that fits to a basic integer and
  narrows a float to its shortest round-tripping form. A `BigInteger` may come
  back a `Long`; a `Double` may come back narrower. That is required, not a
  defect — you cannot both agree octet-for-octet with other canonical encoders
  and preserve a host type the wire has no room for. Use `:clojure` or
  `:interop` when type fidelity matters more than interchange agreement.

  **The knob is `:float-policy`, not the profile**, and the name undersells it:
  `{:float-policy :shortest}` reduces bignums too, under any profile. So
  `(encode (bigint 0) {:float-policy :shortest})` is `00`, not `c2 41 00`, and
  decodes to a `Long`. On the JVM this is easy to miss because `(= 0N 0)` is
  true; on ClojureScript `(= (js/BigInt 0) 0)` is false and the round trip
  visibly fails. `:canonical` implies `:shortest`, which is why this reads as a
  canonical-only property and is not one.

  If what you actually want is a *stable byte sequence* rather than agreement
  with other encoders — two exports that diff clean, a dump you can sign — use
  **`:archival`**, which sorts keys the same way but keeps float width. See
  "The archival profile" below. Reaching for `:canonical` because it is the one
  with determinism in the name is the mistake this paragraph exists to prevent:
  it will silently turn every `Double` in your dump into a `Float`.

- **Canonical output containing an integral float is not byte-identical across
  platforms.** ClojureScript has one number type, so `1.0` *is* the integer `1`
  and encodes as `01`, where the JVM knows it holds a `Double` and emits
  `f93c00`. No option reconciles this. Sign such a document on the JVM and it
  will not verify against ClojureScript's re-encoding of the same value.
  Pinned in the conformance suite so it stays a known quantity.

boring can produce canonical output but does **not** verify that incoming bytes
were canonically encoded — see `doc/SECURITY.md`.
