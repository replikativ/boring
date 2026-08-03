# CBOR concurrency, memory-safety, and completeness review

Date: 2026-08-03  
Reviewed revision: `988ede2` (`seq-index`)  
Scope: current JVM and ClojureScript writers/readers, reusable and streaming
entry points, user-tag dispatch, `boring.nav`, and the JDK 22+ mmap adapter.

This is a companion to
[`SERIALIZATION-CORRECTNESS-REVIEW.md`](SERIALIZATION-CORRECTNESS-REVIEW.md).
The earlier review concentrated on the recent writer/index work. This pass asks
three narrower questions:

1. Which objects may safely cross threads, and what happens when they do?
2. Can hostile input corrupt memory, escape lifetime/bounds checks, or exhaust
   memory despite the stated limits?
3. How completely and accurately do the reader and writer implement the CBOR
   generic data model and the standard tags they claim to understand?

The distinction between **core CBOR**, **validity checking**, and **tag
semantics** matters. A decoder can parse a tag as an inert `TaggedValue` without
implementing that tag's semantics. Once it elects to interpret a standard tag,
however, accepting invalid content or rejecting conforming content is a
semantic conformance issue.

## Executive assessment

There is no evidence of a native spatial-memory-corruption primitive. The JVM
uses checked arrays and the Foreign Function & Memory API, while ClojureScript
uses checked JavaScript typed arrays. Closing an mmap arena invalidates its
cursors instead of allowing a use-after-free. The realistic safety failures
are therefore **wrong values under races**, **unbounded allocation / OOM**, and
**raw exceptions that bypass the documented error contract**, not arbitrary
memory writes or code execution.

The normal isolated paths are sound: one fresh codec per operation, immutable
input bytes, and a transport-level size limit. The current tree also adds the
right navigation remedy, `boring.nav/fork`: a stress run using one fork per
worker completed 3,200 lookups with zero failures.

The important remaining issues are:

- A shared nav source is still intrinsically racy. The best-effort detector is
  explicitly not synchronization and does not cover every positional method.
- There is no decode allocation/item budget. A valid 1,000,005-byte document
  retained approximately 26.75 MB after decode, invalidating the documented
  “roughly 5x” worst-known figure.
- Both readers accept the non-well-formed simple-value encodings `f8 00` through
  `f8 1f`.
- Content-equal byte strings are not equal host map keys. Consequently, both
  readers miss duplicate byte-string keys, and the ordinary writer can emit an
  invalid CBOR map from a valid host map.
- RFC 9581 duration conversion still narrows big integers with `longValue()`
  and can silently return the wrong `Duration`.
- The tag-40 writer emits zero dimensions, which RFC 8746 forbids, while the
  built-in reader implements only a narrow subset of the tag and rejects other
  conforming forms.
- Canonical set elements are staged once but encoded again. Stateful handlers
  can therefore make the emitted set non-canonical, and handlers run twice.

I would treat the simple-value, byte-string-key, duration-overflow, and tag-40
writer findings as correctness blockers for a claim of validated CBOR output
and input. The allocation budget is the main hostile-input availability gap.

## Verification performed

- `clojure -M:test`: **219 tests, 6,745 assertions, 0 failures/errors**.
- Advanced ClojureScript build plus Node: **112 tests, 928 assertions, 0
  failures/errors**.
- Shared-nav stress on the reviewed revision: **3,129 failures in 3,200
  lookups**. The detector reported 3,093 as `:boring/concurrent-use`, but 9
  remained plausible wrong values and 27 escaped as other exception types.
- The same workload with one `nav/fork` per worker: **0 failures in 3,200
  lookups**.
- Targeted byte-level and semantic reproducers for the findings below.
- A retained-heap probe in a fixed 512 MB JVM, forcing GC before and after the
  decode. The heap figure is an observation, not a formal upper bound.

The passing suites are meaningful, especially the skip/decode properties and
hostile-input generators. The remaining bugs occupy gaps those generators do
not naturally reach: two-byte simple values omitted from the generated WG
corpus, two independently allocated byte strings used as map keys, valid
standard-tag frames with unsupported inner forms, stateful handlers, and very
wide but shallow documents.

