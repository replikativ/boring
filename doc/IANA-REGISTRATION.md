# IANA registration for tag 39649

Tag 39649 (shaped array) is **provisional**: boring emits it today under
`:shapes true`, but it is not in the IANA CBOR tag registry, so the number is
not reserved and another registrant could take it.

This file is the request, ready to send. It is written out rather than
described because the registration is a one-shot outward-facing act and the
exact wording is what ends up permanently in a public registry.

## Why it is worth filing rather than dropping

`doc/COMPATIBILITY.md` promises tag assignments are append-only. That promise
starts binding at the first release, so the number has to be either registered
or removed before then — leaving it unregistered means publishing a claim on a
number someone else may already be filing for.

The original choice was 40000. The registry had it **already assigned**, to
`ur:known-value` (Blockchain Commons) — verified again on 2026-07-31, along
with a dense `ur:` cluster running from 40000 to 40918. 39649 was picked to be
hard to collide with rather than tidy: odd and non-round, because registrants
take round numbers and contiguous blocks, and mid-gap in 39000–39999, which is
still entirely unassigned.

Re-verified against <https://www.iana.org/assignments/cbor-tags/tags.csv> on
2026-07-31: 39649 unassigned, nothing assigned in 38000–39999.

**Only one number is claimed.** The offset index was briefly tag 39651 and is
now a tag-27 *name*, `boring/index` — tag 27 being CBOR's registered extension
point for named types, needing no number of its own. That decision was measured
rather than argued: the index occurs once per file, so a name costs 0.05%.

Tag 39649 stays a number because shaped arrays occur **per array**. Measured,
carrying them as a tag-27 name instead would add 19.6% to a document of 100
small tables and 35.2% to one of 500 — on a feature whose whole purpose is to
shrink exactly those. That is the rationale, recorded here because "we could
not remember why" is a poor answer to give during registration.

## Procedure

Tags 32768 and above are **First Come First Served** (RFC 8949 §9.2): no expert
review, no RFC, no standards action. Submit via
<https://www.iana.org/form/protocol-assignment> selecting the *Concise Binary
Object Representation (CBOR) Tags* registry, or email <iana@iana.org>.

FCFS still requires a **permanent, publicly reachable** specification URL. Fill
in `<SPEC-URL>` below with the published location of `doc/SHAPES.md` before
sending; a URL that later 404s is worse than no registration, so prefer a
tagged-release permalink over a branch URL.

## The request

> Please register the following value in the Concise Binary Object
> Representation (CBOR) Tags registry.
>
> **CBOR tag**: 39649
>
> **Data item**: array
>
> **Semantics**: Shaped array; array of maps with the key set hoisted out,
> encoded as `[keys, [values-per-row, ...]]`, where `undefined` in a value
> position and a row shorter than the key set both mean the corresponding key
> is absent from that map
>
> **Reference**: `<SPEC-URL>`
>
> **Point of contact**: Christian Weilbach <ch_weil@topiq.es>

## Wire format, for the record

The tag content is a two-element array:

1. the KEY SET, as an array: the union of every row's keys, each appearing once;
2. an array of rows, each an array of values positionally matching the keys.

```
value    [{:e 1 :a :x} {:e 2 :a :y}]

encoded  d9 9ae1                 tag 39649
           82                      array(2)
             82                      the key set, once
               d827 62 3a65            ":e"
               d827 62 3a61            ":a"
             82                      the rows
               82 01 d827623a78        row 1: values only
               82 02 d827623a79        row 2: values only
```

**A row need not carry every key.** The key set is the union across rows, and
absence has two spellings:

- **`undefined`** (simple value 23) in a value position: that key is absent
  from this map.
- **A short row**: every key from the row's length onward is absent. Trailing
  absences are truncated rather than padded.

```
value    [{:a nil :b 1} {:b 2}]

encoded  d9 9ae1  82
           82  d827623a61  d827623a62      keys :a :b
           82
             82 f6 01                      :a PRESENT, value null
             82 f7 02                      :a ABSENT
```

**`null` (simple value 22) is not absence.** It is a key that is present with a
null value, and `{:a null}` and `{}` are different maps.

**Distinguishing simple values 22 and 23 is therefore a conformance requirement
of this tag.** RFC 8949 §3.3 already makes them distinct data items; an
implementation that maps both to one host value cannot implement this tag
correctly and should decline it rather than report an absent key as present.
The marker appears only inside this tag's content, so a decoder that does not
implement the tag never encounters it.

Surveyed at time of writing, decoding
`39649([["a","b"],[[null,1],[undefined,2]]])`: `cbor2` (Python), `cbor-x` and
`cbor` (JavaScript) and `clj-cbor` all keep the two apart and preserve the tag.
`ciborium` (Rust) preserves the tag but collapses the marker at its `Value`
layer, so an implementer must decode below it. See `doc/INTEROP.md`.

A row longer than the key set is malformed: there is no key for the extra value
to bind to, and a decoder must reject the item rather than truncate. Keys must
be distinct. Both are enforced by boring's readers on each platform.

The `undefined` convention is taken deliberately from
[draft-ietf-cbor-packed][packed], whose tag-114 `record` function spells
absence the same way, so that an implementation of one informs the other.

[packed]: https://datatracker.ietf.org/doc/draft-ietf-cbor-packed/

A decoder that does not implement the tag surfaces an opaque tagged value
rather than misreading, and the tag is only emitted when the caller opts in
with `:shapes true`, so an unregistered collision degrades to "unrecognised
extension" and never to silent corruption.

## What this is NOT

Not a general packing mechanism. `draft-ietf-cbor-packed` covers that ground
with a document-scoped table, and its tag 114 `record` function is the same
idea generalised. boring deliberately did **not** adopt it for the first
release — see `doc/COMPATIBILITY.md` — so 39649 stands on its own as a narrow,
self-contained construct: one array, keys hoisted once, no table, no
references, no expansion.
