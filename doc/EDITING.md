# Editing without decoding

On the JVM, `boring.edit` updates encoded byte arrays and `boring.mmap`
updates mapped files. They locate the target with the navigator and encode
the replacement without materialising the whole document.

Use these APIs for mutable working data whose encoding you control.
Do not edit immutable, content-addressed database blocks in place.

## Requirements

Write the original value with a deterministic, stringref-off profile, usually
`{:profile :archival}`, and pass the same encoding options when editing.
The editor measures the old value by re-encoding it; changing the encoding
rules can give the wrong byte span. These are caller preconditions, not a
validation guarantee for arbitrary CBOR input.

Indexed string references are supported for reading, including memory-mapped
reads. They are not supported for these edits: a replacement can change the
reference namespace used by values elsewhere in the document.

For mapped edits, coordinate writers and readers and choose a recovery policy
before using the API. A successful file flush does not make a multi-byte edit
atomic.

## Edit a byte array

```clojure
(require '[boring.core :as boring]
         '[boring.edit :as edit])

(def opts {:profile :archival})
(def bs (boring/encode {:a {:x 1 :y [10 20 30]} :b 5} opts))

(def changed (edit/assoc-in-bytes bs [:a :x] 1000000 opts))
(boring/decode changed)
;; => {:a {:x 1000000, :y [10 20 30]}, :b 5}

(boring/decode (edit/update-in-bytes bs [:a :y 1] inc opts))
;; => {:a {:x 1, :y [10 21 30]}, :b 5}

(boring/decode (edit/dissoc-in-bytes bs [:a :x] opts))
;; => {:a {:y [10 20 30]}, :b 5}
```

These functions return new bytes and leave `bs` unchanged. Even a same-length
update copies the byte array. Avoiding full decoding saves object construction
and encoding work, but does not eliminate that copy.

Paths dispatch on the container: an integer is a key in a map and an index
in an array. A missing leaf can be added when its parent exists.
A missing parent or scalar in the path raises `:boring/path-absent`;
the API does not create an arbitrary chain of missing parents.

### Replace vs. structural

Replacing an existing leaf encodes the replacement and copies the surrounding
bytes. Adding or removing a key changes the parent container's entry count, so
the editor decodes and re-encodes that parent.

CBOR's container headers contain element counts rather than subtree byte
lengths. Replacing an integer with a longer encoding therefore leaves ancestor
headers unchanged. For example, replacing `1` with `1000000` grows its
encoding from one byte to five; the following bytes move by four.

The [edit tests](../test/boring/edit_test.clj) compare supported edits with
decode/edit/encode results. This does not establish equivalence for every tag
handler or arbitrary non-preferred CBOR encoding.

### The `:index` policy

For size-changing or structural edits:

| `:index` | Behaviour |
| --- | --- |
| `:rebuild` (default) | Build a new frame for the resulting data |
| `:drop` | Return unindexed data |
| `:maintain` | Shift a usable existing frame for a leaf replacement; otherwise rebuild |

Maintenance avoids walking all the data when the replaced span contains no
indexed container. A structural change, or replacing a span containing an
index node, needs a rebuild. With no original frame, the maintenance path
leaves the result unindexed.

The same-length fast path currently copies the blob and retains its frame,
without applying the requested index policy. Do not rely on `:index :drop`
to remove a frame on that path.

### Poke: a same-length overwrite

`poke-in-bytes` is the explicit mutating API:

```clojure
(def mutable-bytes (boring/encode {"counter" 10} opts))
(edit/poke-in-bytes mutable-bytes ["counter"] 11 opts)
(boring/decode mutable-bytes)
;; => {"counter" 11}
```

It requires the replacement's encoded length to match, otherwise it throws
`:boring/not-pokeable`. `same-length?` can check a proposed replacement,
and `poke-plan` exposes the locate-and-length-check step.

Equal length refers to bytes, not characters or collection size. Integer
encodings widen at thresholds: an increment from 23 to 24 is not a
same-length update. Fixed-length byte strings or values with a controlled
fixed-width representation are more predictable.

Same-length replacement preserves a leaf's surrounding offsets. It does not
prove that replacing an entire indexed container preserves that container's
internal boundaries; use a rebuilt encoding for that case.

## Edit a memory-mapped file

`boring.mmap` requires JDK 22+. This example creates a file for the edits:

```clojure
(require '[boring.mmap :as bmm])

(with-open [out (java.io.FileOutputStream. "data.cbor")]
  (.write out ^bytes (boring/encode {"counter" 10 "name" "Ada"} opts)))

(bmm/poke! "data.cbor" ["counter"] 11 opts)
(bmm/poke-update! "data.cbor" ["counter"] inc opts)
(bmm/splice! "data.cbor" ["name"] "a longer name" opts)
```

`poke!` and `poke-update!` overwrite a same-length value and flush the
mapping. `splice!` also accepts a length change: it resizes the file, moves
the following bytes, and maintains the index. A same-length result takes its
poke path automatically.

A size-changing edit near the beginning can move nearly the whole file.
Lookup cost also depends on index placement and the structures traversed.
Neither operation has a constant cost independent of the document.

Use `:offset` for a value after a file header. Poke operations also accept
`:length`; splice takes the value to extend to end-of-file because it resizes
the file. If the affected span contains a node that the index cannot maintain,
`splice!` throws `:boring/unmaintainable-index` before editing.

## Durability

Mapped edits mutate bytes that concurrent readers may already be using.
A crash can leave a partial write. Applications need both read/write
coordination and a way to recover the value.

| Approach | Application responsibility |
| --- | --- |
| Write replacement, fsync, atomic rename, fsync directory | Manage replacement and publication; existing mappings retain the old file |
| In-place with a dirty marker | Detect incomplete writes and reconstruct the value; coordinate readers |
| In-place without a marker | Accept possible corruption, usually for reproducible working data |

A marker detects an interrupted operation; it is not a rollback log and does
not reconstruct the value itself. Splices may dirty a large tail as well as
the target pages.

[Konserve's filestore integration](https://github.com/replikativ/konserve/blob/main/doc/in-place-editing.md)
provides `update-in!`, `assoc-in!`, and `dissoc-in!` with:

- `:durability :rename` as the replacement-file default.
- `:durability :checked` for in-place updates with a torn-write marker.
- `:durability :raw` for unguarded in-place updates.

Konserve serialises writes with a per-key lock. Its mapped reads are
lock-free, so checked mode still requires a reader-coordination policy.
Recovery code must call `torn?` and arrange reconstruction; readers do not
automatically perform recovery. Ineligible values fall back to ordinary
konserve operations.

## What is refused, and why

| Condition | Result |
| --- | --- |
| Missing parent or non-container in an edit path | `:boring/path-absent` |
| Length change passed to an explicit poke | `:boring/not-pokeable` |
| Mapped splice cannot maintain the affected index | `:boring/unmaintainable-index` |

The encoding-profile and concurrency requirements remain the caller's
responsibility. See [Index](INDEX.md) for navigation behaviour and
[Security](SECURITY.md) for untrusted input.