## Concurrency safety

### C1 — High: a nav source and all of its cursors share mutable parser state

Locations: `src/boring/nav.clj` (`Nav`, `source`, `items`, `fork`) and
`Reader.java` (`pos`, `depth`, `scratch`, `skipFrom`, `readFrom`,
`headArgAt`, `headEndAt`).

`Cursor` and `Items` look like immutable read-only values, but every derived
cursor points back to one `Nav`, and that `Nav` owns one mutable `Reader`.
Positional operations temporarily replace the reader's global position and
restore it afterward. Interleaving those save/set/read/restore sequences can
make one thread continue at another thread's offset.

This is an integrity failure, not merely a race that reliably throws. The
stress probe returned truncated but plausible vectors and scalar values where
vectors were expected.

The current revision materially improves the situation:

- `nav/fork` creates a fresh reader and probe cache while sharing the immutable
  decoded index and byte source.
- The docs now state the single-threaded contract.
- `readFrom` and `skipFrom` contain a best-effort overlap detector.

Those are good mitigations, but they do not make the shared object safe. The
flag is deliberately non-volatile, is a check-then-set rather than a lock, and
`headArgAt`/`headEndAt` also mutate `pos` without consulting it. A positional
head read can therefore interfere with a protected `readFrom` operation.

**Required contract:** one source/fork per concurrently active thread. A cursor
may be handed from one thread to another after a happens-before edge, provided
the former thread stops using it.

Recommended follow-up:

- Keep `fork` and make the concurrency rule prominent in the `source` and
  `items` docstrings, not only `SECURITY.md` and `fork`.
- Keep the discovered parallel-fork test added in this revision; it usefully
  pins the supported concurrency model without relying on the racy misuse case
  to fail in one particular way.
- If misuse must fail deterministically, use local positional parser state or
  synchronize all positional operations. Do not present the current detector
  as enforcement.

### C2 — Medium: the byte source is shared, not snapshotted

`nav/fork` safely separates parser state, but intentionally shares `byte[]` or
`ByteSource`. This is safe only if that source supports concurrent reads and
its contents do not change.

- A caller can mutate a `byte[]` during decode/navigation.
- A custom `ByteSource` implementation may not be thread-safe.
- A read-only mmap prevents writes through this mapping, not writes or
  truncation by another file descriptor or process. Concurrent file mutation
  can produce a mixed-version document; truncation of mapped storage can also
  produce an OS/JVM-dependent mapping fault rather than a normal CBOR error.

The library cannot cheaply snapshot a large mapping—that would defeat mmap.
The operational requirement should instead be explicit: map immutable files,
publish them by atomic rename, and do not truncate or rewrite them while any
arena is live. `fork` should document that its custom `ByteSource` must support
concurrent reads.

### C3 — Medium: lazy sequence readers are single-consumer state machines

`decode-seq` and `decode-seq-from` close over one mutable reader; the streaming
form also closes over an `InputStream` and refill buffer. They should be
treated as a single-consumer lazy sequence. Do not concurrently realize
different tails or call a pull source from multiple workers. Parallel work
should start after each decoded item has been detached from the sequence.

Clojure's `LazySeq` realization reduces accidental overlap, but that is not an
API-level synchronization guarantee for the enclosed reader/source and does
not make the underlying `InputStream` thread-safe.

### C4 — Low: registries are safely published but not deeply immutable

The registry maps are final and copy-on-write, so ordinary Clojure construction
and publication are safe. Handler functions remain user code and must
themselves be thread-safe if the registry is shared.

The Java method `TagRegistry.writerFor` returns the registry's live mutable
`Object[]` pair. A Java caller can change its tag or function after publication,
contradicting the class's immutability claim and creating a data race. Replace
the array with an immutable holder/record, or return a defensive copy through a
non-hot public accessor while keeping an internal immutable lookup.

### C5 — Low: same-codec callback re-entry is unsafe

A registered handler or fallback can capture and re-enter the same reusable
writer/reader. The callback does not receive the codec directly, so this
requires an explicit closure and is trusted-code behavior, but the result can
clobber position, namespace, depth, or scratch state. Document handlers as
non-reentrant with respect to the codec invoking them, or add an explicit
re-entry guard if this should fail predictably.

