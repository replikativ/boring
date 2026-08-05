#!/usr/bin/env python3
"""Do boring and cbor2 produce the SAME BYTES for a canonical encoding?

`test_read_boring.py` proves cbor2 can read what boring writes. That can be
right by accident: a reader can be wrong in the same direction as the writer
and the round trip still closes. This is the stronger claim -- two independent
implementations, given the same value, emit the identical octet sequence.
That is the entire point of a deterministic profile.

The corpus lives in `interop/canonical_fixture.cbor`, written by boring. Its
row format and the reasoning behind it are documented in
`interop/gen_canonical_fixture.clj`; nothing about which VALUES are tested
lives in this file, because two hand-synced case lists in two languages drift.

THREE CHECKS RUN HERE, in decreasing order of independence.

1. cbor2's pure-Python canonical encoder against boring's
   `:canonical-rfc7049`. cbor2 5.x's `canonical=True` is the length-first key
   order of RFC 8949 4.2.3 (which is RFC 7049 3.9), and boring models that as
   a separate profile precisely so the comparison can be exact. This is the
   real independent check and any mismatch is a FAILURE.

2. cbor2's C EXTENSION, same comparison. The C extension and the pure-Python
   module are two implementations of one specification shipped in one
   package, and THEY DO NOT AGREE: `_cbor2.dumps(65504.0, canonical=True)` is
   `fa477fe000` where `cbor2._encoder.dumps` gives `f97bff`. A divergence is
   only tolerated when cbor2 disagrees with ITSELF and its pure-Python side
   agrees with boring -- i.e. when the evidence points at cbor2. If the C
   extension ever disagrees with boring in a way the pure-Python side does
   NOT, that is a hard failure and boring is the suspect.

3. boring's `:canonical` profile (RFC 8949 4.2.1, bytewise key order) against
   a reordering of cbor2's OWN octets. Nothing external implements bytewise
   ordering here -- cbor2 and ciborium are both length-first -- so this one is
   ASSISTED, not independent: `assemble()` below applies an ordering rule
   locally over per-item encodings that cbor2 produced. It is kept honest by
   also assembling the LENGTH-FIRST order and requiring that to equal cbor2's
   own canonical output byte for byte. If `assemble` were wrong, that
   self-check fails before it can bless anything.

Run: python3 interop/test_canonical_bytes.py
"""
import os
import sys

import cbor2

# The two stacks, kept strictly apart. `cbor2.dumps` is the C extension when it
# is installed; `cbor2._encoder.dumps` is always the Python one. They must also
# be given values decoded by their OWN decoder: the C extension's CBORTag and
# FrozenDict are different classes from `cbor2._types`', and the pure encoder
# refuses to serialise the C ones.
# cbor2 5.5 renamed the pure-Python modules with a leading underscore. Both
# layouts are accepted because the two are the same code under two names, and
# pinning the developer's machine is not something this file can do -- CI runs
# whatever `pip3 install cbor2` yields on its image, which is how this broke:
# green on a 5.8.0 workstation, ImportError on CI's older build.
try:                                                   # cbor2 >= 5.5
    from cbor2 import _decoder as py_decoder  # noqa: E402
    from cbor2 import _encoder as py_encoder  # noqa: E402
    from cbor2._types import CBORTag as PyCBORTag  # noqa: E402
    from cbor2._types import FrozenDict as PyFrozenDict  # noqa: E402
except ImportError:                                    # cbor2 < 5.5
    from cbor2 import decoder as py_decoder  # noqa: E402
    from cbor2 import encoder as py_encoder  # noqa: E402
    from cbor2.types import CBORTag as PyCBORTag  # noqa: E402
    from cbor2.types import FrozenDict as PyFrozenDict  # noqa: E402

C_EXT = getattr(cbor2.dumps, "__module__", "") == "_cbor2"

FIXTURE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "canonical_fixture.cbor")


# --------------------------------------------------------------- rep specs
# See gen_canonical_fixture.clj. Two five-line expansions, so the 64 KiB
# length-header boundary cases do not put a megabyte in a committed fixture.

