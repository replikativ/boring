# Editing without decoding

`boring.edit` and `boring.mmap` change one leaf of an encoded value without
materialising the rest. This is the write-side mirror of [reading without
decoding](INDEX.md): where navigation reaches a field by walking the wire
format, editing reaches it the same way and then rewrites only the bytes that
changed.

It is an optional layer. Nothing here changes the format — the output is the
same CBOR `decode` and `cbor2` and `ciborium` already read.

---

## The one property it rests on

**A CBOR map or array head carries an element COUNT, not a byte length.** So
`{"a": {"x": 1}}` encodes the inner map as `A1 61 78 01` with no length field
naming its span. Replace the `1` with `1000000` and the inner map grows two
bytes — but the *outer* head is still `A1`, unchanged, and every ancestor head
above it is unchanged too. In a length-prefixed format (fressian, protobuf)
every enclosing length would have to be patched; in CBOR none do.

The bytes after the edit shift by the size delta, and that is the whole cost. A
second fact bounds it: **plain-CBOR data contains no self-referential byte
offsets.** The only absolute offsets in a boring document live in the trailing
[index frame](INDEX.md) and its footer, so splicing the data can never corrupt
the data — only the frame, which is optional and rebuildable.

---

## `boring.edit` — editing a byte array

```clojure
(require '[boring.edit :as edit])

(def bs (boring/encode {:a {:x 1 :y [10 20 30]} :b 5} {:profile :archival}))

(edit/assoc-in-bytes bs [:a :x] 1000000 opts)   ; replace a leaf
(edit/update-in-bytes bs [:a :x] inc opts)      ; apply a fn
(edit/dissoc-in-bytes bs [:a :x] opts)          ; remove a key
```

**Paths resolve on the container, exactly like `clojure.core/get-in`** — a map
key (keyword, string, symbol, or an integer *key*) descends a map, an integer
*index* descends a vector. `[:a :y 1]` is index 1 of the vector under `:a`; a
map keyed by integers is read as a map, not mistaken for an array. A missing
step is `:boring/path-absent`, and a step that lands on a scalar where a
container was needed is the same — a typed error, never a wrong answer.

**The guarantee is equivalence.** For any value the reader would decode, the
bytes these produce decode to exactly what `clojure.core`'s
`update-in`/`assoc-in`/`dissoc` would have produced through a full round trip. A
generative property test asserts this over thousands of random nested values and
paths, indexed and bare. That is the whole reason the layer is safe to use in
place of decode-edit-encode: it is not *similar*, it is *the same value*.

`assoc-in-bytes` and `update-in-bytes` create a missing key like their
`clojure.core` counterparts — an absent leaf falls back to re-encoding the
parent container, so the map keys land in the profile's canonical order.

### Replace vs. structural

- A **replace** at an existing leaf re-encodes only that leaf and splices it in.
- A **structural** change — adding or removing a key, growing an array — changes
  a container's element count, so it re-encodes only the *parent* container
  (O(parent), not O(document)) and splices that. It reuses `clojure.core`
  semantics on the one decoded subtree, which is why it is obviously correct.

### The `:index` policy

`opts` may carry `:index`, deciding what happens to a trailing offset frame:

| `:index` | what it does |
|---|---|
| `:rebuild` (default) | walk the spliced bytes and seal a fresh frame |
| `:drop` | return a bare value; navigation falls back to scanning |
| `:maintain` | shift the existing frame's offsets — for a size-changing **leaf replace** only |

`:maintain` is the fast path for a size-changing leaf. It reconstructs the
pre-seal index map from the *sealed* frame with `boring.nav/frame->index-map`
(O(index), no data walk), shifts every offset past the edit by the delta, and
re-seals. The result is **byte-identical to `:rebuild`** and, on a 1.77 MB
indexed blob, about 3.5× cheaper (4.8 ms against 16.7 ms).

Two things keep `:maintain` honest:

- It applies **only to a leaf replace**. A structural edit rewrites a container,
  so its index nodes change arbitrarily rather than shifting; `:maintain`
  degrades to `:rebuild` there.
