# Index consolidation: plan of record

Working document, not published (`doc/cljdoc.edn` lists what ships). It exists
because this plan was assembled across a long session out of measurements that
are expensive to retake, and half its value is the list of things we tried and
rejected. Anything here marked REJECTED should not be reopened without new
evidence, and the reason is recorded so the evidence can be aimed at it.

Baseline for everything below: konserve-lmdb `bench/results/before-refactor.txt`
(boring d134a2e) -- but see "Benchmark protocol", it was captured under load and
must be retaken.

---

## 1. The goal

Two objectives, in order:

1. **Indexed lookups beat scanning on non-trivial CBOR blobs.** Today this holds
   only when trusted, only above ~200 entries, and only warm.
2. **ClojureScript and Clojure cannot drift.** Today they agree by hand.

Everything else -- size, knobs, tiers -- is subordinate to those.

---

## 2. The cost model (measured, JVM, 8-core, criterium)

```
scan a container of n entries       ~37 ns * n
open the index (materialise)        ~2.0-2.4 us fixed  +  ~17.6 ns/node
                                     allocates 2720 B even for ONE node
expand one node's anchors           O(m),  m = ceil(n/stride)
walk within a stride                O(stride)
per-anchor predecessor check        11.1 ns
```

Consequences, all measured:

- **Cold crossover is n ~ 200.** Below it, scanning wins; the indexed path is
  flat at ~7-9 us because the open dominates. At n=2048 the index wins 8.9x.
- **Cold is dominated by the open, not by stride.** At n=256, stride 16 gives
  7.29 us and stride 64 gives 9.53 -- second-order next to the ~7 us fixed cost.
- **At n=128 the index is 5x faster warm (1.11 us vs 5.69) and exactly a wash
  cold (6.80 vs 6.77).** Setup consumes the entire benefit. This is the single
  most important number in this document.
- Cold-optimal stride by container size: n=64 -> 1; 256 -> 16; 1024 -> 16;
  4096 -> 16; 16384 -> 64. Grows far slower than sqrt(n), and the curve is flat
  (+-30% across a wide range). **A global stride of 16 is near-optimal.**
- Warm inverts: stride 1-4 is best warm, and the index is monotonically bigger
  as stride shrinks. Both real consumers (konserve-lmdb, konserve `mmap-value`)
  are COLD -- a fresh source per read.
- `sorted` is false for Clojure hash-maps only because hash iteration order is
  scrambled. With ordered keys it is true and worth 55x on a flat map.
  `:profile :canonical` orders keys and costs 1.45x-4.32x on encode.
- A Clojure `sorted-map` encodes as a TAGGED record, which navigation cannot
  enter -- 29.6 ms vs 0.65 ms on 20k entries. Backwards from what the name
  suggests; worth a doc note.

---

## 3. Decisions

### 3.1 The frame stays six elements

Non-negotiable. Changing the element COUNT makes the frame unrecognisable and it
is republished as a trailing DATA item -- N items become N+1, in both
directions. `test/boring/index_robustness_test.clj:1008` asserts exactly this.
Changing an element's TYPE is safe: the frame is still recognised, the index is
refused, the caller scans.

### 3.2 Access discipline (the rule the code never states)

| element | access needed | representation |
|---|---|---|
| `containers` | RANDOM (`node-slot` binary-searches it) | flat fixed-width |
| `counts` | RANDOM (per-node) + sequential (`slot-starts`) | flat fixed-width |
| `slots` | SEQUENTIAL per node (prefix sum from a base) | variable-width packed |
| `sorted` | RANDOM (per-node bit) | bitset |

Two representations here is FIT, not inconsistency. Every frame decision follows
from this table and it appears nowhere in the source -- which is why packing
`containers` was proposed and had to be withdrawn. Put it in `seal-index!`.

### 3.3 Options stay FLAT

`:index` stays numeric (the stride), `:index-min` keeps its name and meaning
(first-level entry count), `:trust-index` stays a key.

Two hard constraints discovered by reading consumers:

