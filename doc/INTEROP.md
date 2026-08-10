# Interop

boring emits standard [CBOR][rfc8949]. Any conformant decoder in any language
parses its output without error — that is what "self-describing" means, and it
is the whole reason for choosing CBOR.

What this document answers is the *next* question: once it parses, what do you
actually get, and how do you turn it into something idiomatic?

Everything here is **executed in CI**. `interop/test_read_boring.py` runs on
every push against a committed fixture, so if boring's wire format changes and
this document stops being true, the build breaks. A snippet pasted into a
markdown file rots silently; this one cannot.

## The tag table

| tag | meaning | what a generic decoder gives you | idiomatic form |
|---|---|---|---|
| — | ints, floats, strings, bytes, arrays, maps, booleans, null | the obvious thing | — |
| 0, 1 | date/time | handled natively by most libraries | timestamp |
| 2, 3 | bignum | handled natively | big integer |
| 4 | decimal fraction | handled natively | decimal, **scale preserved** |
| 25, 256 | [stringref][stringref] | handled natively by cbor2; a compression detail | transparent |
| 27 | generic object | `[type-name, field-map]` | your record type |
| 30 | rational | handled natively | fraction |
| **32** | URI | a tagged string | `java.net.URI` |
| **35** | regular expression | a tagged string | regex (source only, no flags) |
| 37 | UUID | handled natively | UUID |
| **40** | multi-dimensional array | `[dims, flat-array]` | row-major matrix |
| **39** | **identifier** | a tagged string | **keyword or symbol** |
| 77–86 | [RFC 8746][rfc8746] typed array | **often unimplemented** — raw bytes | array of numbers |
| 258 | set | handled natively by cbor2 | set |
| **1002** | duration (RFC 9581) | a keyed map | `java.time.Duration` |
| **1004** | full-date (RFC 8943) | a date string | `java.time.LocalDate` |
| **39649** | **shaped array** — boring's one extension | `[keys, [row-values…]]` | array of maps |

Three rows deserve attention.

**Tag 39 is where Clojure shows through.** A keyword is a string with a leading
`:`; a symbol is the same tag without one. Distinguishing them matters: if you
flatten both to plain strings, `:a` and `"a"` become the same map key and you
have silently merged two entries.

**Tags 77–86 are registered, standard, and frequently unimplemented.** Python's
`cbor2` hands back a raw `CBORTag(79, b"…")` rather than a list. This is worth
handling rather than avoiding — the payload is a plain little-endian memory
image, so a homogeneous numeric column costs one bulk read instead of one
decode per element. It is the fastest thing boring emits, and unpacking it is
one `struct` call.

**Tag 39649 is the only thing here that is not a registered CBOR tag.** See
below.

## Reserved tag-27 names

Tag 27 is "serialised language-independent object with type name and
constructor arguments". boring uses it for your records — the type name is the
record's own `namespace/Name`, as written — and also for a handful of types
CBOR has no tag for, under names carrying the same **slash**:

| name | argument | why it is not just the bare value |
|---|---|---|
| `clojure/sorted-map` | a map | a sorted map is a CBOR map; unmarked it returns unsorted |
| `clojure/sorted-set` | an array | same, for sets |
| `clojure/queue` | an array | a queue is a CBOR array; unmarked it returns a vector |
| `clojure/with-meta` | `[meta, value]` | Clojure metadata, which has nowhere else to go |
| `clojure/char` | a 1-character string | `(= \a "a")` is **false** in Clojure |
| `java/period` | an ISO-8601 string, e.g. `"P1Y1M1D"` | a *date* amount; RFC 9581's tag 1002 carries *seconds* |
| `boring/index` | `[stride, containers, counts, slots, sorted, (stringrefs,) data-end]` — **six or seven elements** | a file's offset index. Pure metadata — **ignoring it is always correct** |

The prefix names the runtime that owns the type. These names are **frozen** —
they are pinned in the golden corpus, because renaming one is a silent break: a
reader that does not recognise a name yields an ordinary value rather than
raising.

`boring/index` is the one you can ignore with no loss at all: it is an
optimisation for seeking within a file, never data, and boring itself falls
back to scanning whenever it is absent or stale.

