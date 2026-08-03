# Serialization correctness review

Date: 2026-08-03  
Reviewed revision: `a1382c6` (`seq-index`)  
Scope: JVM and ClojureScript writers/readers, streaming APIs, writer-captured and
scanned indexes, `boring.nav`, and the JDK 22+ mmap adapter.

## Executive summary

The ordinary JVM encode/decode path is in good shape. It has strong evidence
behind it: frozen vectors, generative round trips, hostile-input tests, exact
skip-vs-decode properties, and (for the new index writer) a differential oracle
against an independent byte walk. The recent index/nav work also handles many
failure modes that are commonly missed: stale footers, malformed offsets,
container/count disagreement, empty nodes, unsorted maps, 2 GiB offset overflow,
and heap-vs-mmap parser parity.

I would nevertheless not call the whole serialization surface correct yet.
There are four release-blocking correctness problems:

1. The ClojureScript canonical scratch writer does not preserve the parent's
   depth budget, does not inherit `:encode-fallback`, and does not reject map
   keys whose canonical encodings collide. All three were reproduced.
2. JVM canonical NaN output is not unique and can differ from ClojureScript for
   the same NaN value, contradicting the cross-platform deterministic profile.
3. RFC 8746 tag 40 has untyped malformed-input failures on both platforms, and
   the JVM matrix writer loses the type of empty/ragged matrices and throws on a
   null first row.
4. The registered RFC 9581 duration reader silently truncates valid fractional
   seconds and does not enforce the RFC's critical-key and fractional-field
   rules.

There are also API-contract problems: default `encode-indexed` output cannot be
navigated, registrations for scalar built-ins are silently ignored despite the
documentation, and invalid registered tag numbers can produce malformed CBOR.

Severity below describes the codec's own guarantee, not a claim of remote code
execution. The realistic impacts are wrong values, parser differentials,
unreadable output, untyped error escape, and unavailable navigation.

## What was checked

- Writer dispatch and primitive/header emission on JVM and ClojureScript.
- Width preservation, canonical ordering, canonical scratch writers, metadata,
  fallbacks, records, user tags, bignums, decimals, rationals, typed arrays,
  matrices, dates, durations, and stringref state.
- Reader bounds/count/depth handling, UTF-8, duplicate keys/elements,
  indefinite-length input, tag validation, typed arrays, tag 40, tag 1002, and
  reusable-reader state.
- Writer-captured index construction against `build-index`'s byte walk.
- Footer encoding/detection, slot delta expansion, sorted/unsorted lookup,
  sequence indexing, corruption fallback, and documented trust boundaries.
- Heap and `ByteSource`/`MemorySegment` access paths and mapping lifetime.
- Public option resolution across `encode`, `write-seq!`, footer sealing,
  navigation, and reusable writers/readers.

Verification performed:

- `clojure -M:test`: **199 tests, 6,631 assertions, 0 failures/errors**.
- Advanced ClojureScript build plus Node tests: **106 tests, 913 assertions,
  0 failures/errors**.
- Targeted JVM and ClojureScript reproducers for the findings below.

The passing suites are meaningful evidence, but several findings sit exactly in
the gaps between suites: JVM-only hardening tests for a `.cljc` guarantee,
well-formed wrong-shaped built-in tags, uncommon IEEE NaN payloads, and public
defaults that all examples override.

## Findings

### S1 — High: ClojureScript canonical maps can emit duplicate CBOR keys

Locations: `src/boring/writer.cljs:510-528`, especially the absence of the
duplicate-encoding check present in `Writer.java:1531-1542`.

Canonical encoding reduces source distinctions. In ClojureScript, the number
`1` and `js/BigInt(1)` are distinct map keys, but under `:profile :canonical`
both encode as the one byte `01`. The ClojureScript writer sorts and emits both
without checking adjacent encoded keys. Its own reader then rejects the result
as `:boring/duplicate-map-key`.

Reproduced result:

```text
input map count: 2
encode: succeeds
decode(encoded): :boring/duplicate-map-key
```

