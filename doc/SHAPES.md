# Shapes

Repeated map keys are where serialization size and decode time actually go.
This file specifies what boring does about that today, what it should do about
the case it currently misses, and the constraints any extension must respect.

**Status:** tag 39649 is implemented, as is the `boring/index` tag-27 name. Tag 39650 is *specified here
and not yet implemented* — it is a design under review, deliberately not in the
first release. See "Why not in the first release" at the end.

| tag | what | status |
|---|---|---|
| 39649 | shaped array — repeated map keys hoisted out | implemented |
| 39650 | scattered shapes | specified, not implemented |
| tag 27 `boring/index` | sequence and container index | implemented — a NAME, not a number |

---

## The problem, measured

200 maps sharing the key set `[:count :measure :diff :max-key]`, arranged three
ways. All numbers are boring's own output.

| arrangement | no stringref | default | `:shapes true` |
|---|---:|---:|---:|
| **array** of 200 maps | 10 528 | 6 750 | **2 775** (−58.9%) |
| **map** of 200 maps | 10 904 | 7 126 | 7 126 (**+0.0%**) |
| 200-deep **nesting** | 14 127 | 8 956 | 8 956 (**+0.0%**) |

Two things to read off this.

Stringref already does real work: 10 904 → 7 126 is a 35% saving on the
scattered case, because the key *strings* are deduplicated. But it is not
enough — every occurrence still pays a tag-39 identifier wrapper, a stringref
reference, and a map header per key.

And shaped arrays, as they stand, do **nothing** unless the maps happen to be
elements of one homogeneous array. A map-of-maps gets zero. That is not an
exotic shape: PSS's diff-buf `:slots` is exactly a map-of-maps.

---

## What exists: tag 39649, shaped array

An array whose elements are all maps sharing one key set is written as
`[keys, [row-values...]]`, so the keys appear once.

```
value   [{:e 1 :a :x} {:e 2 :a :y}]

plain   82                    array(2)
          a2                    map(2)
            d827 62 3a65          tag39 ":e"
            01
            d827 62 3a61          tag39 ":a"
            d827 62 3a78          tag39 ":x"
          a2                    map(2)
            d827 62 3a65          tag39 ":e"     <- repeated
            02
            d827 62 3a61          tag39 ":a"     <- repeated
            d827 62 3a79          tag39 ":y"

shaped  d9 9ae1               tag 39649
          82                    array(2)
            82                    the KEY SET, once
              d827 62 3a65          ":e"
              d827 62 3a61          ":a"
            82                    the rows
              82 01 d827623a78        row 1: values only
              82 02 d827623a79        row 2: values only
```

The decode win is larger than the size win: each key is decoded and interned
**once**, then the already-interned keys are interleaved straight into each
row's map backing array. There is no per-row key work at all.

This is what closed the JVM decode gap to hako — **21.0 → 11.4 µs** on
`datom-maps-200`, against hako's 11.5, at half the wire size (9 952 → 4 982
bytes) — and took CLJS from 649 → 247 µs.

One caveat, measured after the fact and worth stating here rather than only in
[PERFORMANCE.md](PERFORMANCE.md): under zstd the size win **inverts**, to
1 237 bytes against 1 121 without shapes. Shapes remove exactly the repetition
a general-purpose compressor is best at. Turn `:shapes` on when you are not
compressing, or when decode latency matters more than ~10% of compressed size.

---

## Proposed: tag 39650, shaped map

### Wire format

A **per-top-level-item shape table**, indexed in order of definition — the same
scoping discipline as the stringref namespace, and for the same reason (see
"Constraints" below).

One tag, self-discriminating on the type of its first element:

```
define      39650([[k0 k1 ... kn], v0, v1, ... vn])    element 0 is an ARRAY
reference   39650([idx,            v0, v1, ... vn])    element 0 is an UNSIGNED INT
```