- If the replaced value is **itself an indexed container** (or contains one),
  shifting its internal nodes wholesale by the value-level delta would describe
  bytes that no longer exist — garbage on array navigation. `spans-index-node?`
  detects this (any container offset inside the old value's span) and falls back
  to a rebuild.

### poke — a same-length overwrite

When the new value encodes to the *same* number of bytes, nothing shifts:
`poke-in-bytes` overwrites the value's bytes in place and any index stays valid.
`poke-plan` is the source-agnostic core (locate + length check), shared with the
memory-mapped path.

**You do not have to reach for it explicitly.** `update-in-bytes` and
`boring.mmap/splice!` detect a same-length result and take the poke path
automatically — overwriting in place, leaving the frame untouched, and skipping
the splice's tail move and reindex. The explicit `poke*` functions exist for the
case where you want the length change to be an *error* (`:boring/not-pokeable`)
rather than a silent splice — for a fixed-width slot whose size must never move.

Same length is **byte-wise**, not element-count-wise, and it is brittle for
arbitrary values — boring encodes integers minimal-width, so `1`→`1000` already
changes length. Make a field reliably pokeable by giving it a fixed width: a
full `uint64`/`float64`, a fixed-length byte string, or a cell of an RFC 8746
typed array. Then every update within range is a poke.

### The profile requirement

Editing needs a **deterministic, stringref-off profile** — `:archival` or
`:canonical`. Two reasons: the old value's byte length is measured by
re-encoding it, which only reproduces the stored bytes under a deterministic
profile; and [stringref](COMPATIBILITY.md) makes a value's length depend on
every string encoded before it, so a value cannot be edited in isolation. These
are the profiles you would already use for storage.

---

## `boring.mmap` — editing a memory-mapped file

JDK 22+ (`java.lang.foreign`). This is where the payoff lands: an edit with no
full read, no full re-encode, and — for a same-length change — no rewrite.

```clojure
(require '[boring.mmap :as bmm])

(bmm/poke!        "data.cbor" ["counter"] 42 opts)      ; same length, in place
(bmm/poke-update! "data.cbor" ["counter"] inc opts)     ; apply a fn, same length
(bmm/splice!      "data.cbor" ["name"] "a longer name" opts)  ; size change, in place
```

- **`poke!` / `poke-update!`** overwrite a same-length value in the mapping and
  `msync`. The locate is O(depth) with an index (position-independent), O(scan)
  without one — so index the file if you edit fields behind bulk. The write is a
  page touch; a field update in a gigabyte file is milliseconds regardless of
  where it sits.
- **`splice!`** handles a size change in place: it grows or shrinks the file,
  memmoves only the tail after the edit *within the mapping*, and maintains the
  index. Compared to decode/edit/encode it never materialises the value;
  compared to a temp-file rewrite it touches only the pages from the edit onward.
  On a 20 MB value it measured 5.9× (front edit) to 13.5× (back edit) against
  read-whole / rebuild / write-whole.

`:offset` narrows to a value that sits past a header — a konserve blob keeps its
value after a 20-byte header and metadata, so `(assoc opts :offset value-start)`
navigates it. `poke!`/`poke-update!` also honour `:length`; `splice!` does not
(a splice resizes the file, so it takes the value to run to end-of-file).

If a framed value's index cannot be maintained in place — the replaced value
spans an index node — `splice!` throws `:boring/unmaintainable-index` before
touching a byte, so a caller can fall back to a rebuild.

---

## Durability

An in-place edit **mutates live bytes**. That is what makes it fast and what
makes it unsafe on its own: a crash mid-splice can leave a value torn, and a
concurrent reader can observe a half-written value. So in-place editing is for a
single writer over a value it owns, either where the data is reproducible or
paired with a torn-write detector.

The memmove is memory-bandwidth bound and cheap even for a large tail; the real
cost of a *safe* size-changing edit is the durability I/O:

- **Rewrite-and-rename** — write the spliced bytes to a temp file, fsync, atomic
  rename, fsync the directory. Crash-safe by construction, O(file). This is what
  `boring.edit` + an ordinary write gives you, and it still beats
  decode/edit/encode by skipping the object graph.
- **In-place + torn-write detection** — edit through the mapping with no copy,
  guarded by a marker (set it, edit, clear it) so a crash is detectable and the
  value reconstructed. O(dirty pages), not O(file).
- **In-place, unguarded** — cheapest, relies on nothing; for a cache or a
  reproducible working set.

[konserve][konserve]'s filestore wires these into `konserve.mmap/update-in!` /
`assoc-in!` / `dissoc-in!` with a `:durability` option (`:rename` the crash-safe
default, `:checked` in-place with a torn-write marker, `:raw` in-place bare), a
`torn?` check for recovery, and per-key locking so an mmap edit serialises
against ordinary konserve writes.

[konserve]: https://github.com/replikativ/konserve

---

## What is refused, and why

- A **non-deterministic or stringref profile** — length measurement and
  isolation both break; use `:archival`/`:canonical`.
- Replacing a value that **spans an index node** in place (`splice!`) — the
  index cannot be shifted; rebuild instead.
- A **missing parent** — `:boring/path-absent`; there is nothing to edit.
- A **length change** to `poke!`/`poke-in-bytes` — `:boring/not-pokeable`; the
  caller splices.

Each refusal is a typed error, never a silently wrong answer.
