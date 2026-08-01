# The datahike dump codec: CBOR vs EDN-lines

[alekcz/datahike#5][pr5] reworks `datahike.migrate` into a streaming,
type-exact, verifiable dump/restore — and parks one question for maintainers
(its §13, "the one real fork"):

> Keep **CBOR** and fix its float handling, or move to **EDN-lines**? EDN-lines
> makes type-exactness, determinism and closed-reader safety clean, and it
> removes the `clj-cbor` dependency; CBOR is more compact and faster to parse at
> 40M-datom scale.

That framing is correct about `clj-cbor` and, we think, mistaken about CBOR.
Every objection in it is a property of *that codec*, not of the format. This
page measures each one.

Regenerate the whole page:

```
clojure -M:bench -m migrate-codec                      # sections 1-3
clojure -J-Xmx64m -M:bench -m migrate-codec streaming  # section 4
```

`bench/migrate_codec.clj` is the harness. Nothing below is hand-typed.

---

## 1. Type exactness — and datahike #633

The PR names the root cause of [#633][633] precisely:

> `clj-cbor` routes zero/NaN/±Inf doubles through float16 and decodes them as
> `java.lang.Float`; datahike's schema predicates (`double?`) have no coercion,
> so the flipped class fails validation.

That reproduces exactly, and boring does not have it:

| case | boring | clj-cbor |
|---|---|---|
| double 1.5 | ok | ok |
| double 0.0 | ok | **→ `java.lang.Float`** |
| double NaN | ok | **→ `java.lang.Float`** |
| double +Inf | ok | **→ `java.lang.Float`** |
| double −Inf | ok | **→ `java.lang.Float`** |
| float 1.5 / 0.0 / NaN | ok | ok |
| `float-array` | ok | **throws** |
| `double-array` | ok | **throws** |
| ref (long) | ok | ok |
| instant (`java.util.Date`) | ok | → `java.time.Instant` |
| uuid | ok | ok |
| bigint (`123N`) | ok | **→ `java.lang.Long`** |
| `BigInteger` | → `clojure.lang.BigInt` | **→ `java.lang.Long`** |
| bigdec (`1.50M`, scale kept) | ok | ok |
| `byte[]` | ok | ok |
| symbol | ok | ok |
| keyword | ok | ok |
| tuple (vector) | ok | ok |
| string / boolean / ratio | ok | ok |
| char | ok | → `java.lang.String` |

**boring 23/24 class-exact, clj-cbor 14/24.**

The one boring row that is not identity is `BigInteger` → `clojure.lang.BigInt`,
and it is the coercion the PR's own EDN codec performs deliberately:

> `BigInteger` is coerced to `clojure.lang.BigInt` before printing so it reads
> back as a bigint rather than a long.

So boring does automatically what the EDN codec does by hand — and unlike
clj-cbor it never collapses either to `Long`.

The practical consequence: the PR's hand-rolled tag set —
`#datahike/float`, `#datahike/bytes`, `#datahike/farray`, `#datahike/darray`,
`#datahike/symbol` — exists to recover classes a codec lost. Against boring
those five tags have nothing to do. `float[]` and `double[]` are not a special
case at all; they are [RFC 8746][rfc8746] typed arrays, which is a registered
standard other languages read natively.

## 2. Determinism — signable dumps

The PR wants byte-identical re-export so a dump can be signed. Under
`{:profile :canonical}`:

```
- same value encoded twice is byte-identical: true
- decode then re-encode is byte-identical: true
- array-map insertion order does not change the bytes: true
- hash-map insertion order does not change the bytes: true
- a sorted-map is distinguishable from a hash map (by design): true
```

The last line is the one worth reading twice. Canonical ordering is *not* the
same as flattening every map to unordered: boring carries sortedness, so a
`sorted-map` restores with its comparator rather than silently becoming a hash
map. EDN gets this wrong in the other direction — `pr-str` of a sorted map is
indistinguishable from a hash map with the same entries.

## 3. Size and speed

200 000 datoms of mixed value type (string / long / double / uuid / Date /
ref), encoded as one document:

| codec | bytes | encode ms | decode ms |
|---|---:|---:|---:|
| **boring** | **5 536 411** | 42 | 47 |
| boring `:shapes` | 5 536 411 | **35** | **26** |
| clj-cbor | 6 669 695 | 69 | 53 |
| EDN lines | 11 067 727 | 131 | 425 |

EDN is **2.0× the bytes and 9× the decode cost**. At the PR's stated scale —
98M datoms, a 285 MB dump — that is the difference between a dump that restores
in minutes and one that restores in an hour, and roughly 285 MB against 570 MB
of object storage per snapshot.

(The `EDN lines` decode uses `read-string`, which is a *floor*: the PR's actual
`clojure.edn/read` with a tag-handler map is slower, not faster. The comparison
is generous to EDN.)

## 4. Bounded memory

The PR's requirement, in its own words: "1.2 GB store → 285 MB dump →
re-imported and verified under a 144 MB heap."

Five million datoms, written as an [RFC 8742][rfc8742] CBOR sequence with
`write-seq!` and consumed with `decode-seq-from`:

```
- wrote 199,331,553 bytes in 1.3 s (one item per datom)
- max heap: 64 MiB
- streamed back 5,000,000 datoms in 1.4 s, eid checksum 12500497500000
- checksum matches the source: true
```

**A 199 MB dump, written and read back under a 64 MiB heap** — three times the
heap — at ~3.5M datoms/second each way. Memory is bounded by the largest single
datom plus the chunk size, not by the dump.

RFC 8742 matters beyond the memory bound: a sequence is a *concatenation* of
self-delimiting items, so chunk files concatenate without a framing layer, and a
chunk can be verified, signed or skipped without parsing the ones before it.
That is the same property the PR builds a manifest to get.

## 5. What EDN still wins

Stated plainly, because the fork deserves an honest answer and not a pitch:

- **Human-readable.** `head -3 datoms-000001.edn` tells an operator what is in
  the dump. A CBOR chunk needs a tool. This is a real operational property, and
  it is the strongest argument for EDN-lines.
- **No dependency at all** on the read side — `clojure.edn` is in the standard
  library. boring is a dependency, however small.
- **A closed reader map is legible.** boring's tag registry is equally closed
  and equally explicit (no `eval`, no `read-string`, no class loading unless you
  opt in — see [SECURITY.md](SECURITY.md)), but the EDN version fits on a
  screen.

The middle ground the PR already built is the right one: it puts the codec
behind a `write-record`/`read-record` seam. If that seam stays, EDN-lines can
be the default for small dumps and boring the default above some size, with no
change to the manifest, the digests or the import path.

## 6. Where this is not yet proven

- **No real datahike store has been dumped through boring end to end.** The
  datoms above are synthetic and shaped by us. datahike's own CBOR suite
  (`clj -M:test:cbor` in datahike, 9 tests) does flush and re-query a real
  1000-entity DB with real persistent-sorted-set index nodes, but the
  export/import path in the PR is not wired to boring yet. This is the gap
  worth closing before anyone acts on this page.
- **98M datoms has not been run.** 5M has. The scaling is linear in the format
  and the memory bound does not depend on the count, but "should" is not
  "did".
- **boring is beta.** The CBOR *format* it emits is stable and versioned (see
  [COMPATIBILITY.md](COMPATIBILITY.md)); the API may still move.

[pr5]: https://github.com/alekcz/datahike/pull/5
[633]: https://github.com/replikativ/datahike/issues/633
[rfc8746]: https://www.rfc-editor.org/rfc/rfc8746
[rfc8742]: https://www.rfc-editor.org/rfc/rfc8742
