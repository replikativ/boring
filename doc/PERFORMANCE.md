# Performance

Boring's performance depends on the data, the API, and how much of the result
the application needs. These measurements cover full encoding and decoding,
compression, and selective reads.

The timing tables below are recorded results, not a new benchmark run.
Their original environment descriptions are retained where available.
Do not combine figures from different tables as if they came from one run.

## Methodology

The committed harnesses define the payloads and calls being compared:

| Harness | Measurement |
| --- | --- |
| [published.clj](../bench/published.clj) | Public encode/decode APIs, wire sizes, compression, typed arrays |
| [hako_ab.clj](../bench/hako_ab.clj) | Matched reuse and copy tiers for boring and hako |
| [nippy_bench.clj](../test-nippy/boring/nippy_bench.clj) | Nippy's stress-data comparison |
| [capability.clj](../bench/capability.clj) | Selected fields and columns versus full decoding |
| [nav.clj](../bench/nav.clj) | Cursor operations, sequence lookup, and index construction |
| [ClojureScript comparison](../bench/cljs/cljsbench/compare.cljs) | Reused boring and Transit readers/writers in Node |

The `published` harness calls `boring/encode`, `boring/decode`,
`hako/encode`, `hako/decode`, and Nippy's fast APIs. Internal object reuse
is therefore library-dependent. Earlier descriptions called this a
fresh-reader/writer comparison, which the current harness does not establish.
Use `hako-ab` when the reuse tier itself is the question.

For new timing runs, use a quiet machine, warm all payload/codec combinations,
and record the runtime, CPU, power profile, dependency versions, and commit.
The published harness reports Criterium means; interleaved A/B harnesses use
minimum-based statistics to reduce contention effects. Those statistics answer
different questions.

Allocation measurements use `getThreadAllocatedBytes` where available.
They report heap allocation, excluding off-heap arenas and other processes.
Lower heap allocation can reduce GC pressure without implying lower total
memory use or faster execution.

```sh
clojure -M:bench -m published
clojure -M:bench -m published size
clojure -M:bench -m hako-ab
clojure -M:nippy-bench
bin/bench
```

The `:bench` alias requires a locally installed hako comparison build and
JDK 25. See [bench/README.md](../bench/README.md) for setup and individual
harnesses. `bin/bench` saves output and machine details under
`target/bench/`; when running a harness directly, retain its output with
the revision and environment.

## JVM, µs/op

Recorded with Nippy 3.9.0-beta1 and the `power-saver` profile.
The original table does not carry a complete machine/commit record, so use it
as historical workload evidence and rerun before making a deployment decision.
Lower times are better.

| payload | op | boring | boring `:shapes` | hako | nippy |
|---|---|---:|---:|---:|---:|
| small-map | encode | 0.82 | 1.27 | 0.68 | 0.95 |
| small-map | decode | 0.92 | 1.13 | 0.71 | 0.97 |
| mixed | encode | 0.77 | 1.21 | 0.65 | 0.68 |
| mixed | decode | 0.67 | 0.63 | 0.62 | 0.84 |
| nested-map-50 | encode | 14.42 | 15.11 | 14.20 | 13.97 |
| nested-map-50 | decode | 22.13 | 22.05 | 14.09 | 18.25 |
| datom-maps-200 | encode | 67.48 | 72.35 | 52.44 | 57.98 |
| datom-maps-200 | decode | 69.39 | 35.94 | 38.93 | 81.73 |
| long-vec-1k | encode | 15.61 | 15.80 | 16.76 | 13.21 |
| long-vec-1k | decode | 36.91 | 36.05 | 32.60 | 34.56 |
| str-maps-200 | encode | 63.43 | 66.70 | 82.45 | 74.97 |
| str-maps-200 | decode | 69.55 | 54.58 | 79.51 | 106.44 |

In this run, boring with shapes has the lowest datom-map decode time.
Hako leads the small-map and nested-map decodes; boring leads the
string-keyed-map cases. The `nested-map-50` fixture is a map containing
50 small maps, not a chain 50 levels deep.

