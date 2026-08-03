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
for speed — it wins the small-payload encodes, ties `datom-maps-200`, wins
`str-maps-200` decode by 1.7× at **2.1× smaller on the wire**, and loses the
nested-map and integer-vector rows (see below).

**The tier this table uses matters, and it flatters boring on the small rows.**
Every codec here allocates fresh per call, which is matched — but `hako/encode`
builds a Writer and a confined Arena each time, and hako's intended path is the
reused `encode-into!`. Reuse both sides and the small-payload encode wins
*reverse*: `small-map` goes from boring 0.50 / hako 1.08 µs to boring 0.86 /
hako 0.59. `clojure -M:bench -m hako-ab` is the tier-matched table — time and
allocation, T1/T2/T3 — and it is the one to quote when comparing the two
libraries as each is meant to be called. Summarised: hako wins map-heavy encode
(1.1–1.8×) and map-heavy decode (~2×); boring wins vector-heavy decode (~1.3×)
and allocates less on every payload measured.

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

## Reading: `byte[]`, FFM, and navigation

hako builds on `java.lang.foreign` — a confined `Arena` owning a native
`MemorySegment`. boring's reader does not, and the reason is measured rather
than assumed, because the first attempt got it wrong.

### The microbenchmark lied

`bench/java/FfmProbe.java` times the access pattern a CBOR codec has: a header
byte then an unaligned scalar. It reports a native segment at parity with
`byte[]` — *provided* you never use `withOrder(BIG_ENDIAN)`, which costs 4.1×
on stock HotSpot by declining to intrinsify. Access in native order and
`Long.reverseBytes` (a bswap intrinsic, byte-identical output) and the penalty
vanishes. `byteArrayViewVarHandle`, by contrast, is endian-neutral.

So a segment-based reader was built. It cost **14–50% on decode** and ~2.5× the
stack per recursive level — enough that `maxDepth`'s 1024 default rose *above*
the real stack limit and the depth cap silently stopped being a cap.

`clj-async-profiler` said why: ~25% of samples in `checkValidStateRaw`,
`checkIndex`, `checkSegment`, `checkBounds` — per-access bounds and liveness
checks. A tight loop over a constant layout lets the JIT hoist all of it, which
is exactly what `FfmProbe` measures. A recursive, branchy decoder does not.
**A microbenchmark of an access pattern is not a benchmark of a decoder built
on it.**

`ByteBuffer` is the JDK-9-compatible alternative and is worse: it ties `byte[]`
on sequential scans but runs **2.29×** on the data-dependent walk a head parser
actually performs, against `MemorySegment`'s 1.22×.

### What shipped: one parser, two accessors

The structural logic is single-source — a second head parser is what drifts,
silently — but the loads branch on whether the source is a heap array. That
recovers the loss in full (`datom-maps-200` decode: 53.39 µs before, 72.06
all-segment, **53.06** with the branch; allocation identical).

Because the `byte[]` path then touches no FFM, the FFM types moved out of
`Reader` behind a JDK-9-named `ByteSource`. `src/java` compiles at
`--release 9`, `src/java22` holds the one `MemorySegment` implementation, and
one jar carries both since the JVM rejects a class only when it *loads* it. The
full suite passes on **JDK 21**, which cannot load FFM at all; 22+ adds mmap.

Off-heap decode costs **1.35×** heap decode, and only that path pays it
(shared/global arena 1.35×, confined 1.46×). It also means: to realise a whole
subtree from a mapping, stage its byte span into a scratch array and decode
through the array path (67.5 µs) rather than in place (75.4 µs).

### Navigation

`boring.nav` is a read-only cursor — `ILookup` (so `clojure.core/get-in`
works), `Indexed`, `Counted`, `Seqable`, `IReduceInit`, and a `clojure.zip`
zipper. `clojure -M:bench -m nav`:

