# Threat model

"Attacker", "hostile input" and "exploitable" are easy words to use loosely
about a parser. This file defines them for boring, and deflates them where the
facts do not support the stronger reading.

## Who is the attacker?

**Anyone who controls the bytes passed to `decode`.** That is the entire
definition. Concretely, in the stack boring is built for:

| source | how bytes become attacker-controlled |
|---|---|
| **kabel** | peers send CBOR over websockets. Textbook untrusted input — the clearest case. |
| **konserve backends** (S3, Redis, JDBC, LMDB) | anyone with write access to the backing store chooses what your process decodes. Bucket-write access becomes decoder input. |
| **datahike dumps** | the dump is designed to be portable, archived, and restored years later, possibly from media of uncertain provenance. It is the artifact meant to *outlive the database*. |
| **CLJS in a browser** | bytes from a server, another origin, or a user-supplied file. |

Note the asymmetry: for a dump on a local disk, an attacker who can rewrite the
file usually already has filesystem access, so the decoder is not the weakest
link. The kabel and konserve cases are the ones where the decoder genuinely *is*
the boundary.

## Trust boundary

`decode` is the boundary.

- **Untrusted**: every byte of the input.
- **Trusted**: the handlers you install. A `register-tag!` or `register-record!`
  callback runs with your process's privileges and boring does not sandbox it.
  Vet what you register.

## What cannot happen, by construction

This is the part worth being unambiguous about, because it is the class of
serializer vulnerability that actually causes CVEs:

- **No code execution.** There is no `Class.forName`, no `eval`, no
  `java.io.Serializable` path. A document names a record type as a *string*;
  the reader looks that string up in a registry and finds a constructor you
  installed, or it does not. A document naming `java.lang.Runtime` yields an
  inert `UnknownRecord`. Java deserialization gadget chains do not apply.
- **No cyclic values.** Tags 28/29 (shareable / shared-reference) are
  deliberately unimplemented, so a document cannot describe a value that
  references itself.
- **No decompression.** Nothing on the read path expands, so there is no
  compression-bomb amplification.
- **No I/O.** Decoding reads the buffer it was handed and nothing else.

**None of the defects found in this project were remote code execution.**
Wherever this project's history calls a defect "exploitable", read it as
availability or integrity, not RCE.

## What the decoder aims to guarantee

1. **Termination.** Every count is validated against remaining bytes before
   allocation; nesting is capped by `:max-depth` (default 1024); no loop depends
   on wire data for its bound.
2. **Bounded memory**, but the multiplier depends on the decoded SHAPE and
   reaches **23×** on documents made of many tiny containers — see below. Bulk
   payloads are 1×. Use `:max-items` to cap it.
3. **Typed failure.** Every rejection is an `ex-info` with a `:type` keyword.
   Nothing escapes as a raw `NullPointerException`, `ClassCastException` or
   `StackOverflowError`, so a caller's `catch ExceptionInfo` is sufficient.

   **`OutOfMemoryError` is the exception, and is deliberately not caught.** A
   heap exhausted by one decode is not a condition that decode can report and
   the caller can shrug off — the JVM is in trouble process-wide, and
   converting it would invite exactly that shrug. The bound that keeps a
   document from getting there is `:max-items`, not a catch.

   The `StackOverflowError` half is enforced at one boundary shared by every
   read path (`boring.errors/with-decode-errors`), because it was previously
   applied to four of six: `decode-seq-from` and `boring.nav` both leaked the
   raw error on deeply nested input. The original is attached as the cause, so
   a document that nests too deep stays distinguishable from a caller's own
   recursion overflowing inside a `nav` traversal.

   This is a **checked** claim, not an aspiration: `boring.hostile` feeds
   malformed content to every built-in tag and asserts a typed failure on both
   platforms. It was added because the claim was false — five tag handlers
   leaked `ClassCastException`, `IllegalArgumentException` and
   `DateTimeParseException`, and ClojureScript failed 17 of the cases. The
   byte-level fuzzers had not found them because they mutate bytes and mostly
   produce truncation, never a well-formed item of the wrong shape inside a
   tag with its own handler.
4. **Deterministic output** under `:profile :canonical`. The same value encodes
   to the same bytes, byte-identically on both platforms. Sets are ordered by
   their canonical encoded bytes, a rule this library defines because no RFC
   does — tag 258 is not core CBOR, so leaving elements in iteration order
   meant two sets that are `=` encoded differently.