def expand_value(spec):
    _, kind, n, unit = spec
    if kind == "text":
        return unit * n
    if kind == "bytes":
        return bytes([unit]) * n
    if kind == "array":
        return [unit] * n
    raise ValueError("unknown rep kind " + repr(kind))


def expand_enc(spec):
    _, head, unit, n = spec
    return head + unit * n


# ----------------------------------------------------- the assisted check
# An encoder that delegates EVERY leaf to cbor2 and only decides map/set
# ordering itself. `head` is the one piece of CBOR this file spells out, and
# the corpus contains the 23/24/255/256/65535/65536 boundaries that would
# expose it if it were wrong.

def head(major, n):
    if n < 24:
        return bytes([(major << 5) | n])
    if n < 0x100:
        return bytes([(major << 5) | 24, n])
    if n < 0x10000:
        return bytes([(major << 5) | 25]) + n.to_bytes(2, "big")
    if n < 0x100000000:
        return bytes([(major << 5) | 26]) + n.to_bytes(4, "big")
    return bytes([(major << 5) | 27]) + n.to_bytes(8, "big")


def _sort_key(order):
    if order == "length-first":          # RFC 8949 4.2.3
        return lambda b: (len(b), b)
    return lambda b: b                   # RFC 8949 4.2.1, bytewise


def assemble(v, order):
    """Canonical bytes for `v` with map/set ordering `order`.

    Leaves go straight to cbor2. Only the ordering, the container heads and
    the tag heads are decided here.
    """
    key = _sort_key(order)
    if isinstance(v, (dict, PyFrozenDict)):
        items = [(assemble(k, order), assemble(x, order)) for k, x in v.items()]
        items.sort(key=lambda kv: key(kv[0]))
        return head(5, len(items)) + b"".join(k + x for k, x in items)
    if isinstance(v, (set, frozenset)):
        elems = sorted((assemble(e, order) for e in v), key=key)
        # Tag 258. RFC 8949 says nothing about ordering inside it; boring,
        # cbor2 and this function all reuse the map-key rule, which is a
        # shared convention rather than a standard. Noted, not claimed.
        return head(6, 258) + head(4, len(elems)) + b"".join(elems)
    if isinstance(v, (list, tuple)):
        return head(4, len(v)) + b"".join(assemble(e, order) for e in v)
    if isinstance(v, PyCBORTag):
        return head(6, v.tag) + assemble(v.value, order)
    return py_encoder.dumps(v, canonical=True)


# ------------------------------------------------------------------- report

def hexs(b, limit=48):
    h = b.hex()
    return h if len(h) <= limit * 2 else h[:limit * 2] + "...(%d B)" % len(b)


