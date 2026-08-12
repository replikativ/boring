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

Under **deflate** — which is what `permessage-deflate` uses over a WebSocket —
the four profiles land within 4% of each other (plain 1 404, stringref 1 388,
shapes 1 399, shapes+stringref 1 342), and shapes+stringref is marginally the
smallest. So the inversion above is a zstd result, not a general one, and one
profile can serve both storage and a deflating wire.

### Ragged rows: the key set is a UNION

The rule above — "all maps sharing one key set" — was once literal, and the
writer declined the moment any row differed. That made shapes all-or-nothing:
one ragged row in a thousand cost the whole table its density, and data
arriving at a system boundary is exactly the ragged case (optional fields,
schema drift, mixed event types).

The keys are now the **union** of every row's, in first-seen order, and a row
that lacks one says so in one of two ways:

```
value   [{:a nil :b 1} {:b 2}]

shaped  d9 9ae1               tag 39649
          82
            82                  keys :a :b
              d827 62 3a61
              d827 62 3a62
            82                  rows
              82 f6 01            :a PRESENT with value null
              82 f7 02            :a ABSENT
```

- **`undefined`** (simple value 23, `0xf7`) in a value position means that key
  is absent from this map.
- **A short row** means every key past its length is absent. Trailing
  absences are truncated rather than padded, so a row that stops early costs
  nothing for the keys it never reaches.

`null` (`0xf6`) is untouched and still means a key that is **present** with a
nil value. `{:a nil}` and `{}` are different maps and stay different bytes —
that distinction is the whole reason absence needed its own spelling rather
than reusing null.

A row that carries `undefined` as an actual value cannot be shaped, since the
two would be indistinguishable coming back; the writer declines such a table.

This is [draft-ietf-cbor-packed][packed]'s idea — its tag-114 `record` function
uses `undefined` for the same purpose. Only the semantics are borrowed, not the
packing framework, and not its tag numbers, which are unassigned. See
[IANA-REGISTRATION.md](IANA-REGISTRATION.md).

### The density bound

The union has a pathological case: **disjoint key sets**. Keys are numbered in
first-seen order, so a row introducing all-new keys sits at high positions and
must be padded past everything before it. The padding is then O(rows²) and
shaping makes the document dramatically *bigger*. Measured, 200 rows of 5 keys
each, shaped size ÷ plain size:

| distinct key sets | 1 | 5 | 10 | 20 | 40 | 200 |
|---|---:|---:|---:|---:|---:|---:|
| ratio | 0.21 | 0.43 | 0.71 | **1.19** | 2.17 | **9.65** |

So the writer measures rather than hopes. Hoisting the keys saves writing
`total-entries − union` key occurrences; the padding costs one byte per empty
slot. Both are accumulated during the pass the shape needs anyway, and shaping
is declined when the padding would exceed the saving — which puts break-even
between 10 and 20 distinct key sets, with no tuned constant.

The guard's guarantee is narrow and worth stating exactly: **shaped output is
never larger than the same value written without shapes.**

[packed]: https://datatracker.ietf.org/doc/draft-ietf-cbor-packed/

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
   error (`:boring/bad-tag-content`, from the tag-39649 reader) — never a null
   or a silently empty map. An earlier draft named a `:boring/bad-shape-ref`
   type that was never implemented; the reader raises `:boring/bad-tag-content`
   for a malformed shaped array, which is the same guarantee under the name the
   code actually throws.
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

## The offset index lives in its own document now

`boring/index` — the frame that records where things are so a reader can jump
instead of walk — used to be documented here, below the shaped-array material,
because the two were designed in the same week. They are independent features
and that was a poor home for it.

**See [INDEX.md](INDEX.md)**, which carries the frame layout, how a reader finds
it without parsing CBOR backwards, **how `boring.nav` turns a node into a jump**,
the stride and threshold economics, and the trust boundary.