### Guarantee 3 is empirical, not proven

It is backed by 300 000 fuzz mutants over valid encodings with zero untyped
failures, plus **RFC 8949 Appendix F.1 in full (94/94)** and the CBOR WG's
not-well-formed corpus (46/47, one documented exemption).

Those are two different corpora, and citing only the second overstated the
first. The WG file is not a superset of Appendix F.1 — it was missing subkind 2
and subkind 5 entirely, 18 of the 24 reserved additional-information bytes, 8 of
the 10 indefinite-chunk cases, and every large-declared-length case. The
subkind-2 gap is exactly why boring decoded `f8 18` as `simple(24)` for as long
as it did: the corpus that would have caught it was the one not being run.

That is evidence, not a proof. The fuzzer mutates *valid* encodings,
so it explores near-valid space well and far-from-valid space poorly. Every
round of fuzzing so far has found something; assume the next one would too.

### What "bounded memory" actually means

`checkCount` requires at least one wire byte per element, but a decoded element
costs more heap than one byte. Re-measured, wire bytes to retained heap:

| input | amplification |
|---|---:|
| 1 MB byte string | **1.0×** |
| `long[]` typed array (tag 79) | **1.0×** |
| array of distinct small integers | 6.5× |
| 100 000 short strings | 7.8× |
| map of 50 000 entries | 11.7× |
| 50 000 two-element vectors | **23.1×** |
| tag 40, dimensions `[500000, 1, 1]` | **149×** |

**This page previously said "roughly 5×", and that was wrong by a factor of
five.** The old table's worst case was an array of *empty* arrays, which is
cheap precisely because empty collections return shared singletons — it had been
64× until that fix. Empty containers are the best case, not the worst.

The `long[]` row is 1.0× only while the values fit a `long`. The same tag over
values above 2^63 has no lossless primitive form and becomes a vector of boxed
integers — **12.5×**, measured. Tag 64 (uint8) is **2.08×** whatever its values,
because Java has no unsigned byte and it widens to `short[]`. And wrapping any
of them in tag 40 with dimensions over them makes one host object per element —
149×. The tag-40 and boxed rows are now charged against `:max-items`; the
widening one cannot be, since it is a single object.

Read the shape of the table rather than any single number. **A bulk payload does
not amplify while it stays bulk**: a megabyte byte string decodes to a megabyte.
What amplifies is OBJECT COUNT — a one-byte container head that
becomes a `PersistentVector` with a header, an array and slots is the worst
per-byte case there is. So amplification tracks how many objects a document
asks for, not how many bytes it occupies.

That is also why the budget below counts items rather than bytes.

### Bounding it

Three limits, and they bound different things:

| option | bounds |
|---|---|
| transport size limit (yours) | how many bytes arrive |
| `:max-depth` (default 1024) | how deeply nested one value may be |
| `:max-items` (default unlimited) | how many items a decode may produce |

`:max-items` is the cumulative one, and it is what actually caps heap: nothing
else bounded the TOTAL, so a document within the size and depth limits could
still amplify past anything documented. Set it from the table above — items are
a good proxy for objects, and objects are what cost.

It counts **objects the decoder builds**, not only items it reads. That
distinction had teeth twice. A tag-40 multi-dimensional array arrives as one
byte string and is expanded into one host object per element, none of which
passes through the item reader: dimensions `[500000, 1, 1]` built **71 MB** of
nested vectors from 500,018 bytes — 149× — with `{:max-items 100}` set. A uint64
typed array whose values exceed 2^63 has no lossless `long`, so it becomes a
vector of boxed integers: 1 MB of `0xff` under tag 67 retained **11 MB**, 12.5×.
Both are charged before the reconstruction is allocated.

**It bounds object count, not bytes**, and one case is worth naming because it
looks like the same thing and is not: tag 64 (uint8) decodes to a `short[]`,
because Java has no unsigned byte. 4 MB in becomes 8 MB held — 2.08× — in a
*single* object, which no item budget can see. Typed arrays are bounded by your
transport size limit, not by `:max-items`.

**The budget is PER TOP-LEVEL ITEM**, and per positional read in `boring.nav`.
It is not a budget for a whole file. That is deliberate and matches what the
streaming API promises — retained memory is bounded by the largest single item,
not by the sequence — but it means a sequence of a million items within budget
is a million times the budget in total, so an input size limit at the transport
is doing real work here rather than being belt-and-braces.