- `konserve/src/konserve/serializers.cljc:116` is
  `(pos? (long (get opts :index default-index-stride)))` -- a public API reading
  `:index` as a number. Nesting (`:index {...}`) or `:index false` throws
  ClassCastException there.
- **A removed option key silently no-ops.** `boring.options/near-miss`'s own
  docstring says "Unknown keys pass", and its guard requires the name lengths to
  differ by at most 1. So `:index-min` -> anything longer is not suggested, not
  rejected, just ignored. konserve-lmdb passes `:index-min` at
  `src/konserve_lmdb/store.clj:503` and in four tests. ANY rename needs a
  throwing tombstone row in `spec`; a rename alone is a silent behaviour change.

### 3.4 Trust collapses to two tiers

Use the index (cheap O(1)/O(N)-at-open checks always on) or `:ignore` it.
`:trust-index :trusted` becomes a documented no-op or a throwing tombstone --
NOT a deleted key, per 3.3.

Justification: validation is an accident-detector, not a security boundary --
`doc/SECURITY.md` says the attacker controls the footer AND the data. The
expensive far-end check has no recorded catch on container nodes, and disabling
it leaves 392 tests / 0 failures.

### 3.5 No key-ordering option

`:profile :canonical` already orders keys. Indexing already forces
`:stringref false`, so canonical + index costs only `:shapes` and float width.
Document the 55x, add no knob.

---

## 4. REJECTED (do not reopen without new evidence)

- **A 7th `ends` element** to make the far-end check O(1). Breaks 3.1. Also the
  stored end is WEAKER than the computed one: today's check compares a
  footer-supplied anchor walked through data against a data-derived `skip(c)`;
  storing the end removes the only data-derived term and moves container nodes
  into the uniform-shift blind spot that `anchor-sound?` exists to cover.
- **Packing `containers`/`counts` like `slots`.** `containers` must stay
  randomly addressable for the binary search; delta-packing forces an eager
  prefix-sum expansion at open, undoing 062e1c7. Measured saving ~1.6%.
- **Dropping or deriving `counts`.** It is the ONLY input to `slot-starts`, the
  gate that refuses a mismatched frame at open WITHOUT reading the data section.
  Deriving it from `headArgAt` destroys that and turns `node-valid?`'s O(1)
  count check into a tautology.
- **`:index-min` as SUBTREE ITEM COUNT.** Not decidable where the decision must
  be made. `Writer.java:1427` allocates `long[] anchors` at the container's
  HEAD, and `reserveNode`'s docstring explains why: claiming pre-order is what
  makes `containers` ascend with no sort. Subtree size is unknown until the
  container ends, and in streaming mode those bytes are already at the sink.
  Doing it anyway means either capturing anchors speculatively for every
  container (the allocation regression both builders were optimised away from,
  3.9-5.2x on small payloads) or claiming at container end (post-order, needs a
  sort). And a metric only the byte walk can compute breaks byte-identity with
  the Writer, which is the invariant `the-two-index-builders-agree` holds.
  NOTE: n IS the right quantity anyway -- scan cost is `37ns * n`, and n is the
  first-level entry count. The threshold VALUE is what was wrong, not the metric.
- **Nesting `:index {...}`.** See 3.3.
- **Raising the default stride to 32-64.** Contradicts the measured cold optima
  at the sizes that matter.
- **Per-node derived stride (~sqrt(T)).** The measured optimum grows much slower
  than sqrt(n) and the curve is flat; this buys tens of percent, not multiples.
- **cljs `build-index` 3-arity (`base`).** No consumer; the base arity exists for
  konserve-lmdb's split blob, which is JVM-only.
- **Read-side accessors into `.cljc`.** cljs has NO index reader. It would be
  dead code in every bundle, and `delta-at`'s width-3 branch does
  `(bit-shift-left ... 56)`, which wraps at 32 bits on ClojureScript.

---

## 5. Commit sequence

None of 1-8 may change bytes. If a benchmark moves on one of them, it was not a
refactor.

