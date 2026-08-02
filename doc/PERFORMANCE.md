# Performance

Numbers, methodology, and the cases where boring loses.

## Methodology

Everything below was taken with:

- **A quiet machine.** Load average ≈ 4. An earlier round of numbers was taken
  on a loaded box and had to be withdrawn: run-to-run variance under load
  exceeded 60%, which is larger than most of the differences being measured.
- **Fresh objects per call, for every library.** boring through
  `boring/decode`, hako through `hako/decode`, nippy through
  `nippy/fast-thaw` — each allocating its own reader per call. An earlier
  version of our benchmark compared boring's *reused, warm-cache* reader
  against hako's fresh one, which flattered boring and was not a fair
  comparison.
- **criterium `quick-benchmark`, mean**, for the published tables. The
  interleaved A/B harness (`bench/ab.clj`) reports a minimum of 8 rounds
  instead, because it exists to compare two boring variants under load, where
  the minimum approximates the uncontended cost and the mean measures the
  machine. Do not mix figures from the two.
- **Allocation measured with `getThreadAllocatedBytes`**, which is
  deterministic and immune to load, wherever a claim can be made about
  allocation instead of time.

- **A warm process, not a warm cell.** Per-cell warmup is not enough: hako's
  small-map encode measured 2.52 / 1.30 / 1.15 / 1.08 µs across four
  consecutive runs of the *same* cell, so whichever cell runs first is
  penalised and the first block of any table is fiction. The suite warms every
  payload through every codec before it measures anything. Two byte-identical
  boring variants once timed 76% apart without this.

Reproduce with:

```
clojure -M:bench -m published        # exactly the tables on this page
clojure -M:bench -m published size   # the deterministic sections only; seconds
clojure -M:nippy-bench               # nippy's own benchmark, unmodified
bin/bench                            # the wider suite; several minutes
```

Every table below is emitted verbatim by one of the first three commands. They
used to be hand-maintained, and had drifted: the wire-size table quoted 6 951
bytes for a `datom-maps-200` that now measures 9 952, because the *payload*
definition it was taken from was never committed. The payloads live in
`bench/published.clj` now.

Output lands in `target/bench/<timestamp>.txt` with the machine description at
the top, because a benchmark number without its hardware is a rumour. Sizes run
first on purpose — they are deterministic, so they are the one section worth
keeping from a run that turns out to be too noisy to trust. `bench/README.md`
covers the individual harnesses.

## JVM, µs/op

Lower is better. **Bold** is the winner.

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

Wire size, bytes:

| payload | boring | boring `:shapes` | hako | nippy |
|---|---:|---:|---:|---:|
| small-map | 56 | 56 | 43 | **41** |
| mixed | 63 | 63 | 55 | **53** |
| nested-map-50 | 1 561 | 1 561 | **1 079** | 1 381 |
| datom-maps-200 | 9 952 | **4 982** | 5 165 | 10 665 |
| long-vec-1k | 2 726 | 2 726 | 2 740 | 2 874 |
| str-maps-200 | 7 550 | **4 570** | 9 741 | 11 465 |

boring beats nippy on all twelve timing cells, and on size for the two payloads
where the shape machinery applies. Against hako — an experimental codec built
for speed — it wins the small-payload encodes outright, ties `datom-maps-200`,
wins `str-maps-200` decode by 1.7× at **2.1× smaller on the wire**, and loses
the nested-map and integer-vector rows (see below).

The four small cells are close enough that the ordering is not stable across
machines; treat 0.13 vs 0.18 µs as "the same". The gaps worth reading are the
ones with a stated cause.

### Compressed

The row that actually matters for a storage or wire codec, since konserve, kabel
and every HTTP transport compress. zstd level 3, and nippy's own built-in LZ4
for the `nippy/freeze` column:

| payload | boring+zstd | boring `:shapes`+zstd | hako+zstd | fressian+zstd | nippy (own LZ4) |
|---|---:|---:|---:|---:|---:|
| small-map | 65 | 65 | 52 | 60 | **45** |
| mixed | 72 | 72 | 64 | 69 | **57** |
| nested-map-50 | 354 | 354 | 388 | **345** | 1 385 |
| datom-maps-200 | 1 121 | 1 237 | 1 168 | **1 014** | 2 488 |
| long-vec-1k | 1 851 | 1 851 | 1 861 | **1 503** | 2 878 |
| str-maps-200 | 1 062 | 1 168 | 1 118 | **982** | 2 493 |

Two things worth noting:

