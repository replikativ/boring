# The offset index

`boring/index` is an optional frame sealed onto a document or a sequence. It
records where things are, so a reader can jump instead of walk.

**It is an optimisation and nothing else.** Every answer is identical with or
without it; a missing, truncated or stale index falls back to scanning. That
property is what the rest of this page is organised around — the format, how a
reader finds it, how navigation turns it into a jump, and where it does not
pay.

This material used to live in `SHAPES.md`, which is about the shaped-array
encoding and was a poor home for it. The two are independent features that
happen to have been designed in the same week.

---

## The layout

A sealed document is a CBOR **sequence** (RFC 8742) of two or more top-level
items: the data, then the frame, then a nine-byte trailer.

```
<the data item>                       untouched, exactly what `encode` writes
d8 1b  82                             tag 27, array(2)
   6c 626f72696e672f696e646578          "boring/index"
   87 ...                               the payload
48 <8 bytes>                          data-end, as a back-pointer
```

Tag 27 is CBOR's registered "serialised language-independent object with type
name and constructor arguments", so the frame is `[name, payload]` and the name
is a **string**, not a number of its own. Only one tag number is claimed by this
library, and it is not this one.

### The payload

**Six or seven elements.** Six without a stringref namespace, seven with one:

```
[stride, containers, counts, slots, sorted, data-end]                six
[stride, containers, counts, slots, sorted, stringrefs, data-end]    seven
```

| element | what it is |
|---|---|
| `stride` | the file-level anchor spacing: one anchor per `stride` entries |
| `containers` | the offset of each indexed container, ascending — the search key |
| `counts` | how many entries each of those containers has |
| `slots` | the anchors themselves, packed as **deltas** (see below) |
| `sorted` | one bit per node: are this container's keys in canonical byte order |
| `stringrefs` | present only with a namespace: `(reference index → defining offset)` |
| `data-end` | where the data section ends and this frame begins |

**`data-end` is always last, whatever the width**, and that is what lets a
reader handle both without a version field: take the element count off the
payload's array head, and it tells you whether element 5 is `data-end` or the
stringref table. Readers accept **six through fifteen**, so a later widening is
*recognised* rather than mistaken for data — which matters more than it sounds,
because a reader that fails to recognise a frame never learns where the data
ends and republishes the frame as a trailing data item.

### Why it is at the end

Offsets are only known once the items are written, so a leading index would
mean buffering the whole document before emitting a byte — fatal for a log.
ZIP's central directory and Parquet's footer sit at the end for the same
reason. It also means a foreign reader sees every real item first and the
metadata last, so one that stops early never encounters it.

### How it is found, without leaving CBOR

CBOR cannot be parsed backwards. So the last element is a byte string of
exactly eight bytes, which always encodes as `0x48` followed by eight — a
sealed file ends in **nine predictable bytes** however large the payload is.
Read them, take the pointer, seek there.

**Those nine bytes are not a magic trailer.** They are the ordinary encoding of
an ordinary byte string. The file stays a valid CBOR sequence end to end, and
`cbor2` or `ciborium` reading it get every data item plus one tagged value they
can ignore.

Finding it costs **75 ns** — measured: a nine-byte read and a pointer decode.
There is nothing to optimise there, and it is worth saying because the phrase
"seek to the end and look for the frame" sounds like a search and is not.

### The pointer verifies as well as locates

It is both where the frame begins and how long the data section is. A reader
seeks there and checks for the `boring/index` name; if it is not there the
index is stale — truncated, or appended to after sealing — and the reader
scans. Four checks guard against a file that merely happens to end in the right
shape:

1. the tail must have the shape of an eight-byte byte string;
2. the pointer must be in range;
3. the target must carry the seventeen-byte `tag 27 / array(2) /
   "boring/index"` prefix;
4. **the frame must end exactly at the file's end.**

The fourth is not paranoia. `write-seq!` counts from zero, so its back-pointer
is chunk-relative: concatenate two sealed sequences of the same data length and
the trailing pointer lands squarely on the *first* batch's frame — a genuine
`boring/index`, so every other check passes. `nav/items` then stopped at the
first chunk's `data-end` and reported 100 of 200 items, silently.

---

## How navigation uses it

This is the reader side, and it is the half that was never written down.

### Opening

`nav/source` does **not** parse the frame. Detection and parsing are deferred
to the first operation that consults the index, because a document nobody
navigates into should cost nothing to open. Measured on a 1000-row segment,
a cold point read decomposes as:

| step | ns |
|---|---:|
| `Reader` + `Nav` construction | 542 |
| locate the frame (trailer + pointer) | 75 |
| parse the payload | ~450 |
| the read itself | 354 |