This violates the most basic writer invariant: successful encode should not
produce output the paired decoder rejects. It is also a JVM/CLJS differential;
the JVM writer already raises `:boring/canonical-duplicate` before emission.

Recommendation: after sorting the staged entries, compare adjacent encoded key
byte arrays with the bytewise comparator and raise
`:boring/canonical-duplicate`, exactly as the JVM path does. Add a portable
`.cljc` test using number/BigInt keys on ClojureScript and an equivalent
identity/custom map on the JVM.

### S2 — High: ClojureScript canonical key staging bypasses the depth budget

Locations: `src/boring/writer.cljs:455-478`, `480-528`, and `145-158`.

`canonical-sub-writer` copies the parent's current `depth` into the scratch
writer, but every key/element is then preceded by `(reset! scratch)`. `reset!`
sets `depth` back to zero, so the copied depth is never used.

Reproduced:

```clojure
(boring/encode {[[1]] :v} {:profile :canonical :max-depth 3})
;; ClojureScript: succeeds
;; JVM: :boring/max-depth-exceeded
```

The limit is described as an encode-side stack/security bound. A map key gets a
fresh budget, and maps nested through key positions can repeatedly obtain fresh
budgets. The JVM implementation's separate `depthOffset`, which deliberately
survives scratch reset and accumulates across nested scratch writers, is the
right model (`Writer.java:1441-1495`).

Recommendation: add a depth-offset field to the CLJS writer (not merely another
assignment to `depth`), include it in `enter!`'s check, and preserve/accumulate
it across scratch resets. Move `canonical-key-nesting-shares-one-depth-budget`
or an equivalent into a portable test file.

### S3 — Medium: ClojureScript canonical keys and set elements ignore `:encode-fallback`

Locations: `src/boring/writer.cljs:455-478`, `480-528`.

The canonical scratch writer inherits registry, metadata, simple-value, width,
ordering, and depth options, but not `encodeFallback` or its `inFallback`
re-entry guard. A fallback works in ordinary values yet fails when the same
unsupported value is a canonical map key or set element.

Reproduced:

```text
canonical map with an unsupported JS object key and a string fallback
=> :boring/unsupported-type
```

The JVM version already fixed both halves: inherit the callback and inherit the
guard so a fallback returning the unsupported value cannot recurse through a
fresh scratch writer (`Writer.java:1471-1485`). The test is currently JVM-only
in `index_robustness_test.clj`.

Recommendation: port both fields and that test to ClojureScript. Copying the
fallback without the guard is not sufficient.

### S4 — High: canonical NaN is not deterministic on the JVM

Locations: `Writer.java:854-890`; compare the explicit CLJS NaN normalization
at `src/boring/writer.cljs:319-326`.

The JVM `toHalf` retains some payload bits from a float NaN. Consequently,
different double NaN payloads can produce different canonical bytes:

```text
Double bits 7ff8000000000001 -> f97e00
Double bits 7ffaaaa000000000 -> f97eaa
```

ClojureScript always emits `f97e00`. The project says canonical output is the
same value to the same bytes, byte-identically across platforms. It exposes no
NaN-payload or signaling-NaN value type, and decoding collapses all half NaNs to
ordinary `Float.NaN`, so retaining these bits is not a supported semantic
feature.

RFC 8949 says a deterministic protocol without intentional NaN-payload support
needs one representation, typically `0xf97e00`; its examples use that form.

Recommendation: special-case `Double.isNaN(d)` in the JVM shortest-float path
and emit half `0x7e00`, matching ClojureScript. Freeze several raw NaN payloads,
including a negative NaN, under the canonical profile.

### S5 — High: tag-40 malformed content escapes as raw runtime exceptions

Locations: `Reader.java:2015-2045` and
`src/boring/reader.cljs:807-829`.

