# The offset index

Boring's offset index helps a JVM reader locate selected values without
walking all the CBOR items before them. Use it for repeated field lookups,
large nested values, or random access to a sequence. Full decoding does not
use the index.

The frame is optional for stringref-off data. For a document containing string
references, navigation needs the frame's string-pointer table. This includes
memory-mapped reads. Editing has stricter requirements; see
[Editing](EDITING.md).

## Read a field

```clojure
(require '[boring.core :as boring]
         '[boring.nav :as nav])

(def customers
  (into {} (for [i (range 200)]
             [(str "customer-" i) {"name" (str "name-" i)}])))

(def bs (boring/encode-indexed customers {:profile :archival}))
(def cursor (nav/root bs))

(nav/value (get-in cursor ["customer-137" "name"]))
;; => "name-137"
```

Here the archival profile sorts keys by encoded bytes, enabling binary search.
The default profile also supports navigation, including string references.
Unsorted maps can use a stride-1 node to skip values while testing keys.

Cursor `get-in` follows map keys. Use `nth` for an array position:
`(nav/value (nth (get cursor :scores) 1))`.
Construct the source or cursor once when making several lookups.

## Write and navigate a sequence

`write-seq!` writes a CBOR sequence and indexes it by default:

```clojure
(def events (vec (for [i (range 1000)] {:id i :event "opened"})))

(with-open [out (java.io.FileOutputStream. "events.cbor")]
  (boring/write-seq! (boring/writer 65536) events out))

(def items
  (nav/items (java.nio.file.Files/readAllBytes
               (.toPath (java.io.File. "events.cbor")))))

(nav/value (nth items 999))
;; => {:id 999, :event "opened"}
```

`write-seq!` disables stringref and rejects an explicit
`{:stringref true}`. Each top-level item would have its own namespace,
while the current index stores only one string-pointer table.

On JDK 22+, `boring.mmap/mmap-source` maps a document and
`boring.mmap/mmap-items` maps a sequence. Both return a view and its owning
arena; close the arena after use and do not retain cursors beyond it.
See [Storage](STORAGE.md).

## Configuration

| Option | Default | Meaning |
| --- | --- | --- |
| `:index` | 16 | File-level anchor stride; 0 disables indexing |
| `:index-min` | 4 | Minimum container entry count considered for a node |
| `:trust-index` | Normal indexed read path | `:ignore` disables index use; `:trusted` selects the normal path |

A container must also pass the builder's cost checks. The current
`keep-node?` implementation requires at least two anchors. Arrays and sorted
maps need enough structural work to skip; unsorted maps need enough work
per entry to justify recording every value boundary.

Unsorted maps use stride 1 even when the file stride is larger. This allows
the reader to skip each value without needing sorted keys.
Raising `:index-min` can remove useful nodes from small maps that wrap large
subtrees, so evaluate it on your actual documents.

For small documents, `encode-indexed` can choose a shorter, stringref-off
encoding without a frame. Requesting indexing does not guarantee a frame or
a node for every container.

## When it pays, in one table

CBOR arrays and maps record element counts, not subtree byte lengths. Skipping
a container requires walking its structure; skipping a byte string uses its
length. Index value therefore depends on the number of structural items
before the requested field, not just document size.

The following recorded `konserve-lmdb` crossover experiment reads one field
from each of 200 documents on ZFS with the CPU power profile held fixed:

| items before the field | walk | jump (`:index 16`) |
|---|---|---|
| 32 | 0.80 µs | 0.73 µs |
| 160 | 1.68 | 1.80 |
| 288 | 2.38 | 1.69 |
| 512 | 4.02 | 1.75 |
| 2048 | 15.36 | 1.94 |
| 8192 | **56.84** | **2.79** |

The index helped after a few hundred preceding items in this experiment.
That crossover depends on the source, workload, and machine. A field near the
front of a small document may be cheaper to find by scanning.

## The layout

A single indexed document is a two-item CBOR sequence: the data value, followed
by a tag-27 object named `boring/index`. An indexed log has its data items
followed by that same frame.

```text
<data item or items>
27(["boring/index", [stride, containers, counts, slots, sorted, data-end]])
```

The frame's last payload element, `data-end`, is an eight-byte byte string.
Its CBOR header plus content form the final nine bytes of the file. It is
part of the frame, not a third top-level trailer item.

A generic CBOR sequence reader sees the data and the tagged frame. Boring's
sequence readers recognise and omit the valid trailing frame. A foreign
application must choose how to handle it; not every decoder automatically
skips unknown tags or trailing items.

### The payload

The writer emits six fields without stringref, seven with it:

```text
[stride, containers, counts, slots, sorted, data-end]
[stride, containers, counts, slots, sorted, stringrefs, data-end]
```

| Field | Meaning |
| --- | --- |
| `stride` | File-level anchor spacing |
| `containers` | Ascending container offsets; −1 denotes the sequence node |
| `counts` | Entry count for each indexed container |
| `slots` | Packed per-node anchor deltas |
| `sorted` | Per-node bit indicating encoded-key order |
| `stringrefs` | Referenced string indices and their defining offsets |
| `data-end` | Offset where data ends and the frame begins |

`data-end` is always last. Frame recognition accepts payload widths from six
through fifteen, allowing a widened frame to be recognised as metadata even
when a reader cannot use a future layout.

### Finding and checking the frame

The reader inspects the final nine bytes, reads the back-pointer, and checks
the target. Detection requires:

1. A final eight-byte CBOR byte string.
2. An in-range pointer.
3. The tag-27 `boring/index` prefix and a recognised payload width.
4. A frame ending exactly at the file's end.