All of that except the last line is **per source, not per lookup**, so it
amortises: 1583 ns for one read, **373 ns** at a hundred reads on the same
source. Open a source once and hold it.

### Finding the node for a container

Everything below starts from an offset — "I am standing on a container at byte
`off`, does the index know anything about it?"

`containers` is ascending, so this is a **binary search**: O(log C) where C is
the number of indexed containers. A container that was not indexed is simply
not found, and the caller walks. That is what lets the index be **sparse** —
there is no requirement to index everything, and most things are not indexed.

### Turning a node into a jump

Once a node is found, `slots` gives its anchors: the offset of entry 0, entry
`stride`, entry `2·stride`, and so on.

**Arrays** need nothing else. Element *i* lives at anchor `⌊i/stride⌋`, then at
most `stride−1` skips forward. `nth` is therefore O(1) to the anchor and O(stride)
after it, under any profile — position is position.

**Maps depend on key order**, and this is where the two paths diverge:

- **Sorted** (`:canonical`, `:archival`) — the node's `sorted` bit is set, and
  the anchors are in canonical byte order. So the lookup is a **binary search
  over the anchors comparing encoded key bytes**, then a walk of at most
  `stride−1` entries within one span. O(log n). Keys are never decoded; the
  probe is the encoded key and the comparison is a `memcmp`.

- **Unsorted, stride 1** — every entry has an anchor, so moving from one to the
  next **jumps over the value** instead of walking it. Measured 2.72× on a
  20-key map of 1000-int vectors.

- **Unsorted, stride > 1** — **refused outright, and the container is scanned.**
  Without key order you must try each anchor's span until the key turns up,
  which visits every entry — exactly what one scan from the head does, plus a
  jump and a call per anchor. Measured on a map of 2000 scalars: 16.81 µs
  indexed against 11.29 scanning, i.e. the anchors cost 50% and bought nothing.

That last rule is why the README's index example passes `:archival`. A hash map
under the default profile gets no usable node, and the lookup scans.

### Per-node stride

`stride` in the payload is the file's, and a node may have been written at a
different one. It is **derived, not stored**: if a node's anchor count equals
its entry count, every entry has an anchor and the stride is 1; otherwise it is
the file's. One comparison, no extra bytes.

### Misses are re-derived

A lookup that finds nothing does **not** trust that answer. `confirm` re-walks
the container honestly before reporting absent.

This looks like a damage check and is not. A node marked `sorted` over a map
ordered by something *other* than canonical CBOR bytes — a Clojure `sorted-map`
is ordered by `compare` — leaves the binary search comparing with a function
the array is not sorted by. Re-deriving negatives is what keeps it correct.
Deleting it as a redundant optimisation breaks lookups on undamaged, correctly
written data.

### Resolving a stringref through the index

A stringref reference `25(n)` means "the n-th string defined so far in this
namespace". Resolving it normally requires having decoded everything before it
— which a cursor holding only an offset has not done.

So the seventh payload element maps each **referenced** index to the offset
where that string was *defined*. A cursor resolves a reference by jumping
there and reading the literal. Only referenced entries are stored, so the table
is proportional to the references that exist rather than to the namespace.

**This is why a stringref document needs an index to be navigable at all**, and
why `{:trust-index :ignore}` refuses one: ignoring the index means ignoring the
only thing that can resolve a reference. `boring/decode` is unaffected — it
decodes in order and builds the table itself, consulting no frame.

### The offset layer

Everything above is reachable without allocating a cursor:

```clojure
(let [s   (nav/source bs nil)
      off (nav/root-offset s)]                  ; past a stringref envelope
  (nav/field-offset s off :profile)             ; -> offset, or -1 absent, -2 not-a-map
  (nav/nth-offset s off 3)                      ; -> offset, or -1 / -2
  (nav/container-count s off)                   ; -> long, throws on a non-container
  (nav/value-at s off)                          ; -> the value
  (nav/long-at s off)                           ; -> a primitive long, no boxing
  (nav/reduce-at s off f init))                 ; -> fold over child offsets
```

`-1` means **absent**. `-2` means **there, but no offset names it** — a tag, or
a row of a shaped array whose bytes are an array while its value is a map. Both
are negative, so a caller that checks `neg?` gets not-found either way;
`value-at` refuses both rather than reading the head of the document.

`container-count` **throws** `:boring/not-a-container` instead of joining that
convention, because a count has no spare value: every negative long is a
plausible count to arithmetic downstream.

---

## The trust boundary

**A reader that uses the index trusts what it says.** Nothing re-derives, at
read time, whether its offsets are the right ones.