- **Compression erases most of the uncompressed size differences.** boring's
  9 952-byte `datom-maps-200` and nippy/fast's 10 665 both land near 1.1 KB.
  Only nippy's own LZ4 is meaningfully worse, and that is LZ4 versus zstd, not
  a format property. Choosing a codec on uncompressed size is choosing on a
  number your storage layer deletes.
- **`:shapes` is a small *loss* under compression** — 1 237 against 1 121 on
  `datom-maps-200`. It removes exactly the repetition zstd is best at, and
  replaces it with a header zstd cannot exploit. `:shapes` is a win when you do
  not compress (2× smaller, and 1.8× faster to decode) and roughly a 10% cost
  when you do. The same reasoning applies to stringref, which is why it is on
  by default but not load-bearing.

fressian+zstd is 5–20% ahead of boring+zstd on four of six payloads. That is a
real, if small, loss, and it is the entire remaining size argument for fressian.

### On nippy's benchmark

`clojure -M:nippy-bench` reruns nippy's own benchmark — nippy's `stress-data`,
nippy's reader+fressian filter, nippy's timing loop — across all six codecs
nippy reports, plus boring and the compressed tiers:

| codec | freeze µs | thaw µs | round µs | bytes |
|---|---:|---:|---:|---:|
| **boring** | **285** | **310** | **595** | 15 326 |
| boring `:shapes` | 288 | 315 | 603 | 15 326 |
| nippy/fast | 399 | 533 | 932 | 17 105 |
| nippy (LZ4) | 527 | 575 | 1 102 | 8 518 |
| **boring + zstd** | 637 | 465 | 1 102 | **4 900** |
| boring `:shapes` + zstd | 641 | 460 | 1 101 | 4 900 |
| nippy/encrypted | 546 | 618 | 1 164 | 8 546 |
| fressian | 1 647 | 1 213 | 2 860 | 12 222 |
| fressian + zstd | 2 007 | 1 117 | 3 124 | 4 600 |
| `pr-str` + `read-string` | 2 220 | 3 149 | 5 369 | 15 880 |
| nippy/lzma2 | 6 336 | 2 743 | 9 079 | 3 888 |

Raw against raw, boring is 1.6× nippy/fast and 4.8× fressian on round-trip.

The size column needs the compressed rows to be read fairly: `nippy/freeze`
compresses above a size threshold, so its 8 518 is a codec *plus* a compressor
against boring's raw 15 326. Put both behind a compressor and **boring+zstd
lands at 4 900 bytes in the same 1 102 µs nippy takes for 8 518** — 1.7×
smaller at equal time. nippy/lzma2 is smaller still at 3 888, for 8× the
round-trip. fressian+zstd is 6% smaller than boring+zstd and 2.8× slower.

## Why the hot path is `byte[]`, and not FFM `MemorySegment`

hako builds on `java.lang.foreign`: a confined `Arena` owning a native
`MemorySegment`, written through unaligned `ValueLayout` accessors. It is a
coherent design and its allocation goals are real ones. The question this
section answers is whether boring should follow, and the answer is no — not
because off-heap is a bad idea, but because **CBOR is big-endian**.

`bench/java/FfmProbe.java` times the access pattern a CBOR codec actually has:
a header byte followed by an unaligned scalar, in a loop. ns per byte+scalar
pair, min over 200 interleaved rounds:

| backing | order | OpenJDK 25 (C2) | GraalVM 25 (Graal JIT) |
|---|---|---:|---:|
| `byte[]` + `byteArrayViewVarHandle` | BE | **1.37** | **1.38** |
| `byte[]` + `byteArrayViewVarHandle` | LE | 1.37 | 1.37 |
| heap `MemorySegment` | BE | 1.39 | 1.38 |
| native `MemorySegment` (confined arena) | BE | 5.63 | 2.23 |
| native `MemorySegment` (confined arena) | LE | 1.37 | 2.21 |

Two things fall out, and only the second one is about FFM at all:

1. **`byteArrayViewVarHandle` is endian-neutral.** BE and LE cost the same,
   1.37 ns, on both JVMs. A `byte[]` hot path pays nothing for CBOR's byte
   order.
2. **A native segment is not.** On stock HotSpot, big-endian access to an
   off-heap segment costs 5.63 ns against 1.37 for the same access in native
   order — a **4.1× penalty that exists only because the layout is not the
   platform's**. The arena flavour is irrelevant: `ofConfined`, `ofShared` and
   `global` all measure within noise of each other, so this is not a liveness
   or thread-confinement check being missed.

