"""Reference reader: consuming boring's CBOR from Python.

boring emits standard CBOR (RFC 8949). Every tag it uses is either registered
with IANA or, in one case, a documented extension. `cbor2` already handles most
of them natively; this module adds the handful that carry Clojure-specific
meaning, plus the one extension.

Nothing here is required to *parse* boring's output -- CBOR is self-describing,
so plain `cbor2.loads` never fails on it. These helpers only turn the tagged
values into idiomatic Python.

    from read_boring import loads
    value = loads(open("data.cbor", "rb").read())

Run the tests with:

    python3 interop/test_read_boring.py
"""

import struct
from fractions import Fraction

import cbor2

# --- tags -------------------------------------------------------------------
#
# Handled natively by cbor2, listed for completeness:
#   0, 1    date/time            -> datetime
#   2, 3    bignum               -> int
#   4       decimal fraction     -> Decimal
#   30      rational             -> Fraction
#   37      UUID                 -> uuid.UUID
#   258     set                  -> set
#
# Handled below, because they carry meaning cbor2 cannot guess -- or, in the
# case of typed arrays, because cbor2 simply does not implement them:
TAG_STRINGREF_NAMESPACE = 256   # boring wraps its output in this
TAG_STRINGREF = 25              # back-reference into that namespace
TAG_IDENTIFIER = 39             # keyword or symbol
TAG_GENERIC_OBJECT = 27         # record: [type-name, field-map]
TAG_SET = 258                   # a set
TAG_SHAPED_ARRAY = 39649        # extension: [keys, [row-values...]]

# RFC 8746 typed arrays, little-endian. These are REGISTERED, standard tags,
# but cbor2 does not decode them -- it hands back a raw CBORTag. Unpacking is
# one struct call, and it is by far the fastest thing boring emits: the payload
# is a plain little-endian memory image, so a homogeneous numeric column costs
# one bulk read instead of one decode per element.
TYPED_ARRAYS = {
    77: ("h", 2),   # sint16
    78: ("i", 4),   # sint32
    79: ("q", 8),   # sint64
    85: ("f", 4),   # float32
    86: ("d", 8),   # float64
}


class Keyword(str):
    """A Clojure keyword. Subclasses str so it works as a dict key and prints
    readably; the leading ':' is kept so ':a' and 'a' stay distinguishable."""
    __slots__ = ()

    def __repr__(self):
        return f"Keyword({str.__repr__(self)})"


class Symbol(str):
    """A Clojure symbol. Distinguished from Keyword by the absence of ':'."""
    __slots__ = ()

    def __repr__(self):
        return f"Symbol({str.__repr__(self)})"


class Record:
    """A Clojure record: a named map. boring writes these as CBOR tag 27,
    the registered 'language-independent object with type name and
    constructor arguments'."""

    __slots__ = ("type_name", "fields")

    def __init__(self, type_name, fields):
        self.type_name = type_name
        self.fields = fields

    def __eq__(self, other):
        return (isinstance(other, Record)
                and self.type_name == other.type_name
                and self.fields == other.fields)

    def __repr__(self):
        return f"Record({self.type_name!r}, {self.fields!r})"


def _shaped_array(payload):
    """Tag 39649: [keys, [row-values, ...]] -> [dict, ...].

    An array whose elements are all maps sharing one key set is written with
    the keys ONCE. This is boring's only non-registered tag; reconstructing it
    needs no state beyond the tag's own contents, so this function is the
    entire implementation.
    """
    keys, rows = payload
    return [dict(zip(keys, row)) for row in rows]


def _tag_hook(*args):
    """cbor2 calls this with the CBORTag, but WHERE in the argument list has
    moved between releases -- CI installed a newer cbor2 than the machine this
    was written on and passed a bool where the tag was expected, so tag 39
    failed to decode with `'bool' object has no attribute 'tag'`. Pick the
    CBORTag out of whatever we are handed rather than pinning a signature."""
    tag = next((a for a in args if isinstance(a, cbor2.CBORTag)), None)
    if tag is None:
        raise TypeError(
            "cbor2 called tag_hook without a CBORTag: %r" % (args,))
    if tag.tag == TAG_IDENTIFIER:
        s = tag.value
        return Keyword(s) if s.startswith(":") else Symbol(s)
    if tag.tag == TAG_GENERIC_OBJECT:
        type_name, fields = tag.value
        return Record(type_name, fields)
    if tag.tag == TAG_SET:
        # Values may be unhashable (a decoded list); fall back to a list rather
        # than raising, so a document never fails to load over this.
        try:
            return frozenset(tag.value)
        except TypeError:
            return tag.value
    if tag.tag == TAG_SHAPED_ARRAY:
        return _shaped_array(tag.value)
    if tag.tag in TYPED_ARRAYS:
        fmt, width = TYPED_ARRAYS[tag.tag]
        raw = tag.value
        return list(struct.unpack(f"<{len(raw) // width}{fmt}", raw))
    return tag


def loads(data):
    """Decode boring's CBOR into idiomatic Python."""
    return cbor2.loads(data, tag_hook=_tag_hook)


def load(fp):
    return loads(fp.read())