The last check prevents a pointer into an earlier concatenated batch from
hiding subsequent data. These checks establish frame structure; they do not
prove the correctness of every anchor.

### Slots are deltas, in the narrowest type that holds them

`slots` is one packed byte string:

```text
layout byte | 2-bit width code per node | dense start table | node delta runs
```

Each node uses unsigned 8-bit, unsigned 16-bit, signed 32-bit, or signed
64-bit little-endian deltas. The first delta is relative to the container
offset, or zero for the sequence node. Later deltas are relative to the
preceding anchor.

The layout byte identifies the packed layout and the start-table width.
The dense table contains each delta run's start plus a terminal entry.
Per-node widths allow a container with large gaps to coexist with compact
nodes. The current representation is not an array of CBOR typed arrays.

See `pack-slots` and `seal-index!` in
[boring.core](../src/boring/core.clj) for the precise layout.

## How navigation uses it

### Opening

Index detection and loading are generally deferred until navigation needs
them. Stringref documents require the pointer table during source setup.
Reuse the source to amortise that work.

### Finding the node for a container

The reader binary-searches `containers` for the current byte offset. If there
is no node, it scans the container.

### Turning a node into a jump

An array lookup selects the anchor at `floor(i / stride)`, then skips at
most `stride - 1` entries. A sorted-map lookup binary-searches encoded keys
at anchors before scanning within the selected span.

An unsorted map uses stride 1, testing keys while jumping over their values.
This is still a linear key search. The index helps when those values contain
substantial structure.

### Per-node stride

A node whose anchor count equals its entry count has stride 1; otherwise it
uses the file stride. Sortedness is determined from emitted key bytes, not
from the Clojure collection type.

### Misses are re-derived

Before returning an indexed map miss, the reader scans to confirm absence.
This catches some damaged-anchor cases. It does not validate positive answers
or make an attacker-supplied index trustworthy.

### Resolving a stringref through the index

A reference such as `25(n)` refers to a string registered earlier in a
namespace. The index maps referenced slots to their defining byte offsets, so
a cursor can resolve them without decoding the preceding data.

Keys may appear either as literals or as references. The reader can form both
probes and search in the encoded-byte order recorded by the node. Resolving
all keys to strings and sorting those strings would not preserve that order.

Ignoring or losing the pointer table makes a stringref document unnavigable.
`boring/decode` can still decode it sequentially without consulting the index.

### The offset layer

For callers avoiding cursor allocation:

```clojure
(let [s (nav/source bs nil)
      off (nav/root-offset s)]
  (nav/field-offset s off "customer-137")) ; offset, or a negative sentinel
```

| Function | Result and failure behaviour |
| --- | --- |
| `walk-from`, `field-offset`, `nth-offset` | Offset; −1 for absent, −2 when direct offset descent is unavailable |
| `container-count` | Count; throws for unsupported input |
| `long-at` | Primitive long; throws for unsupported input |
| `value-at` | Decoded value; throws for invalid access |
| `reduce-at`, `reduce-kv-at` | Accumulator; throws for invalid access |

A −2 result can mean a tag or a logical value whose structure differs from its
encoded representation, such as a shaped-array row. Check the sentinel before
passing an offset to a value reader; legitimate counts and values have no
reserved sentinel.

## The trust boundary

The indexed read path trusts anchor positions and string pointers after
structural and bounds checks. A damaged or malicious frame can redirect a
lookup within the document and return a wrong value. Confirming misses does
not prevent this.

Use `{:trust-index :ignore}` to scan stringref-off data without trusting its
index. For untrusted stringref documents, use full decoding, which builds its
own reference table. The current API has no `:validate` mode that verifies
every anchor before enabling indexed reads.

An absent or rejected frame can fall back to scanning only when the data is
navigable without it. Do not treat fallback as corruption detection.
See [Security](SECURITY.md) for input limits and remaining boundaries.

## Stride is a parameter, not a constant

This recorded sequence experiment uses 200,000 items and 7.36 MB of data.
Reproduce with `clojure -M:bench -m nav index`:

| stride | anchors | slot width | index | overhead | µs/seek |
|---:|---:|---|---:|---:|---:|
| — | — | *no index* | — | — | **~12 000** |
| 1 | 200 000 | u8 | 195.4 KB | 2.72% | 0.09 |
| 8 | 25 000 | u16 | 48.9 KB | 0.68% | 0.8 |
| 16 | 12 500 | u16 | 24.5 KB | 0.34% | 1.1 |
| 64 | 3 125 | u16 | 6.2 KB | 0.09% | ~4.7 |
| 256 | 781 | u16 | 1.6 KB | 0.02% | ~4.7 |

Seek time excludes materialising the selected item and varies across runs.
The unindexed seek was roughly 12–16 ms; stride 16 ranged around 1–2.4 µs.
Denser anchors reduce scanning but increase frame size and cache use.
Choose using the item sizes and lookup pattern you expect.

## Building it

JVM `write-indexed!` and `write-seq!` capture offsets while writing.
`build-index` walks bytes that already exist, useful for indexing previously
encoded data. ClojureScript also exposes `encode-indexed`,
`build-index`, and `seal-index!`, but not the navigation API.

Both builders descend supported tagged structures. They exclude tag-27
wrapper arrays from node placement while considering their contents.
Tests compare captured and walked indexes, including string references and
shaped arrays. See [writer-index tests](../test/boring/writer_index_test.clj).

When evaluating write overhead, measure the whole operation: offset capture,
packing, and frame emission all contribute. The harness
`clojure -M:bench -m nav write` compares captured and walked construction.