### The endian penalty is avoidable

That 4.1× is not a property of off-heap memory. It is the JIT declining to
intrinsify a *non-native* `ValueLayout`, and it disappears entirely if you
access in native order and swap the bytes yourself. `Long.reverseBytes` is a
`bswap` intrinsic, so this costs nothing:

```java
// slow  — 5.71 ns on stock HotSpot
seg.set(JAVA_LONG_UNALIGNED.withOrder(BIG_ENDIAN), off, v);
// fast  — 1.38 ns, byte-identical output
seg.set(JAVA_LONG_UNALIGNED /* native LE */, off, Long.reverseBytes(v));
```

| native segment, big-endian result | OpenJDK 25 (C2) | GraalVM 25 |
|---|---:|---:|
| via `withOrder(BIG_ENDIAN)` — write / read | 5.71 / 5.49 | 2.23 / 1.11 |
| via native order + `reverseBytes` — write / read | **1.38 / 1.31** | 2.23 / 1.11 |
| `byte[]` + `VarHandle` BE, for reference | 1.37 / 1.30 | 1.38 / 1.20 |

The probe asserts the two paths produce identical bytes, so this is a real
option and not a measurement artifact. With it, an off-heap path is **at parity
with `byte[]` on stock HotSpot**. On Graal the picture is mixed and independent
of endianness: native-segment writes cost 1.6×, native-segment reads are ~7%
faster than `byte[]`.

So cost is not the argument against FFM here — parity is available for the
asking. The argument is payoff, and the payoff measures small:

- **Allocation.** `encode-buffered!` measures **0 bytes/op** on `small-map`,
  `datom-maps-200`, `long-vec-1k` and `long-array-1k` (`clojure -M:bench -m
  alloc`). The zero-allocation encode path that motivates the arena is
  reachable on-heap with a reused writer; it needs no native memory.
- **An off-heap source for decode.** Feeding hako's reader a pre-wrapped
  `MemorySegment` instead of a `byte[]` is worth 0–5% (`clojure -M:bench -m
  hako-ab`), because decode is dominated by building Clojure data, not by
  reading bytes.
- **Selective decode from an mmap'ed file.** `clojure -M:bench -m mmap` probes
  20 000 random items out of a 16 MB / 200 000-item CBOR sequence, three ways:

  | strategy | ns/item | over floor |
  |---|---:|---:|
  | pre-sliced `byte[]` → decode (floor, no copy) | 805–819 | — |
  | heap `byte[]` → scratch → decode | 861–892 | 0–5% |
  | **mmap segment → scratch → decode** | **841–941** | **3–17%** |
  | `pread` syscall → scratch → decode | ~2 000 | 2.3× |

  So boring can already do random selective decode over a mapping, at roughly
  5–15% over the floor and **2.3× better than a syscall per item**. That
  spread, not the microbenchmark, is the honest ceiling on what a
  segment-native reader could win back on structure-heavy data. Note the win
  is mmap-versus-`pread`, and it needs no FFM in the decoder at all.

### Where zero-copy actually pays: blobs, not structure

The rows above all decode small items, where the payload is structure and
decoding means allocating maps, keywords and strings. A CBOR file of *blobs*
inverts that: for a bytestring, decoding **is** the copy. Same harness, 64 × 1
MiB bytestrings:

| | µs/blob |
|---|---:|
| full decode — materialises each blob as a heap `byte[]` | 300–420 |
| locate + slice, materialising nothing (zero-copy ceiling) | 0.23–0.40 |
| ratio | **~1 000×** |

That is three orders of magnitude, and it is the one number in this document
that would justify new API. It also does **not** require an off-heap reader:
what it requires is the ability to *not materialise a payload* — to hand back a
handle the caller may never dereference. A `byte[]`-backed reader can do that
for a heap buffer just as a segment-backed one can for a mapping.

### Skipping, and whether one reader can serve both backings

Lazy navigation — cursor, zipper, `get-in` — has `skip` in its inner loop:
walk a value's structure without building anything. CBOR arrays and maps carry
an *element* count, not a byte length, so seeking is a scan, not a jump.
`clojure -M:bench -m skip`:

| shape | bytes | skip ns | decode ns | skip is | skip ns/byte |
|---|---:|---:|---:|---:|---:|
| small-map-8 | 180 | 155 | 555 | 3.6× cheaper | 0.86 |
| wide-map-200 | 1 268 | 1 715 | 19 136 | 11.2× | 1.35 |
| long-vec-1k | 2 723 | 5 341 | 22 716 | 4.3× | 1.96 |
| bytes-64k | 65 541 | 483 | 9 135 | 18.9× | 0.007 |
| datom-maps-200 | 8 936 | 16 549 | 58 832 | 3.6× | 1.85 |

