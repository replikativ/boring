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
> **Semantics**: Shaped array; array of maps sharing one key set, encoded as
> `[keys, [values-per-row, ...]]`
>
> **Reference**: `<SPEC-URL>`
>
> **Point of contact**: Christian Weilbach <ch_weil@topiq.es>

## Wire format, for the record

The tag content is a two-element array:

1. the key set, as an array, in the order the keys appear in each row;
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

Every row must have exactly as many values as there are keys; a decoder that
finds otherwise must reject the item rather than pad or truncate. Keys must be
distinct. Both are enforced by boring's readers on each platform.

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