The JVM reader casts both dimensions directly to `Number` and calls
`Array.getLength(flat)` without first proving their types. For example, a valid
CBOR tag-40 frame with dimensions `["bad", 1]` produces a raw
`ClassCastException`, not the promised typed `ExceptionInfo`:

```text
java.lang.ClassCastException
class java.lang.String cannot be cast to class java.lang.Number
```

The ClojureScript path similarly calls `neg?`, reads `.-length`, and later calls
`.subarray` without requiring non-negative integer dimensions and a supported
typed-array payload. Wrong-shaped content can therefore throw a raw JS error or
be coerced in platform-specific ways.

This directly contradicts `doc/SECURITY.md`'s typed-failure guarantee. The
existing arbitrary-byte fuzzer rarely synthesizes a fully well-formed tag with
wrong-shaped inner content, the exact limitation that document already notes.

Recommendation:

- Require each dimension to be a non-negative CBOR integer within the platform's
  array/count range before conversion.
- Require `flat` to be one of the primitive typed-array results supported by
  tag 40 before asking for its length or component type.
- Convert reflection/allocation/copy failures caused by wire shape into
  `:boring/bad-tag-content`.
- Add tag 40 to the targeted hostile built-in-tag corpus on both platforms.

### S6 — Medium: the JVM matrix writer mishandles empty, ragged, and null-row matrices

Location: `Writer.java:1297-1339`.

Three related cases contradict the method's comments and the library's
type-preservation posture:

- A zero-row `double[][]` takes the non-rectangular fallback and decodes as a
  `PersistentVector`, silently losing the source type.
- A ragged primitive matrix also decodes as a vector of primitive row arrays,
  not the original 2-D array type. The comment calls this “still round-trips”,
  but it does not round-trip the type.
- If the first row is null, `rowLen(rows[0])` executes before the loop's null
  check and throws a raw `NullPointerException`. The stated null-row fallback is
  therefore unreachable for the first row.

Reproduced:

```text
double[0][0] -> clojure.lang.PersistentVector
double[][] { null } -> NullPointerException
```

There is also an extreme-size arithmetic issue: `int n = rows.length * cols`
can overflow before it is widened for `typedArrayHeader`, bypassing the output
size check. It requires an impractically large live matrix but is easy to avoid.

Recommendation: treat zero-row matrices as rectangular using the known
`rowType`; check `rows[0]` before `rowLen`; decide and document whether ragged
matrices intentionally widen to vectors or need a private type-preserving
frame; and compute the flat element/byte counts in `long` before narrowing.

### S7 — High: RFC 9581 duration decoding is semantically incomplete and can return the wrong duration

Location: `Reader.java:1946-1967`; CLJS validation at
`src/boring/reader.cljs:762-775` has a related incomplete model.

Tag 1002 is specified as structurally identical to RFC 9581's extended time
map. The current JVM implementation treats key `1` and optional key `-9` as
arbitrary `Number`s and calls `longValue()` on both. This causes several
problems:

- A valid `{1 1.5}` duration is silently decoded as one second, losing 0.5 s.
- If `-9` is present, RFC 9581 requires key 1 to be an integer and `-9` to be an
  unsigned integer. The reader accepts fractional/negative values and truncates
  them.
- The map must contain exactly one unsigned base-time key. Keys 4 and 5 are also
  registered base forms (decimal fraction and bigfloat), but this reader rejects
  them as “no base value”.
- Unknown unsigned keys are critical and must cause an error. The reader ignores
  them.
- At most one of the scaled fraction keys may occur. Only `-9` is examined; the
  remaining registered fraction keys are silently ignored rather than supported
  or rejected as an unsupported representation.

The writer's own `{1 seconds, -9 nanos}` subset is internally correct, so normal
round trips hide this. The defect appears on conforming or adversarial foreign
input and is a parser/semantic differential.

Recommendation: either implement the RFC 9581 map rules and supported numeric
forms, or narrow the documented claim to a boring-specific tag-1002 subset and
reject every representation outside that subset. Never truncate a floating
base with `longValue()`.

### S8 — Medium: default `encode-indexed` output is not navigable

