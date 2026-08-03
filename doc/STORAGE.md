# Storage

Using boring as a storage format: reading without decoding, memory-mapped
files, writing logs, compression, and what can be updated in place.

Numbers here are ratios rather than absolutes — reproduce them with
`clojure -M:bench -m nav` and `clojure -M:bench -m mmap`. `doc/PERFORMANCE.md`
has the methodology and the machine caveats.

## What this does and does not claim

None of this changes CBOR. Every win below comes from **not decoding**, which
any CBOR reader could do; what boring adds is the API for it.

CBOR's own design decides how far that goes, and it cuts both ways. Byte
strings and text strings are **length-prefixed**, so stepping over one is a
jump whose cost does not scale with its size — that is where the three-orders-
of-magnitude numbers come from. But arrays and maps carry an **element count,
not a byte length**, so reaching the fifth key of a map means stepping over
four values. Seeking is a *scan*, not a jump.

So: a format designed for random access — FlatBuffers, Cap'n Proto — has offset
tables and gives O(1) field access with true zero-copy on *structure*. boring
gives zero-copy on *payloads* and lazy scanning on structure, while staying
ordinary CBOR that `cbor2` and `ciborium` still read. That is a real capability
and a real limit, and it is worth knowing which one you are relying on.

## Reading without decoding

`boring.nav` walks the wire format and materialises only what you ask for.
`get` returns a **cursor**, not a value — that is what stops intermediate maps
from being built — so `nav/value` at the end:

```clojure
(require '[boring.nav :as nav])

(def c (nav/source bs {:stringref false}))
(nav/value (get-in c ["customer-137" "name"]))
```

`get-in` works because a cursor implements `ILookup`. Also implemented:
`nth` (O(n) — see the scan/jump distinction above), `count` (O(1), the element
count is in the head), `seq`, `reduce`, and a read-only `clojure.zip` zipper
via `nav/zipper`. Not `IDeref`: `@` would read as a cheap field access while
doing arbitrary decode work.

A log is a CBOR sequence, walked item by item:

```clojure
(transduce (comp (filter #(= "error" (nav/value (get % "lvl"))))
                 (map #(nav/value (get % "n"))))
           conj [] (nav/items bs opts))
```

`reduce` honours `reduced`, so an early-exit query does not pay for the tail.

### What it is worth

Against decode-then-`get-in`, on 68 KB of records:

| | ratio |
|---|---:|
| `get-in` one leaf | **21×** |
| `count` a container | **~1400×** |
| locate a 1 MiB blob rather than materialise it | **~290×** |
| reduce over every record, one field each | 2.2× |

`count` is O(1) because the number is in the head. The reduce row is only 2.2×
because it visits everything — the win there is the fields it never builds.

### When not to use it

Taking only the **first** item of a log is *slower* than `decode-seq`
(3.9 µs against 2.2 µs). `decode-seq` is already lazy, so it decodes just that
item too, and for one small item a cursor plus a key probe costs more than
decoding it.

Navigation wins by what it **skips**. If you are going to touch nearly
everything in a small value, `decode` is the cheaper call.

### Constraints, enforced rather than documented

- **`:stringref false` is required.** A stringref is an index into a table
  built from every preceding string, so a cursor holding only an offset cannot
  resolve one. Navigating a stringref document is refused, not silently wrong.
  boring writes stringref *by default*, so this is a decision at write time.
- **Indefinite-length containers cannot be descended.** Their count is not on
  the wire, so `count` could not be O(1) and `Counted` would be lying. boring
  never emits them; only a foreign streaming encoder will.
- **Tags are opaque.** `get` on a tagged value realises it through the ordinary
  reader and continues with `clojure.core/get`. A tag's reader is an arbitrary
  function, so structure does not imply semantics — the slow path *is* the
  reference implementation, which is what makes the fast path safe to trust.
  A consequence: positional records (a tag 27 wrapping a vector, as datahike's
  `Datom` uses) can be reached but not descended into.

## Memory-mapped files

`boring.mmap` needs **JDK 22+** for `java.lang.foreign`. Everything else,
including `boring.nav`, runs on JDK 9.

```clojure
(require '[boring.mmap :as mmap])

(mmap/with-mmap [c "events.cbor"]
  (nav/value (get-in c ["customer-137" "name"])))
```

That is the shape for a file holding **one** value. A file holding a *sequence*
— a log — wants `mmap-items`, which returns what `nav/items` does:

```clojure
(let [[items arena] (mmap/mmap-items "log.cbor" {:stringref false})]
  (with-open [a arena]
    (nav/value (nth items 199999))))
```

If the sequence was sealed with `:index N`, `nth` uses it here exactly as on the
heap — which is the pairing the two features exist for: seek into a large file
without faulting in the pages you skipped over.

Pages you never probe are never faulted in, so a lookup costs what the *item*
costs, not what the file costs. Random selective decode over a mapping beats
one `pread` per item by **2.3×**.

Three things to know:

- **Lifetime.** The mapping is owned by an `Arena`; closing it invalidates
  every cursor derived from it, and access afterwards **throws** rather than
  reading freed memory. That is the property `MappedByteBuffer` never had, and
  the reason the arena owns it. Do not let a cursor escape `with-mmap`.
- **Off-heap decode costs ~1.35× heap decode** — per-access bounds and liveness
  checks. So to realise a *whole* subtree from a mapping, stage its byte span
  into a scratch array and decode through the array path rather than in place.
  `nav/byte-span` and `nav/raw-bytes` give you the extent.