Skipping structure is only 3–11× cheaper than decoding it, because you still
visit every element — roughly 1–2 ns/byte. Bytestrings are the exception at
0.007 ns/byte: they are length-prefixed, so skipping one *is* a jump. That
asymmetry is the whole case for lazy navigation, and it says the win comes from
the subtrees you never enter, not from a faster walk.

The same benchmark runs one scanner over three backings. Its Clojure version
reports the heap segment 1.58× and the native segment 3.17× slower than
`byte[]` — but that is a **Clojure interop artifact**, not an FFM property.
`FfmProbe`, in Java, byte-at-a-time:

| | OpenJDK 25 | GraalVM 25 |
|---|---:|---:|
| sequential scan, `byte[]` vs heap segment | 0.629 / 0.630 | 0.198 / 0.406 |
| sequential scan, `byte[]` vs native segment | 0.629 / 0.636 | 0.198 / 0.203 |
| data-dependent walk, `byte[]` vs native segment | 10.16 / 12.41 | 13.59 / 15.23 |

At parity to within ~20%. That predicted one segment-based reader could serve
heap buffers and mmap'ed files alike, with `MemorySegment.ofArray` covering the
`byte[]` case.

**That prediction was wrong, and the reader was built and measured before it
was believed.** Routing every load through a `MemorySegment` cost:

| payload | byte[] | all-segment | delta |
|---|---:|---:|---:|
| small-map | 0.84 | 1.11 | +32% |
| mixed | 0.36 | 0.54 | +50% |
| nested-map-50 | 14.25 | 18.89 | +33% |
| datom-maps-200 | 53.39 | 72.06 | +35% |

…plus ~2.5× the stack per recursive level, which pushed the real depth limit
from ~1500–2000 to ~600–700 and left `maxDepth`'s 1024 default *above* it — the
depth cap silently stopped being a cap.

`clj-async-profiler` says exactly where it goes. The segment path carries five
frames the array path does not, all of them safety checks:

| frame | % of samples |
|---|---:|
| `MemorySessionImpl.checkValidStateRaw` (arena liveness) | 9.9 |
| `Preconditions.checkIndex` | 5.3 |
| `SegmentVarHandle.checkSegment` | 4.6 |
| `NativeMemorySegmentImpl.unsafeGetOffset` | 3.0 |
| `AbstractMemorySegmentImpl.checkBounds` | 2.6 |
| **total** | **~25%** |

Per-access bounds and liveness checking. A tight loop over a constant layout
lets the JIT hoist all of it — which is what `FfmProbe` measures, and why it
predicted parity. A recursive, branchy decoder does not get that. The lesson
generalises: **a microbenchmark of an access pattern is not a benchmark of a
decoder built on it.**

The arena flavour is a second-order effect: shared and global measure 1.35×,
confined 1.46×, so prefer a shared or global arena for a mapping.

### What shipped instead: one parser, two accessors

The structural logic is single-source — a second head parser is what drifts —
but the loads branch on whether the source is a heap array. That recovers the
loss in full:

| payload | pre-migration | all-segment | **dual accessor** |
|---|---:|---:|---:|
| small-map | 0.84 | 1.11 | **0.90** |
| mixed | 0.36 | 0.54 | **0.38** |
| datom-maps-200 | 53.39 | 72.06 | **53.06** |
| long-vec-1k | 28.73 | 30.54 | **28.77** |

Because the `byte[]` path then touches no FFM at all, the FFM types could be
moved out of `Reader` entirely, behind a `ByteSource` interface named in JDK-9
terms. `src/java` compiles at `--release 9`; `src/java22` holds the one
`MemorySegment` implementation. One jar carries both, since the JVM rejects a
class only when it *loads* it — so **JDK 21 LTS still runs the codec** (the
full suite passes on it) and 22+ additionally gets mmap. Moving FFM out of the
recursive methods also restored the stack limit to ~1400–1500, so `maxDepth`
went back to 1024.

Off-heap decode costs **1.35×** heap decode. That is the price of mmap, and
only the mmap path pays it. It also means: to realise a whole subtree from a
mapping, stage its byte span into a scratch array and decode through the array
path (67.5 µs) rather than in place (75.4 µs). Navigate over the segment,
realise through the array.