Locations: `src/boring/core.clj:506-527` and `src/boring/nav.clj:35-45,127-133`.

`encode-indexed` says its result can be passed to `boring.nav/source`, and its
one-arity overload delegates with default options. The default profile enables
stringref; navigation categorically refuses a root stringref namespace.

Reproduced:

```clojure
(nav/source (boring/encode-indexed (vec (range 20))))
;; => :boring/stringref-not-navigable
```

All index tests pass `{:stringref false}` or an archival/canonical profile, so
the advertised default is untested.

Recommendation: make `encode-indexed` force or default to `:stringref false`,
or reject the call before encoding unless the caller selected it. At minimum,
the one-arity function and docstring must not promise an unusable result.

### S9 — Medium: scalar write handlers are silently ignored despite the registry contract

Locations: `Writer.java:1839-1878`, `src/boring/writer.cljs:615-654`, and
`doc/EXTENDING.md:59-65`.

The docs state that a registration wins over built-in encoding for its exact
class. In both writers, however, the hottest scalar types are dispatched before
the registry lookup. JVM handlers for `String`, `Long`, `Keyword`, `Double`,
`Boolean`, boxed small integers, `Float`, `Symbol`, and `byte[]` are ignored.
The analogous number/string/keyword/boolean/symbol/Uint8Array/BigInt handlers
are ignored on ClojureScript.

Later built-ins such as URI, UUID, dates, regex, records, maps, and sets are
correctly overrideable, which makes this particularly subtle: the same API
works or silently does nothing based only on the registered class.

Recommendation: either move the registry lookup ahead of every built-in (and
measure the hot-path cost), add an explicitly separate scalar-override table or
fast-path guard, or document and reject registrations for non-overrideable
classes. Silent partial override is the incorrect state.

### S10 — Medium: invalid registered tag numbers can produce malformed CBOR

Locations: `TagRegistry.java:76-84`, `Writer.java:1873-1877`,
`src/boring/core.clj:266-284`, and the CLJS `register-tag`/`head!` paths.

The registry accepts negative tag numbers. The JVM registered-writer branch
calls the internal unchecked `head(TAG, tag)` rather than validated `writeTag`.
A handler registered as tag `-1` emits `ff` (the CBOR break byte) followed by
its content. No exception is raised; malformed output is returned.

`TaggedValue` itself is validated on the JVM, but ClojureScript's numeric
`TaggedValue` path goes directly through `head!`, whose ordinary number branch
also lacks a non-negative/integer/u64 range check. Thus the validation depends
on platform and entry point.

Recommendation: validate tag numbers at registry construction on both read and
write sides, and keep validation at emission as defense in depth. The accepted
domain is an integer in `[0, 2^64-1]`; the JVM registry's `long` API can accept
only `[0, Long/MAX_VALUE]` and should say so explicitly.

### S11 — Low: mmap setup leaks its arena when cursor construction fails

Locations: `src/boring/mmap.clj:56-90`.

`mmap-source` and `mmap-items` create a shared arena, map the file, and then call
`nav/source`/`nav/items`. If navigation setup throws—for example because the
file has the default stringref root—the function never returns the arena and
never closes it. The mapping remains live until GC/cleaner behavior happens to
recover it.

Recommendation: wrap navigation construction in `try`/`catch`, close the arena
on failure, and rethrow. Successful calls should retain the current explicit
caller-owned lifetime.

### S12 — Low: index option range failures are untyped

Location: `src/boring/core.clj:571-590`.

`:index` is converted to `long`, then to `int` for `Writer.setIndex`. A value
above `Integer/MAX_VALUE` throws a raw `ArithmeticException` from Clojure's
checked `int` conversion. Fractional, negative, and out-of-range values are not
validated as options with `:boring/bad-option`. `:index-min` has the same
conversion boundary.

The writer is right to reject widths the int32 index format cannot represent;
the issue is validation and error contract, not the limitation itself.