## Memory and lifetime safety

### M1 — High: input size and depth do not bound decoded heap

`checkCount` prevents a tiny document from declaring an impossible giant
container, and `:max-depth` prevents stack exhaustion. Neither bounds the
number of valid items or their host-object amplification.

A concrete valid input was an array of 500,000 one-character text strings with
stringref disabled:

```text
wire bytes       1,000,005
decoded count      500,000
retained delta   26,750,784 bytes
ratio                  26.75x
```

Each two-byte wire item becomes a `String` object/backing storage plus a vector
slot and tree storage. Maps and other object-rich shapes have similar overhead.
Indefinite containers avoid even a declared item count; EOF bounds the loop,
but not the heap relative to an allowed mmap/file size.

This invalidates `doc/SECURITY.md`'s current “roughly 5x” worst-known statement.
It also means a transport byte limit alone must be set much lower than the
available heap. For mmap, “the file fits address space” says nothing about
whether a selected subtree fits heap when realized.

Recommended fix: add optional decode budgets—at least maximum materialized
items and maximum materialized byte/string payload, ideally one cumulative
allocation-cost budget—shared across recursion and tag handlers. Keep a hard
external byte/file limit as the first line of defense.

### M2 — High on very large sources: map staging multiplies in `int`

For a definite JVM map, the reader validates `n` against remaining bytes and
then allocates:

```java
Object[] kvs = new Object[n * 2];
```

On a `ByteSource` larger than 2 GiB, `checkCount(n, 2)` can succeed for an `n`
whose `n * 2` overflows signed `int`, leading to a raw
`NegativeArraySizeException`. Smaller values can still request an allocation
far beyond the usable heap. The shaped-array path has the same interleaved
key/value allocation shape.

Use checked multiplication before narrowing and reject against an explicit
materialization budget. Catching `OutOfMemoryError` after the request is not a
safe substitute; the process may already be unrecoverable.

### M3 — Medium: indefinite strings use multiple full-size copies

The JVM indefinite byte-string reader creates each chunk, copies all chunks
into `ByteArrayOutputStream`, then copies again in `toByteArray`. CLJS retains
chunks and joins/copies them. Indefinite text similarly holds chunk strings and
the final joined value. Streaming input therefore bounds retained source data,
but peak heap for one item is larger than “item plus chunk size” suggests.

The JVM stream refill path also grows/copies the accumulation buffer and then
passes another `Arrays.copyOf` to the reader after each refill. A single item
larger than the chunk is therefore repeatedly copied while it is assembled;
old buffers become collectible, but transient peak memory and allocation
traffic can be several times the final item.

This is not a correctness bug, but the bounded-memory documentation should say
“a multiple of the largest materialized item plus the refill buffer,” and a
payload budget should cover the sum of indefinite chunks before concatenation.

### M4 — Medium: reusable codecs retain attacker-selected peak capacity

Reused writers deliberately keep their largest output buffer, canonical
scratch writer, and symbol tables. Readers retain off-heap staging scratch,
duplicate-key hash arrays, stringref arrays, and bounded identifier caches.
This is sensible for trusted hot loops but means a pooled codec retains the
largest message seen by that pool member.

Provide either a `trim`/`discard-if-over` policy or document that pooled codecs
should be discarded after an oversized or untrusted operation. Clearing
contents on reset prevents semantic leakage; it does not return capacity.

### M5 — Medium: `:chunk-size` is not validated

On the JVM, `decode-seq-from` with `{:chunk-size 0}` silently returns an empty
sequence for nonempty input. A negative value throws raw
`NegativeArraySizeException`. On CLJS, zero/negative sizes reach typed-array
construction or copy failures. This is both an option-validation gap and a
platform differential.

Reject non-integer, non-positive, and platform-unrepresentable chunk sizes with
`:boring/bad-option` before constructing the buffer.

### M6 — Low: positional byte helpers need checked ranges

`Reader.bytesBetween(start,end)` narrows `end-start` directly to `int`, and
`bytesEqualAt` checks `p + n > limit` with overflow-prone addition. Current nav
callers normally provide offsets produced by the parser, so this is primarily
Java API hardening. Validate `0 <= start <= end <= limit`, require the length to
fit an array, and compare `n <= limit - p` rather than adding.

