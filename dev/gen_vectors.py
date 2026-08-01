import json

d = json.load(open(__import__('os').path.join(__import__('os').path.dirname(__file__),'resources','appendix_a.json')))

# Diagnostic-only vectors: hand-mapped to fixture markers.
HAND = {
 31:'[:f16 ##Inf]', 32:'[:f16 ##NaN]', 33:'[:f16 ##-Inf]',
 34:'[:f32 ##Inf]', 35:'[:f32 ##NaN]', 36:'[:f32 ##-Inf]',
 37:'[:f64 ##Inf]', 38:'[:f64 ##NaN]', 39:'[:f64 ##-Inf]',
 43:':undefined',
 44:'[:simple 16]', 45:'[:simple 24]', 46:'[:simple 255]',
 47:'[:instant "2013-03-21T20:04:00Z"]',
 48:'[:instant "2013-03-21T20:04:00Z"]',
 49:'[:instant "2013-03-21T20:04:00.5Z"]',
 50:'[:tagged 23 [:bytes 1 2 3 4]]',
 51:'[:tagged 24 [:bytes 100 73 69 84 70]]',
 52:'[:tagged 32 "http://www.example.com"]',
 53:'[:bytes]',
 54:'[:bytes 1 2 3 4]',
 67:'{1 2, 3 4}',
 71:'[:bytes 1 2 3 4 5]',   # indefinite byte string -> concatenated
}

# hex -> reason an RFC 8949 encoder must not produce it
# hex -> reason our canonical representation of the decoded value re-encodes
# to different bytes. Not a defect: the value decodes correctly, we just have
# one preferred way to write it.
ENCODING_DIFFERS = {
 'c11a514b67b0':
   'source uses tag 1 (epoch); boring writes instants as tag 0 (RFC 3339), which '
   'is lossless where a float epoch is not. Decodes to the same instant.',
 'c1fb41d452d9ec200000':
   'source uses tag 1 (epoch float); boring writes instants as tag 0 (RFC 3339).',
}

ENCODE_FORBIDDEN = {
 'f818': 'RFC 8949 3.3: an encoder MUST NOT issue two-byte sequences that start '
         'with 0xf8 and continue with a byte less than 0x20. Simple value 24 is '
         'decodable but not encodable; the vector predates RFC 8949.',
}

def edn(v, hexs):
    if isinstance(v, bool):  return 'true' if v else 'false'
    if v is None:            return 'nil'
    if isinstance(v, int):
        if -(2**63) <= v < 2**63: return str(v)
        return '[:bigint "%d"]' % v
    if isinstance(v, float):
        w = 'f16' if hexs.startswith('f9') else ('f32' if hexs.startswith('fa') else 'f64')
        return '[:%s %s]' % (w, repr(v))
    if isinstance(v, str):   return json.dumps(v)
    if isinstance(v, list):  return '[' + ' '.join(edn(x, '') for x in v) + ']'
    if isinstance(v, dict):
        return '{' + ', '.join('%s %s' % (edn(k,''), edn(x,'')) for k,x in v.items()) + '}'
    raise Exception('unmapped: %r' % v)

out = []
out.append(';; GENERATED from https://github.com/cbor/test-vectors (RFC 8949 Appendix A).')
out.append(';; Regenerate with scratchpad/gen_vectors.py — do not hand-edit the vector list.')
out.append(';;')
out.append(';; Fixture markers (realised by boring.conformance/->expected):')
out.append(';;   [:bytes b...]      byte array          [:tagged n v]  tagged value')
out.append(';;   [:simple n]        simple value        :undefined     the undefined value')
out.append(';;   [:bigint "n"]      arbitrary integer')
out.append(';;   [:f16 x] [:f32 x] [:f64 x]  float of a specific encoded width')
out.append(';;')
out.append(';; :roundtrip true means "re-encoding the decoded value reproduces :hex".')
out.append(';; That holds only under the :shortest float policy — see :float-width.')
out.append('')
out.append('(ns boring.vectors)')
out.append('')
out.append('(def appendix-a')
out.append('  [')
for i, e in enumerate(d):
    hexs = e['hex']
    if i in HAND:
        val = HAND[i]
    else:
        val = edn(e['decoded'], hexs)
    parts = [':hex %s' % json.dumps(hexs)]
    parts.append(':value %s' % val)
    parts.append(':roundtrip %s' % ('true' if e.get('roundtrip') else 'false'))
    if 'diagnostic' in e:
        parts.append(':diag %s' % json.dumps(e['diagnostic']))
    # float width drives which encode profile can reproduce :hex
    if hexs.startswith('f9'):   parts.append(':float-width :f16')
    elif hexs.startswith('fa'): parts.append(':float-width :f32')
    elif hexs.startswith('fb'): parts.append(':float-width :f64')
    # indefinite-length items: decode only, we never emit them
    if not e.get('roundtrip'): parts.append(':decode-only true')
    # RFC 8949 tightened what RFC 7049 allowed; these vectors survive in the
    # test-vector repo but a conforming RFC 8949 encoder must refuse them.
    if hexs in ENCODING_DIFFERS:
        parts.append(':encoding-differs true')
        parts.append(':encoding-differs-reason %s' % json.dumps(ENCODING_DIFFERS[hexs]))
    if hexs in ENCODE_FORBIDDEN:
        parts.append(':encode-forbidden true')
        parts.append(':encode-forbidden-reason %s' % json.dumps(ENCODE_FORBIDDEN[hexs]))
    out.append('   {%s}' % ' '.join(parts))
out.append('  ])')
out.append('')
open(__import__('os').path.join(__import__('os').path.dirname(__file__),'..','test','boring','vectors.cljc'),'w').write('\n'.join(out))
print('\n'.join(out[:14]))
print('...')
print('total vectors:', len(d))