0. **Re-baseline** on an idle machine. Add an untrusted-cold-lookup row to
   `bench/nav.clj run-index` FIRST -- commit 8's headline number (352 us -> ~28)
   has no benchmark in the repo today (`bench/` never varies `:trust-index`).
1. **Add the missing index tests.** Splice, array middle-anchor-off-by-one,
   `nth-item` bounds. They must fail to detect TODAY. Re-derive
   `doc/SHAPES.md`'s 2.1% silently-wrong figure. These land BEFORE commit 8
   removes the check they cover.
2. **Golden: freeze the index frame, portably.** Nothing freezes it today --
   `golden.cljc:145-149` CLAIMS the corpus holds a sealed index frame and it does
   not. The only byte gate compares the JVM's two builders and never runs on
   cljs. The portable entry must be a VECTOR: hash-map iteration order differs
   across platforms, which is the same thing that makes `sorted` false.
3. **cljs: promote index offsets past 2^31** instead of wrapping. CHANGES BYTES
   past 2 GiB, where they are already wrong (wrapped negative -> containers stop
   ascending -> the index is silently refused).
4. **`boring/index.cljc` -- WRITE SIDE ONLY.** `anchor-count`,
   `slot-width-code`, `pack-slots`, `pack-sorted`, `ptr-bytes`,
   `wire-containers`, `frame-payload`. The read side stays JVM-only.
5. **One `.cljc` byte walk** (`walk*`, `scan`, `frame-payload-array?`). Host
   accessors must be MACROS dispatching on `(:ns &env)` -- measured 13-14%
   slower as functions. This makes five clj/cljs divergences unrepresentable.
6. **`frame`: Reader-based `footer-start-in`**; nav calls it. Deletes the fourth
   footer implementation. The two current copies provably agree
   condition-for-condition, so this is a pure refactor.
7. **One `bytes!`, one `default-index-stride`** (verify both are 16 first -- if
   they had diverged this would be a byte change hiding in a dedup).
8. **Per-anchor validation replaces the container far-end check.** Sequence
   nodes keep theirs (already O(1), `want-end` is `ptr`). Must ADD validation to
   `nth-item` -- which has NONE today and is protected only by the check being
   removed -- and to `lookup-map`'s anchor jumps, in the SAME commit.
   `anchor-sound?` must carry the map-vs-array `per` factor (2 vs 1); getting it
   wrong is silent, making every map node report unsound and every map lookup
   fall back to scanning. Rewrite `doc/SHAPES.md:317-341`.

### The clj/cljs divergences these close

`ceil(n/s)` is written SIX times: `Writer.anchorCount`; `core.clj:1522` (named)
and `core.clj:1333` (inline); `nav.clj:1751`; `core.cljs:616` (named) and
`core.cljs:770` (inline). Commits 4-5 take it to TWO. `Writer.anchorCount`
cannot be unified -- Java, sizes `new long[]` on the capture hot path -- but
`pack-slots` already throws `:boring/bad-index` on disagreement at every seal,
which makes a drift unshippable rather than merely tested.

Also closed: cljs's missing `:boring/bad-count` guard (a live OOM risk on a
public entry point), `Int32Array.from` vs sint64 promotion, `INDEX-WALK-MAX-DEPTH`
named on cljs vs a bare `200` on the JVM, duplicated `frame-payload-array?`,
`slot-width-code`'s differing sentinels, and cljs rebasing the container offset
by `base` but NOT the anchors (dormant only because cljs always passes 0).

---

## 6. Open: the cold path allocates an index to serve one lookup

This is the largest remaining win and it is NOT yet in the sequence above.

Opening a ONE-NODE index allocates **2720 B** and costs ~2.0-2.4 us. It copies
`containers`, `counts`, `slots` and `sorted` out of the buffer into Java arrays,
runs an O(N) prefix sum (`slot-starts`), and allocates three memo arrays plus a
15-key map. A single lookup needs none of it.

A lookup could instead read in place -- typed arrays on the wire are fixed-width
and contiguous, and `Reader.u32At`/`byteAt` already exist. Measured: binary
searching `containers` in place is 0.67 us over 769 nodes against 0.83 us just
to COPY them.