### Wire size

Sizes in bytes, rerun on 2026-09-06 at `d14ad50`, JDK 25.0.1, with Nippy
3.9.0-beta1 and hako 1.0.0-alpha35. The Nippy sizes differ from the previous
table, so they are reported separately from the historical timings above.
The [raw size output](../bench/results/published-size-2026-09-06.txt) records
the command, revision, and dependency versions.

| payload | boring | boring `:shapes` | hako | nippy |
|---|---:|---:|---:|---:|
| small-map | 56 | 56 | 43 | 41 |
| mixed | 63 | 63 | 55 | 53 |
| nested-map-50 | 1 561 | 1 561 | 1 079 | 1 191 |
| datom-maps-200 | 9 952 | 4 982 | 5 165 | 5 592 |
| long-vec-1k | 2 726 | 2 726 | 2 740 | 2 746 |
| str-maps-200 | 7 550 | 4 570 | 9 741 | 11 336 |

Shapes reduce repeated keys in the two arrays of maps. They do not apply to
`nested-map-50` because its maps are values of a map rather than rows of
one array.

### Tier-matched: `hako-ab`, both codecs reused

Recorded with `power-saver`, interleaved A/B measurements, minimum over
60 rounds and median of three runs. Ratio is boring time divided by hako
time: below 1 favours boring.

| payload | encode (T3, reused, no copy) | decode (T2, reused reader) |
|---|---:|---:|
| small-map | 1.64× hako | 1.37× hako |
| mixed | 1.67× hako | 0.63× boring |
| nested-map-50 | 1.36× hako | 1.46× hako |
| datom-maps-200 | 1.46× hako | 1.77× hako |
| datom-vec-1k | 1.72× hako | 0.50× boring |
| long-vec-1k | 0.89× boring | 1.20× hako |

T3 encoding reuses buffers without copying the result; T2 decoding reuses the
reader. These differ from allocating independent byte arrays through a
convenience API. The recorded results favour hako for map-heavy encoding and
several decodes, and boring for mixed-value and datom-vector decoding.

Heap allocation is a separate comparison: hako's native arena is not included
in the thread-allocation counter. Consult the harness output for time and
allocation at each tier.

### Compressed

Sizes from the same rerun after zstd level 3, except Nippy's column, which
uses `nippy/freeze` with its default compression policy. That policy may leave
a value uncompressed. Include compression only when the application uses it;
uncompressed data can be useful for direct navigation.

| payload | boring+zstd | boring `:shapes`+zstd | hako+zstd | fressian+zstd | nippy/freeze (default) |
|---|---:|---:|---:|---:|---:|
| small-map | 65 | 65 | 52 | 60 | 45 |
| mixed | 72 | 72 | 64 | 69 | 57 |
| nested-map-50 | 354 | 354 | 388 | 345 | 1 195 |
| datom-maps-200 | 1 121 | 1 237 | 1 168 | 1 014 | 5 596 |
| long-vec-1k | 1 851 | 1 851 | 1 861 | 1 503 | 2 750 |
| str-maps-200 | 1 062 | 1 168 | 1 118 | 982 | 2 489 |

Compression reduces the size differences between formats. Its choice matters:
the Nippy column includes a different compressor and is not a codec-only
comparison.