| 68 KB, 200 records | nav | decode + `get-in` | ratio |
|---|---:|---:|---:|
| `get-in` one leaf (heap) | 5.9 µs | 124 µs | **21×** |
| `count` the top-level map | 0.08 µs | 121 µs | **1400×** |
| reduce over all 200, one field each | 57 µs | 125 µs | 2.2× |
| `get-in` one leaf (mmap'ed) | 6.2 µs | 131 µs | **21×** |
| locate a 1 MiB blob vs materialise it | 0.6 µs | 185 µs | **290×** |

`count` is O(1) — the element count is in the head. The reduce row is only 2.2×
because it visits every record. Skipping is 3–11× cheaper than decoding for
structure and **18×** for a bytestring, which is length-prefixed, so ignoring
one is a jump whose cost does not scale with its size.

A log is a CBOR sequence, walked by `nav/items`:

| 5 000 events, 360 KB | nav | decode-seq | ratio |
|---|---:|---:|---:|
| scan for matching events | 1 542 µs | 5 330 µs | **3.5×** |
| first event only (early exit) | 3.9 µs | 2.2 µs | **0.6×** |

**That second row is where nav loses, and it is the useful one.** `decode-seq`
is already lazy, so stopping at the first item decodes only that item — and for
one small item a cursor plus a key probe costs more than decoding it.
Navigation wins by what it *skips*.

Write such a file with the options on the **writer**, not per call:

```clojure
(let [w (boring/writer 65536 {:stringref false})]
  (with-open [out (BufferedOutputStream. (FileOutputStream. f) 262144)]
    (doseq [e events] (boring/write-to! w e out))))
```

`resolve-opts` merges the caller's map over the profile defaults on every
encode, which costs ~250 heap bytes per event — and it bites hardest here,
because a navigable file needs `:stringref false` and so cannot use the
nil-opts fast path. Resolved once on the writer, a log event costs **301 → 15**
bytes through `encode-buffered!` and **248 → 0** through `write-to!`.

Two constraints are enforced, not documented-and-hoped: `:stringref` documents
are refused (a cursor holding only an offset cannot resolve an index into a
table built from preceding strings), and indefinite-length containers cannot be
descended (their count is not on the wire, so `Counted` would lie — boring
never writes them). Tags are opaque: `get` realises through the ordinary reader
and delegates, because a tag's reader is an arbitrary function and structure
does not imply semantics.

### mmap: good for reading, not for writing

`clojure -M:bench -m mmap`. Selective decode over a mapping beats a `pread` per
item **2.3×**, and costs 3–17% over a no-copy floor.

Writing is the opposite. Appending 200 000 items: `BufferedOutputStream`
**130 ms**, mmap 171 ms, encode-only floor **105 ms**. A mapping faults per
4 KiB page while `write(2)` hands the kernel one prepared buffer. The floor
matters more than the ranking — **I/O is 19% of the job** — so a writer that
encoded straight into a mapping would compete for that 19% against an overhead
larger than the copy it removes. The actionable finding is that an unbuffered
`FileOutputStream` is **2.9× slower** than wrapping it.

### Compression: chunk at page size

Compression and mmap'ed selective access pull against each other: mmap pages at
4 KiB, a compressed block only decodes whole. zstd level 3, random lookups:

| chunk | compressed | ratio | ns/lookup | vs raw |
|---|---:|---:|---:|---:|
| uncompressed | 15.4 MB | 1.00× | 1 498 | 1.0× |
| **4 KB** | **1.59 MB** | **9.7×** | **5 400** | **3.6×** |
| 64 KB | 1.22 MB | 12.7× | 55 987 | 37× |
| 256 KB | 1.21 MB | 12.8× | 201 755 | 135× |

Lookup cost scales with chunk size; ratio saturates almost immediately. 4 KB
reaches 77% of whole-file ratio and aligns with the page granularity mmap gives
you anyway. This is the argument *against* filesystem compression here: btrfs
compresses 128 KiB extents and ZFS a 128 KiB recordsize, landing at the bottom
of that table with no knob. Compression also forecloses zero-copy — a chunk
must be decompressed to the heap — so it and the blob win are alternatives for
the same bytes.

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