That is a narrowing and it was deliberate. The reader used to verify each
anchor against its predecessor on first use, which costs O(stride) *skips* per
jump — and a skip is O(1) only for a scalar, so stepping over sixteen
twenty-entry maps is ~640 sub-skips, measured at four times the cost of the
lookup it guarded. Corruption beneath us is the storage layer's job.

**What is still promised, and is not negotiable:**

- **No untyped exception, ever**, from any damage to any byte. A wrong answer
  is inside the boundary; an `ArrayIndexOutOfBoundsException` out of `get` is
  not.
- **No read outside the document.** Bounds are not a matter of trust, because
  `Reader.skipFrom` does an unchecked array access. So the O(1) frame-structure
  checks stay, and so do two explicit segment bounds: a node's delta run must
  lie inside the packed `slots`, and its anchors must lie inside the data
  section.
- **A reader that consults no index is never affected by frame damage.**
- **Undamaged data always reads correctly** — see "Misses are re-derived".

On an untrusted document the index is attacker-chosen, and since it now also
carries the stringref pointer table, a chosen index can redirect what a *string*
resolves to as well as where a lookup lands. Both stay inside the document.
[SECURITY.md](SECURITY.md) has the threat model and the safe postures.
### Slots are deltas, in the narrowest type that holds them

Offsets inside a container ascend, so consecutive differences are small and
nearly uniform while the absolutes are large and unbounded. Each slot therefore
goes out as differences from the previous entry, in the narrowest of a **byte
string**, **sint16** (tag 77) or **sint32** (tag 78), and the first difference
is measured from the container's own offset — 0 for the sequence node, whose
sentinel −1 is not a position.

The CBOR element type *is* the width declaration, so there is no per-entry flag
of the kind PostgreSQL needs for its `JEntry` array. Postgres reads offsets in
place out of a TOAST'd datum and pays a prefix sum per probe; boring now does
the same — nothing is materialised at open, and a lookup expands one node's
deltas, which at the default stride is two of them. The comparison used to run
the other way, when the index was expanded into `int[]` at load.

A reader must therefore treat an int32 slot as deltas too — absolutes and deltas
are indistinguishable on the wire. That is why this landed before the format was
published rather than after.

### Stride is a parameter, not a constant

A lookup scans up to *N*-1 items, so stride trades size against seek. On 200 000
items of ~36.8 bytes (7.36 MB of data) — reproduce with
`clojure -M:bench -m nav index`:

| stride | anchors | slot width | index | overhead | µs/seek |
|---:|---:|---|---:|---:|---:|
| — | — | *no index* | — | — | **~12 000** |
| 1 | 200 000 | u8 | 195.4 KB | 2.72% | 0.09 |
| 8 | 25 000 | u16 | 48.9 KB | 0.68% | 0.8 |
| 16 | 12 500 | u16 | 24.5 KB | 0.34% | 1.1 |
| 64 | 3 125 | u16 | 6.2 KB | 0.09% | ~4.7 |
| 256 | 781 | u16 | 1.6 KB | 0.02% | ~4.7 |

THIS TABLE IS THE ONE HOME for these numbers. Every other mention in this
repository points here rather than restating them; where a figure appears
without a harness behind it, it is wrong by default. See #41.

The **slot width** column says which of `pack-slots`'s four per-node widths the
sequence node got — 2 bits of a code inside one packed byte string, not a CBOR
element type. It used to read "byte string" at stride 1 and "sint16" above,
which was the pre-#17 layout of one typed array per node; the harness that
produced it had itself been broken since that change, reading `(nth slots 0)`
of what is now a `byte[]` and falling through to a constant. So the table and
its own harness had drifted apart in two DIFFERENT directions, which is the
argument for a single home rather than for more careful copying.

The first row is the same seek with no index: about 12 **milliseconds**,
because reaching item 199 999 means skipping 199 999 items. That is the number
the whole feature exists to remove — roughly four orders of magnitude at
stride 8 — and against it every other row is within a rounding error of the
same answer. It is also the noisiest figure here, moving between 11.8 and 16.0
ms across two runs on the same machine, so read its magnitude and not its
digits.

Sizes are exact and reproduce byte for byte; **seek times are not**, and the
last three rows are quoted loosely on purpose. Run to run, stride 16 moved
between 1.0 and 2.4 µs and 64/256 swapped order — the index competes for cache with the data, so a
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
using for storage.

**Without them, an unsorted map above stride 1 is not accelerated at all** —
the reader refuses the node and scans. This paragraph used to say the lookup
still jumped anchor to anchor at O(n/stride); it does not, and the reason is in
"Turning a node into a jump" above: trying each anchor's span until the key
turns up visits every entry, so it is a scan with extra jumps. At **stride 1**
it inverts and the index is the whole point, because moving anchor to anchor
skips over each value.

