#!/usr/bin/env python3
"""Compare two outcome files. Reports acceptance and value disagreements."""
import sys, re, collections

FLOAT = re.compile(r"f(-?(?:\d+\.\d+(?:[eE][-+]?\d+)?|Infinity|NaN))")


def canon(s):
    # normalize float spellings: JVM Double.toString vs Python repr
    def f(m):
        t = m.group(1)
        try:
            return "f" + repr(float(t))
        except ValueError:
            return m.group(0)
    return FLOAT.sub(f, s)


def load(p):
    d = {}
    for line in open(p, encoding="utf-8", errors="replace"):
        parts = line.rstrip("\n").split("\t", 2)
        if len(parts) == 3:
            d[int(parts[0])] = (parts[1], parts[2])
    return d


def kind(res):
    return res.split(" ", 1)[0]


def main():
    a, b = load(sys.argv[1]), load(sys.argv[2])
    na, nb = sys.argv[3], sys.argv[4]
    limit = int(sys.argv[5]) if len(sys.argv) > 5 else 8
    accept_diff = collections.defaultdict(list)
    value_diff = []
    both_ok = 0
    for i in sorted(a):
        if i not in b:
            continue
        hexa, ra = a[i]
        hexb, rb = b[i]
        assert hexa == hexb, i
        ka, kb = kind(ra), kind(rb)
        if ka == "OK" and kb == "OK":
            both_ok += 1
            va = canon(ra[3:].split("  /*+")[0])
            vb = canon(rb[3:].split("  /*+")[0])
            if va != vb:
                value_diff.append((i, hexa, va, vb))
        elif ka != kb:
            accept_diff[(ka, kb)].append((i, hexa, ra, rb))
    print("compared %d docs; both-OK %d" % (len(a), both_ok))
    print("\n== ACCEPTANCE DIFFERENCES (%s vs %s) ==" % (na, nb))
    for k, v in sorted(accept_diff.items(), key=lambda kv: -len(kv[1])):
        print(" %s/%s : %d" % (k[0], k[1], len(v)))
        seen = set()
        shown = 0
        for (i, h, ra, rb) in sorted(v, key=lambda t: len(t[1])):
            sig = (ra.split(":")[0], rb.split(":")[0])
            if sig in seen and shown >= limit:
                continue
            seen.add(sig)
            print("   #%d %s\n     %s: %s\n     %s: %s" % (i, h, na, ra[:110], nb, rb[:110]))
            shown += 1
            if shown >= limit:
                break
    print("\n== VALUE DIFFERENCES: %d ==" % len(value_diff))
    for (i, h, va, vb) in sorted(value_diff, key=lambda t: len(t[1]))[:limit * 3]:
        print("   #%d %s\n     %s: %s\n     %s: %s" % (i, h, na, va[:160], nb, vb[:160]))


main()