def main():
    with open(FIXTURE, "rb") as f:
        raw = f.read()

    # Each stack decodes with its own decoder, so each encoder sees its own
    # classes. The C decode is also what proves the fixture is readable at all.
    rows_c = cbor2.loads(raw)
    rows_py = py_decoder.loads(raw)

    if not rows_c:
        print("FAIL - the fixture is empty; the gate would pass vacuously")
        return 1
    if len(rows_c) != len(rows_py):
        print("FAIL - the two cbor2 decoders disagree about the fixture length")
        return 1

    failures = []
    c_ext_only = []          # cbor2 disagrees with itself; pure side backs boring
    checked = 0
    skipped = []
    order_differs = 0
    order_same = 0
    assisted = 0

    for row_c, row_py in zip(rows_c, rows_py):
        label = row_c[0]
        flags = set(row_c[4])

        exp7049 = expand_enc(row_py[2]) if "rep" in flags else row_py[2]
        exp8949 = row_py[3] or exp7049
        if exp8949 == exp7049:
            order_same += 1
        else:
            order_differs += 1

        if "py-collapse" in flags:
            # Python's dict/set equality merges keys CBOR keeps apart (1 and
            # 1.0, True and 1, 0 and -0.0). cbor2 cannot even represent the
            # decoded value, so this is a limitation of the checker, not a
            # disagreement. The Rust checker covers these.
            skipped.append(label)
            continue

        # The value travels as an embedded byte string, decoded here by each
        # stack's own decoder. See gen_canonical_fixture.clj for why.
        try:
            val_c = cbor2.loads(row_c[1])
            val_py = py_decoder.loads(row_py[1])
        except Exception as e:                       # noqa: BLE001
            failures.append("%s: cbor2 cannot decode the embedded value: %r"
                            % (label, e))
            continue
        if "rep" in flags:
            val_c = expand_value(val_c)
            val_py = expand_value(val_py)

        # ---- 1. the independent check
        try:
            got_py = py_encoder.dumps(val_py, canonical=True)
        except Exception as e:                       # noqa: BLE001
            failures.append("%s: cbor2 (pure) could not encode: %r" % (label, e))
            continue
        checked += 1
        agree_py = got_py == exp7049
        if not agree_py:
            failures.append("%s: boring %s != cbor2/pure %s"
                            % (label, hexs(exp7049), hexs(got_py)))

        # ---- 2. the C extension, same comparison
        if C_EXT:
            try:
                got_c = cbor2.dumps(val_c, canonical=True)
            except Exception as e:                   # noqa: BLE001
                failures.append("%s: cbor2 (C) could not encode: %r" % (label, e))
                got_c = None
            if got_c is not None and got_c != exp7049:
                if agree_py and got_c != got_py:
                    c_ext_only.append((label, hexs(exp7049), hexs(got_c)))
                else:
                    failures.append(
                        "%s: boring %s != cbor2/C %s (and the pure encoder does "
                        "NOT vindicate boring)" % (label, hexs(exp7049), hexs(got_c)))

        # ---- 3. the assisted bytewise check
        # Self-validate first: the same assembler, run with the LENGTH-FIRST
        # rule, must reproduce cbor2's own canonical output exactly.
        mine_len = assemble(val_py, "length-first")
        if mine_len != got_py:
            failures.append(
                "%s: the local assembler disagrees with cbor2's own canonical "
                "encoder (%s vs %s) -- check 3 is not trustworthy for this case"
                % (label, hexs(mine_len), hexs(got_py)))
            continue
        assisted += 1
        mine_byte = assemble(val_py, "bytewise")
        if mine_byte != exp8949:
            failures.append(
                "%s: boring :canonical (RFC 8949 4.2.1) %s != cbor2 octets in "
                "bytewise key order %s" % (label, hexs(exp8949), hexs(mine_byte)))

    print("corpus: %d cases (%d order-sensitive, %d order-invariant)"
          % (len(rows_c), order_differs, order_same))
    print("check 1  independent  cbor2 pure-Python vs boring :canonical-rfc7049"
          "  -- %d cases" % checked)
    print("check 2  independent  cbor2 C extension  vs boring :canonical-rfc7049"
          "  -- %s" % ("%d cases" % checked if C_EXT else "SKIPPED, no C extension"))
    print("check 3  assisted     boring :canonical vs cbor2 octets reordered "
          "bytewise -- %d cases" % assisted)
    if skipped:
        print("skipped in Python (host-language key collapse; Rust covers "
              "them): %s" % ", ".join(skipped))

    if c_ext_only:
        print()
        print("cbor2 DISAGREES WITH ITSELF on %d cases. In every one the "
              "pure-Python encoder emits exactly boring's bytes and the C "
              "extension does not, so the divergence is cbor2's:" % len(c_ext_only))
        for label, exp, got in c_ext_only[:6]:
            print("    %-22s boring & cbor2/pure %-14s cbor2/C %s" % (label, exp, got))
        if len(c_ext_only) > 6:
            print("    ... and %d more." % (len(c_ext_only) - 6))
        print("    The class is exact: sweeping all 65536 binary16 bit "
              "patterns, the C extension refuses binary16 for precisely the "
              "2048 values with exponent field 0x1e -- the top binade, "
              "32768.0 to 65504.0 and its mirror -- and agrees everywhere "
              "else. boring is right; RFC 8949 4.1 requires the shortest "
              "float encoding that preserves the value.")

    if failures:
        print()
        for f in failures:
            print("  " + f)
        print("FAIL - %d disagreements over %d cases" % (len(failures), len(rows_c)))
        return 1

    print()
    print("ok - %d values encode to identical bytes in boring and cbor2's "
          "pure-Python canonical encoder" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