- **Prefer a shared or global arena** over a confined one: 1.35× against 1.46×,
  because a confined arena adds a thread check to every access.

## Writing

**mmap does not help.** Appending 200 000 items: `BufferedOutputStream`
130 ms, mmap 171 ms, encode-only floor 105 ms. A mapping faults per 4 KiB page
while `write(2)` hands the kernel one prepared buffer. The floor matters more
than the ranking — **I/O is 19% of the job** — so there is not much to win by
improving the write path at all.

The shape to use:

```clojure
(let [w (boring/writer 65536 {:stringref false})]      ; opts on the WRITER
  (with-open [out (BufferedOutputStream. (FileOutputStream. f) 262144)]
    (doseq [e events] (boring/write-to! w e out))))    ; rotate by size
```

Three traps, in order of how much they cost:

1. **An unbuffered `FileOutputStream` is 2.9× slower.** `write-to!` writes
   straight to the stream it is handed; buffering is the caller's job.
2. **Put options on the writer, not on every call.** Resolving them per call
   allocates ~250 heap bytes each time. On the writer, a log event costs
   **0 bytes/event** through `write-to!` — the whole write path allocates
   nothing on the heap. This bites hardest exactly where it is least wanted,
   because a navigable file needs `:stringref false` and so cannot use the
   nil-opts fast path.
3. **Frame as a CBOR sequence** (RFC 8742 — just concatenated items, which is
   what `write-to!` in a loop produces). Each item is independently decodable,
   `decode-seq-from` streams in bounded memory, and a truncated tail loses only
   the last item.

Reaching item *n* costs *n* skips, so tailing and scanning are cheap while
seeking into the middle of a large file is not. If you need that, seal the
sequence with an index:

```clojure
(boring/write-seq! w events out {:stringref false :index 8})
```

That appends one extra CBOR item holding the offsets of every 8th item, and
`nav/items` then jumps rather than skips — on 200 000 records, reaching the last
one takes **10.6 ms** unindexed against **0.6 µs** indexed, for 0.68% of the
file. The
offsets are stored as deltas in the narrowest type that holds them, so even a
stride of 1 — no scan at all — costs 2.7% rather than the 10.9% absolute
offsets would. `doc/SHAPES.md` has the format and the full stride table.

The index is not load-bearing: a stale or missing one, or damage that leaves it
structurally inconsistent, is detected and falls back to scanning. That stops at
damage which leaves the payload *consistent* — including ordinary bit rot, which
returns a wrong answer about 2% of the time. Verifying every anchor would cost
the scan the index exists to avoid, so the index frame is a trust boundary and
wants a checksum if the medium is not trusted. `doc/SHAPES.md` has the detail. It does **not** survive appending, though
— re-seal rather than append to a sealed file.

## Compression

Compression and mmap'ed selective access pull against each other: mmap pages at
4 KiB, and a compressed block only decodes as a whole. **The chunk size is the
exchange rate.** zstd level 3, random single-item lookups:

| chunk | compressed | ratio | vs uncompressed lookup |
|---|---:|---:|---:|
| uncompressed | 15.4 MB | 1.00× | 1.0× |
| **4 KB** | **1.59 MB** | **9.7×** | **3.6×** |
| 64 KB | 1.22 MB | 12.7× | 37× |
| 256 KB | 1.21 MB | 12.8× | 135× |

Lookup cost scales with chunk size; **ratio saturates almost immediately**.
4 KB reaches 77% of whole-file ratio and aligns with the granularity mmap gives
you anyway.

This is the argument *against* filesystem compression here, not for it: btrfs
compresses 128 KiB extents and ZFS a 128 KiB recordsize, landing at the bottom
of that table with no knob to turn. It is the right choice if you scan most of
the file, and the wrong one if you read a few records out of a large one.

Compression also forecloses zero-copy — a chunk must be decompressed onto the
heap — so compression and the blob win are **alternatives for the same bytes**.
A common split is to leave the hot segment uncompressed and compress on
rotation, which works cleanly because chunk boundaries can fall on item
boundaries.

## Updating

One format fact makes this more tractable than it looks: **CBOR containers are
element-counted, not byte-length-counted.** An array header says "5 elements",
not "37 bytes", so changing a child's encoded size does not invalidate any
ancestor's header. Only absolute offsets after the edit shift. (Contrast
protobuf, where nested length prefixes cascade upward.)

Three tiers:

1. **Same-width in-place patch.** If the new value encodes to exactly the same
   byte count, overwrite it in a `READ_WRITE` mapping. Length-checked, so it
   cannot corrupt. Covers counters, flags, fixed-width timestamps, status
   enums, tombstone bits.
2. **Append-only diff.** Write a CBOR item describing the change and fold on
   read. This is a data-modelling problem, not a codec one — sequences already
   support it.
3. **Copy-on-write rewrite.** Navigate the old document, stream a new one, and
   copy unchanged subtrees **verbatim** rather than decode-then-re-encode them.
   `nav/byte-span` gives the extents.

**Variable-length in-place updates are not feasible** and should not be
attempted. Everything real is append or copy-on-write — which for a database is
fine, since tier 2 plus periodic tier 3 is just LSM shape.

Note that tiers 1 and 3 need a writer primitive that emits pre-encoded bytes
verbatim, which does not exist yet. Until it does, a rewrite has to re-encode
the subtrees it copies.
