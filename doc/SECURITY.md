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
2. **Bounded memory**, at roughly **5× the input size** — see below; not 1×.
3. **Typed failure.** Every rejection is an `ex-info` with a `:type` keyword.
   Nothing escapes as a raw `NullPointerException`, `ClassCastException`,
   `StackOverflowError` or `OutOfMemoryError`, so a caller's
   `catch ExceptionInfo` is sufficient.

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
failures, plus the CBOR WG's not-well-formed corpus (46/47, one documented
exemption). That is evidence, not a proof. The fuzzer mutates *valid* encodings,
so it explores near-valid space well and far-from-valid space poorly. Every
round of fuzzing so far has found something; assume the next one would too.

### What "bounded memory" actually means

`checkCount` requires at least one wire byte per element, but a decoded element
costs more heap than one byte. Measured:

| input | heap |
|---|---|
| 2 MB array of small integers | 10 MB (**5×**) |
| 2 MB array of empty arrays | 10 MB (**5×**) |

The second row was **121 MB (64×)** until empty collections were changed to
return the shared singletons — every empty array had been allocating a fresh
vector. So the multiplier is a property of the decoded shapes, and 5× is what
the current worst known case gives, not a bound anyone has proved.

**Enforce an input size limit at the transport.** boring reads what you give it.

## Encode-side refusals

Not every error is a decode error. boring refuses to ENCODE a value it cannot
represent faithfully, rather than writing an approximation:

| type | when |
|---|---|
| `:boring/invalid-utf16` | a string containing an unpaired surrogate — it has no UTF-8 encoding, and both platforms used to substitute silently |
| `:boring/canonical-duplicate` | two set elements that encode identically under `:canonical` |
| `:boring/bad-simple-value` | a simple value outside 0–255 |
| `:boring/reserved-simple-value` | 24–31, which RFC 8949 §3.3 forbids emitting |
| `:boring/incompatible-options` | an option that contradicts the profile |
| `:boring/max-depth-exceeded` | nesting past `:max-depth`, on the write side too |
| `:boring/unsupported-type` | a type with no encoding and no registered handler |

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

## Reporting

Findings in this file came from fuzzing, from reading other implementations
(hako's unbounded `readMap`, QCBOR/TinyCBOR's no-recursion design), and from two
targeted review passes. Both of the latter found defects the fuzzer had not.
