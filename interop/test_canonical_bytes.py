#!/usr/bin/env python3
"""Do boring and cbor2 produce the SAME BYTES for a canonical encoding?

`test_read_boring.py` proves cbor2 can read what boring writes. That can be
right by accident: a reader can be wrong in the same direction as the writer
and the round trip still closes. This is the stronger claim -- two independent
implementations, given the same value, emit the identical octet sequence.
That is the entire point of a deterministic profile, and until now nothing
checked it.

The corpus lives in `interop/canonical_fixture.cbor`, written by boring, as
[label, value, expected-hex] triples. cbor2 decodes the value and re-encodes
it with its OWN canonical writer; the bytes must match the hex boring wrote.
Defining the values once, in Clojure, is deliberate -- two hand-synced case
lists in two languages drift, and a drifting gate silently shrinks.

cbor2 5.8's `canonical=True` implements RFC 7049's canonical form: map keys
ordered by encoded LENGTH first, and no preference for the shortest float. So
the comparison is against boring's `:canonical-rfc7049`, which exists as a
separate profile for exactly this reason.

KNOWN AND ACCEPTED DIVERGENCE, asserted rather than skipped: RFC 8949 4.2.2
requires the shortest float that round-trips and RFC 7049 did not, so cbor2
writes 65504.0 as float32 `fa477fe000` where boring writes float16 `f97bff`.
boring follows the newer rule. Any float whose shortest form is narrower than
cbor2 picks is listed below; if that list ever changes, this test fails and
somebody has to say why.
"""
import os
import sys

import cbor2

FIXTURE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "canonical_fixture.cbor")

# Labels where cbor2 and boring legitimately differ, with the reason.
KNOWN_DIFFERENT = {
    "float-65504":
        "RFC 8949 4.2.2 requires the shortest float that round-trips and "
        "RFC 7049 did not. 65504.0 is the largest finite float16, so boring "
        "writes f97bff and cbor2 writes float32 fa477fe000. boring follows "
        "the newer rule; this is a deliberate divergence, not a defect.",
}


def main():
    with open(FIXTURE, "rb") as f:
        rows = cbor2.load(f)

    if not rows:
        print("FAIL — the fixture is empty; the gate would pass vacuously")
        return 1

    checked = 0
    failures = []
    for row in rows:
        label, value, expected = row[0], row[1], row[2]
        got = cbor2.dumps(value, canonical=True).hex()
        if label in KNOWN_DIFFERENT:
            if got == expected:
                failures.append(
                    f"{label}: listed as a known divergence but now AGREES "
                    f"({got}) — remove it from KNOWN_DIFFERENT")
            continue
        checked += 1
        if got != expected:
            failures.append(f"{label}: boring {expected} != cbor2 {got}")

    if failures:
        for f in failures:
            print("  " + f)
        print(f"FAIL — {len(failures)} of {len(rows)} canonical encodings differ")
        return 1

    print(f"ok — {checked} values encode to identical bytes in boring and cbor2 "
          f"({len(KNOWN_DIFFERENT)} known divergences)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
