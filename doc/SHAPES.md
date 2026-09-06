# Shapes

The `:shapes` option stores an array of maps with a shared key list, reducing
repeated keys and their decode work. It is useful for tables of records with
overlapping fields. The key list is part of the value, so readers do not need
a separate application schema.

Shaped arrays are implemented on the JVM and ClojureScript, but disabled by
default. Tag 39649 is provisional and unregistered. The proposed scattered-map
encoding, tag 39650, is not implemented. The [offset index](INDEX.md) is an
independent feature.

## Use shaped arrays

```clojure
(require '[boring.core :as boring])

(def rows [{:name "Ada" :active true}
           {:name "Grace" :active false}])

(def encoded (boring/encode rows {:shapes true}))
(= rows (boring/decode encoded))
;; => true
```

Shaping is an encoder choice. Readers recognise the tag without a matching
decode option. It can combine with the default profile's stringref, which
deduplicates repeated values after the keys have been removed from each row.

Profiles such as `:archival`, `:interop`, and `:canonical` lock shapes
off. Use the default `:clojure` profile with `:shapes true` to opt in.

The writer declines arrays that cannot be represented faithfully, including
rows with type or metadata requirements the shared-key representation cannot
carry. An actual `undefined` field value also prevents shaping because that
wire value denotes absence within a row.

## Wire format: tag 39649

The tag contains `[keys, rows]`; each row is an array of values aligned with
the shared keys.

```text
Input:   [{:e 1 :a :x} {:e 2 :a :y}]

Encoded structure:
39649([[:e, :a], [[1, :x], [2, :y]]])
```

This notation abbreviates keywords. On the wire they use the ordinary tag-39
identifier encoding. The array tag is three bytes, `d9 9a e1`.

A decoder reads the keys once and uses them to build each row's map.
A foreign CBOR reader needs a tag handler to return maps instead of the raw
tagged arrays. [Interop](INTEROP.md) includes a Python example.

### Ragged rows: the key set is a union

Rows may omit fields. Keys are collected in first-seen order across the
array, with two encodings for absence:

- `undefined` (`0xf7`) means the key at that position is absent.
- A short row omits all keys after its final position.

```text
Input:   [{:a nil :b 1} {:b 2}]
Encoded: 39649([[:a, :b], [[null, 1], [undefined, 2]]])
```

`null` remains a present key with a nil value. It is distinct from an absent
key. Trailing absences are omitted rather than padded.

### The density bound

Rows with mostly disjoint keys can require more padding than the shared key
list saves. The writer compares padding against an estimate of repeated-key
cost and declines shaping when the estimate is unfavourable.

This is a heuristic, not a byte-size guarantee. It uses estimated key sizes
rather than encoding both complete alternatives with their stringref state.
Measure output sizes for your data, especially sparse or heterogeneous rows.

## Size and decoding trade-offs

The following recorded fixture arranges 200 maps with keys
`[:count :measure :diff :max-key]` in three ways. Sizes are bytes:

| arrangement | no stringref | default | `:shapes true` |
|---|---:|---:|---:|
| array of 200 maps | 10 528 | 6 750 | 2 775 (-58.9%) |
| map of 200 maps | 10 904 | 7 126 | 7 126 (+0.0%) |
| 200-deep nesting | 14 127 | 8 956 | 8 956 (+0.0%) |

Shaping applies to an array of maps. It does not share keys among maps stored
as values of another map or along a nested chain. Stringref can still
deduplicate their strings.

For the separate committed `datom-maps-200` benchmark, shapes reduce the
uncompressed encoding from 9,952 to 4,982 bytes. See
[Performance](PERFORMANCE.md) for the matching decode results and reproduction
commands. Timing depends on the runtime and API used.

Compression changes the size comparison. In that fixture, zstd level 3
produces 1,237 bytes with shapes and 1,121 without them. A deflate experiment
gave closer results: plain 1,404 bytes, stringref 1,388, shapes 1,399, and
shapes plus stringref 1,342. These are compressor-specific measurements.

Evaluate compressed size and end-to-end decode time together. Removing keys
can still reduce decode work even when the compressed file is slightly larger.

## Proposed: tag 39650, shaped map

The following is a design proposal, not an available option or a settled
wire contract. It addresses repeated keys in maps that are not rows of one
array.

### Proposed wire format

A table scoped to one top-level item would register key sets as they appear:

```text
define:    39650([[k0, k1, ...], v0, v1, ...])
reference: 39650([index,         v0, v1, ...])
```

An array in position zero defines a key set; an unsigned integer references
one. Values remain in the same array to avoid a second array header.

In the recorded simulation of the 200-map map-of-maps fixture, this used
3,946 bytes versus 7,126 with stringref alone. A manually constructed
`[keys, rows]` representation used 2,772 bytes. These measure a simulated
encoding, not an implemented codec's speed.

### Relationship to shaped arrays

Tag 39649 shares one key list across a whole array, avoiding a reference
header per row. A scattered-map extension could complement it and potentially
share a per-item key table. That integration still needs specification and
tests.

### Performance questions

Shape-table lookup would add work to map encoding. The proposal would gate it
on an explicit option and use explicit definitions rather than requiring every
plain-map decoder to register key sets. Encoding and decoding costs have not
been established for a shipped implementation.

## Constraints any shape mechanism must respect

### Self-contained per top-level item

Stored values must remain independently decodable when fetched in isolation.
Sharing definitions across values would require an external dictionary or
stream state. It would also make encoded bytes depend on earlier writes,
which matters for byte-based hashing and reproducible exports.

A cross-message dictionary could be a separate protocol feature; it is not
part of either encoding described here.

### Security

A scattered-map implementation would need bounded per-item tables, checked
references, validated row arity, duplicate-key rejection, and allocation
limits before construction. Its error types and treatment of ragged rows
would need specification alongside the reader tests.

The implemented shaped-array reader validates its tag contents and reports
malformed content with `:boring/bad-tag-content`. Short rows are legal;
a blanket requirement that every row have exactly as many values as keys
would contradict the absence encoding.

### Interoperability

Both proposals use CBOR tags, so their wire structure can be parsed by a
generic decoder. Interpreting that structure as maps requires extension
support. A provisional tag collision may cause a foreign decoder to interpret
the number differently; generic parseability does not remove that risk.

## IANA

Tag 39649 is unregistered in the
[IANA registry](https://www.iana.org/assignments/cbor-tags/) checked on
2026-09-06. Tag 39650 is a proposed number only. The
[registration draft](IANA-REGISTRATION.md) documents the intended request.

Keep `:shapes` off when provisional tag assignments are unsuitable for the
data's lifetime or consumers.

## Why not in the first release

Scattered-map support has not been implemented. The existing array encoding
covers tables of records; the additional namespace, reference validation, and
interaction with stringref need their own implementation and interoperability
tests. There is no release commitment for tag 39650.

## Offset navigation

Shapes reduce repeated key data; indexes locate encoded values. They can be
combined, including with indexed string references.
See [Index](INDEX.md) for the frame and navigation API.