It used to accumulate across items, which sounds stricter and was in fact
incoherent: `reset()` on a streaming refill cleared the counter while an
in-memory decode did not, so the same five items decoded at `:chunk-size 2` and
were refused at `65536`. Acceptance cannot depend on a buffering knob.

Both platforms enforce it. ClojureScript accepted the option and ignored it
until recently, so a browser or Node reader had no bound at all — if you are
relying on this in a CLJS deployment, check the version.

**Still enforce an input size limit at the transport.** boring reads what you
give it, and `:max-items` caps the result rather than the arrival.

## Malformed UTF-8

By default a text string whose bytes are not valid UTF-8 is refused, with
`:boring/invalid-utf8`. RFC 8949 §3.1 makes this the decoder's call, and
refusing is the right default: silently substituting U+FFFD changes the value,
and a value that differs between two readers of the same bytes is a parser
differential.

`{:validate-utf8 false}` switches to **replacement** decoding on both platforms
— the malformed bytes become U+FFFD and the document is accepted. ClojureScript
used to keep a fatal `TextDecoder` under this option, so the one setting that
exists to accept such input raised an untyped host `TypeError` there while the
JVM returned a string. Use it only for a producer you already know emits
mis-encoded text and whose data you need anyway.

## Encode-side refusals

Not every error is a decode error. boring refuses to ENCODE a value it cannot
represent faithfully, rather than writing an approximation:

| type | when |
|---|---|
| `:boring/invalid-utf16` | a string containing an unpaired surrogate — it has no UTF-8 encoding, and both platforms used to substitute silently |
| `:boring/canonical-duplicate` | two set elements that encode identically under `:canonical` |
| `:boring/bad-simple-value` | a simple value outside 0–255 |
| `:boring/reserved-simple-value` | 24–31, which RFC 8949 §3.3 forbids emitting — see below |
| `:boring/incompatible-options` | an option that contradicts the profile |
| `:boring/max-depth-exceeded` | nesting past `:max-depth`, on the write side too |
| `:boring/unsupported-type` | a type with no encoding and no registered handler |
| `:boring/unrepresentable-date` | a year outside 0000–9999, which RFC 3339 cannot express |

### `:permit-reserved-simple-values` emits what boring rejects

RFC 8949 §3.3 forbids *encoding* simple values 24–31, and its final sentence —
"Such sequences are not well-formed" — binds the **decoder** too. boring
enforces both: the writer refuses by default, and the reader raises
`:boring/malformed-simple-value` for `f8 00`..`f8 1f`.

`{:permit-reserved-simple-values true}` overrides the writer half. What comes
out is **not well-formed CBOR, and boring will not read it back.** The option
predates the decoder fix, when it could round-trip; it now exists only to
generate a vector for a peer that is lenient about this. Do not enable it on a
production encode path.

## Duplicate map keys

RFC 8949 §5.6 offers three approaches and requires that **"generic decoders
need to document which of these three approaches they implement"**. boring
implements the first: **a map with duplicate keys is rejected**, with
`:boring/duplicate-map-key`. This holds for definite- and indefinite-length
maps, on both platforms, and the same rule applies to tag-258 sets
(`:boring/duplicate-set-element`) — a set that declares *n* elements of which
fewer are distinct is refused rather than silently collapsed.

Duplicate detection compares keys by **CBOR data-item equality**, in one pass,
on both platforms. Host equality is used for values the host compares by value,
and **content** equality for the ones it compares by identity — a `byte[]`,
`short[]`, `int[]`, `long[]`, `double[]` or `float[]` on the JVM, any
`Uint8Array` or typed array in ClojureScript. Two byte strings with the same
bytes are one data item, so a map holding both is refused rather than decoded
with two entries.

Keys that merely *look* alike stay distinct, per §5.6.1: `1` and `1.0` are
different keys, and so are the text string `"a"` and the byte string `h'61'`.
Comparison deliberately does **not** run on raw encoded bytes: that would miss a
key written once as a literal string and once as a stringref, which is the same
key.

`{:check-duplicate-keys false}` turns this off, giving **last-wins** — RFC 8949
§5.6's second approach — on both platforms and at every map size. It is the
wrong default for anything reading untrusted input: silent last-wins is how two
implementations end up disagreeing about what a document says, which is a
parser differential with a signature check on the other side of it.

