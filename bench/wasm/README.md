# Would a WASM decoder be faster?

An optional WASM module, falling back to the JS reader when it fails to load,
is the obvious way to buy native-speed scanning on ClojureScript. This is the
experiment that answers it. **It does not pay** — at most 6–9%, and that is an
upper bound that assumes the JS side consumes the WASM output for free.

Not part of the build. It needs clang with a `wasm32` target and `wasm-ld`,
neither of which CI has, and nothing in `src/` depends on it.

## Running it

```
mkdir -p target/wasm
clang --target=wasm32 -nostdlib -Wl,--no-entry -Wl,--export-all \
      -Wl,--export-memory -O3 -o target/wasm/scan.wasm bench/wasm/scan.c

clojure -M:cljs-compare -m cljs.main -co '{:language-in :ecmascript-next}' \
  -O simple -t node -o target/gen.js -c cljsbench.wasm-fixtures
node target/gen.js                                       # writes the fixtures
node bench/wasm/bench.js
```

If clang cannot find its linker (`Executable "wasm-ld-N" doesn't exist`), it is
looking for a version-matched `wasm-ld`; symlink the one you have onto the name
it wants and put that directory first on `PATH`.

## What it measures

`scan.c` walks the CBOR structure and counts items, **constructing nothing**.
That is deliberate: it is the *most* a WASM module could take off the JS
decoder, because everything else a decoder produces — JS strings, CLJS
`Keyword`s, `PersistentArrayMap`s, `PersistentVector`s — has to be built on the
JS side no matter what. `bench.js` runs the identical walk in JavaScript.

## Result

node v23.11, `datom-maps-200`:

| | JS | WASM | |
|---|---:|---:|---:|
| skeleton scan, generic encoding (9 952 B) | 19 821 ns | 9 243 ns | 2.14× |
| skeleton scan, `:shapes` encoding (4 982 B) | 8 813 ns | 4 814 ns | 1.83× |

WASM really is about twice as fast at the scan, and copying the buffer into
WASM memory is not the obstacle — 10 KB costs ~120 ns, under 2% of the call.

The problem is what fraction of a decode the scan is:

| | scan share of decode | saving if ALL of it moved to WASM |
|---|---:|---:|
| generic (170 279 ns) | 11.6% | **6.2%** |
| `:shapes` (45 127 ns) | 19.5% | **8.9%** |

And 6–9% is the ceiling, not the estimate. It assumes JS pays nothing to walk
whatever index WASM writes — but a CBOR header is already one byte with the
major type in its top three bits, so reading an index entry is not obviously
cheaper than reading the header it replaces. The realistic figure is lower.

Two independent reasons the ceiling is that low:

- **Strings cannot move.** A JS string cannot be a view into WASM memory; every
  string crossing the boundary is copied and transcoded UTF-8 → UTF-16. That is
  the same wall a Rust JSON parser in WASM hits, which measures ~8× *slower*
  than native `JSON.parse` for exactly this reason. boring's string time is
  already spent in `TextDecoder`, which is native code either way.
- **Clojure values cannot move.** `Keyword`, `PersistentArrayMap` and
  `PersistentVector` are JS objects with CLJS-specific layout. WASM cannot
  allocate them.

For comparison, the lever that is already built: `:shapes` takes this payload
from 170 µs to 45 µs — **3.8×** — with no second implementation, no binary to
ship, and no load-time fallback to keep conformant. Widening where shapes fire
(tag 39650 for nested maps, see [../../doc/SHAPES.md](../../doc/SHAPES.md)) is
worth more than a WASM scanner by a wide margin.
