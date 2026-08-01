"""Regenerate test/boring/wg_bad.cljc from the CBOR WG's not-well-formed corpus.

Source: github.com/cbor-wg/cbor-test-vectors, tests/rfc8949/bad.edn
(the official WG corpus; note github.com/cbor/test-vectors is a DIFFERENT,
older repo that only carries appendix_a.json).

    curl -sL -o dev/resources/wg-bad.edn \\
      https://raw.githubusercontent.com/cbor-wg/cbor-test-vectors/main/tests/rfc8949/bad.edn
    python3 dev/gen_wg_bad.py
"""
import re, os

HERE = os.path.dirname(__file__)
src = open(os.path.join(HERE, 'resources', 'wg-bad.edn')).read()
entries = re.findall(
    r'\{\s*"description":\s*"((?:[^"\\]|\\.)*)",\s*"encoded":\s*h\'([0-9a-fA-F]*)\'', src)

EXEMPT = {
    # The one entry boring accepts by default: ~500 nested `81`. Our default
    # :max-depth is 1024, so this is a depth policy rather than a
    # well-formedness question. Asserted rejected at :max-depth 256 instead.
    'array: deeply-nested missing item':
        "a recursion bomb ~500 deep; boring's default :max-depth is 1024, so this "
        "is a depth-policy choice rather than a well-formedness failure. "
        "Rejected with :max-depth 256.",
}

out = [
    ";; GENERATED from cbor-wg/cbor-test-vectors tests/rfc8949/bad.edn",
    ";; Regenerate with dev/gen_wg_bad.py — do not hand-edit.",
    ";;",
    ";; The official CBOR working group's not-well-formed corpus. Every entry",
    ";; must be REJECTED with a typed boring error. `:exempt-reason` marks entries",
    ";; we deliberately accept under default options, with the reason.",
    "",
    "(ns boring.wg-bad)",
    "",
    "(def cases",
    "  [",
]
for desc, hexs in entries:
    esc = desc.replace('\\', '\\\\').replace('"', '\\"')
    extra = ''
    if desc in EXEMPT:
        extra = ' :exempt-reason "%s"' % EXEMPT[desc]
    out.append('   {:desc "%s" :hex "%s"%s}' % (esc, hexs.lower(), extra))
out += ["  ])", ""]

open(os.path.join(HERE, '..', 'test', 'boring', 'wg_bad.cljc'), 'w').write("\n".join(out))
print("wrote %d cases, %d exempt" % (len(entries), sum(1 for d, _ in entries if d in EXEMPT)))