### M7 — Positive: mmap lifetime and native bounds are checked

The mmap adapter uses `Arena.ofShared`, which permits cross-thread memory
access, and returns the arena so ownership is explicit. Failure during mapping
or nav construction closes the arena. Closing it invalidates all derived
segments; the FFM API performs liveness and bounds checks instead of exposing a
dangling native pointer.

That is memory-safe in the Java sense. It does not make the shared `Reader`
thread-safe (C1), snapshot file contents (C2), or guarantee a library-typed
`ExceptionInfo` when a caller closes the arena concurrently with access.

## CBOR core correctness and completeness

The relevant baselines are [RFC 8949](https://www.rfc-editor.org/rfc/rfc8949.html),
[RFC 8746](https://www.rfc-editor.org/rfc/rfc8746.html), and
[RFC 9581](https://www.rfc-editor.org/rfc/rfc9581.html).

### F1 — High: non-well-formed two-byte simple values are accepted

RFC 8949 §3.3 says an encoder must not emit `f8` followed by a byte below 32;
those sequences are not well-formed. Appendix F lists this exact error class.
Both readers currently dispatch additional information 24 as an unrestricted
`SimpleValue` constructor.

Reproducer:

```text
f800  => #boring.data.SimpleValue{:n 0}
```

The same applies through `f81f`. `test/boring/vectors.cljc` explicitly exempts
the old vector because it predates RFC 8949, but the current RFC—not the older
test vector—is the correct authority for a present-day CBOR claim. Reject these
bytes as not well-formed on both platforms. Keep the writer's opt-in emission
only if byte-identical legacy passthrough is an explicit non-conforming mode.

### F2 — High: duplicate byte-string map keys are neither detected nor prevented

RFC 8949 treats maps with duplicate keys as invalid because generic CBOR maps
have unique keys. `byte[]` and `Uint8Array` use object identity for host map
equality, while CBOR byte-string equality is by contents. Two independently
decoded `h'01'` keys therefore survive as two host keys and defeat the current
hash/equality duplicate check.

Reader reproducer:

```text
a2 4101 00 4101 01  => decoded map count 2
```

The ordinary writer can create the same invalid bytes from a host map whose
two distinct array keys both contain byte `01`:

```text
host map count 2
encoded a2410100410101
paired decoder count 2
```

Canonical mode happens to catch identical encoded keys, but ordinary and
interop profiles do not. Fixing only the reader is insufficient: successful
encode must not produce an invalid map.

Options include a content-value byte-string wrapper for key position, or a
CBOR-semantic key comparator/hash used by both writer and reader. The latter
must retain CBOR distinctions such as integer `1` versus floating `1.0` even
where a host language's equality model does not.

### F3 — High: RFC 9581 duration conversion still silently overflows/truncates

The current tag-1002 patch correctly rejects several formerly truncated float
forms, but it still calls `longValue()` before proving that integer values fit a
Java `long`:

```text
{1 18446744073709551616N}          => PT0S
{1 1, -9 18446744073709551616N}    => PT1S
{1 1.5M}                           => PT1S
{1 1.0000000005}                   => PT1.000000001S
```

The first two are silent modular narrowing. The BigDecimal case arrives through
tag 4 under key 1, whose allowed form is a basic int/float; treating every
`Number` as such erases the wire distinction and truncates it. The final case
rounds a binary float even though the comments claim “exact, or refused.”

There are completeness/validation issues in the key walk as well:

- Unsupported negative integer and text keys are elective in RFC 9581 and must
  be ignored, not rejected. The current code correctly ignores text keys but
  rejects every negative key except `-9`.
- Keys of types other than unsigned integer, negative integer, or text string
  are outside the tag's map model. The code skips every non-`Number`, accepting
  tagged/byte-string/other keys silently.
- Keys 4 and 5 are valid alternative bases. Rejecting them is an acceptable
  documented implementation limit if lossless conversion is unavailable, but
  it means tag 1002 support is partial rather than “used as specified.”

Validate the decoded CBOR representation before narrowing: exact integer type,
`BigInteger` range checks, exactly one supported base key, unsigned fraction in
range, and an explicit exactness/rounding policy for binary floats. CLJS should
perform the same structural validation even though it preserves the value as a
`TaggedValue`.

### F4 — High: the tag-40 writer emits semantically invalid dimensions

RFC 8746 §3.1.1 requires dimensions to be **unsigned integers distinct from
zero**. A zero-row primitive matrix is currently encoded as tag 40 with
dimensions `[0,0]`:

```text
d82882820000d84f40    ; tag 40, dims [0,0], empty s64-le typed array
```

The paired reader accepts and reconstructs it, so round-trip tests bless output
that violates the registered tag's content rules. There is no standard tag-40
representation of a zero-dimensional extent under RFC 8746. Use the existing
non-tagged/private type-preserving fallback or explicitly refuse the value;
do not attach tag 40.

### F5 — Medium: the recognized tag-40 subset rejects other conforming forms

RFC 8746 permits any number of nonzero dimensions and permits the flattened
payload to be an ordinary CBOR array, Typed Array, or Homogeneous Array. The
built-in handlers require exactly two dimensions and a primitive platform
array. They therefore reject conforming 3-D arrays and the RFC's own ordinary
array example. Tag 1040 column-major arrays are not interpreted.

Partial standard-tag support is reasonable, but a generic decoder should not
turn supported-by-RFC forms into hard errors merely because the convenience
conversion is narrower. Prefer returning an inert `TaggedValue` when the
content is valid but outside the native-matrix subset, or make semantic tag-40
interpretation opt-in.

The CLJS handler additionally needs pre-use checks that dimensions are safe
integers and `flat` is a supported typed array. It currently reads `.-length`,
uses `neg?`, constructs an array, and calls `.subarray` before establishing all
of those facts, so carefully shaped bad tag content can still escape as a raw
JS error or be coerced differently from the JVM.

### F6 — Medium: canonical sets re-run handlers after determining order

Both writers pre-encode each set element to obtain its canonical sort key, but
then emit by calling `writeValue` on the original element again instead of
copying the staged bytes. A registered writer function can be stateful, observe
time, or read mutable state. Its second result need not match the bytes used to
sort.

A two-element set with a handler returning staged values `[1,2]` and emitted
values `[2,1]` produced:

```text
handler calls: 4
d9010282d99c4102d99c4101
```

The final elements are descending even though the profile claims deterministic
canonical set ordering. Map keys already copy their staged encodings; sets
should do the same. This also restores the expected one callback invocation per
value.

### F7 — Medium: `TaggedValue` accepts non-integer tag numbers

The JVM writer accepts any `Number` and calls `longValue()`. With stringref
disabled, tags `1.5` and `1` both encode as `c100`. CLJS `head!` likewise does
not validate that a numeric tag is a non-negative integer in the uint64 range;
negative and fractional values can be truncated into malformed or unintended
headers.

Registry APIs now validate their signed-`long` subset, but the public
`tagged-value` constructor bypasses the registry. Validate at the tag emission
boundary on both platforms. The accepted domain is exactly uint64; on the JVM
that includes `BigInteger`/`BigInt` through `2^64-1`, and on CLJS values above
the safe-number range must be `BigInt`.

### F8 — Medium: tag 0 collapses a valid leap second

RFC 3339 permits `time-second = 60` at an inserted leap second. Java's
`Instant.parse` accepts the spelling but normalizes it to second 59:

```text
2016-12-31T23:59:60Z => 2016-12-31T23:59:59Z
2016-12-31T23:59:59Z => 2016-12-31T23:59:59Z
```

Two distinct valid tag-0 strings therefore decode to the same instant. CLJS's
`Date` path generally rejects the leap-second spelling, creating a platform
differential. Since neither platform type can faithfully represent it, reject
`:60` consistently or preserve that valid form as a `TaggedValue`; silently
normalizing is the worst option.

### F9 — Medium: some built-in tag-27 markers still leak raw exceptions

Well-formed tag-27 frames with a null sequence payload reach raw null
dereferences on the JVM:

```text
27(["java/boolean-array", null]) => NullPointerException
27(["java/string-array",  null]) => NullPointerException
27(["java/object-array",  null]) => NullPointerException
```

This contradicts the `ExceptionInfo`-only rejection guarantee. `seqableContent`
currently treats `nil` as seqable, but these consumers require a non-null
collection/list. Validate the exact marker content before casting or calling
`size`/`toArray`. Add every reserved tag-27 name, including nil and wrong-type
arguments, to the targeted hostile-tag corpus on both platforms.

### F10 — Medium: typed arrays differ on indefinite byte strings

RFC 8746 defines typed-array content as a CBOR byte string; it does not narrow
that to the definite-length representation. The JVM fast path manually reads a
head and rejects indefinite byte strings, while CLJS calls the general reader
and accepts them. For example, tag 77 around an indefinite byte string is
rejected by the JVM as `:boring/bad-tag-content`.

Supporting indefinite content requires materialization, so the zero-copy fast
path can remain for definite strings with a slower fallback for the indefinite
case. Otherwise document the restriction as a deliberate non-complete semantic
implementation and make the platforms agree.

### F11 — Low: `SimpleValue`'s public domain is wider than its round-trip domain

`simple-value` documents `0..255`, but codes 20–22 encode as false/true/null and
decode to native values rather than a `SimpleValue`; code 23 has the dedicated
`undefined`; and 24–31 are reserved/non-well-formed under RFC 8949. Either
validate the constructor's opaque-value domain or document that these values
canonicalize to their CBOR semantics and will not retain the wrapper.

### F12 — Intentional completeness limits that should remain explicit

These are not bugs if accurately documented:

- The writer emits definite-length values only. Definite form is complete for
  values already held in memory; an indefinite/chunked writer would be a new
  streaming API, not required for CBOR correctness.
- Unknown tags remain `TaggedValue`s, which is the correct lossless generic
  behavior. CLJS leaving most RFC 8746 typed-array tags inert is preferable to
  lossy conversion.
- The reader accepts non-preferred integer/float widths and unsorted maps. It
  is a general decoder, not a deterministic-encoding validator. Signing code
  needs a separate “require canonical input” mode or must verify the exact
  original bytes.
- `decode` intentionally returns the first item and ignores trailing bytes;
  `decode-seq` handles RFC 8742 sequences. An `decode-exactly-one` entry point
  would still be valuable for callers validating a single CBOR document.
- Host number models differ: ClojureScript cannot natively retain every CBOR
  distinction between integer/float keys and number widths. Such cases need
  wrapper values if exact generic-data-model fidelity is required.

## Recommended order of work

1. Reject `f8 00..1f`; add the missing RFC 8949 Appendix F cases to both
   platforms' generated/handwritten bad corpus.
2. Define CBOR-semantic map-key equality and use it on read and write, starting
   with content-equal byte strings.
3. Rewrite tag-1002 validation around exact wire-level numeric categories and
   checked `BigInteger` conversions.
4. Stop emitting tag 40 for zero dimensions; decide whether unsupported valid
   tag-40 forms remain tagged or are fully implemented.
5. Add cumulative decode budgets and correct the 5x/bounded-memory docs.
6. Emit canonical sets from staged bytes; validate arbitrary tag numbers.
7. Validate tag-27 marker payloads and stream `:chunk-size`; align JVM/CLJS
   typed-array and tag-40 failure behavior.
8. Keep `nav/fork`, promote the one-fork-per-worker contract, and turn its
   stress proof into a discovered test.
9. Harden range arithmetic and add a policy for trimming oversized reusable
   codecs.

## Bottom line

The implementation is strong at ordinary round trips, bounds-checking declared
lengths, depth control, stringref state, index/nav correctness under isolated
use, and lossless unknown-tag preservation. The mmap layer does not introduce
an unsafe native-pointer path.

It is not yet safe to describe the decoder as having a fixed small memory
amplification or universal typed failure, nor the recognized standard-tag set
as fully “used as specified.” Most importantly, a successful ordinary map
encode can still produce invalid CBOR, valid duration input can still become a
wrong `Duration`, and sharing a nav source can still return wrong values unless
the caller uses `nav/fork`.
