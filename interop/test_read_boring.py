#!/usr/bin/env python3
"""Executable proof that Python can read boring's output.

This is not an illustrative snippet. It runs in CI against a committed fixture,
so a change to boring's wire format breaks it — which is the only way a
documented example stays true.

    python3 interop/test_read_boring.py

Regenerate the fixture with `bin/regen-interop` after a deliberate wire change.
"""

import datetime
import os
import sys
import uuid
from decimal import Decimal
from fractions import Fraction

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from read_boring import Keyword, Record, Symbol, load  # noqa: E402

FIXTURE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixture.cbor")

EXPECTED = {
    # --- things every CBOR library already gives you --------------------
    "string":       "hello",
    "string-utf8":  "héllo wörld 💩",
    "int":          42,
    "int-negative": -7,
    "float":        1.5,
    "bool-true":    True,
    "bool-false":   False,
    "null":         None,
    "vector":       [1, 2, 3],
    "bytes":        b"\x01\x02\xfd",

    # --- registered tags cbor2 handles natively -------------------------
    "uuid":     uuid.UUID("9682952b-fafa-4b41-8e4a-31ae948d6f08"),
    "bignum":   18446744073709551616,
    # Scale is preserved: Decimal("1.50") != Decimal("1.5") under as_tuple().
    "decimal":  Decimal("1.50"),
    "ratio":    Fraction(22, 7),
    "instant":  datetime.datetime(2009, 2, 13, 23, 31, 30, 123000,
                                  tzinfo=datetime.timezone.utc),
    "set":      {1, 2, 3},

    # --- registered tags cbor2 does NOT implement (read_boring does) ----
    "long-array":   [1, -2, 3],
    "double-array": [1.5, -2.5],

    # --- Clojure-specific meaning, via tag 39 and tag 27 ----------------
    "keyword":      Keyword(":user/name"),
    "keyword-bare": Keyword(":simple"),
    "symbol":       Symbol("my.ns/sym"),
    "map":          {Keyword(":a"): 1, Keyword(":b"): "two"},
    "record":       Record("gen-interop-fixture/Point",
                           {Keyword(":x"): 3, Keyword(":y"): 4}),

    # Stringref at the exact threshold: a 3-byte string IS table-eligible.
    # A reader using `>` instead of `>=` desyncs its table here and every
    # later index resolves to the wrong string.
    "sr-threshold": ["abc", "abc", "wxyz", "wxyz"],

    # --- boring's one extension: tag 39649, shaped array ----------------
    "shaped": [{Keyword(":e"): 1, Keyword(":a"): Keyword(":x")},
               {Keyword(":e"): 2, Keyword(":a"): Keyword(":y")}],

    # RAGGED ROWS. The shape's keys are the UNION of every row's, so a row can
    # lack one: `undefined` (0xf7) marks a gap in the middle, and a SHORT ROW
    # marks a missing tail. Neither key appears in the reconstructed map.
    #
    # `:a` in the first row is PRESENT with the value None -- `null` (0xf6) is
    # a value, not absence -- so a reader that filters on falsiness rather than
    # on `cbor2.undefined` fails right here.
    #
    # This case exists because the reader reconstructed rows with
    # `dict(zip(...))`, which is right for a short row by accident and wrong
    # for a gap: it produced a phantom key holding `undefined`. CI was green,
    # because nothing in the fixture had a ragged row.
    "ragged": [{Keyword(":a"): None, Keyword(":b"): 1, Keyword(":c"): 2},
               {Keyword(":a"): 3, Keyword(":c"): 4},
               {Keyword(":a"): 5}],
}


def main():
    with open(FIXTURE, "rb") as fp:
        actual = load(fp)

    failures = []

    missing = set(EXPECTED) - set(actual)
    extra = set(actual) - set(EXPECTED)
    if missing:
        failures.append(f"fixture is missing keys: {sorted(missing)}")
    if extra:
        failures.append(
            f"fixture has keys this test does not check: {sorted(extra)} — "
            "add them, do not ignore them")

    for key, want in EXPECTED.items():
        if key not in actual:
            continue
        got = actual[key]
        if got != want:
            failures.append(f"{key}: expected {want!r}, got {got!r}")
        elif type(got) is not type(want) and not isinstance(got, type(want)):
            failures.append(
                f"{key}: value equal but type differs — "
                f"expected {type(want).__name__}, got {type(got).__name__}")

    # Decimal scale survives: 1.50M and 1.5M must stay distinguishable, which
    # `==` alone does NOT check (Decimal("1.50") == Decimal("1.5") is True).
    if actual.get("decimal") is not None:
        if actual["decimal"].as_tuple().exponent != -2:
            failures.append(
                f"decimal lost its scale: {actual['decimal'].as_tuple()!r}")

    # A keyword must not compare equal to the bare string, or a consumer using
    # them as dict keys silently conflates :a with "a".
    if actual.get("keyword") == "user/name":
        failures.append("Keyword compares equal to the un-prefixed string")

    if failures:
        print(f"FAIL — {len(failures)} problem(s):")
        for f in failures:
            print(f"  - {f}")
        return 1

    print(f"ok — {len(EXPECTED)} values read from boring's CBOR by Python")
    return 0


if __name__ == "__main__":
    sys.exit(main())