**The blocker is finding node i's slot segment**, which is O(N) because segment
lengths are derived (`sum of m_j * 2^w_j`) rather than stored. Once you are
paying O(N), copying everything is free by comparison -- which is how the
current design happened.

Two candidate fixes, both TYPE-compatible (payload stays six elements):

- **A start table inside `slots`**: `[widths | starts | deltas...]`. O(1) segment
  lookup, keeps deltas compact. ~2 bytes/node.
- **Absolute anchors instead of deltas.** Deltas are compact but inherently
  sequential -- even with a start table, reaching anchor k means summing 0..k.
  Absolutes give true random access: binary search in place, O(log m), no
  expansion, no allocation. Cost depends entirely on stride (n=20000:
  stride 1 -> 20 KB deltas vs 80 KB absolutes; stride 256 -> 79 B vs 316 B).
  ARITHMETIC, not measured -- confirm on real frames before relying on it.

Note these two and "use a high stride" are the same design decision seen from
different sides: deltas + stride 1 optimise the WARM case, absolutes + high
stride optimise the COLD case, and both consumers are cold.

**Prototype result (done).** Reading node 0's anchors straight out of the
buffer -- positional reads only, no `.readFrom` of any payload element, no memo
arrays, no map -- against the CURRENT format, 1 node / 128 entries / stride 16:

    MATERIALISE  read-index (the open)   2.701 us   2760 B
    IN PLACE     anchors for node 0      0.211 us    304 B

Identical anchors, 12.8x faster, 9x less allocation, and the 304 B is almost
entirely the long[8] of anchors it returns. NO FORMAT CHANGE was needed for
this. The open's cost is materialisation, essentially in full.

Two caveats on generalising it:

- The prototype has ONE node, so it says nothing about the O(N) slot-start
  problem. Reaching node i's segment still costs O(i) without a start table, so
  in-place reads alone fix the copying and NOT the scaling. Both are needed for
  documents with many nodes; only the first is needed for few.
- Removing the open does not close the whole cold gap. At n=128, cold is 6.80 us
  against 1.11 us warm; the open is ~2.4 of that difference and roughly 3 us
  remains, which tracks the extra 5024 B the cold path allocates (6224 vs 1200)
  rather than any single computation. Lower allocation should recover part of
  that too, but it must be MEASURED end to end, not extrapolated from this.

So the sequencing is: do in-place reads first (no format change, provable win),
re-measure, and only then decide whether the start table or absolute anchors are
still worth a format change.

---

## 7. Benchmark protocol

- `konserve-lmdb/bench/baseline.sh` runs both benches under `:boring-local` and
  stamps the boring SHA, JVM, loadavg and whether `../boring` was dirty.
- **`run-benchmark` is the WRONG target** -- it is put/get/batch KV throughput
  against native LMDB and Datalevin and never touches nav or the index. It
  cannot move on any of this work.
- **`bench-projection` is the right konserve-lmdb target.** Its own docstring
  says the RATIOS survive load and the milliseconds do not, so compare ratio and
  ALLOCATION columns; allocation is deterministic and is the honest regression
  detector for a refactor.
- boring's own targets: `clojure -M:bench -m nav index` (the KNOBS table is the
  `:index-min` x stride cross) and `-m nav write` (interleaved, min-of-rounds --
  the most trustworthy timing harness in the repo). `bin/index-mutants` is the
  correctness gate for commit 8.
- The fold-vs-SQLite comparison has NO harness -- it exists only as a comment
  table at `konserve-lmdb/src/konserve_lmdb/store.clj:1679-1686`, marked "ONE
  RUN". Either write it or stop citing it.

**Known open regression, from the baseline:** in the projection profile the
INDEXED rows are 2-3x SLOWER than unindexed on the same data (12458 B/400 blobs:
1.08 ms unindexed vs 3.47 ms indexed). Projection opens a source per blob and
`find-field` walks linearly by design, so the index is built, opened and never
consulted -- pure per-blob overhead, ~6 us each. Section 6 is the fix; a
read-side "ignore any frame" would also do it.