**Arrays need no profile.** Element *i* is the *i*-th recorded offset, so an
indexed array is O(1) under any encoding.

### Two knobs, and which one matters

| | | |
|---|---|---|
| `:index` | stride | entries per recorded offset |
| `:index-min` | threshold | smallest container worth a node |

**`:index-min` is a floor, not a tuning knob, and it no longer dominates.**
Real data is mostly small containers, and each one costs an offset, a count, a
slot run and a flag whether or not it is worth searching — so something has to
exclude them. That job moved. `keep-node?` now requires `walk >= 64` for an
array or sorted map, and refuses unsorted maps above stride 1 outright, and
those rules bite first on every realistic shape.

Measured on 2 000 records of two fields each (`:archival`, 38 613 B), varying
only `:index-min`:

| `:index-min` | 1 | 2 | 8 | 16 |
|---|---:|---:|---:|---:|
| nodes | 1 | 1 | 1 | 1 |
| index size | 0.78% | 0.78% | 0.78% | 0.78% |

It makes no difference at all. An earlier version of this section reported
**76%** of the file at `:index-min` 1 against **1.3%** at 8, and that was true
when the threshold was the only gate; it is not reproducible now. Reach for the
profile (`:archival` sorts keys, which is what makes a map's node usable) before
reaching for this.

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

### Two builders, and why

**`write-seq!` captures the index as it encodes.** The writer already knows
`pos` — the offset it is about to write to — and knows a container's entry
count before emitting a byte of it, so the nodes fall out of encoding. Nothing
in the index needs a subtree's *length*, only where its entries start, so there
is nothing to back-patch. In a length-prefixed format this would need a second
pass; CBOR's element counts make the write side free and the read side
expensive, and this takes the good half of that trade.

On 50 000 records through a `BufferedOutputStream`, against the same write with
no index — reproduce with `clojure -M:bench -m nav write`:

| stride | walked | captured |
|---:|---:|---:|
| 16 | +80% | **+3%** |
| 8 | +85% | **+5%** |
| 1 | +113% | +30% |

Stride 1's remainder is not the capture — it is delta-encoding and emitting a
50 000-entry slot at the end, which the walk paid too.

**`build-index` walks encoded bytes instead**, and stays, because it is the only
way to index a value that is already encoded — re-indexing after a compaction,
or indexing a file somebody else wrote. It is also the reference implementation
the captured index is tested against, generatively, in
`boring.writer-index-test`.

The walk returns each value's **end offset** rather than calling `skipFrom` per
entry. That matters: `skipFrom` is O(subtree), so an entry-at-a-time walk
re-walks everything beneath each level and is O(n²) in depth — measured at
486 ns/byte against a skip's 1.3–2, and 27× slower on a 200-deep document. A
single descent visits each byte once and the node falls out of it. Even so it
cannot beat ~31% of encode time, because stepping over a subtree *is* walking
it. That floor is what capturing avoids rather than optimises.

### The two builders differ, deliberately

The walk indexes containers **on the wire**, and boring puts several there that
no user wrote: a sorted-map is tag 27 around a two-element `[name, map]`, a
shaped array is `[keys, rows]`. On the wire those are indistinguishable from a
user's own two-element vector, so the walk indexes them; the writer knows the
difference and skips them.

That is a **subset, not a disagreement**, and it is legitimate — the index is
never load-bearing, and a node for `[name, map]` is pure overhead. The pinned
contract:

- every captured node is byte-identical to one the walk found — always;
- the two agree completely once `:index-min` excludes frames.

Every *structural* frame boring emits — a sorted map's `[name, map]`, a shaped
array's `[keys, rows]` — has 3 entries or fewer. The `boring/index` frame's own
payload has **6 or 7**, and a re-index over an already-sealed file — which
`doc/STORAGE.md` presents as a real operation — walks that frame like any other
container.

An earlier version of this paragraph concluded that "7 is the boundary" and
that `:index-min` 16 cleared it. Both halves are wrong now. The payload can be
7 elements, so the arithmetic would give 8 — and more to the point **it is no
longer `:index-min` that decides.** Measured over a sealed file at `:index-min`
4, 6, 7, 8 and 16, the frame payload gets a node at **none** of them, because
`keep-node?` additionally requires `walk >= 64` for an array. The frame is not
excluded by a threshold on its width; it is excluded by being small.

Tags are **descended through**, not stepped over. A set is tag 258 around an
array, a record tag 27 around `[name, map]`, a shaped array tag 39649 around
`[keys, rows]` — skipping tags would leave exactly those uncovered, and
descending is free because `skipFrom` walks the same bytes anyway.
