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
seeking into the middle of a large file is not. **`write-seq!` therefore seals
the sequence with an index by default** (stride 16):

```clojure
(boring/write-seq! w events out)              ; indexed, stride 16
(boring/write-seq! w events out {:index 8})   ; finer, bigger
(boring/write-seq! w events out {:index 0})   ; off
```

`:index 0` is the off switch on `write-seq!` and `write-indexed!` — but **not**
on `encode-indexed` or `build-index`, where it raises `:boring/bad-option`. An
`encode-indexed` that does not index is just `encode`, and silently returning an
unindexed single item would change what the return value *is*. Call `encode`
there instead.

Four things follow from the default:

- **`:stringref false` is forced** whenever a stride is set, and passing
  `:stringref true` alongside `:index` throws rather than one silently winning.
  On a sequence this costs nothing — the stringref table resets per top-level
  item, so short records never amortise it and dropping it is a ~1.5% *saving*.
- **A sequence too small to benefit gets no frame at all.** `:index-min`
  (default 16) gates it, so 15 small items cost no more than they did before.
- **`decode-seq` hides the frame.** You get your items, not your items plus a
  trailing `#boring/index`. A foreign reader sees the extra item and can ignore
  it; only the final position is treated this way.
- **`encode` is untouched and stays untouched.** Appending to a single value
  would stop it being a single well-formed CBOR item. A sequence is already
  `application/cbor-seq`, where extra items are the point.

The frame is one extra CBOR item holding the offsets of every Nth item, and
`nav/items` then jumps rather than skips — on 200 000 records, reaching the last
one takes **10.6 ms** unindexed against **1–2 µs** indexed, for 0.34% of the
file. Those are the stride-16 numbers, which is what the paragraph above says
the default is; an earlier version of this sentence quoted the **stride-8** row
of `doc/SHAPES.md`'s table (0.6 µs, 0.68%) under the stride-16 heading, so it
promised a seek twice as fast at twice the cost. The
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

**ZFS and btrfs land at the bottom of that table by DEFAULT, but the knob
exists.** An earlier version of this section said there was none; that was
wrong. ZFS `recordsize` is settable per dataset and is exactly the chunk column
above:

```
zfs create -o recordsize=16K -o compression=lz4 rpool/blobs
```

It applies only to files written after it is set. At the 128 KiB default a cold
random read of a 200-byte record decompresses a whole 128 KiB record — roughly
600x read amplification, which is most of the cold latency measured below.
Turning it down trades ratio for amplification along the same curve.

Measured on a real 209 MB / 1 000 000-record file on ZFS with lz4 at the 128 KiB
default: **208 901 831 bytes apparent, 19 997 696 on disk — 10.45x**, with mmap
and the index working untouched. So filesystem compression is the right answer
when you control the filesystem, and the qualifier matters: ZFS or btrfs on
Linux only. macOS APFS has no per-dataset switch, NTFS compression is LZNT1 and
poor under random access, and a stock VPS is usually ext4 with none at all.
There the same file occupies its full 209 MB and everything else still works.

Compression also forecloses zero-copy — a chunk must be decompressed onto the
heap — so compression and the blob win are **alternatives for the same bytes**.
A common split is to leave the hot segment uncompressed and compress on
rotation, which works cleanly because chunk boundaries can fall on item
boundaries.

## At scale

Numbers from a 209 MB, 1 000 000-record file, ZFS/lz4, JDK 25, warm ARC unless
stated. Extrapolations to a terabyte assume ~1000 files of 1 GB.

| | |
|---|---:|
| index open (expand deltas to absolutes) — warm | 10–15 ms / 62 500 anchors |
| index open — first call in a process | 137 ms (JIT, not I/O) |
| `nav/fork` — reuse an already-expanded index | 45 µs |
| random record read, warm | 10.8 µs |
| random record read, cold | 54–242 µs |

Reading a small field out of every file in a terabyte therefore costs roughly a
**minute of index work in total**, plus one cold read per file. You never touch
the terabyte: the index frame is at the end of each file, and mmap faults in
only the pages you land on.

Three things that surprise people, in order of how much they cost:

1. **Do not whole-file compress.** A single zstd blob per 1 GB file forces
   decompressing a gigabyte to read 200 bytes. This is what a naive
   store-then-compress pipeline does, and it is the one shape that defeats
   everything above. Compress at the filesystem, or in blocks — see below.
2. **Navigating to a single field is not always faster than decoding the
   record.** Measured on 5-key records: `(nav/value (get cursor :k))` took
   13.3 µs against 10.8 µs to decode the whole record. Scanning encoded keys
   costs more than decoding a small map. The per-field path wins on LARGE
   containers, which is what `:index-min` exists to select. At scale, "pull out
   a small bit" should mean *seek to the right record with the index and decode
   it whole*.
3. **Open the index once per file and `fork` per thread.** Expansion is 10–15 ms
   and a fork is 45 µs — three orders of magnitude. A cursor is not
   thread-safe; `fork` is how you share one expanded index across threads.

### If you need compression off ZFS

Do not put it inside the CBOR. Stack it underneath, in independently
decompressible blocks with their own offset table — which is what
[zstd's seekable format][zstd-seekable] already specifies: a series of
independent frames plus a seek table in a *skippable* frame at the end. A
seekable-zstd file is still a valid zstd file, so `zstd -d` yields the ordinary
CBOR sequence with its ordinary index, while a seekable reader decompresses one
frame. boring's index maps item to decompressed offset; the seek table maps
decompressed offset to frame. The two compose and neither knows the other
exists.

The same shape is [BGZF][bgzf] in genomics (gzip blocks plus a `.gzi`), Parquet
pages, ORC compression blocks, and Avro's object container. **No production
format fuses compression into the document format**; they all layer it, and the
reason is exactly the one stringref runs into below.

[zstd-seekable]: https://github.com/facebook/zstd/tree/dev/contrib/seekable_format
[bgzf]: https://samtools.github.io/hts-specs/SAMv1.pdf

### Why stringref and the index are mutually exclusive

`boring.nav` refuses a stringref document, and indexing therefore forces
`:stringref false`. This is not a limitation of the implementation.

A stringref (tag 25) is an index into a table built *incrementally, in
occurrence order, while decoding*. To resolve one at offset X you must already
have decoded everything from the namespace start to X — the precise opposite of
seeking. Any compression whose dictionary is built by the decoder as it goes has
this property: LZ77's window, gzip, and stringref alike.

The schemes that DO permit random access all use a **static** dictionary
available up front — Parquet's dictionary pages, [FSST][fsst]'s symbol table, a
trained zstd dictionary. Given the table, any single value decodes alone.

That is the whole distinction, and it is why the answer is to layer rather than
fuse. It is also why the cost of giving stringref up is small: stringref is
2.09x on record-shaped data where whole-file zstd is 36.7x, and stringref *plus*
zstd is only 10% better than zstd alone. Give it up and let a real compressor
work.

[fsst]: https://www.vldb.org/pvldb/vol13/p2649-boncz.pdf

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
