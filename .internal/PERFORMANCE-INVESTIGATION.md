# Serialization performance investigation

Date: 2026-08-03

Initial baseline: `ab0b92c` (`seq-index`)

Integration baseline: `28000d0` (`seq-index`)

Investigation branch: `codex/perf-investigation`

## Outcome

The useful changes from this investigation are deliberately narrow:

- The JVM reader no longer constructs a `HashSet` and one `KeyProbe` wrapper per
  key for every small map. It first lets `PersistentArrayMap` perform its normal
  equality checks, then performs the content-aware array scan only when the map
  contains an array key. Large maps retain the linear-time, content-aware hash
  check introduced on `seq-index`.
- The JVM writer avoids tag-registry lookup work when the registry has no custom
  writers. Big-integer magnitudes now use the same direct-to-stream payload path
  as other large leaves, and a payload exactly as large as the staging buffer is
  written directly.
- The CLJS writer keeps its resolved default options on reusable writers instead
  of rebuilding and traversing an empty option map for every value. The CLJS
  reader avoids a redundant general equality call after identity has already
  failed for interned keywords and symbols.
- Focused JVM and advanced-compiled Node benchmark programs were added so these
  paths can be remeasured without relying on an interactive REPL transcript.

These changes preserve the existing wire format. No new public format tag or
representation is introduced.

## Measurements

Measurements are best-of repeated warmed batches on this development machine.
They are useful for comparing these revisions, not as portable throughput
claims. The JVM comparison used clean worktrees and the exact same
`bench/perf_worktree.clj` harness on each revision. `seq-index` advanced during
the investigation: `28000d0` independently replaced unconditional hashing with
a bounded small-map pair scan, so both the initial and final baselines are shown.

### JVM reader and writer

The primary workload is a vector of 200 small datom-shaped maps. Allocation is
measured for a safety-enabled decode.

| Revision | Encode | Decode, duplicate check on | Decode, check off | Bytes allocated per checked decode |
|---|---:|---:|---:|---:|
| `ab0b92c` | 41.1 us | 60.3 us | 44.2 us | 124,984 B |
| `28000d0` integration baseline | 40.7 us | 52.3 us | 44.6 us | 43,384 B |
| rebased performance branch | 42.1 us | 47.9 us | 46.3 us | 43,384 B |

Across the whole series, checked decoding is about 21% faster than `ab0b92c` and
allocates about 65% less. Most of the allocation win is already present in
`28000d0`; against that final integration baseline, building the small map once
and scanning only when an array key is present is another roughly 8% faster on
this keyword-keyed workload with identical measured allocation. Encode and
unchecked-decode differences are within the noise expected from separate JVM
processes; the changed checked fast path is bypassed when checking is disabled.

The streaming leaf probe also compares the current direct payload path with the
old "grow the writer buffer, then copy to the stream" behavior:

| Byte-array leaf | Direct stream | Staged then copied | Direct writer buffer | Staged writer buffer |
|---|---:|---:|---:|---:|
| 64 KiB | 4.7 us | 7.3 us | 4 KiB | 128 KiB |
| 1 MiB | 85.9 us | 181.7 us | 4 KiB | 2 MiB |

Most of this streaming improvement belongs to the existing `153e79d` work on
`seq-index`, rather than to this branch. The investigation validates that design
and extends it to big-integer magnitude payloads and the exact-buffer-size
boundary.

### CLJS options and reader paths

The default-options comparison is interleaved within one advanced-compiled Node
process. `default` uses the writer's pre-resolved empty options; `resolve` passes
an explicit `{}` and therefore exercises option resolution on every call.

| Workload | Pre-resolved default | Explicit empty options | Difference |
|---|---:|---:|---:|
| Small map | 3.13 us | 3.77 us | about 17% faster |
| Mixed scalar map | 3.33 us | 4.01 us | about 17% faster |
| 200 datom-shaped maps | 438 us | 438 us | negligible |

As expected, option setup matters for small values and is amortized by larger
values. Against the final `seq-index` baseline in separate advanced-compiled
Node processes, the keyword/symbol reader shortcut was about 1% on the large
datom workload. That is small enough to treat as directional rather than a
precise claim.

## Correctness and safety constraints