For a foreign reader, ignoring the others is safe and lossy in a predictable
direction — a sorted map arrives as a map, a queue as an array, a char as a
one-character string. Handling `clojure/with-meta` is worth the four lines, since
otherwise every annotated value arrives wrapped:

```python
def tag27(name, arg):
    if name == "clojure/with-meta":
        return tag27_value(arg[1])       # or keep arg[0] if you want the meta
    if name in ("clojure/queue", "clojure/sorted-set"):
        return arg
    ...
```

Only `clojure/with-meta` changes the *shape* of what you get; the rest change
only the type, which a language without sorted maps or characters was going to
lose anyway.

## The one extension

An array of maps is written with the keys **once**:

```
value    [{:e 1 :a :x} {:e 2 :a :y}]

wire     39649([ [":e", ":a"],            <- the key set, once
                 [ [1, ":x"],             <- row 1, values only
                   [2, ":y"] ] ])         <- row 2
```

It needs **no state outside the tag** — that is the property that makes it easy
to support elsewhere, and the reason it was designed this way rather than as a
stream-scoped structure table.

**It is not quite a zip.** The key set is the UNION of every row's keys, so a
row need not carry all of them, and absence has two spellings:

- **`undefined`** (simple value 23) in a value position — that key is absent
  from this map;
- **a short row** — every key from the row's length onward is absent.

**`null` is not absence.** It is a key present with a null value: `{:a null}`
and `{}` are different maps, and CBOR spells them differently on purpose —
`0xf6` is null, `0xf7` is undefined.

```
value    [{:a nil :b 1} {:b 2}]
wire     39649([ [":a", ":b"], [ [null, 1], [undefined, 2] ] ])
                                        ^^^^        ^^^^^^^^^
                                        present     absent
```

A plain `dict(zip(keys, row))` gets the short-row case right by accident, since
`zip` stops at the shorter side, and the mid-row case **wrong**: it produces a
phantom key holding `undefined`. Filter it:

```python
def shaped_array(payload):
    keys, rows = payload
    return [
        {k: v for k, v in zip(keys, row) if v is not cbor2.undefined}
        for row in rows
    ]
```

**Keeping `undefined` and `null` apart is a conformance requirement of this
tag.** RFC 8949 §3.3 makes simple values 22 and 23 distinct data items; a
decoder that maps both to one host value has lost information the format
carries. The marker only ever appears *inside* tag 39649's content, so a reader
that does not implement the tag never encounters it — this is a requirement on
implementers of 39649, not on CBOR readers generally.

Measured, decoding `39649([["a","b"],[[null,1],[undefined,2]]])`:

| library | 22 vs 23 | keeps tag 39649 | can implement this tag |
|---|---|---|---|
| `cbor2` (Python) | yes — `cbor2.undefined` | yes | **yes** |
| `cbor-x` (JavaScript) | yes — `null` / `undefined` | yes, as `{value, tag}` | **yes** |
| `cbor` (node-cbor) | yes | yes, as `Tagged` | **yes** |
| `clj-cbor` | yes — an `Undefined` value | yes | **yes** |
| `ciborium` (Rust) | **no** — both give `Value::Null` | yes | only below `Value` |
| Jackson (Java) | **no** | **no** — swallows tags | no |

Two notes on the failures, because they are different problems.

**ciborium** preserves the tag but collapses the marker inside it, so an
implementer must drop below `ciborium::value::Value` to read row values.
`interop/rust` does not, and therefore handles short rows correctly and reports
a mid-row gap as a null — the assertion there pins that wrong answer
deliberately, so it fails the day ciborium distinguishes them.

**Jackson** does not surface tags at the token level at all, so it cannot see
tag 39649 in the first place. No choice of absence marker would help; a Jackson
consumer needs `{:profile :interop}` below, which turns shapes off entirely.

Do **not** reach for an unassigned simple value as a private marker if you are
designing something similar: measured, Jackson decodes `simple(0)` to the
*integer* `0` — indistinguishable from real data — and ciborium rejects the
document outright.

It is **off by default** (`:shapes true` opts in), and a decoder that ignores it
gets an inert tagged value — never a misreading. Use `{:profile :interop}` to
guarantee no extension appears at all.