**Residual gap, stated rather than implied.** An array *nested inside* a
compound key is still compared by the host, so `{[h'01']: 1, [h'01']: 2}` keeps
two entries. Both platforms have this, for the same reason and to the same
extent, so they agree on what they accept — which is the property that matters
most here. Closing it means a deep content-aware walk of every key, and the
work is attacker-controlled by construction; that trade has not been made.

The detection itself is **not** quadratic. It was: byte-string keys went through
an O(n²) pair scan that was unbounded above the array-map threshold, and a
second O(n²) scan ran over the rest. Both are gone.

## Realistic harms, in order

1. **Availability.** Unbounded allocation, an infinite loop, a stack overflow, a
   quadratic scan. Most defects found in this project were of this kind. Impact:
   a wedged or dead thread, or a process OOM.
2. **Integrity.** The decoder returns a value the sender did not encode — `nil`
   smuggled in via an unchecked stringref index, a uint64 silently truncated to
   -1, a hostile tag aliased onto a built-in handler. Whether this matters
   depends entirely on what the application does with the value; boring's failure
   is returning something wrong, not the downstream consequence. An earlier
   draft of this project's notes called the `nil` case an "authorization
   hazard" — that was speculation about a hypothetical caller, and is
   withdrawn.
3. **Parser differential.** Two implementations disagree about what a document
   means. This matters in a specific architecture: one component validates or
   signs, another acts. boring has had JVM/CLJS differentials (tag truncation,
   tag-39 UTF-8 validation) and they are treated as defects for this reason.
4. **Error-handling bypass.** An untyped `Error` escaping the decoder defeats a
   caller's `catch`, which can leave a connection or reader pool in a bad state.
   This is why guarantee 3 is a guarantee and not a nicety.

## `:auto-construct-records?` — what it relaxes

By default there is no path from wire content to class loading. This option
opts out of that, and it is worth being precise about how far.

| | boring (opt-in) | nippy | `clojure.core/read-string` |
|---|---|---|---|
| resolver | `classForNameNonLoading` | `RT/classForName` | `classForNameNonLoading` |
| runs the named class's `<clinit>`? | **no** | **yes** | no |
| extra shape check | `IRecord` **and** static `create(IPersistentMap)` | `create(IPersistentMap)` | record ctor |
| gate | explicit option, default off | always on for records | `*read-eval*` |

The distinction that matters is the resolver. `Class.forName(name, true, …)`
initialises the class, so with nippy's resolver a hostile document can trigger
the static initialiser of **any class on the classpath** — which may open
sockets, read configuration or spawn threads — before anything checks whether
the class is even a record. Verified: a class whose initialiser writes a file
ran under `classForName` and did not under `classForNameNonLoading`.

What remains when you enable it is narrower than it first appears, and worth
measuring rather than asserting. With the option **off**, a hostile
`27(["my.app.AdminUser", {:admin? true}])` already decodes to a value where
`(:admin? v)` is `true` — `UnknownRecord` answers field lookups, so the
attacker-controlled data flows through either way. The option changes exactly
one thing:

```
off   (:admin? v) => true    (instance? AdminUser v) => false
on    (:admin? v) => true    (instance? AdminUser v) => true
```

So the residual risk is specifically **code that treats `instance?`, protocol
dispatch or a multimethod on `type` as a trust signal**. Such code needs
validation regardless, since the field values are attacker-chosen in both
cases. It is still a widening, and still off by default — but the reason to
leave it off is that narrow one, not a general fear of deserialization.

It is also **JVM-only**, and refused loudly on ClojureScript rather than
ignored: advanced compilation minifies record constructor names and there is
no runtime `resolve`. Accepting it silently there would mean one `.cljc`
codebase decoding to real records on the JVM and `UnknownRecord`s in the
browser.

- **Input size limits.** Enforce at the transport.
- **Canonical-form validation on decode.** *Not implemented.* boring can *produce*
  canonical output but does not verify that incoming bytes were canonically
  encoded: `1800`, `1900ff` and `1a0000ffff` all decode as `0`/`255`/`65535`
  exactly as their shortest forms do, and an unsorted map is accepted. **So an
  attacker can take a signed canonical document, re-encode an integer with a
  longer argument, and the value still verifies while the bytes differ.** If you
  are signing dumps, you must compare the bytes you verified against the bytes
  you use — do not verify a decoded value and then re-encode it.