A definition registers its key set as the next shape index *and* carries that
occurrence's values, so defining costs nothing beyond the keys that would have
been written anyway. Keys are always an array and an index is always an
unsigned integer, so the two forms are unambiguous without a second tag.

The values are **flattened into the same array** rather than nested in a second
one. That saves one byte per occurrence, which matters because references are
the common case.

### Measured

Same 200-map map-of-maps as above, with the proposed encoding simulated on the
wire:

| | bytes | vs default |
|---|---:|---:|
| default (stringref only) | 7 126 | — |
| **proposed 39650 define/ref** | **3 946** | **−44.6%** |
| hand-built `[keys rows]` (theoretical floor) | 2 772 | −61.1% |

**5.9 bytes of overhead per reference** — 3 for the tag, 1 for the array
header, 1 for the index, and the remainder in structure. That captures roughly
73% of the win available against the floor.

### Why this does not replace 39649

For a 200-element homogeneous array, define/reference costs ~5.9 bytes *per
row* that `[keys, rows]` does not pay at all. The grouped case genuinely wants
39649 and the scattered case genuinely wants 39650. They are two mechanisms,
not one — but they should **share one shape table**: a 39649 frame registers
its key set too, so a scattered map later in the same item can reference it for
free.

---

## Keeping the performance edge

The stated risk is that shape lookup taxes every map write. It does not, for
two reasons.

**It is gated on `:shapes true`.** The default profile performs no shape lookup
whatsoever, so nothing that does not opt in pays anything.

**Within `:shapes true`, references are strictly cheaper than writing keys.**
The encoder hashes the key set — `k` identity hashes, since keywords are
interned — and probes an open-addressed table. That is O(k), the same order as
writing the k keys it replaces. On a hit it then skips writing k keywords
entirely. On shape-repetitive data the encoder does *less* work than the plain
path, not more.

Decode is the same story: a reference's keys are already decoded and interned,
so building the map is a straight interleave into the backing array — the
identical mechanism 39649 already uses.

### Thresholds

- **Only shape maps with ≥ 2 keys.** A 1-key map costs 5 bytes as a reference
  and about 5 bytes plain. Below two keys there is nothing to win.
- **Always define on first sight.** The alternative — write the first
  occurrence plain and register it implicitly — would force the decoder to
  hash and index the key set of *every* map it decodes, whether or not shapes
  are ever used. That is an unconditional decode tax on all data, and it is
  the reason implicit registration is rejected. Explicit definition costs
  ~4 bytes per distinct shape per item and costs nothing to a decoder that
  never sees the tag.

---

## Constraints any shape mechanism must respect

### Self-contained per top-level item — non-negotiable

The shape table must not span top-level items, however much better that would
compress. Two hard reasons:

**Content addressing.** datahike content-addresses index nodes. If a node's
bytes depend on what was serialized before it, identical content yields
different bytes and therefore different addresses, and content-addressed
deduplication collapses.

**Independent decodability.** konserve fetches blobs by address, alone and out
of order. A stream-scoped table can only be read from the beginning of the
stream.

This is the same decision boring already made deliberately for stringref, and
`boring.core` documents it: one namespace per top-level item, because "every
item then depends on everything before it, so the chunk must be read from the
start and cannot be split."

cbor-x's `useRecords` takes the opposite choice — structures are defined once
per *stream* and can even be persisted across runs via
`getStructures`/`saveStructures`. That is the better design **for a wire
protocol** and a worse one for content-addressed storage. It is not an
oversight in either direction; the two are optimizing different things. A
shared table for the kabel case remains open as a separate, opt-in feature.

### Security

The decoder-side shape table is **attacker-controlled state that persists
across an item**. This is the same class as the stringref index defect already
found and fixed (`:boring/bad-stringref`), so every one of these belongs in the
first implementation, not after fuzzing finds them:

1. **Bound the table.** Cap shapes per item and reject beyond it. Unbounded
   growth from hostile input is otherwise trivial. (cbor-x's 64 is a
   reasonable reference point.)