The optimized small-map reader path retains the format's stronger duplicate-key
rule: primitive and object arrays compare by contents, not Java identity. For a
small map, `PersistentArrayMap.createAsIfByAssoc` detects all ordinary Clojure
equality duplicates. If an array key is present, a bounded pairwise pass applies
the same `KeyProbe.eq` content rules used by the large-map hash path. The scan is
bounded by the persistent array-map threshold; large maps still use the O(n)
content-aware set.

The CLJS keyword/symbol shortcut relies on an existing reader invariant: wire
identifiers are interned by the reader cache, so two equal decoded keywords or
symbols have identical object identity during the duplicate scan. Other key
types continue through the general equality test.

Reusable CLJS writers store opaque resolved options rather than accepting raw
writer state from callers. Explicit per-call options still override those
defaults. Streamed JVM leaves still write the header before the body, so a sink
failure can leave a partial item exactly as it could before; the optimization
does not claim transactional stream output.

## Related implementation study

The local implementations were most useful for identifying design directions,
not for transplanting code:

- **Hako** uses hot exact-type dispatch, bulk primitive `MemorySegment` copies,
  reusable arenas, and reader scratch storage. Its bulk primitive approach is
  attractive when the sink itself supports native or `ByteBuffer` transfers,
  but `OutputStream` cannot consume primitive arrays directly.
- **Nippy** exposes a direct `DataOutput`/stream-oriented API. Relevant leaf
  paths still buffer in places, and its wire-format and security tradeoffs are
  different enough that its dispatch structure is not directly comparable.
- **Fress** uses typed-array `.set` for fixed-buffer CLJS output. Its growing
  bytes output falls back to per-byte writes into a JavaScript array, reinforcing
  the value of Boring's bounded/fixed-buffer fast path but not suggesting a safe
  drop-in change.
- **clj-cbor** was reviewed for canonical encoding choices. Its architecture did
  not expose a directly applicable hot-path improvement for this implementation.

## Experiments not retained

- Fusing byte-array map-key preflight with emission did not produce a measurable
  improvement. It also delayed canonical-order and duplicate failures until
  bytes had already been emitted, weakening the writer's current preflight
  behavior, so it was reverted.
- Removing the CLJS writer's `try`/`finally` depth restoration was not measurable
  and would have made a failed reusable writer harder to reason about, so it was
  reverted.
- The existing FFM/mmap experiments remain slower in the real recursive decoder
  and writer paths. A fast isolated native load is not sufficient evidence to
  complicate those paths.

## Remaining candidates

These are plausible follow-ups, but none had enough measured support to include
in this change:

- JVM typed primitive arrays still need staging/growth when writing to an
  `OutputStream`. Bounded streaming would require chunked endian conversion, or
  a separate channel/`ByteBuffer`-aware sink abstraction. Hako's bulk native
  copies make the latter worth testing if this workload is important.
- JVM text encoding still creates the complete UTF-8 byte array. An incremental
  `CharsetEncoder` could bound memory for very large text, at the cost of a more
  complex common path.
- Canonical indexed collections can still retain encoded keys/elements for
  sorting. Direct leaf streaming does not make all canonical output bounded.
- Large JVM maps still allocate a `HashSet` and a `KeyProbe` per key while
  checking duplicates. A reusable open-addressed scratch table could remove
  those wrappers, but it would duplicate subtle Clojure and array equality rules
  and is not hot for the common small-map workload.
- A registered CLJS writer handler is looked up twice on the rare custom-tag
  branch. Binding the handler once is worth testing only with a tag-heavy
  benchmark.
- CLJS `write-seq!` copies each emitted slice to preserve ownership. A borrowed,
  synchronous sink API could avoid that copy, but would need an explicitly
  unsafe lifetime contract.

## Verification

- JVM: `clojure -M:test` -- 255 tests, 7,049 assertions, 0 failures, 0 errors.
- CLJS: advanced compilation through the `:cljs-deps` alias followed by the
  generated Node test program -- 129 tests, 1,090 assertions, 0 failures,
  0 errors.
- JVM benchmark: `clojure -M:bench -m perf-worktree`.
- CLJS benchmark: advanced compilation of
  `bench/cljs/cljsbench/perf_worktree.cljs`, then execution under Node.
- `git diff --check` passes.