`ByteBuffer` is the obvious way to have one mmap-capable implementation
*without* the JDK 22 baseline, and it looks good until you measure the right
access pattern. Stock OpenJDK 25, one run, ns for the data-dependent walk:

| backing | walk ns | vs `byte[]` | mmap? | JDK |
|---|---:|---:|:-:|:-:|
| `byte[]` | 10.2 | — | no | 9+ |
| heap `MemorySegment` | 9.2 | **0.89×** | n/a | 22+ |
| native `MemorySegment` | 12.4 | 1.22× | yes | 22+ |
| direct `ByteBuffer` | 22.6 | **2.29×** | yes (2 GB cap) | 9+ |

`ByteBuffer` ties `byte[]` on sequential scans and on wide big-endian loads —
big-endian is its *default* order, so it has no equivalent of the
`withOrder(BIG_ENDIAN)` cliff — but it is **2.29× slower on the walk**, which
is the shape a CBOR head parser actually has. `MemorySegment` is 1.22×. So the
JDK-9-compatible route to a single implementation costs roughly twice what the
JDK-22 one does, on the operation that dominates.

### mmap does not help encoding

The read result does not mirror. `clojure -M:bench -m mmap-write`, 200 000
items to a 16 MB file, median of 5 fresh files:

| strategy | ms | MB/s |
|---|---:|---:|
| A `write-to!` → `FileOutputStream` (today's obvious call) | 375.2 | 43 |
| B `write-to!` → `BufferedOutputStream` | **130.1** | 123 |
| C mmap + bulk copy per item | 171.4 | 93 |
| D mmap, pages pre-faulted | 161.8 | 99 |
| E `encode-buffered!` only, no I/O — the floor | 105.5 | 152 |

mmap **loses** to a buffered stream for sequential append, because a mapping
takes a minor fault per 4 KiB page and the file's pages must be allocated,
while `write(2)` hands the kernel one prepared buffer. Pre-faulting moves that
cost rather than removing it.

The floor matters more than the ranking: encoding is 105 ms of B's 130, so
**I/O is 19% of the job**. A writer that encoded straight into a mapping — the
primitive neither boring nor hako has, since hako's `Writer` always allocates
its own confined arena and cannot target a caller's segment — competes for that
19%, against an mmap overhead (C − E = 66 ms) that is larger than the copy it
would remove. Not worth building for throughput. It would be worth building for
*random in-place update* of an existing file, which is a different feature.

The actionable finding is row A: unbuffered `FileOutputStream` is **2.9×
slower** than wrapping it in a `BufferedOutputStream`. `write-to!` writes
straight to the stream it is handed, so that is the caller's to get right, and
it should say so.

### Compression: chunk at page size, not at extent size

Compression and mmap'ed selective access pull against each other. mmap pages at
4 KiB; a compressor needs a larger block to find matches, and a compressed block
only decodes as a whole. `clojure -M:bench -m mmap-compress`, zstd level 3,
random single-item lookups:

| chunk | compressed | ratio | ns/lookup | vs uncompressed |
|---|---:|---:|---:|---:|
| uncompressed | 15.41 MB | 1.00× | 1 498 | 1.0× |
| **4 KB** | **1.59 MB** | **9.71×** | **5 400** | **3.6×** |
| 16 KB | 1.29 MB | 11.95× | 16 959 | 11.3× |
| 64 KB | 1.22 MB | 12.67× | 55 987 | 37.4× |
| 256 KB | 1.21 MB | 12.79× | 201 755 | 134.7× |
| whole file | 1.22 MB | 12.59× | — | no random access |

Lookup cost scales with chunk size; **ratio saturates almost immediately**.
Going 4 KB → 256 KB buys 32% more compression for 37× the lookup. 4 KB chunks
already reach 77% of whole-file ratio, and they align with the granularity mmap
gives you anyway.

This is the argument against filesystem compression for this workload, not for
it: btrfs compresses 128 KiB extents and ZFS a 128 KiB default recordsize, so
they land near the bottom of that table and offer no knob. Transparent, yes —
but transparently in the wrong regime. Chunking in the application keeps the
knob, and boring's CBOR-sequence output already gives item-aligned boundaries
for free.

A trained dictionary did **not** help here (4 KB: 6.98× with, 9.71× without).
The likely cause is the benchmark rather than dictionaries in general: the
payload is sequential (`person-0` … `person-199999`) and the dictionary was
trained on the first 8 000 items, so it carries literals that never recur later.
Worth re-testing on a payload whose vocabulary is stationary before drawing any
conclusion about dictionaries.

Note that compression forecloses zero-copy: a compressed chunk must be
decompressed to the heap, so the ~1 000× blob win and compression are
alternatives, not companions, for the same bytes.

### Navigation: what the shipped API delivers

`boring.nav` is a read-only cursor over encoded CBOR — `ILookup` (so
`clojure.core/get-in` works), `Indexed`, `Counted`, `Seqable`, `IReduceInit`,
and a `clojure.zip` zipper. `clojure -M:bench -m nav`, 68 KB of 200 customer
records:

| | nav | decode + `get-in` | ratio |
|---|---:|---:|---:|
| `get-in` one leaf (heap) | 5.91 µs | 124.19 µs | **21×** |
| `count` the top-level map | 0.08 µs | 121.54 µs | **1432×** |
| reduce over all 200, one field each | 56.75 µs | 127.67 µs | 2.2× |
| `get-in` one leaf (mmap'ed file) | 6.23 µs | 137.17 µs | **22×** |

One 1 MiB bytestring beside a small map:

| | nav | decode | ratio |
|---|---:|---:|---:|
| locate the blob vs materialise it | 0.64 µs | 184.62 µs | **287×** |
| read a field *past* the blob | 0.28 µs | 171.87 µs | **618×** |

`count` is 1432× because the element count is in the head — O(1), no walk. The
reduce row is only 2.2× because it visits every record; the win there is the
five fields per record it never builds. The blob rows are the shape that
matters most: a bytestring is length-prefixed, so skipping one is a jump, and
the cost of ignoring it does not scale with its size.

Two constraints are enforced rather than documented-and-hoped. Navigation
refuses a `:stringref` document (a cursor holding only an offset cannot resolve
an index into a table built from preceding strings), and refuses to descend
into an indefinite-length container (its count is not on the wire, so `Counted`
would be lying). boring never writes indefinite lengths; only foreign streaming
encoders do.

Tags are opaque: `get` on a tagged value realises it through the ordinary
reader and continues with `clojure.core/get`. A tag's reader is an arbitrary
function, so structure does not imply semantics — the slow path is the
reference implementation, which is what makes the fast path safe.

### What the measurements argue for

- **Encode** stays on the reused `byte[]` writer. It already allocates 0
  bytes/op, and Graal penalises native-segment writes 1.6×.
- **Structure decode** stays on `byte[]` by default — not for speed, but for
  the JDK-9 baseline. A segment-backed reader is a viable *addition*, not a
  replacement.
- **Bytestring payloads and unentered subtrees** are where the wins are: ~1 000×
  for a blob you never materialise, 19× for one you skip. Both need the ability
  to *not build a value*, which is orthogonal to FFM.

One constraint on all of it: `:stringref` is on by default, and a string
reference is an index into a table built from every preceding string in the
item. So a skipped subtree still has to register the strings inside it. Random
access wants `:stringref false`, or offset-and-length bookkeeping in place of
materialised strings.

Nothing in the current `:local/root` consumers (konserve, datahike) asks for
any of this yet.

Caveats: measured on x86-64, where native order is little-endian; on a
big-endian host the penalty lands on the native-order codec instead. The probe
targets JDK 22+ for the final FFM API, so the JDK 21 line is untested. And
`getThreadAllocatedBytes` counts heap only — hako's arena bytes are invisible
to it, so the allocation rows above compare GC pressure, not total memory
traffic.

## Where boring loses

### Deeply nested maps — 1.4× bigger, 1.6× slower

`nested-map-50` is 1 561 bytes against hako's 1 079, and decodes in 7.08 µs
against 4.34.

The cause is understood: 50 maps share a key set, but they are *nested* rather
than collected into an array, so shaped arrays do not fire — 1 561 bytes either
way. Stringref deduplicates the key *strings*, but every occurrence still pays
a tag-39 identifier wrapper, a stringref reference and a map header.

Under compression the size half of this loss inverts: 354 bytes against hako's
388.

This is the case [SHAPES.md](SHAPES.md) specifies tag 39650 for. Measured on a
comparable payload, define-then-reference takes a map-of-maps from 7 126 to
3 946 bytes (−44.6%). It is specified and deliberately not shipped in the first
release.

### A plain vector of integers — 2.4× slower

`long-vec-1k` decodes in 11.05 µs against hako's 4.65. This is the largest
remaining gap in the table, and it is a decode-side gap only — encode is a tie
at 6.74 against 6.65.

Handing boring a **primitive array** instead of a vector changes the picture
entirely, because it becomes an [RFC 8746][rfc8746] typed array — a raw
little-endian memory image:

| representation | bytes | decode |
|---|---:|---:|
| vector of 1000 ints | 2 726 | 11.10 µs |
| `long[]` (tag 79) | 8 008 | 1.25 µs |
| `int[]` (tag 78) | 4 008 | 0.69 µs |
| **`short[]` (tag 77)** | **2 008** | **0.34 µs** |
| hako vector | 2 740 | 4.61 µs |

`short[]` decodes **33× faster than the vector and is smaller on the wire**.
On the JVM that is a bulk `VarHandle` read; in JavaScript it is a `TypedArray`
view over the buffer, which is genuinely zero-copy.

Unusually, the fastest path is also the most portable one: RFC 8746 is a
registered standard that other CBOR libraries read natively.

Automatically narrowing a homogeneous integer *vector* to a typed array would
close this gap — measured at **5 144 bytes and 1.65 µs against 7 133 and 37.11
µs** for a 512-row four-column payload, i.e. smaller *and* 22× faster. It needs
narrowest-fit selection per column to avoid `long[]`'s 2.3× size penalty, and
is specified in [SHAPES.md](SHAPES.md) as the columnar extension.

## ClojureScript

node v23.11, ns/op, reused writer and reader on both sides (transit's `tw`/`tr`
are created once, so boring's are too). Regenerate with:

```
clojure -M:cljs-compare -m cljs.main -co '{:language-in :ecmascript-next}' \
  -O advanced -t node -o target/cljs-compare.js -c cljsbench.compare
node target/cljs-compare.js
```

An earlier version of this page claimed boring "beats transit-cljs on every
axis — 1.6x encode, 2.6x decode, 2.9x smaller". That is wrong as a general
claim. It holds for one payload shape, with `:shapes` enabled, and the reverse
holds elsewhere.

### Decode, ns/op

| payload | boring | boring `:shapes` | transit | JSON.parse |
|---|---:|---:|---:|---:|
| small-map | 2 253 | 2 244 | **1 055** | 224 |
| mixed | 1 470 | 1 475 | **627** | 172 |
| string-100 | 349 | 350 | **135** | 46 |
| nested-map-50 | 43 207 | 43 192 | **24 997** | 6 991 |
| datom-maps-200 | 199 111 | **52 820** | 91 042 | 33 727 |
| datom-vec-1k | 242 767 | 240 819 | **152 313** | 97 903 |
| long-vec-1k | **10 030** | 10 094 | 12 733 | 5 609 |

### Encode, ns/op

| payload | boring | boring `:shapes` | transit | JSON.stringify |
|---|---:|---:|---:|---:|
| small-map | 1 702 | 1 825 | **895** | 123 |
| nested-map-50 | 41 870 | 41 652 | **25 263** | 3 557 |
| datom-maps-200 | 194 045 | **124 680** | 128 435 | 20 144 |
| datom-vec-1k | 705 819 | 729 565 | **449 123** | 71 121 |
| long-vec-1k | 40 961 | 41 061 | **39 041** | 8 427 |

### Size, bytes

boring is smaller on every payload:

| payload | boring | boring `:shapes` | transit | JSON |
|---|---:|---:|---:|---:|
| small-map | **56** | 56 | 75 | 48 |
| nested-map-50 | **1 561** | 1 561 | 2 176 | 1 621 |
| datom-maps-200 | 9 952 | **4 982** | 14 307 | 13 091 |
| datom-vec-1k | **25 748** | 25 748 | 39 000 | 40 991 |
| long-vec-1k | **2 726** | 2 726 | 3 891 | 3 891 |

### Why transit wins on JS, and where it does not

transit-cljs `:json` writes JSON text, so **`JSON.parse` does the entire
byte-to-structure walk in native C++** and transit only pays for the JS-level
walk that builds Clojure values. boring parses binary in JavaScript. Profiling
`datom-maps-200` decode (`node --cpu-prof`) puts boring's time at:

| | share |
|---|---:|
| byte scan / dispatch | 31.8% |
| string decode (`TextDecoder` + UTF-8 validation) | 25.4% |
| collection building (transients) | 16.8% |
| keyword interning | 13.5% |
| other, GC | 12.5% |

The first two — **57% of decode** — are what V8 does natively for transit. That
is a structural disadvantage of binary-in-JS, not a defect, and it is why the
fastest JS CBOR codecs generate their decoders with `new Function`/`eval` (which
a strict Content Security Policy forbids, and which boring does not do).

boring wins where the format advantage beats native text parsing:

- **`:shapes` on an array of same-shaped maps.** Stripping the repeated keys
  removes most of the scan and all of the repeated keyword interning:
  `datom-maps-200` decodes in **53 µs against transit's 91 µs — 1.7× faster, at
  2.9× smaller** — and encode is now ahead too, 125 µs against 128. This is the datom shape boring exists for.
  It does nothing for `nested-map-50` (maps nested, not collected) or
  `datom-vec-1k` (vectors, not maps), which is why those rows are flat.
- **Dense numeric data.** `long-vec-1k` decodes in 9.8 µs against 12.6 —
  CBOR integers are 1–3 binary bytes where JSON must parse decimal text. A
  typed array widens this to more than an order of magnitude.

Everywhere else — small maps, plain strings, nested maps — **transit is 1.5–2.7×
faster and boring is 1.3–1.4× smaller**. If you are CPU-bound in a browser on
generic data, transit is the faster choice today; boring's case on JS is wire
size, cross-language reach, and the shaped-array path.

### The JSON column is not the bar it looks like

`JSON.parse` returns plain JS objects with string keys. boring and transit
return ClojureScript persistent maps with keyword keys. Those are different
jobs, and the difference is most of the apparent gap. On `datom-maps-200`:

| | ns |
|---|---:|
| `JSON.parse` → plain JS objects | 34 348 |
| `JSON.parse` + a hand-written CLJS build that knows the 5 keys | 43 420 |
| **boring `:shapes`** | **45 127** |
| boring, generic | 170 279 |
| `JSON.parse` + `js->clj :keywordize-keys` | 197 026 |
| CLJS construction alone, nothing parsed | 4 297 |

**With `:shapes`, boring is within 4% of `JSON.parse` doing the same job** —
45.1 µs against 43.4, where the JSON side has been hand-specialised to the same
five keys boring's shape header carries. That is about as close to the native
parser as anything returning Clojure values gets.

**Against what a CLJS app actually writes, boring wins outright.** Nobody
hand-unrolls their keys; they call `js->clj`, and that costs 197 µs — **4.4×
slower than boring `:shapes`**, and slower than boring's generic path too. For
reading into ClojureScript data, boring is the faster option today.

The last row decides what is left to optimise: building the result — 200
`PersistentArrayMap`s and a vector — costs **4.3 µs, under 10% of the shaped
decode**. What remains is parsing, not construction.

### A WASM decoder: measured, ~6–10%

An optional WASM module with a JS fallback is the obvious way to buy
native-speed scanning. `bench/wasm/` is the experiment: a CBOR skeleton walker
in C that counts items and constructs nothing — the *most* a WASM module could
take off the JS decoder, since strings and Clojure values have to be built on
the JS side regardless.

| | JS | WASM | |
|---|---:|---:|---:|
| skeleton scan, generic (9 952 B) | 21 317 ns | 10 471 ns | 2.04× |
| skeleton scan, `:shapes` (4 982 B) | 9 806 ns | 5 242 ns | 1.87× |

WASM is genuinely ~2× at the scan, and the buffer copy is not the obstacle —
10 KB costs ~120 ns. The problem is the share:

| | scan share of decode | saving if ALL of it moved |
|---|---:|---:|
| generic (170 279 ns) | 12.5% | **6.4%** |
| `:shapes` (45 127 ns) | 21.7% | **10.1%** |

That is a ceiling, not an estimate: it assumes JS consumes whatever index WASM
writes for free, and a CBOR header is already one byte with the major type in
its top three bits, so reading an index entry is not obviously cheaper than
reading the header it replaces.

Two things pin the ceiling there. **Strings cannot move** — a JS string cannot
be a view into WASM memory, so every one is copied and transcoded UTF-8 →
UTF-16; this is the wall a Rust JSON parser in WASM hits at ~8× *slower* than
native `JSON.parse`, and boring's string time is already inside `TextDecoder`
either way. **Clojure values cannot move** — `Keyword`, `PersistentArrayMap`
and `PersistentVector` are JS objects WASM cannot allocate.

Against that, `:shapes` takes the same payload from 170 µs to 45 — **3.8×** —
with no second implementation to keep conformant. Widening where shapes fire
(tag 39650, [SHAPES.md](SHAPES.md)) is worth more than a WASM scanner by a wide
margin, and `nested-map-50` — where boring is furthest behind transit — is
exactly that case.

[rfc8746]: https://www.rfc-editor.org/rfc/rfc8746