2. **Validate the index.** A reference to an unregistered shape is a typed
   error, `:boring/bad-shape-ref` — never a null or a silently empty map.
3. **Validate arity.** A reference carrying a different number of values than
   its shape has keys is an error. 39649 already does this.
4. **Reject duplicate keys** in a definition's key set, as `buildMap` does.
5. **`checkCount` before allocating** for both the key array and the value
   list, as every other count-bearing path does.

### Interoperability

39650 is an extension, exactly as 39649 is. A foreign decoder parses it without
error — CBOR is self-describing — and receives the raw tagged structure rather
than a map. It cannot misread it.

Both forms are **self-contained**, which makes them materially easier to
support out-of-band than cbor-x's stream-scoped records: a generic
preprocessor needs no cross-item state. `doc/INTEROP.md` carries the reference
readers.

---

## IANA

39649 is provisional and unregistered. 39650 would be a second.

**Register the family together, once, rather than dribbling out tags.** A
First-Come-First-Served registration is what turns "documented" into
"discoverable by someone who never read our docs", and it is what stops another
specification claiming the number — which already happened once: shaped arrays
used 40000 until a registry check found 40000 assigned to `ur:known-value`.

---

## Why not in the first release

Three reasons, in order of weight:

1. **The array case is already handled**, and it is the one datahike's index
   depends on. The scattered case is `:slots`, schema maps and query results —
   real, but second-order.
2. **It is new wire surface with new failure modes** at exactly the moment the
   goal is validating one vertical end to end.
3. **kabel is not helped by it.** Its win is the cross-message table, which
   per-item scoping cannot deliver at all.

Specify it now, so the wire format and the security requirements are settled
and reviewable. Ship it once the vertical is proven.


---

## `boring/index`: the sequence offset index

A CBOR sequence (RFC 8742) has no way to reach item *n* except by skipping the
*n-1* before it. For a log that is fine while tailing and useless while seeking:
reaching the last of 200 000 items measured 10.6 ms by skipping and 0.2 µs
through an index — see the stride table below, and reproduce it with
`clojure -M:bench -m nav index`.

`boring/write-seq!` with `:index N` seals a sequence with one extra item:

```
<item> <item> ... <item>              the data section, untouched
d8 1b  82                             tag 27, array(2)
   6c 626f72696e672f696e646578        "boring/index"
   86 ...                             [stride, containers, counts,
                                       slots, sorted, 48 <8 bytes>]
```

`slots` are **deltas, not absolute offsets** — see "Slots are deltas" below
before implementing a reader.

### Why the index is at the END

Offsets are only known once the items are written, so a leading index would
mean buffering the whole sequence in memory before emitting a byte — fatal for
a log. ZIP's central directory and Parquet's footer sit at the end for exactly
this reason. It also means a foreign reader sees every real item first and the
metadata last, so one that stops early never encounters it.

### How it is found, without leaving CBOR

CBOR cannot be parsed backwards. The last element is therefore a byte string of
exactly 8 bytes, which always encodes as `0x48` plus 8 — so a sealed file ends
with 9 predictable bytes however large the offsets array is. Read them, take
the pointer, seek.

**Those 9 bytes are not a magic trailer.** They are the ordinary encoding of an
ordinary byte string. The file remains a valid CBOR sequence end to end, and
`cbor2` or `ciborium` reading it gets every data item plus one tagged value
they can ignore.

### The pointer verifies as well as locates

It is both where the index begins and how long the data section is. A reader
seeks there and checks for the `boring/index` frame; if it is not there the
index is stale —
the file was truncated, or appended to after sealing — and the reader scans
instead. Three checks guard against a file that merely happens to end in the
right shape: the tail must look like an 8-byte byte string, the pointer must be
in range, and the target must actually carry the `boring/index` name.

**The index is never load-bearing for correctness.** It is rebuildable by a
full scan, discardable at any time, and a missing or damaged one changes only
the speed. That is deliberate, and it is what the test suite pins.

### Slots are deltas, in the narrowest type that holds them

