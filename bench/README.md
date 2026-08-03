# bench/

Nothing here is part of the library.

## Start here

```
bin/bench                  # everything; several minutes
bin/bench size compress    # just the deterministic sections; seconds
bin/bench ab               # just the interleaved comparison
```

`bench/suite.clj` runs the sections in the order that survives a noisy machine:
sizes first (deterministic, so they are worth keeping even if the timings are
thrown away), then the A/B interleaved comparison, then criterium last. One
global warmup covers both timing sections. Output goes to
`target/bench/<timestamp>.txt` with the machine description at the top, because
a benchmark number without its hardware is a rumour.

Three kinds of thing live in this directory:

## Reusable tooling

| file | what it is |
|---|---|
| `sizes.clj` | payload size across codecs, with and without compression |
| `compressors.clj` | lz4 / lz4-hc / deflate / zstd / zstd-dict over 500 messages |
| `published.clj` | **the exact tables in README.md and doc/PERFORMANCE.md.** They had no harness until this existed |
| `suite.clj` | the entry point above — runs the others in the right order, with one global warmup |
| `ab.clj` | A/B harness that interleaves two codecs in short bursts. Use this, not criterium, whenever the machine is not quiet — criterium measures A for ~7s then B for ~7s and background load drift makes that comparison meaningless. Includes a global warmup, because per-cell warmup was not enough (hako's small-map encode measured 2.52 / 1.30 / 1.15 / 1.08 µs across four consecutive runs). |
| `bench.clj` | main JVM competitive benchmark vs nippy, hako, clj-cbor |
| `hako_ab.clj` | **the tier-matched boring-vs-hako table**, time and allocation. Quote this one, not `bench.clj`'s hako column: `hako/encode` builds a fresh Writer and Arena per call and is not the path hako is meant to be used through, so pairing it against boring's reused writer measured our fast path against their slow one. Also carries the heap-only caveat on the allocation columns |
| `alloc.clj` | bytes allocated per op via `ThreadMXBean` — the axis timing benchmarks miss |
| `mmap.clj` | everything mmap, in three sections (`read`/`write`/`compress`): selective decode beats a `pread` per item 2.3x; mmap **loses** to a buffered stream for append; and the chunked-zstd curve that picks a chunk size. `clojure -M:bench -m mmap write` runs one section |
| `nav.clj` | navigation, in two sections (`skip`/`cursor`): how cheap it is to walk past a value without decoding it — the inner loop — and then what the shipped `boring.nav` api delivers against decode-then-`get-in`. Records a NEGATIVE result too: early-exit on the first item of a log is *slower* than `decode-seq`, which is lazy already |
| `java/FfmProbe.java` | standalone (no classpath) probe: `byte[]`+VarHandle vs heap vs native `MemorySegment`, and the big-endian intrinsification cliff. Run it on more than one JIT — the finding differs between C2 and Graal. See "Why the hot path is `byte[]`" in `doc/PERFORMANCE.md` |
| `fuzz.clj` | mutation fuzzer over valid encodings. Found 154 untyped failures per 60k mutants on its first run. Run it after any decoder change. |
| `prof.clj` | clj-async-profiler driver for the JVM decode loop |
| `large.clj` | MB-scale payloads and streaming throughput, with a bounded-memory check |
| `stability.clj` | min/median/max over repeated runs for the noisy large-payload cells |
| `hexdump_jvm.clj` + `cljs/cljsbench/hexdump.cljs` + `test/boring/hexdump_cases.cljc` | **cross-platform byte-identity gate.** Dump the same values from both platforms and diff. This is a correctness check that happens to live here because it needs two runtimes. |
| `cljs/cljsbench/runner.cljs` | runs the `.cljc` conformance suite under Node (CI gate) |
| `cljs/cljsbench/fuzz.cljs` | CLJS mutation fuzzer (CI gate) |
| `cljs/cljsbench/baseline.cljs` | CLJS floor: JSON and hand-written JS |
| `cljs/cljsbench/compare.cljs` | CLJS competitive benchmark vs transit, fress, JSON |
| `cljs/cljsbench/profrun.cljs` | driver for `node --cpu-prof` |
| `cljs/cljsbench/micro.cljs` | CLJS micro-benchmarks: collection construction, call dispatch, byte access. Mostly negative results, kept so they are not retried |
| `cljs/cljsbench/wasm_fixtures.cljs` | writes the CBOR fixtures `wasm/bench.js` reads |
| `wasm/` | does a WASM decoder pay? See `wasm/README.md`. Not part of the build |

## Where the one-shot probes went

The scripts that produced the one-shot comparison tables have been removed:
re-running a probe on a different machine at a different commit does not
reproduce the comparison, and more than one conclusion had to be withdrawn
because a re-run disagreed with its own first answer. What survives is the
harnesses below, which regenerate their numbers on demand.

Two exceptions are kept because their finding is written down nowhere else:

| file | what it measures |
|---|---|
| `stack.clj` | end-to-end codec+compressor stack; embeds its own result table |
| `wire_dict.clj` | trained vs streaming zstd dictionaries on a websocket |

Packed CBOR's verdict moved into `doc/COMPATIBILITY.md`, which
`doc/IANA-REGISTRATION.md` had been pointing at before the section existed.

## Warnings earned the hard way

- **Two runs of the same measurement can differ 2x.** hako's `long-vec-1k`
  decode measured 4.54, 10.27, 8.70, 8.78, 10.05 and 4.68 µs across runs of the
  identical harness on an idle machine. A published table once recorded the
  4.54 and boring's 11.12 from the same run, implying a 2.4x gap; measured
  three times consecutively the ratio is 1.11–1.17x. **Before optimising
  against a gap, measure it three times.** An hour was nearly spent chasing
  that one.
- **Allocation is the honest axis when timing is noisy.** `getThreadAllocatedBytes`
  is deterministic and immune to load, and it found in one run what the timings
  could not: a fresh Reader allocated 784 B beyond its own 80 B object to
  decode a 56-byte map.

- **Type-hint Java interop.** An unhinted Jackson benchmark reported 7 ms/op
  against boring's 65 µs. It was pure reflection. Set `*warn-on-reflection*`.
- **A micro-benchmark measures its setup as much as its subject.** Two
  conclusions had to be retracted because the harness varied something other
  than the named variable — a `#js [v pos]` tuple returned per value, and
  Keyword-vs-string `aset` keys.
- **Warm the whole process, not the cell.** See `ab.clj`.