It is **not yet registered with IANA**, so treat the number as provisional. The
registration is tracked in [COMPATIBILITY.md](COMPATIBILITY.md).

## Python

`interop/read_boring.py` is a complete reader in about 60 lines of logic. It is
the file CI runs.

```python
from read_boring import loads

value = loads(open("data.cbor", "rb").read())
```

`cbor2` already handles tags 0–4, 25/256, 30, 37 and 258 natively. What
`read_boring` adds:

```python
def _tag_hook(decoder, tag):
    if tag.tag == 39:                       # identifier
        s = tag.value
        return Keyword(s) if s.startswith(":") else Symbol(s)
    if tag.tag == 27:                       # record
        type_name, fields = tag.value
        return Record(type_name, fields)
    if tag.tag == 39649:                    # shaped array
        keys, rows = tag.value
        return [dict(zip(keys, row)) for row in rows]
    if tag.tag in TYPED_ARRAYS:             # RFC 8746
        fmt, width = TYPED_ARRAYS[tag.tag]
        return list(struct.unpack(f"<{len(tag.value) // width}{fmt}", tag.value))
    return tag

def loads(data):
    return cbor2.loads(data, tag_hook=_tag_hook)
```

Run it yourself:

```
pip install cbor2
python3 interop/test_read_boring.py
```

The test asserts 24 values against literal expectations, including that a
decimal keeps its scale — `Decimal("1.50") == Decimal("1.5")` is `True` in
Python, so equality alone would not catch that loss, and the test checks
`as_tuple().exponent` instead.

## Rust

`interop/rust/` is a complete reader in ~350 lines of `ciborium::value::Value`
walking — no boring-specific crate, because there isn't one and none is needed.
It reads the same committed fixture the Python reader does and checks the same
values, so the two languages agree or CI says so:

```
cargo run --manifest-path interop/rust/Cargo.toml -- interop/fixture.cbor
```

Two things it has to do that a naive reader will not, and both are worth
knowing before you write your own:

- **Stringref is resolved before anything else.** ciborium does not implement
  tags 25/256, so the reader walks the raw value tree once and substitutes
  back-references. The table-building rule must match the writer's *exactly* —
  a string is only entered if referencing it would be shorter than repeating it
  — because one disagreement makes every subsequent index wrong. Use
  `{:stringref false}` if you would rather not implement it.
- **A tag-258 set is an array, and its order means nothing.** This fixture
  emits `1, 3, 2`. Comparing set contents positionally passes on some values
  and fails on others, which is worse than failing consistently.

## Go, JavaScript

The same four hooks, against these libraries:

- **Go** — [`fxamacker/cbor`][fxamacker], which is the most rigorous Go
  implementation; register a `TagSet`.
- **JavaScript** — [`cbor-x`][cbor-x] or [`cbor`][cbor-js]; both expose a tag
  hook.

**Neither is executed in CI**, unlike the Python and Rust readers above. Treat
them as guidance — the shape of the problem is identical, but I would rather
say plainly that they are untested than imply a coverage that does not exist.

(Rust used to be in this list. It is not any more: `interop/rust` runs in CI
and `bin/ci` FAILS rather than skips it when `CI` is set, because a reference
reader that silently does not run is worse than one that is absent.)

## Guaranteeing no extensions

If you are writing data for a consumer you do not control:

```clojure
(boring/encode value {:profile :interop})
```

That disables stringref and shapes. Every byte is then a registered CBOR
construct, and any conformant decoder produces the right structure without a
single hook — at the cost of a larger document.

[rfc8949]: https://www.rfc-editor.org/rfc/rfc8949
[rfc8746]: https://www.rfc-editor.org/rfc/rfc8746
[stringref]: https://cbor.schmorp.de/stringref
[ciborium]: https://docs.rs/ciborium
[fxamacker]: https://github.com/fxamacker/cbor
[cbor-x]: https://github.com/kriszyp/cbor-x
[cbor-js]: https://github.com/hildjj/node-cbor

[cbor2]: https://github.com/agronholm/cbor2
[ciborium]: https://github.com/enarx/ciborium