Offsets inside a container ascend, so consecutive differences are small and
nearly uniform while the absolutes are large and unbounded. Each slot therefore
goes out as differences from the previous entry, in the narrowest of a **byte
string**, **sint16** (tag 77) or **sint32** (tag 78), and the first difference
is measured from the container's own offset — 0 for the sequence node, whose
sentinel −1 is not a position.

The CBOR element type *is* the width declaration, so there is no per-entry flag
of the kind PostgreSQL needs for its `JEntry` array. Postgres reads offsets in
place out of a TOAST'd datum and pays a prefix sum per probe; boring
materialises the index once when it loads, expands there, and every lookup path
then reads a plain `int[]` exactly as it did before. Delta encoding costs
nothing at lookup time here.

A reader must therefore treat an int32 slot as deltas too — absolutes and deltas
are indistinguishable on the wire. That is why this landed before the format was
published rather than after.

### Stride is a parameter, not a constant

A lookup scans up to *N*-1 items, so stride trades size against seek. On 200 000
items of ~36.8 bytes (7.36 MB of data) — reproduce with
`clojure -M:bench -m nav index`:

| stride | anchors | slot type | index | overhead | µs/seek |
|---:|---:|---|---:|---:|---:|
| — | — | *no index* | — | — | **10 600** |
| 1 | 200 000 | byte string | 195.4 KB | 2.72% | 0.2 |
| 8 | 25 000 | sint16 | 48.9 KB | 0.68% | 0.6 |
| 16 | 12 500 | sint16 | 24.5 KB | 0.34% | 1–2.5 |
| 64 | 3 125 | sint16 | 6.2 KB | 0.09% | ~4.2 |
| 256 | 781 | sint16 | 1.6 KB | 0.02% | ~4.3 |

The first row is the same seek with no index: 10.6 **milliseconds**, because
reaching item 199 999 means skipping 199 999 items. That is the number the
whole feature exists to remove — roughly 18 000× at stride 8 — and against it
every other row is within a rounding error of the same answer.

Sizes are exact; **seek times are not**, and the last three rows are quoted
loosely on purpose. Run to run, stride 16 moved between 1.1 and 2.4 µs and
64/256 swapped order — the index competes for cache with the data, so a
smaller index sometimes wins back what a longer scan costs. Read the column
for its shape, not its digits.

Seek is separated from decode because only the seek is the index's doing:
materialising the item is a ~0.3–0.5 µs floor at any stride, which dominates at
stride 1 and disappears into the noise by stride 64.

**Narrowing breaks the proportionality.** Doubling the stride halves the anchor
count but can double the element width, so size falls in steps rather than
smoothly and there are bands where a denser index is nearly free. Absolute
int32 offsets would have cost 781 KB at stride 1 (10.9% of this file); deltas
cost 195 KB, and that 4× is what makes a stride-1 index — one with no scan
component at all — a defensible choice rather than a curiosity.

The sweet spot still moves with item size — the scan cost is per item, the index
cost per entry — so there is no default worth baking in.

### Reaching into an item

The offsets above locate top-level items. The same trailing item also carries a
node per **container**, which is what reaches inside one — see "part two"
below. That was written as future work in an earlier draft of this document and
has since shipped; the sequence offsets are simply the node at the sentinel
offset −1, so both live in one uniform list.


---

## `boring/index`, part two: container nodes

The sequence index above reaches item *n*. The same item also carries a node
per **container**, which reaches *into* an item.

A node is the byte offsets of a container's entries. With them, an array
indexes positionally in O(1) and a map with sorted keys binary-searches in
O(log n), comparing **encoded key bytes** — no key is ever decoded and no value
is ever touched.

```
tag 27 [ "boring/index",
         [ stride, containers, counts, slots, sorted, <8-byte data-len> ] ]
```

### A name, not a number

