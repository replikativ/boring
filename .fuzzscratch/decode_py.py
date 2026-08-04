#!/usr/bin/env python3
"""Decode a boring fuzz corpus with cbor2 and emit one outcome line per doc.

Line format:  <i>\t<hex>\t<OK norm | ERR class | UNTYPED class>
Normalization matches decode_jvm.clj's `norm`.
"""
import sys, struct, math, io
import cbor2
import collections.abc
from decimal import Decimal
from fractions import Fraction
import datetime, uuid


def norm(v, depth=0):
    if depth > 200:
        return "...deep"
    if v is None:
        return "null"
    if v is True:
        return "T"
    if v is False:
        return "F"
    if isinstance(v, int):
        return "i%d" % v
    if isinstance(v, float):
        if math.isnan(v):
            return "fNaN"
        if math.isinf(v):
            return "fInf" if v > 0 else "f-Inf"
        if v == 0.0 and math.copysign(1.0, v) < 0:
            return "f-0.0"
        return "f" + jvm_double_str(v)
    if isinstance(v, str):
        return "s" + clj_pr_str(v)
    if isinstance(v, (bytes, bytearray)):
        return "b" + bytes(v).hex()
    if isinstance(v, cbor2.CBORSimpleValue):
        return "SIMPLE%d" % v.value
    if isinstance(v, cbor2.CBORTag):
        return "TAG%d(%s)" % (v.tag, norm(v.value, depth+1))
    if v is cbor2.undefined:
        return "UNDEF"
    if isinstance(v, collections.abc.Mapping):
        return "{" + ",".join(sorted(norm(k, depth+1) + "=>" + norm(val, depth+1)
                                     for k, val in v.items())) + "}"
    if isinstance(v, (set, frozenset)):
        return "#{" + ",".join(sorted(norm(x, depth+1) for x in v)) + "}"
    if isinstance(v, (list, tuple)) or (isinstance(v, collections.abc.Sequence) and not isinstance(v,(str,bytes,bytearray))):
        return "[" + ",".join(norm(x, depth+1) for x in v) + "]"
    if isinstance(v, cbor2.CBORTag):
        return "TAG%d(%s)" % (v.tag, norm(v.value, depth+1))
    if isinstance(v, cbor2.CBORSimpleValue):
        return "SIMPLE%d" % v.value
    if v is cbor2.undefined:
        return "UNDEF"
    return "?%s:%r" % (type(v).__name__, v)


def clj_pr_str(s):
    out = ['"']
    for ch in s:
        if ch == '"':
            out.append('\\"')
        elif ch == "\\":
            out.append("\\\\")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "\b":
            out.append("\\b")
        elif ch == "\f":
            out.append("\\f")
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def jvm_double_str(d):
    """Approximate java.lang.Double.toString. Only used for equality between
    two implementations that both went through it, so we canonicalize instead:
    use repr of the shortest round-tripping decimal, uppercased exponent form
    is normalized away by the comparator."""
    return repr(d)


def main():
    corpus, out = sys.argv[1], sys.argv[2]
    strict = len(sys.argv) > 3 and sys.argv[3] == "strict"
    fi = open(corpus, "rb")
    fo = open(out, "w")
    i = 0
    while True:
        hdr = fi.read(4)
        if len(hdr) < 4:
            break
        n = struct.unpack(">I", hdr)[0]
        bs = fi.read(n)
        try:
            f = io.BytesIO(bs)
            dec = cbor2.CBORDecoder(f)
            v = dec.decode()
            # boring's `decode` reads the FIRST item and ignores trailing bytes,
            # so we do too -- but record whether there were leftovers.
            trailing = len(bs) - f.tell()
            res = "OK " + norm(v) + ("" if trailing == 0 else "  /*+%d*/" % trailing)
        except cbor2.CBORDecodeError as e:
            res = "ERR " + type(e).__name__ + ": " + str(e)[:80]
        except RecursionError:
            res = "ERR RecursionError"
        except Exception as e:
            res = "UNTYPED " + type(e).__name__ + ": " + str(e)[:80]
        fo.write("%d\t%s\t%s\n" % (i, bs.hex(), res))
        i += 1
    fo.close()
    print("done", i)


if __name__ == "__main__":
    sys.setrecursionlimit(20000)
    main()