Recommendation: validate `:index` and `:index-min` once at the public boundary
as integral values in their supported ranges and return typed option errors.

## NAV/index/mmap assessment

Apart from the default mismatch and resource cleanup above, the new navigation
and index implementation is thoughtfully designed and heavily defended.

Correct properties I found:

- Writer capture is opt-in and does not alter document bytes.
- Nodes are reserved in preorder, so container offsets are naturally sorted.
- Array/map anchors are recorded at entry starts, map sortedness examines every
  adjacent key rather than an anchor sample, and legacy RFC 7049 ordering is not
  falsely marked bytewise sorted.
- Empty containers produce zero anchors.
- Writer-captured offsets are checked against the index format's signed-int32
  limit instead of wrapping.
- `write-seq!` keeps a file-relative base while resetting the per-item writer,
  then reliably disables capture on success and failure.
- Footer options match data options, and footer detection checks pointer shape,
  tag/name, exact end-of-file extent, payload arities, ascending nodes/anchors,
  container type/count, and first-entry alignment.
- Invalid or stale index structures fall back to scanning.
- Heap and mmap use one structural parser, with only byte access abstracted.
- Segment multi-byte reads use native-order unaligned access plus explicit byte
  swapping, and tests exercise every argument width and indexed sequence access.

One important limitation is already documented accurately: a structurally
consistent but false index can silently misdirect navigation. Validating every
middle anchor and the `sorted` flag would require the scan the index exists to
avoid. Therefore the index is a trust boundary and needs integrity protection
(checksum, MAC, signed envelope, or trusted storage) wherever bit rot or
adversarial modification matters. The current docs quantify this risk and
should remain prominent; it is not merely a performance footnote.

The signed-int32 offset format also means indexed files are limited to 2 GiB,
even though mmap and the trailing pointer use `long`. The writer now refuses
overflow, which is correct. Operationally, indexed logs need rotation before
that boundary or a future versioned int64 index format.

## Additional design limitations (not new defects)

- `decode` reads the first item and intentionally permits trailing bytes;
  `decode-seq` is the full-sequence API. Callers that require exactly one item
  need to enforce that policy themselves.
- Decode accepts non-preferred/non-canonical CBOR. Canonical signatures must be
  verified over the exact bytes consumed, not over decode/re-encode.
- Regex flags are intentionally lost because tag 35 carries only pattern source.
- Tag 0 cannot distinguish `java.util.Date` from `Instant`; an option chooses the
  receiving JVM type.
- Navigation requires `:stringref false`, treats tags as opaque, and cannot
  descend indefinite containers. These are checked constraints, not silent
  misdecodings.
- A borrowed writer buffer is unsafe across asynchronous or concurrent use; the
  API documents this clearly.

## Recommended fix order

1. Fix S1–S4 (canonical correctness and cross-platform parity), then move the
   canonical scratch tests into `.cljc` coverage.
2. Fix tag-40 validation and matrix edge cases (S5–S6); expand the targeted
   hostile-tag corpus rather than relying on random mutation.
3. Decide whether tag 1002 is fully RFC 9581 or a deliberately strict subset,
   and implement/reject accordingly (S7).
4. Make the indexed default usable (S8).
5. Align registry implementation and contract, including tag validation
   (S9–S10).
6. Close mmap arenas on construction failure and type-check index options
   (S11–S12).

After those changes, rerun both full suites plus the hostile/fuzz stages. I
would also add one small cross-platform “contract matrix” suite that exercises
each behavior-affecting writer option in ordinary value, map-key, and set-element
position; that is the recurring place where scratch writers diverge from root
writers.

## Standards references

- [RFC 8949, CBOR — preferred and deterministic serialization](https://www.rfc-editor.org/rfc/rfc8949.html)
- [RFC 9581, CBOR tags for time, duration, and period](https://www.rfc-editor.org/rfc/rfc9581.html)
- [RFC 8746, typed arrays and multi-dimensional arrays](https://www.rfc-editor.org/rfc/rfc8746.html)