The index is a **tag-27 name**, not a tag of its own. It began as tag 39651 and
was moved once the cost was measured: the index appears exactly **once per
file**, so a name costs 14 bytes — 0.05% of a 28 KB file. Against that it
removes a registration obligation entirely, is self-describing (`cbor2` sees
the string rather than an unregistered number), and narrows false-positive
detection, since a stray file must now end in the right shape **and** point at
tag 27 **and** carry this exact name.

**Tag 39649 keeps its own number for the opposite reason**, and this is the
rationale that was missing from this document. Shaped arrays occur **per
array**, not once per file, and `:shapes` exists precisely to shrink documents:

| payload | shaped arrays | cost as a tag-27 name |
|---|---:|---:|
| datom-maps-200 | 1 | +0.4% |
| 100 small tables | 100 | **+19.6%** |
| nested tables ×500 | 500 | **+35.2%** |

Paying up to a third of the file back would defeat the feature on exactly the
documents it exists for. A 3-byte tag is the right trade there; a 17-byte name
is not. One number to register, not two.

`containers` are sorted byte offsets, so a reader binary-searches them; the
sequence itself is the node at the sentinel offset −1. `sorted` is recorded per
node rather than inferred, because the encoding profile is not on the wire.

### Sorted keys are what make it systematic

Binary search needs ordering, so it requires `:canonical` or `:archival` — the
profiles that sort map keys by encoded bytes, and which you would already be
using for storage. Without them a lookup still jumps anchor to anchor rather
than entry to entry, but it is O(n/stride), not O(log n).

**Arrays need no profile.** Element *i* is the *i*-th recorded offset, so an
indexed array is O(1) under any encoding.

### Two knobs, and which one matters

| | | |
|---|---|---|
| `:index` | stride | entries per recorded offset |
| `:index-min` | threshold | smallest container worth a node |

**`:index-min` dominates.** Real data is mostly small containers, and each one
costs an offset, a count, a typed-array slot and a flag whether or not it is
worth searching. On 2 000 records of two fields each, indexing every container
cost **76%** of the file; indexing only containers of 8+ cost **1.3%** — and
was *faster*, because the smaller index also fits in cache.

Measured, one container, lookups spread across it:

| keys | overhead | scan | indexed |
|---:|---:|---:|---:|
| 100 | 3.4% | 4.23 µs | 2.09 µs |
| 1 000 | 1.4% | 26.1 µs | 0.79 µs |
| 10 000 | 1.2% | 175.1 µs | **0.74 µs** |

### Where it loses

An index can be **slower**. Finding the node is itself a binary search over the
container list, once per level. When a container is narrow *and* its entries are
cheap to skip, walking it costs less than looking up how to jump:

| nesting depth, 64-key levels | scan | indexed |
|---:|---:|---:|
| 4 | 0.395 µs | 0.856 µs |
| 64 | 4.025 µs | **8.168 µs** |

Raise `:index-min` above the width of such containers and they fall back to
walking. This is the case where the default is wrong and the knob is the fix,
which is why both are parameters and neither is implicit.

### Building it

The index is derived by **walking encoded bytes**, not by hooking the writer —
so the writer's hot path is untouched and any already-encoded value can be
indexed after the fact, including one somebody else wrote. `boring/build-index`
does that; `boring/encode-indexed` encodes and seals in one step, producing a
two-item sequence that `decode` still reads as the value.

The walk returns each value's **end offset** rather than calling `skipFrom` per
entry. That matters: `skipFrom` is O(subtree), so an entry-at-a-time walk
re-walks everything beneath each level and is O(n²) in depth — measured at
486 ns/byte against a skip's 1.3–2, and 27× slower on a 200-deep document. A
single descent visits each byte once and the node falls out of it. Building an
index costs 1.1–1.4× a plain encode.

Tags are **descended through**, not stepped over. A set is tag 258 around an
array, a record tag 27 around `[name, map]`, a shaped array tag 39649 around
`[keys, rows]` — skipping tags would leave exactly those uncovered, and
descending is free because `skipFrom` walks the same bytes anyway.