- **Constant-time anything.** No timing-attack resistance; do not decode secrets
  where decode timing is observable and meaningful.
- **Sandboxing your handlers.**

## Thread safety

- `Writer` and `Reader` are **not** thread-safe. One per thread, or one per
  loop.
- **`boring.nav` is not thread-safe either, and this is the one that surprises
  people.** A source owns one `Reader`, and every cursor derived from it shares
  that Reader's mutable position and depth. Sharing one source across threads
  does not merely throw — it can return a **plausible but wrong document**. In
  200 parallel passes over one `items`, 6 came back silently wrong.

  Nothing about the surface warns you, which is why this is stated here rather
  than left to be inferred: the namespace is *read-only* navigation, `Items` is
  a reducible that invites `fold`, and `boring.mmap` picks a shared arena
  precisely so the mapping is not pinned to one thread.

  Use **`boring.nav/fork`** for a per-thread view. It shares the decoded index —
  the expensive part, 145 µs for a 20 000-item index — and replaces only the
  Reader, at 175 ns.

  A **best-effort** detector raises `:boring/concurrent-use` when it notices
  overlapping use of one Reader. It caught 178 of those 200 passes. It is a
  smoke alarm, not a lock: it is deliberately non-volatile so it costs nothing
  on the hot path, and one pass in that run still returned a wrong answer
  without tripping it. Do not rely on it to make sharing safe — `fork` is what
  makes sharing safe.
- There is **no process-global registry**. `tag-registry` returns an immutable
  value and every registration returns a new one, so two libraries in one JVM
  cannot register into a shared namespace and resolve by load order. An earlier
  design had a mutable `TagRegistry/DEFAULT`; it is gone.
- A **reusable** `Writer` or `Reader` carries its configuration between calls.
  `encode-into!` and the three-arity `decode-with` reset every option,
  including the registry; the two-arity `decode-with` deliberately does not,
  which is what makes it the fast path. Do not share one across trust
  boundaries without passing opts — a registry that survived one call into the
  next was a real defect here, not a hypothetical.
- The `boring.data` types the writer needs are resolved once, in a lazy holder
  class whose fields are `static final`, so publication is safe by
  construction. An earlier design published them through mutable statics.

- **What `fork` shares, and what that requires of you.** A fork separates parser
  state; it deliberately does NOT copy the bytes, because copying a mapping
  would defeat the point of mapping it. So the source itself must not change
  while anyone is reading it:

  - do not mutate a `byte[]` you have handed to `decode` or `nav`;
  - a custom `ByteSource` must support concurrent reads;
  - a read-only mmap stops writes *through that mapping*, not writes or
    truncation by another descriptor or process. Map immutable files, publish
    by atomic rename, and do not rewrite or truncate one while an arena is
    live. Truncating mapped storage can fault at the OS level rather than
    surfacing as a CBOR error.

- **`decode-seq` and `decode-seq-from` are single-consumer.** Each closes over
  one mutable reader, and the streaming form over an `InputStream` and a refill
  buffer as well. Do not realise different tails concurrently or pull from
  several workers. Clojure's `LazySeq` realisation reduces accidental overlap
  but is not an API-level guarantee about the enclosed reader, and it does not
  make an `InputStream` thread-safe. Detach each item first, then parallelise.

- **Handlers are not re-entrant with respect to the codec that invoked them.** A
  registered handler or `:encode-fallback` that captures and re-enters the same
  reusable Writer or Reader will clobber its position, depth, stringref
  namespace or scratch state. This takes an explicit closure to arrange, so it
  is trusted-code behaviour rather than an input-driven hazard -- but it is not
  checked, and the failure is silent corruption rather than an error.

- **Registry values are immutable, handler functions are yours.** The maps are
  final and copy-on-write, so building and publishing a registry is safe.
  `TagRegistry.writerFor` returns an immutable holder rather than the
  registry's own array, so a Java caller cannot rewrite a tag or function after
  publication. The handler functions themselves are user code and must be
  thread-safe if the registry is shared.

## Reporting

Findings in this file came from fuzzing, from reading other implementations
(hako's unbounded `readMap`, QCBOR/TinyCBOR's no-recursion design), and from two
targeted review passes. Both of the latter found defects the fuzzer had not.