For `datom-maps-200`, shapes reduce raw size but increase zstd output from
1,121 to 1,237 bytes. Repeated keys are also useful input to a general-purpose
compressor. This result does not establish that shapes always increase
compressed size; the [deflate experiment](SHAPES.md#size-and-decoding-trade-offs)
has a different result.

### On Nippy's benchmark

`clojure -M:nippy-bench` uses Nippy's stress data, its reader/Fressian filter,
and its timing loop. The recorded run used Nippy 3.9.0-beta1 and
`power-saver`.

| codec | freeze µs | thaw µs | round µs | bytes |
|---|---:|---:|---:|---:|
| nippy/fast | 584 | 1 075 | 1 659 | 14 017 |
| boring | 728 | 934 | 1 662 | 15 326 |
| boring `:shapes` | 746 | 940 | 1 686 | 15 326 |
| nippy (LZ4) | 893 | 1 157 | 2 050 | 7 835 |
| nippy/encrypted | 979 | 1 289 | 2 268 | 7 863 |
| boring + zstd | 1 812 | 1 409 | 3 221 | 4 900 |
| boring `:shapes` + zstd | 1 861 | 1 382 | 3 243 | 4 900 |
| fressian | 5 165 | 3 286 | 8 451 | 12 222 |
| fressian + zstd | 5 981 | 3 298 | 9 279 | 4 600 |
| `pr-str` + `read-string` | 7 094 | 10 075 | 17 169 | 15 880 |
| nippy/lzma2 | 15 804 | 7 162 | 22 966 | 3 700 |

The raw boring and `nippy/fast` round trips are close in this run. Boring's
decode is faster and its encode slower. The compressed rows show the
size/latency trade-off: boring plus zstd is smaller than Nippy's LZ4 output
and takes longer to round-trip. Nippy's LZMA2 result is smaller again, with a
substantially higher time.

## Reading: byte arrays, buffers, and navigation

Boring uses one structural reader with source-specific access. Heap reads use
the byte-array path. `BufferSource` accepts `ByteBuffer` on JDK 9+;
`SegmentSource` supports memory segments and mapped files on JDK 22+.

There is no committed real-storage harness establishing a general speed
ranking between those two off-heap sources. Measurements on tmpfs or native
allocated memory do not establish disk-backed page-fault behaviour.

### Reading a field without decoding the value it is in

This experiment asks each codec for the same selected values. Boring uses
navigation; the hako and Nippy arms in this harness decode their whole stored
value first. This compares those access paths, not just decoder throughput.

The boring settings are
`encode-indexed` with `{:shapes true :stringref true}`.
The harness calls that combination `:store`; it is not a valid
`:profile` option.

Reproduce with `clojure -M:bench -m capability`:

| 5 000 rows × 5 fields | boring | hako | nippy |
|---|---:|---:|---:|
| size | 134 465 B | 138 766 B | 273 865 B |
| one field of one row | 0.94 µs | 236 µs | 1 005 µs |
| sum one column | 110 µs | 267 µs | 1 070 µs |
| filter on one column, project another | 114 µs | 267 µs | 1 070 µs |
| heap allocated, one field of one row | 4 048 B | 987 344 B | — |
| heap allocated, sum one column | 121 120 B | 1 107 320 B | 6 306 800 B |

The point read skips most rows. The column operations visit every row but
construct fewer objects and decode fewer fields.

The same recorded experiment reports 231 µs for boring's full-decode column
scan, compared with 110 µs for navigation and 267 µs for hako's decode path.
That additional boring baseline helps distinguish selective-access gains
from codec differences.

Shapes and stringref can be combined. The smaller fixture from this harness
has the following sizes:

| 200-row table | bytes |
|---|---:|
| plain | 12 613 |
| stringref only | 10 037 |
| shapes only | 6 648 |
| shapes + stringref | 5 063 |
| hako | 5 165 |

These payloads differ from the `published` fixture; their byte counts should
not be substituted into its tables.

### Navigation

`clojure -M:bench -m nav` compares cursor operations with decoding first:

| 68 KB, 200 records | nav | decode + `get-in` | ratio |
|---|---:|---:|---:|
| `get-in` one leaf (heap) | 5.9 µs | 124 µs | 21× |
| `count` the top-level map | 0.08 µs | 121 µs | 1400× |
| reduce over all 200, one field each | 57 µs | 125 µs | 2.2× |
| `get-in` one leaf (mmap'ed) | 6.2 µs | 131 µs | 21× |
| locate a 1 MiB blob vs materialise it | 0.6 µs | 185 µs | 290× |

A container's count is available in its CBOR header. A reduction visits all
records, so it has less to skip than a single-field lookup. A byte string can
be skipped from its length without visiting its payload.

For a sequence:

| 5 000 events, 360 KB | nav | decode-seq | ratio |
|---|---:|---:|---:|
| scan for matching events | 1 542 µs | 5 330 µs | 3.5× |
| first event only (early exit) | 3.9 µs | 2.2 µs | 0.6× |

`decode-seq` is already lazy. When only the first small event is needed,
constructing and probing a cursor costs more than decoding that event.

The navigator supports indexed string references in one document.
Navigable sequences require stringref off; `write-seq!` enforces that.
See [Index](INDEX.md) for configuration and the trust boundary.

### Mmap reads and writes

The recorded `clojure -M:bench -m mmap` experiment found selective mapping
faster than one `pread` per item. For appending 200,000 items, a
`BufferedOutputStream` took 130 ms, mapping 171 ms, and encode-only work
105 ms. These results favour buffered streaming for that append workload;
they do not describe the cost of updating existing mapped fields.

[Editing](EDITING.md) covers the latter case, including byte movement,
index maintenance, and durability I/O.

### Compression and lookup granularity

A random lookup must decompress its containing chunk before navigating it.
This recorded zstd-level-3 experiment varied chunk size:

| chunk | compressed | ratio | ns/lookup | vs raw |
|---|---:|---:|---:|---:|
| uncompressed | 15.4 MB | 1.00× | 1 498 | 1.0× |
| 4 KB | 1.59 MB | 9.7× | 5 400 | 3.6× |
| 64 KB | 1.22 MB | 12.7× | 55 987 | 37× |
| 256 KB | 1.21 MB | 12.8× | 201 755 | 135× |

Larger chunks improved compression but increased lookup cost in this fixture.
Choose chunk size using expected reads, cache behaviour, and storage costs.
Application-level chunk decompression and filesystem compression have
different cache and I/O paths; these numbers are not measurements of ZFS or
btrfs defaults.

## ClojureScript

Recorded on Node v23.11 with reused readers and writers for boring and
Transit. Times are ns/op.

```sh
clojure -M:cljs-compare -m cljs.main -co '{:language-in :ecmascript-next}' \
  -O advanced -t node -o target/cljs-compare.js -c cljsbench.compare
node target/cljs-compare.js
```

The comparison alias also needs the local fress benchmark dependency described
in `deps.edn`.

### Decode, ns/op

| payload | boring | boring `:shapes` | transit | JSON.parse |
|---|---:|---:|---:|---:|
| small-map | 2 253 | 2 244 | 1 055 | 224 |
| mixed | 1 470 | 1 475 | 627 | 172 |
| string-100 | 349 | 350 | 135 | 46 |
| nested-map-50 | 43 207 | 43 192 | 24 997 | 6 991 |
| datom-maps-200 | 199 111 | 52 820 | 91 042 | 33 727 |
| datom-vec-1k | 242 767 | 240 819 | 152 313 | 97 903 |
| long-vec-1k | 10 030 | 10 094 | 12 733 | 5 609 |

### Encode, ns/op

| payload | boring | boring `:shapes` | transit | JSON.stringify |
|---|---:|---:|---:|---:|
| small-map | 1 702 | 1 825 | 895 | 123 |
| nested-map-50 | 41 870 | 41 652 | 25 263 | 3 557 |
| datom-maps-200 | 194 045 | 124 680 | 128 435 | 20 144 |
| datom-vec-1k | 705 819 | 729 565 | 449 123 | 71 121 |
| long-vec-1k | 40 961 | 41 061 | 39 041 | 8 427 |

### Size, bytes

Boring is smaller than Transit for every payload in this table, not
necessarily smaller than JSON:

| payload | boring | boring `:shapes` | transit | JSON |
|---|---:|---:|---:|---:|
| small-map | 56 | 56 | 75 | 48 |
| nested-map-50 | 1 561 | 1 561 | 2 176 | 1 621 |
| datom-maps-200 | 9 952 | 4 982 | 14 307 | 13 091 |
| datom-vec-1k | 25 748 | 25 748 | 39 000 | 40 991 |
| long-vec-1k | 2 726 | 2 726 | 3 891 | 3 891 |

### Why Transit wins on JS, and where it does not

Transit JSON uses the JavaScript engine's native parser before constructing
ClojureScript values. Boring parses CBOR in JavaScript. In these results,
Transit leads most general-purpose full-decode cases.

Shapes reduce repeated keys and their parsing work: `datom-maps-200`
decodes in about 53 µs with shapes versus 91 µs with Transit, with a smaller
encoding. The numeric-vector decode also favours boring. These benefits do
not extend automatically to maps stored outside arrays.

### Comparing equivalent JSON results

`JSON.parse` produces plain objects with string keys. Boring and Transit
produce ClojureScript collections and keyword keys. A separate recorded
experiment includes that conversion cost:

| | ns |
|---|---:|
| `JSON.parse` → plain JS objects | 34 348 |
| `JSON.parse` + a hand-written CLJS build that knows the 5 keys | 43 420 |
| boring `:shapes` | 45 127 |
| boring, generic | 170 279 |
| `JSON.parse` + `js->clj :keywordize-keys` | 197 026 |
| CLJS construction alone, nothing parsed | 4 297 |

For this five-key fixture, shaped boring is close to a specialised JSON
conversion and faster than `js->clj :keywordize-keys`.
Applications using plain JS objects or their own conversion code should
compare against those paths rather than assume the generic conversion cost.
These timings are from a separate run from the preceding table.

## Design experiments

The following results explain implementation choices. They are retained as
experiments rather than current comparative throughput claims.

### Heap and off-heap access

A segment-only reader experiment increased full-decode time and recursive
stack use. Profiling attributed substantial work to bounds and arena-liveness
checks that a tight accessor microbenchmark had allowed the JIT to hoist.
Keeping heap access in the shared reader avoided paying those checks on the
byte-array path.

The original standalone accessor probe was not committed. Its timings should
not be used to select a storage backend or infer the speed of a complete
decoder.

### Optimisations that did not work

On a small-document LMDB navigation workload, these recorded within-run
comparisons used a loaded machine and the `powersave` governor:

| attempt | result |
|---|---|
| Shared encoded-key cache (`nav/context`) | 2.4× faster (implemented) |
| Pack records into one indexed CBOR sequence | 0.80× (slower) |
| Compiled path: no `Cursor`, no probe lookup, primitive offsets | 0.78× (slower) |
| Reuse one `Reader` across documents via `reset` | 1.00× microbench, 0.92× in a real LMDB scan |

The shared key cache eliminated repeated key encoding. The other changes
reduced allocation or dispatch without improving the measured scan.
For example, the compiled path reduced allocation from 208 to 80 bytes per
document while taking longer. This is evidence about those paths, not a
general rule that allocation never matters on the JVM.

### Primitive arrays

The `published` harness also compares the same thousand integer values in
vectors and JVM primitive arrays. Arrays use RFC 8746 typed encodings, allowing
bulk reads; they also change the result type and numeric range.

The deterministic sizes are 2,726 bytes for a vector, 8,008 for `long[]`,
4,008 for `int[]`, and 2,008 for `short[]`. Run
`clojure -M:bench -m published` for matching current decode timings.
Use a narrower array only when the values fit and the application wants an
array. Boring does not automatically transpose maps into typed columns.

### A WASM scanner

The [WASM experiment](../bench/wasm/) walks CBOR structure in C without
constructing strings or ClojureScript values:

| | JS | WASM | |
|---|---:|---:|---:|
| skeleton scan, generic (9 952 B) | 21 317 ns | 10 471 ns | 2.04× |
| skeleton scan, `:shapes` (4 982 B) | 9 806 ns | 5 242 ns | 1.87× |

The scanner was roughly twice as fast in this experiment. Relative to the
complete JS decode measured in that run, replacing only the scan would save
about 6–10% under an optimistic model that ignores result-handoff work.
This is not a measured integrated WASM decoder, nor a ceiling for every
possible WASM design. No WASM decoder is shipped.
