package org.replikativ.boring;

import clojure.lang.IPersistentCollection;
import clojure.lang.ITransientCollection;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashSet;
import clojure.lang.PersistentVector;
import clojure.lang.RT;
import clojure.lang.Symbol;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * CBOR reader over a byte[]. Companion to Writer; same prototype scope.
 */
public final class Reader {

    private static final VarHandle SHORT_BE =
        MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT_BE =
        MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG_BE =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private byte[] buf;
    private int pos;

    /**
     * Scratch for buildMap's duplicate-key hashes, reused across maps.
     *
     * A fresh int[n] per map is invisible in a timing benchmark and obvious in
     * an allocation one: decoding 200 five-key maps allocated 200 arrays.
     *
     * Safe as a field despite nested maps: buildMap runs only after every value
     * of its map has been read, so an inner map's check completes before the
     * outer map's begins, and nothing in it calls back into read().
     */
    private int[] dupHashes = new int[16];

    /** Unknown tags surface as TaggedValue rather than throwing. DATAHIKE-
     *  REQUIREMENTS.md §7 wants strict-by-default; set false for that. */
    public boolean tolerateUnknownTags = true;

    /** Which JVM type tag 1004 produces. LocalDate is the modern type and the
     *  default; java.sql.Date exists for code still on the JDBC type. */
    public boolean fullDateAsSqlDate = false;

    /** Which JVM type tag 0/1 produces. CBOR has one time concept; Date (ms)
     *  and Instant (ns) are a JVM distinction the wire cannot carry. */
    public boolean instantAsDate = true;

    public TagRegistry registry = TagRegistry.EMPTY;

    /**
     * Maximum container nesting depth.
     *
     * The decoder is recursive, so without a limit a document of repeated 0x81
     * bytes (each "an array of one") recurses once per byte: 100 KB of them
     * throws StackOverflowError. That is an Error, not an Exception, so a
     * caller's `catch Exception` will not stop it from taking out the thread.
     *
     * QCBOR and TinyCBOR both advertise never recursing at all, which is the
     * stronger design; a depth cap is the cheap equivalent for a recursive
     * decoder. 1024 is far beyond any legitimate Clojure value.
     */
    public int maxDepth = 1024;

    private int depth;

    private static final VarHandle SHORT_LE =
        MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_LE =
        MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The boring.data constructors the decoder needs, resolved once at class init
     * of this holder rather than assigned by boring.core at load time. See
     * Writer.Data for why: mutable statics were a data race on paper and, when
     * observed unset, produced silently wrong values here too — CBOR
     * `undefined` decoded as null instead of the distinct undefined value, and
     * a simple value threw an untyped NullPointerException straight through the
     * ex-info contract. `static final` makes both impossible.
     */
    static final class Data {
        static final clojure.lang.IFn MAKE_SIMPLE;
        static final clojure.lang.IFn MAKE_TAGGED;
        static final Object UNDEFINED;
        static final clojure.lang.IFn MAKE_UNKNOWN_RECORD;

        static {
            try {
                clojure.lang.RT.var("clojure.core", "require")
                    .invoke(clojure.lang.Symbol.intern("boring.data"));
                MAKE_SIMPLE = clojure.lang.RT.var("boring.data", "simple-value");
                MAKE_TAGGED = clojure.lang.RT.var("boring.data", "tagged-value");
                UNDEFINED = clojure.lang.RT.var("boring.data", "undefined").deref();
                MAKE_UNKNOWN_RECORD = clojure.lang.RT.var("boring.data", "unknown-record");
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    /** stringref namespace: index -> decoded payload (String, or interned ident). */
    private Object[] srStrings;
    private int srCount;
    /**
     * Whether a tag-256 namespace is currently open.
     *
     * This used to be implicit -- the table was simply global to the message --
     * which is not what the stringref spec says and produced WRONG VALUES on
     * legal third-party CBOR. Two sibling namespaces decoded as
     * `[["abcd","abcd"],["wxyz","abcd"]]`: the second namespace's reference 0
     * resolved against the first namespace's table. Nested namespaces failed
     * the same way, and a tag 25 outside any namespace was accepted rather
     * than rejected.
     *
     * boring's own writer emits exactly one namespace at the root, so its own
     * output was never affected -- which is precisely why the test suite did
     * not catch it, and why the reference reader in interop/rust (written
     * against the spec rather than against this file) implemented the scoping
     * this one lacked.
     */
    private boolean srActive;
    /** Parallel cache of the *interned* Keyword/Symbol per stringref index, so a
     *  repeated keyword costs one array load — the same trick as hako's symref. */
    private Object[] srIdents;

    public Reader(byte[] b) { this.buf = b; this.pos = 0; }

    public void reset(byte[] b) {
        this.reused = true;
        this.buf = b;
        this.pos = 0;
        this.depth = 0;
        srActive = false;
        if (srCount > 0) {
            java.util.Arrays.fill(srStrings, 0, srCount, null);
            java.util.Arrays.fill(srIdents, 0, srCount, null);
            srCount = 0;
        }
        // identKeys/identVals deliberately survive reset: interning is a pure
        // function of the bytes, so the cache stays valid across messages and
        // warms up over a decode loop. It is only discarded when a much larger
        // message arrives and the cache it was sized for is now too small --
        // the entries cannot simply be carried over, because the slot mask
        // changes with the size.
        if (identKeys != null && (b.length >>> 4) > identKeys.length
                && identKeys.length < 512) {
            identKeys = null;
        }
    }

    public int position() { return pos; }
    public boolean atEnd() { return pos >= buf.length; }

    /**
     * Read the next top-level item of a CBOR sequence (RFC 8742), clearing the
     * per-message stringref namespace first but keeping the ident cache, which
     * is a pure function of bytes and stays valid across items.
     */
    public Object readNext() {
        this.reused = true;
        depth = 0;
        srActive = false;
        if (srCount > 0) {
            java.util.Arrays.fill(srStrings, 0, srCount, null);
            java.util.Arrays.fill(srIdents, 0, srCount, null);
            srCount = 0;
        }
        return read();
    }

    private int u8()  { return buf[pos++] & 0xFF; }
    private int u16() { int v = ((short) SHORT_BE.get(buf, pos)) & 0xFFFF; pos += 2; return v; }
    private long u32(){ long v = ((int) INT_BE.get(buf, pos)) & 0xFFFFFFFFL; pos += 4; return v; }
    private long u64(){ long v = (long) LONG_BE.get(buf, pos); pos += 8; return v; }

    private int remaining() { return buf.length - pos; }

    private void enter() {
        if (++depth > maxDepth)
            throw Err.of("max-depth-exceeded",
                "boring: nesting deeper than maxDepth (" + maxDepth + ")",
                "max-depth", (long) maxDepth);
    }

    private void exit() { depth--; }

    /**
     * Validate a wire-supplied count before allocating anything for it.
     *
     * Without this, a handful of bytes declaring a huge array/map count causes a
     * multi-gigabyte allocation — `9a ff ff ff ff` is five bytes claiming
     * 4294967295 elements. Every element costs at least `minBytesEach` bytes on
     * the wire, so a count larger than the bytes left cannot be honest.
     *
     * This is the class of bug that makes "we validate lengths before
     * allocating" a claim worth testing rather than asserting.
     */
    private int checkCount(long n, int minBytesEach) {
        if (n < 0 || n > Integer.MAX_VALUE)
            throw Err.of("bad-count", "boring: count out of range: " + n, "count", n);
        if (n * minBytesEach > remaining())
            throw Err.of("bad-count",
                "boring: declared count " + n + " needs at least " + (n * minBytesEach)
                + " bytes but only " + remaining() + " remain",
                "count", n, "remaining", (long) remaining());
        return (int) n;
    }

    /** Read the argument of a header byte whose additional-info is `info`. */
    private long arg(int info) {
        if (info < 24) return info;
        switch (info) {
            case 24: return u8();
            case 25: return u16();
            case 26: return u32();
            case 27: return u64();
            default:
                throw Err.of("reserved-info", "boring: reserved additional-info " + info, "info", (long) info);
        }
    }

    /**
     * uint major. CBOR's uint64 range exceeds a signed long, so the top of the
     * range must widen to a bignum rather than wrap — `1bffffffffffffffff` is
     * 18446744073709551615, not -1. Silent truncation here is exactly what
     * datahike's dump requirements ask us not to do.
     */
    private Object uintValue(int info) {
        long v = arg(info);
        if (info == 27 && v < 0) {
            return clojure.lang.BigInt.fromBigInteger(
                new java.math.BigInteger(Long.toUnsignedString(v)));
        }
        return v;
    }

    /** negint major: the encoded argument n denotes -1-n. */
    private Object nintValue(int info) {
        long v = arg(info);
        if (info == 27 && v < 0) {
            java.math.BigInteger n = new java.math.BigInteger(Long.toUnsignedString(v));
            return clojure.lang.BigInt.fromBigInteger(
                java.math.BigInteger.valueOf(-1L).subtract(n));
        }
        return -1L - v;
    }

    /**
     * Validate a stringref index against what has actually been registered.
     *
     * Without this, `d8 19 05` returned a null slot as a decoded value —
     * attacker-chosen nil, including as a map key — and `d8 27 d8 19 00`
     * dereferenced an unfilled slot for a NullPointerException that escaped the
     * typed-error contract entirely. The argument is also uint64 on the wire,
     * so a plain (int) cast could produce a negative index.
     */
    private int stringrefIndex(long idx) {
        if (!srActive)
            throw Err.of("bad-stringref",
                "boring: stringref outside any tag-256 namespace", "index", idx);
        if (idx < 0 || idx >= srCount)
            throw Err.of("bad-stringref",
                "boring: stringref " + idx + " but only " + srCount
                + " strings have been seen", "index", idx, "count", (long) srCount);
        return (int) idx;
    }

    /**
     * The argument of tag 25, which MUST be an unsigned integer.
     *
     * This used to be `arg(u8() & 0x1F)`: the mask keeps the additional-info
     * bits and discards the major type, so a negative-integer header (major 1)
     * aliased onto an index -- `d8 19 20` is the integer -1 and was read as
     * index 0. Malformed CBOR silently became a valid reference.
     */
    /**
     * A tag-27 argument that must be a sequence. `RT.seq` on a Long raises
     * IllegalArgumentException -- a raw JVM exception through the typed-error
     * contract -- so the shape is checked before it is handed over.
     */
    private static Object seqableContent(Object argument, String name) {
        if (argument == null
                || argument instanceof java.util.List
                || argument instanceof clojure.lang.Seqable)
            return argument;
        throw Err.of("bad-tag-content",
            "boring: " + name + " must wrap an array, got "
            + argument.getClass().getSimpleName(), "tag", 27L);
    }

    private long stringrefArg() {
        int h = u8();
        if ((h >>> 5) != 0)
            throw Err.of("bad-tag-content",
                "boring: tag 25 content must be an unsigned integer, got major "
                + (h >>> 5), "tag", 25L);
        return arg(h & 0x1F);
    }

    private void srPut(Object s) {
        // Allocated on the first stringref rather than eagerly at 64 slots:
        // a document with no stringref namespace -- anything under the
        // :interop or :canonical profile, and all third-party CBOR -- paid
        // 544 bytes per Reader for two arrays it never touched. Doubling from
        // 16 keeps the growth cost logarithmic for documents that do use them.
        if (srStrings == null) {
            srStrings = new Object[16];
            srIdents = new Object[16];
        } else if (srCount == srStrings.length) {
            srStrings = java.util.Arrays.copyOf(srStrings, srCount << 1);
            srIdents  = java.util.Arrays.copyOf(srIdents, srCount << 1);
        }
        srStrings[srCount] = s;
        srIdents[srCount] = null;
        srCount++;
    }

    private static int minLenForIndex(int idx) {
        if (idx < 24) return 3;
        if (idx < 256) return 4;
        if (idx < 65536) return 5;
        return 7;
    }

    /**
     * Reject text that is not well-formed UTF-8.
     *
     * `new String(bytes, UTF_8)` silently substitutes U+FFFD for malformed
     * input, so `62 c3 28` decoded to "�(" rather than failing. RFC 8949
     * §3.1 requires text strings to be valid UTF-8, and accepting them anyway
     * has two concrete costs: decode-then-encode stops being byte-identical
     * (which is the property a signable dump depends on), and implementations
     * that do reject disagree with us — a parser differential.
     *
     * Cost is kept low by testing eight bytes at a time for any high bit; pure
     * ASCII, which is the overwhelming case, exits without per-byte work.
     */
    /**
     * Validate, and report whether the run was pure ASCII.
     *
     * The bulk scan below already establishes this; returning it lets the
     * caller construct the String with ISO_8859_1 instead of UTF_8. Since JDK 9
     * compact strings, an all-ASCII String has coder=LATIN1 and a byte[] that
     * is byte-identical to its UTF-8 encoding, so ISO_8859_1 construction is a
     * plain array copy with NO decode loop -- while UTF_8 construction scans
     * for multi-byte sequences a second time, work this method just did.
     *
     * The two produce identical Strings for ASCII input; the fast path is only
     * taken when this method has proved the input IS ASCII.
     */
    private boolean validateUtf8(int start, int n) {
        int i = start;
        int end = start + n;

        boolean ascii = true;
        while (i + 8 <= end) {                       // bulk ASCII scan
            if ((((long) LONG_BE.get(buf, i)) & 0x8080808080808080L) != 0L) break;
            i += 8;
        }
        while (i < end) {
            int b = buf[i] & 0xFF;
            if (b < 0x80) { i++; continue; }

            int len, min, cp;
            if ((b & 0xE0) == 0xC0)      { len = 2; cp = b & 0x1F; min = 0x80; }
            else if ((b & 0xF0) == 0xE0) { len = 3; cp = b & 0x0F; min = 0x800; }
            else if ((b & 0xF8) == 0xF0) { len = 4; cp = b & 0x07; min = 0x10000; }
            else throw Err.of("invalid-utf8",
                    "boring: invalid UTF-8 lead byte 0x" + Integer.toHexString(b)
                    + " at offset " + (i - start), "offset", (long) (i - start));

            if (i + len > end)
                throw Err.of("invalid-utf8",
                    "boring: truncated UTF-8 sequence at offset " + (i - start),
                    "offset", (long) (i - start));

            for (int j = 1; j < len; j++) {
                int c = buf[i + j] & 0xFF;
                if ((c & 0xC0) != 0x80)
                    throw Err.of("invalid-utf8",
                        "boring: invalid UTF-8 continuation byte at offset " + (i + j - start),
                        "offset", (long) (i + j - start));
                cp = (cp << 6) | (c & 0x3F);
            }
            if (cp < min)
                throw Err.of("invalid-utf8",
                    "boring: overlong UTF-8 encoding of U+" + Integer.toHexString(cp),
                    "offset", (long) (i - start));
            if (cp >= 0xD800 && cp <= 0xDFFF)
                throw Err.of("invalid-utf8",
                    "boring: UTF-8 encodes a surrogate U+" + Integer.toHexString(cp),
                    "offset", (long) (i - start));
            if (cp > 0x10FFFF)
                throw Err.of("invalid-utf8",
                    "boring: UTF-8 codepoint out of range U+" + Integer.toHexString(cp),
                    "offset", (long) (i - start));
            i += len;
            ascii = false;
        }
        return ascii;
    }

    /** Set false to accept malformed UTF-8 with U+FFFD substitution. */
    public boolean validateUtf8 = true;

    /**
     * Opt-in: reconstruct a defrecord from its wire name without a
     * registration. OFF by default, and the default is the security posture.
     *
     * Resolution uses classForNameNonLoading -- Class.forName(name, INITIALIZE
     * = FALSE) -- so naming a class in a document does not run its static
     * initialiser. nippy's equivalent uses RT.classForName, which passes
     * initialize = true, so a hostile document can trigger the <clinit> of any
     * class on the classpath (verified: a class whose initialiser writes a file
     * ran under classForName and did not under classForNameNonLoading). That
     * initialiser can open sockets, read configuration or spawn threads, and it
     * runs BEFORE anything checks whether the class is even a record.
     *
     * Here the class must already be loaded by the host application, and it is
     * initialised only if it passes the shape check below -- a public static
     * `create(IPersistentMap)`, which is what defrecord generates and little
     * else does.
     *
     * This still widens the trust boundary: it lets a document choose which of
     * YOUR OWN already-loaded record types to instantiate. Leave it off for
     * untrusted input; doc/SECURITY.md states the guarantee it relaxes.
     */
    public boolean autoConstructRecords = false;

    /**
     * name -> static create(IPersistentMap), or NOT_A_RECORD.
     *
     * Bounded: a hostile stream of distinct names must not grow it without
     * limit, and negative results are cached too so a repeated unknown name
     * does not re-resolve. Static because the answer is a property of the
     * classpath, not of one Reader, and reflection here is the dominant cost.
     */
    private static final Object NOT_A_RECORD = new Object();
    private static final int CTOR_CACHE_MAX = 1024;
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> CTOR_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static Object tryConstructRecord(String name, Object fields) {
        Object cached = CTOR_CACHE.get(name);
        if (cached == null) {
            cached = resolveRecordCreate(name);
            if (CTOR_CACHE.size() < CTOR_CACHE_MAX) CTOR_CACHE.putIfAbsent(name, cached);
        }
        if (cached == NOT_A_RECORD) return null;
        try {
            return ((java.lang.reflect.Method) cached).invoke(null, fields);
        } catch (Exception e) {
            // A create that throws is the caller's record rejecting the data.
            // Fall back rather than propagating a reflection wrapper.
            return null;
        }
    }

    private static Object resolveRecordCreate(String name) {
        try {
            Class<?> c = clojure.lang.RT.classForNameNonLoading(name);
            if (c == null || !clojure.lang.IRecord.class.isAssignableFrom(c))
                return NOT_A_RECORD;
            java.lang.reflect.Method m =
                c.getMethod("create", clojure.lang.IPersistentMap.class);
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) return NOT_A_RECORD;
            return m;
        } catch (Throwable t) {
            return NOT_A_RECORD;
        }
    }

    private String readTextRaw(int info) {
        int n = checkCount(arg(info), 1);
        boolean ascii = validateUtf8 && validateUtf8(pos, n);
        String s = ascii ? new String(buf, pos, n, StandardCharsets.ISO_8859_1)
                         : new String(buf, pos, n, StandardCharsets.UTF_8);
        pos += n;
        // Only INSIDE a namespace. A string outside one is not a table entry,
        // and registering it shifted every later index by one.
        if (srActive && n >= minLenForIndex(srCount)) srPut(s);
        return s;
    }

    /**
     * A repeated keyword or symbol is always exactly the five bytes
     * `D8 27 D8 19 <idx>` — tag 39 wrapping tag 25 wrapping an inline index.
     * Walking that through the general tag dispatch costs five separate reads
     * and two nested switches; hako's equivalent symref is a single byte, which
     * is the entire measured decode gap on keyword-dense payloads.
     *
     * Recognise the pattern with one 32-bit compare instead. Guarded by a
     * single byte test first, so non-tag values pay ~nothing.
     */
    private static final int KW_SYMREF_PREFIX = 0xD827D819;

    /**
     * Hot entry point; the rest of the dispatch lives in readGeneral().
     *
     * Honest note: this split was made on the theory that `read()` had grown
     * past HotSpot's FreqInlineSize and that inlining was the bottleneck.
     * Measuring it showed no meaningful effect — the real cost was identifier
     * re-interning (see identFromBytes). The split is kept because it is
     * harmless and keeps the hot path readable, not because it bought speed.
     */
    public Object read() {
        int b0 = buf[pos] & 0xFF;

        // Repeated keyword/symbol: D8 27 D8 19 <idx>
        if (b0 == 0xD8 && pos + 4 < buf.length
                && ((int) INT_BE.get(buf, pos)) == KW_SYMREF_PREFIX) {
            int idx = buf[pos + 4] & 0xFF;
            if (idx < 24) {                       // inline uint: byte IS the index
                pos += 5;
                stringrefIndex(idx);              // may not be registered yet
                Object cached = srIdents[idx];
                return cached != null ? cached : internAt(idx);
            }
        }

        // Inline small unsigned int — the single most common item in practice.
        if (b0 < 24) { pos++; return Long.valueOf(b0); }

        return readGeneral();
    }

    private Object internAt(int idx) {
        Object made = internIdent(stringrefText(idx));
        srIdents[idx] = made;
        return made;
    }

    /**
     * The table slot at `idx`, which must hold TEXT.
     *
     * A byte string legally occupies a stringref slot -- it has since byte
     * strings started taking indices -- so a document can point tag 39 at one.
     * The cast was unguarded, and `d9 0100 82 43 010203 d827 d81900` (a
     * namespace, a 3-byte byte string at index 0, then an identifier
     * referencing it) threw a raw ClassCastException through the typed-error
     * contract.
     */
    private String stringrefText(int idx) {
        Object o = srStrings[idx];
        if (!(o instanceof String))
            throw Err.of("bad-tag-content",
                "boring: tag 39 references stringref " + idx
                + ", which holds a byte string, not text", "tag", 39L);
        return (String) o;
    }

    private Object readGeneral() {
        int header = u8();
        int major = header >>> 5;
        int info = header & 0x1F;

        switch (major) {
            case 0: return uintValue(info);                 // uint
            case 1: return nintValue(info);                 // negint
            case 2: {                                       // byte string
                if (info == 31) return readIndefiniteBytes();
                int n = checkCount(arg(info), 1);
                byte[] bs = new byte[n];
                System.arraycopy(buf, pos, bs, 0, n);
                pos += n;
                // BYTE strings take stringref indices too, exactly as text
                // strings do. Omitting them here made our table shorter than
                // the writer's on the far side: cbor2 reading boring's output
                // for [<5 bytes>, "abcd", "abcd"] returned the BYTE STRING in
                // place of the second "abcd", because cbor2 had assigned index
                // 0 to the bytes and boring's writer had assigned it to
                // "abcd". Silently wrong data, in the outbound direction that
                // matters most for interop.
                if (srActive && n >= minLenForIndex(srCount)) srPut(bs);
                return bs;
            }
            case 3:                                         // text
                if (info == 31) return readIndefiniteText();
                return readTextRaw(info);
            case 4: {                                       // array
                if (info == 31) { enter(); try { return readIndefiniteArray(); } finally { exit(); } }
                int n = checkCount(arg(info), 1);
                // Shared singleton. Allocating a fresh empty vector per
                // occurrence was the worst case for memory amplification:
                // 2 MB of `80` bytes became 2 million distinct empty vectors
                // (121 MB, 64x) instead of one shared object.
                if (n == 0) return PersistentVector.EMPTY;
                enter();
                try {
                // adopt() takes the array as the vector's TAIL, so it is only
                // valid up to 32 elements. Beyond that, build transiently.
                if (n <= 32) {
                    Object[] items = new Object[n];
                    for (int i = 0; i < n; i++) items[i] = read();
                    return PersistentVector.adopt(items);
                }
                ITransientCollection tv = PersistentVector.EMPTY.asTransient();
                for (int i = 0; i < n; i++) tv = tv.conj(read());
                return tv.persistent();
                } finally { exit(); }
            }
            case 5: {                                       // map
                if (info == 31) { enter(); try { return readIndefiniteMap(); } finally { exit(); } }
                int n = checkCount(arg(info), 2);
                Object[] kvs = new Object[n * 2];
                enter();
                try {
                    for (int i = 0; i < n; i++) {
                        kvs[i * 2] = read();
                        kvs[i * 2 + 1] = read();
                    }
                } finally { exit(); }
                return buildMap(kvs, n);
            }
            case 6: {                                       // tag
                // CBOR tags are uint64. Truncating to int let
                // `db 00 00 00 01 00 00 00 27 ...` reach the tag-39 handler and
                // `da 80 00 00 00 ...` produce a NEGATIVE tag — and CLJS does
                // not truncate, so it was a parser differential too.
                long t = arg(info);
                enter();
                try { return readTagged(t); } finally { exit(); }
            }
            case 7: {                                       // simple / float
                switch (info) {
                    case 20: return Boolean.FALSE;
                    case 21: return Boolean.TRUE;
                    case 22: return null;                   // null
                    case 23: return Data.UNDEFINED;              // NOT nil — distinct value
                    case 24: return Data.MAKE_SIMPLE.invoke(Long.valueOf(u8()));
                    case 25: return readHalf();
                    case 26: return Float.intBitsToFloat((int) u32());
                    case 27: return Double.longBitsToDouble(u64());
                    default:
                        if (info < 20) return Data.MAKE_SIMPLE.invoke(Long.valueOf(info));
                        throw (info == 31
                            ? Err.of("unexpected-break",
                                "boring: break code outside an indefinite-length item")
                            : Err.of("reserved-simple-value",
                                "boring: reserved simple value " + info, "value", (long) info));
                }
            }
            default: throw Err.of("bad-major", "boring: unhandled major type " + major, "major", (long) major);
        }
    }

    // ---- indefinite-length items ------------------------------------------
    //
    // Decode-only by design: datahike's dump requirements ask for definite
    // lengths on the write path (deterministic, and the size is known before
    // writing), but a reader must accept what other implementations emit.

    private static final Object BREAK = new Object();

    private boolean atBreak() {
        return pos < buf.length && (buf[pos] & 0xFF) == 0xFF;
    }

    private Object readOrBreak() {
        if (atBreak()) { pos++; return BREAK; }
        return read();
    }

    /** One definite-length chunk of major type `major`, or BREAK. */
    private Object readChunk(int major) {
        if (atBreak()) { pos++; return BREAK; }
        int header = buf[pos] & 0xFF;
        if ((header >>> 5) != major)
            throw Err.of("bad-indefinite-chunk", "boring: indefinite-length item of major " + major
                + " contains a chunk of major " + (header >>> 5));
        if ((header & 0x1F) == 31)
            throw Err.of("bad-indefinite-chunk", "boring: indefinite-length chunks must have a definite length");
        return read();
    }

    private byte[] readIndefiniteBytes() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Object chunk;
        while ((chunk = readChunk(2)) != BREAK) {
            byte[] b = (byte[]) chunk;
            out.write(b, 0, b.length);
        }
        return out.toByteArray();
    }

    private String readIndefiniteText() {
        StringBuilder sb = new StringBuilder();
        Object chunk;
        while ((chunk = readChunk(3)) != BREAK) sb.append((String) chunk);
        return sb.toString();
    }

    private Object readIndefiniteArray() {
        ITransientCollection tv = PersistentVector.EMPTY.asTransient();
        Object item;
        while ((item = readOrBreak()) != BREAK) tv = tv.conj(item);
        return tv.persistent();
    }

    private Object readIndefiniteMap() {
        java.util.ArrayList<Object> kvs = new java.util.ArrayList<>();
        Object k;
        while ((k = readOrBreak()) != BREAK) {
            kvs.add(k);
            kvs.add(read());
        }
        return buildMap(kvs.toArray(), kvs.size()/2);
    }

    /**
     * Build a map from an interleaved k/v array, rejecting duplicate keys.
     *
     * `RT.map` does this via `createWithCheck`, whose O(n^2) loop calls
     * `Util.equiv` on every pair. For boxed numbers that lands in
     * `Numbers.equal`'s category dispatch, measured at 96 ns for a 5-pair map
     * (34 ns keyword keys, 118 ns string keys) — 10-37% of decode time.
     *
     * Prefiltering on hash makes the comparison nearly free: distinct keys
     * almost never collide, so `equiv` runs only for genuine candidates.
     *
     * The check is kept rather than dropped because differing duplicate-key
     * behaviour between implementations is a parser-differential attack
     * surface, and this decoder is meant to be safe on untrusted input
     * (RFC 8949 §5.6). `checkDuplicateKeys` disables it for trusted data.
     */
    public boolean checkDuplicateKeys = true;

    /**
     * `PersistentArrayMap.HASHTABLE_THRESHOLD` is package-private, and Clojure
     * 1.13 raised it for keyword-only maps, so probe it rather than hardcode.
     * Probed with string keys, i.e. conservatively: a keyword-keyed map on
     * 1.13 may become a hash map slightly earlier than the runtime would
     * choose, which is a representation difference, not a correctness one.
     */
    private static final int ARRAY_MAP_PAIRS = probePairs(false);
    /** Clojure 1.13 raised the array-map limit for keyword-only maps, so it is
     *  probed separately — an idea taken from hako's reader.clj. */
    private static final int ARRAY_MAP_KW_PAIRS = probePairs(true);

    private static int probePairs(boolean keywordKeys) {
        for (int pairs = 1; pairs <= 128; pairs++) {
            Object[] kvs = new Object[pairs * 2];
            for (int i = 0; i < pairs; i++) {
                kvs[i * 2] = keywordKeys
                    ? clojure.lang.Keyword.intern(null, "probe-key-" + i)
                    : (Object) ("probe-key-" + i);
                kvs[i * 2 + 1] = Integer.valueOf(i);
            }
            if (!(clojure.lang.RT.map(kvs) instanceof clojure.lang.PersistentArrayMap)) {
                return pairs - 1;
            }
        }
        return 8;
    }

    private static boolean allKeywordKeys(Object[] kvs, int n) {
        for (int i = 0; i < n; i++) {
            if (!(kvs[i * 2] instanceof clojure.lang.Keyword)) return false;
        }
        return true;
    }

    /** Would the runtime itself represent this map as a PersistentArrayMap? */
    private static boolean fitsArrayMap(Object[] kvs, int n) {
        if (n <= ARRAY_MAP_PAIRS) return true;
        return n <= ARRAY_MAP_KW_PAIRS && allKeywordKeys(kvs, n);
    }

    private Object buildMap(Object[] kvs, int n) {
        if (n == 0) return clojure.lang.PersistentArrayMap.EMPTY;
        if (checkDuplicateKeys && n > 1) {
            if (fitsArrayMap(kvs, n)) {
                if (dupHashes.length < n) dupHashes = new int[Math.max(n, dupHashes.length * 2)];
                final int[] hashes = dupHashes;
                for (int i = 0; i < n; i++) hashes[i] = clojure.lang.Util.hasheq(kvs[i * 2]);
                for (int i = 0; i < n; i++) {
                    for (int j = i + 1; j < n; j++) {
                        if (hashes[i] == hashes[j]
                                && clojure.lang.Util.equiv(kvs[i * 2], kvs[j * 2])) {
                            throw Err.of("duplicate-map-key", "boring: duplicate map key: " + kvs[i * 2], "key", kvs[i * 2]);
                        }
                    }
                }
            } else {
                // Above the array-map threshold the hash map's own build does
                // an O(n) check already — but it raises Clojure's
                // IllegalArgumentException, which is untyped as far as our
                // callers are concerned. Fuzzing surfaced it.
                try {
                    return clojure.lang.PersistentHashMap.createWithCheck(kvs);
                } catch (IllegalArgumentException e) {
                    throw Err.of("duplicate-map-key",
                        "boring: " + e.getMessage());
                }
            }
        }
        if (fitsArrayMap(kvs, n)) {
            return new clojure.lang.PersistentArrayMap(kvs);
        }
        return clojure.lang.PersistentHashMap.create(kvs);
    }

    /**
     * Tag content arrives from the wire and cannot be assumed to have the shape
     * the tag implies. Fuzzing found 154 untyped failures per 60k mutants here
     * — ClassCastException, NullPointerException and DateTimeParseException
     * leaking out of handlers that cast content straight to Number or parsed it
     * without guarding. Every accessor below reports a typed error instead.
     */
    private static Number numberContent(Object o, int tag) {
        if (!(o instanceof Number))
            throw Err.of("bad-tag-content",
                "boring: tag " + tag + " content must be a number, got "
                + (o == null ? "null" : o.getClass().getName()), "tag", (long) tag);
        return (Number) o;
    }

    private static String stringContent(Object o, int tag) {
        if (!(o instanceof String))
            throw Err.of("bad-tag-content",
                "boring: tag " + tag + " content must be a text string, got "
                + (o == null ? "null" : o.getClass().getName()), "tag", (long) tag);
        return (String) o;
    }

    private static java.util.List listContent(Object o, int tag, int size) {
        if (!(o instanceof java.util.List) || ((java.util.List) o).size() != size)
            throw Err.of("bad-tag-content",
                "boring: tag " + tag + " content must be a " + size + "-element array",
                "tag", (long) tag);
        return (java.util.List) o;
    }

    private static java.math.BigInteger integerContent(Object o, int tag) {
        if (o instanceof clojure.lang.BigInt) return ((clojure.lang.BigInt) o).toBigInteger();
        if (o instanceof java.math.BigInteger) return (java.math.BigInteger) o;
        if (o instanceof Long || o instanceof Integer || o instanceof Short || o instanceof Byte)
            return java.math.BigInteger.valueOf(((Number) o).longValue());
        throw Err.of("bad-tag-content",
            "boring: tag " + tag + " content must be an integer, got "
            + (o == null ? "null" : o.getClass().getName()), "tag", (long) tag);
    }



    /** RFC 8746 typed array: the tag wraps a byte string of packed LE elements. */
    /** f16 bits -> float, from a byte[] at an offset. Shares the scalar path's maths. */
    private static float halfBitsToFloat(int h) {
        int sign = (h >>> 15) & 0x1, exp = (h >>> 10) & 0x1F, mant = h & 0x3FF;
        float val;
        if (exp == 0)            val = (float) (mant * Math.pow(2, -24));
        else if (exp == 0x1F)    val = mant == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        else                     val = (float) ((mant + 1024) * Math.pow(2, exp - 25));
        return sign == 0 ? val : -val;
    }

    private Object readTypedArray(int tag, int elemSize) {
        // Decode straight out of `buf` where we can. Going through read()
        // always materialised the payload as an intermediate byte[], so a
        // long[1000] cost 8 KB of copy on top of the 8 KB result -- exactly
        // double, and the whole allocation gap to hako on that payload.
        //
        // "Where we can" matters: a byte string takes a stringref index, and
        // the table entry IS the byte[]. When this payload qualifies for one we
        // must materialise it anyway, or a later reference resolves to nothing
        // and our table desynchronises against every other implementation --
        // silently, and in the direction that hurts interop most. So the copy
        // is skipped only when no index is owed: always under :interop and
        // :canonical, and below the index threshold under :clojure.
        int h = u8();
        if ((h >> 5) != 2)
            throw Err.of("bad-tag-content", "boring: typed-array tag " + tag + " must wrap a byte string");
        int info = h & 0x1F;
        if (info == 31)
            throw Err.of("bad-tag-content",
                "boring: typed-array tag " + tag + " must wrap a definite-length byte string");
        int blen = checkCount(arg(info), 1);
        if (blen % elemSize != 0)
            throw Err.of("bad-tag-content", "boring: typed-array tag " + tag + " payload is not a multiple of " + elemSize);

        final byte[] b;
        final int off;
        if (srActive && blen >= minLenForIndex(srCount)) {
            b = java.util.Arrays.copyOfRange(buf, pos, pos + blen);
            off = 0;
            srPut(b);
        } else {
            b = buf;
            off = pos;
        }
        pos += blen;
        int n = blen / elemSize;
        switch (tag) {
            case 77: { short[] a = new short[n];
                       for (int i = 0; i < n; i++) a[i] = (short) SHORT_LE.get(b, off + (i << 1));
                       return a; }
            case 78: { int[] a = new int[n];
                       for (int i = 0; i < n; i++) a[i] = (int) INT_LE.get(b, off + (i << 2));
                       return a; }
            case 79: { long[] a = new long[n];
                       for (int i = 0; i < n; i++) a[i] = (long) LONG_LE.get(b, off + (i << 3));
                       return a; }
            case 85: { float[] a = new float[n];
                       for (int i = 0; i < n; i++)
                           a[i] = Float.intBitsToFloat((int) INT_LE.get(b, off + (i << 2)));
                       return a; }
            case 86: { double[] a = new double[n];
                       for (int i = 0; i < n; i++)
                           a[i] = Double.longBitsToDouble((long) LONG_LE.get(b, off + (i << 3)));
                       return a; }
            // ---- READ-ONLY interop. RFC 8746 defines 24 typed-array tags; boring
            // WRITES five (the signed little-endian integers plus f32/f64 LE),
            // because the JVM has no unsigned primitives and no float16 outside an
            // incubator module. It can READ all of the ones with a lossless JVM
            // representation, so an array from numpy, Rust or Go arrives as a real
            // primitive array instead of an inert TaggedValue.
            //
            // The rule throughout is the NARROWEST JVM PRIMITIVE THAT HOLDS EVERY
            // VALUE. uint8 becomes short[], not byte[]: a byte[] would preserve the
            // bits while silently reinterpreting 200 as -56, and a value that reads
            // back different is the failure this codec exists to prevent.
            case 64: case 68: {                     // uint8, uint8 clamped
                short[] a = new short[n];
                for (int i = 0; i < n; i++) a[i] = (short) (b[off + i] & 0xFF);
                return a; }
            case 72: {                              // sint8
                byte[] a = new byte[n];
                System.arraycopy(b, off, a, 0, n);
                return a; }
            case 65: case 69: {                     // uint16 BE / LE
                int[] a = new int[n];
                for (int i = 0; i < n; i++)
                    a[i] = (tag == 65 ? (short) SHORT_BE.get(b, off + (i << 1))
                                      : (short) SHORT_LE.get(b, off + (i << 1))) & 0xFFFF;
                return a; }
            case 73: {                              // sint16 BE
                short[] a = new short[n];
                for (int i = 0; i < n; i++) a[i] = (short) SHORT_BE.get(b, off + (i << 1));
                return a; }
            case 66: case 70: {                     // uint32 BE / LE
                long[] a = new long[n];
                for (int i = 0; i < n; i++)
                    a[i] = (tag == 66 ? (int) INT_BE.get(b, off + (i << 2))
                                      : (int) INT_LE.get(b, off + (i << 2))) & 0xFFFFFFFFL;
                return a; }
            case 74: {                              // sint32 BE
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = (int) INT_BE.get(b, off + (i << 2));
                return a; }
            case 67: case 71: {                     // uint64 BE / LE
                long[] a = new long[n];
                for (int i = 0; i < n; i++) {
                    long u = tag == 67 ? (long) LONG_BE.get(b, off + (i << 3))
                                       : (long) LONG_LE.get(b, off + (i << 3));
                    // Above 2^63 a uint64 has no lossless long. Refuse rather than
                    // hand back a negative number that silently is not the value.
                    if (u < 0)
                        throw Err.of("bad-tag-content",
                            "boring: uint64 element " + i + " exceeds Long/MAX_VALUE",
                            "tag", (long) tag);
                    a[i] = u;
                }
                return a; }
            case 75: {                              // sint64 BE
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = (long) LONG_BE.get(b, off + (i << 3));
                return a; }
            case 80: case 84: {                     // float16 BE / LE -- widened, lossless
                float[] a = new float[n];
                for (int i = 0; i < n; i++)
                    a[i] = halfBitsToFloat((tag == 80 ? (short) SHORT_BE.get(b, off + (i << 1))
                                                      : (short) SHORT_LE.get(b, off + (i << 1))) & 0xFFFF);
                return a; }
            case 81: {                              // f32 BE
                float[] a = new float[n];
                for (int i = 0; i < n; i++)
                    a[i] = Float.intBitsToFloat((int) INT_BE.get(b, off + (i << 2)));
                return a; }
            case 82: {                              // f64 BE
                double[] a = new double[n];
                for (int i = 0; i < n; i++)
                    a[i] = Double.longBitsToDouble((long) LONG_BE.get(b, off + (i << 3)));
                return a; }
            default: throw Err.of("malformed", "boring: unhandled typed array " + tag);
        }
    }

    /** f16 decode — we never emit it, but interop requires reading it. */
    private Float readHalf() {
        int h = u16();
        int sign = (h >>> 15) & 0x1;
        int exp = (h >>> 10) & 0x1F;
        int mant = h & 0x3FF;
        float val;
        if (exp == 0) {
            val = (float) (mant * Math.pow(2, -24));
        } else if (exp != 31) {
            val = (float) ((mant + 1024) * Math.pow(2, exp - 25));
        } else {
            val = (mant == 0) ? Float.POSITIVE_INFINITY : Float.NaN;
        }
        return sign == 1 ? -val : val;
    }

    private Object readTagged(long tag) {
        // Tags are uint64. Only in-range values can reach the built-in switch;
        // anything else goes straight to the registry/TaggedValue path, so a
        // huge tag can no longer alias onto a built-in handler.
        if (tag < 0 || tag > Integer.MAX_VALUE) return unknownTag(tag);

        // A REGISTERED reader wins over the built-in one. It used to be consulted
        // only in unknownTag(), i.e. only for tags the switch below does not
        // handle -- so registering a reader for a tag boring knows natively was
        // SILENTLY IGNORED. Exactly the defect already fixed on the write side,
        // and adding the RFC 8746 typed-array tags made it bite: a consumer with
        // its own float16 type registered a reader for tag 84 and still got a
        // float[].
        //
        // Registration is an instruction. Consumers with their own numeric types
        // -- a Julia-style Float16, a fixed-point, a unit-carrying quantity --
        // need the built-in mapping to be a DEFAULT, not a ceiling.
        clojure.lang.IFn override = registry.readerFor(tag);
        if (override != null) return override.invoke(read());

        switch ((int) tag) {
            case 256: {                                      // stringref namespace
                // A fresh table, and the enclosing one restored on the way out.
                // Nested namespaces SHADOW rather than extend, so an index can
                // never leak across a boundary in either direction.
                Object[] savedStrings = srStrings;
                Object[] savedIdents  = srIdents;
                int      savedCount   = srCount;
                boolean  savedActive  = srActive;
                srStrings = null; srIdents = null; srCount = 0; srActive = true;
                try {
                    return read();
                } finally {
                    srStrings = savedStrings; srIdents = savedIdents;
                    srCount = savedCount; srActive = savedActive;
                }
            }
            case 25:                                         // stringref
                return srStrings[stringrefIndex(stringrefArg())];
            case 39: {                                       // identifier
                // Peek: a stringref here means we can reuse the interned ident.
                int save = pos;
                int h = u8();
                if ((h >>> 5) == 6) {                        // nested tag => stringref
                    int inner = (int) arg(h & 0x1F);
                    if (inner == 25) {
                        // stringrefArg + stringrefIndex, NOT a raw read. This
                        // path used to mask the major type and index srIdents
                        // directly, so `d8 27 d9 00 19 00` (an alternate-width
                        // stringref) threw a raw NullPointerException straight
                        // through the typed-error contract, and a negative
                        // header aliased to index 0.
                        int idx = stringrefIndex(stringrefArg());
                        Object cached = srIdents[idx];
                        if (cached != null) return cached;
                        Object made = internIdent(stringrefText(idx));
                        srIdents[idx] = made;
                        return made;
                    }
                }
                pos = save;
                // Literal text. Read the header ourselves so the payload can be
                // interned straight from the bytes — building a String here is
                // pure waste for an identifier we have already seen, and short
                // keywords never get a stringref index to spare us.
                int th = u8();
                if ((th >>> 5) != 3)
                    throw Err.of("bad-tag-content", "boring: tag 39 must wrap a text string, got major " + (th >>> 5));
                int n = checkCount(arg(th & 0x1F), 1);
                int start = pos;
                // Validated exactly as readTextRaw does. This path reads the
                // payload straight into the identifier cache to avoid building
                // a String, and skipped the UTF-8 check with it -- so
                // `d8 27 62 c3 28` produced an identifier containing a
                // replacement character while the ordinary text path and the
                // ClojureScript tag-39 path both rejected it. An optimised
                // branch that omits a check is where the generic tests have
                // their blind spot, because they exercise the general path.
                if (validateUtf8) validateUtf8(start, n);
                pos += n;
                Object ident = identFromBytes(start, n);
                // Keep the stringref index space in lockstep with the encoder:
                // it registers every literal above the threshold, so we must too.
                // Inside a namespace only -- see readTextRaw.
                if (srActive && n >= minLenForIndex(srCount)) {
                    srPut(new String(buf, start, n, StandardCharsets.UTF_8));
                    srIdents[srCount - 1] = ident;
                }
                return ident;
            }
            case 258: {                                      // set
                int h = u8();
                if ((h >>> 5) != 4)
                    throw Err.of("bad-tag-content",
                        "boring: tag 258 must wrap an array, got major " + (h >>> 5),
                        "tag", 258L);
                int n = checkCount(arg(h & 0x1F), 1);
                if (n == 0) return PersistentHashSet.EMPTY;
                Object[] items = new Object[n];
                for (int i = 0; i < n; i++) items[i] = read();
                clojure.lang.IPersistentSet set = PersistentHashSet.create(items);
                // Maps reject duplicate keys as an anti-differential measure;
                // sets silently collapsed them. Same rule, same reason.
                if (checkDuplicateKeys && set.count() != n)
                    throw Err.of("duplicate-set-element",
                        "boring: tag 258 declared " + n + " elements but "
                        + set.count() + " are distinct", "declared", (long) n);
                return set;
            }
            case 2:                                          // positive bignum
            case 3: {                                        // negative bignum
                Object bs = read();
                if (!(bs instanceof byte[]))
                    throw Err.of("bad-tag-content", "boring: bignum tag " + tag + " must wrap a byte string");
                java.math.BigInteger m =
                    new java.math.BigInteger(1, (byte[]) bs);
                java.math.BigInteger v = (tag == 2)
                    ? m
                    : java.math.BigInteger.valueOf(-1L).subtract(m);
                return clojure.lang.BigInt.fromBigInteger(v);
            }
            case 0: {                                        // RFC 3339 string
                String v = stringContent(read(), 0);
                java.time.Instant t;
                try {
                    t = java.time.Instant.parse(v);
                } catch (java.time.format.DateTimeParseException e) {
                    throw Err.of("bad-tag-content",
                        "boring: tag 0 content is not a valid RFC 3339 instant: " + v,
                        "tag", 0L, "value", v);
                }
                try {
                    return instantAsDate ? java.util.Date.from(t) : t;
                } catch (java.time.DateTimeException | ArithmeticException | IllegalArgumentException e) {
                    throw Err.of("bad-tag-content",
                        "boring: tag 0 instant does not fit a java.util.Date: " + t,
                        "tag", 0L);
                }
            }
            case 1: {                                        // epoch seconds
                Number v = numberContent(read(), 1);
                java.time.Instant t;
                try {
                    if (v instanceof Double || v instanceof Float) {
                        double d = v.doubleValue();
                        if (Double.isNaN(d) || Double.isInfinite(d))
                            throw Err.of("bad-tag-content",
                                "boring: tag 1 epoch is not finite: " + d, "tag", 1L);
                        long secs = (long) Math.floor(d);
                        long nanos = Math.round((d - secs) * 1e9);
                        t = java.time.Instant.ofEpochSecond(secs, nanos);
                    } else {
                        java.math.BigInteger secs = integerContent(v, 1);
                        if (secs.bitLength() > 63)
                            throw Err.of("bad-tag-content",
                                "boring: tag 1 epoch out of range: " + secs, "tag", 1L);
                        t = java.time.Instant.ofEpochSecond(secs.longValue());
                    }
                } catch (java.time.DateTimeException | ArithmeticException | IllegalArgumentException e) {
                    // Instant.ofEpochSecond throws for values outside its range,
                    // and Date.from throws separately. Fuzzing found both.
                    throw Err.of("bad-tag-content",
                        "boring: tag 1 epoch out of range: " + v, "tag", 1L, "value", v);
                }
                try {
                    return instantAsDate ? java.util.Date.from(t) : t;
                } catch (java.time.DateTimeException | ArithmeticException | IllegalArgumentException e) {
                    throw Err.of("bad-tag-content",
                        "boring: tag 1 instant does not fit a java.util.Date: " + t,
                        "tag", 1L);
                }
            }
            case 30: {                                       // rational
                java.util.List l = listContent(read(), 30, 2);
                java.math.BigInteger num = integerContent(l.get(0), 30);
                java.math.BigInteger den = integerContent(l.get(1), 30);
                if (den.signum() == 0)
                    throw Err.of("bad-tag-content",
                        "boring: tag 30 denominator is zero", "tag", 30L);
                return clojure.lang.Numbers.divide(num, den);
            }
            case 39649: {                                    // shaped array
                // [keys, rows]. Read the frame by hand rather than via read():
                // materialising each row as a vector only to convert it to a
                // map would undo the point of the extension.
                int outer = u8();
                if ((outer >>> 5) != 4 || checkCount(arg(outer & 0x1F), 1) != 2)
                    throw Err.of("bad-tag-content",
                        "boring: shaped array must wrap [keys rows]", "tag", 39649L);

                int kh = u8();
                if ((kh >>> 5) != 4)
                    throw Err.of("bad-tag-content",
                        "boring: shaped array keys must be an array", "tag", 39649L);
                int n = checkCount(arg(kh & 0x1F), 1);
                if (n == 0)
                    throw Err.of("bad-tag-content",
                        "boring: shaped array needs at least one key", "tag", 39649L);
                Object[] keys = new Object[n];
                for (int i = 0; i < n; i++) keys[i] = read();

                int rh = u8();
                if ((rh >>> 5) != 4)
                    throw Err.of("bad-tag-content",
                        "boring: shaped array rows must be an array", "tag", 39649L);
                int rows = checkCount(arg(rh & 0x1F), 1);

                ITransientCollection tv = PersistentVector.EMPTY.asTransient();
                for (int r = 0; r < rows; r++) {
                    int vh = u8();
                    if ((vh >>> 5) != 4)
                        throw Err.of("bad-tag-content",
                            "boring: shaped array row must be an array", "tag", 39649L);
                    int vn = checkCount(arg(vh & 0x1F), 1);
                    if (vn != n)
                        throw Err.of("bad-tag-content",
                            "boring: shaped array row has " + vn + " values but the shape has "
                            + n + " keys", "tag", 39649L);
                    // Interleave straight into the map's backing array — the
                    // keys are already decoded and interned, so there is no
                    // per-row key work at all.
                    Object[] kvs = new Object[n * 2];
                    for (int i = 0; i < n; i++) {
                        kvs[i * 2] = keys[i];
                        kvs[i * 2 + 1] = read();
                    }
                    tv = tv.conj(buildMap(kvs, n));
                }
                return tv.persistent();
            }
            case 27: {                                       // generic object
                // Tag 27 is "serialised language-independent object with type
                // name and constructor arguments", so the content is
                // [type-name, argument]. The argument is USUALLY a field map --
                // that is how boring writes a defrecord -- but it is not
                // required to be one, and pinning it to a map made the tag
                // unusable for positional types.
                //
                // Measured on 512 datahike Datoms: carrying the five values as
                // a field map costs 56.1 bytes each against 31.1 for a vector,
                // a 2x difference on the densest payload in the stack. The
                // tradeoff is that an UNREGISTERED positional type degrades to
                // an UnknownRecord wrapping a vector, so `(:field x)` yields
                // nil where a map payload would have answered. Records still
                // use maps and still degrade fully.
                Object arr = read();
                if (!(arr instanceof java.util.List) || ((java.util.List) arr).size() != 2)
                    throw Err.of("bad-tag-content", "boring: tag 27 must wrap [type-name argument]");
                java.util.List l = (java.util.List) arr;
                String name = stringContent(l.get(0), 27);
                Object argument = l.get(1);
                // Built-in collection markers, checked AFTER the registry so a
                // caller can still take these names for themselves.
                clojure.lang.IFn ctor = registry.recordCtor(name);
                if (ctor == null) {
                    switch (name) {
                        case "clojure/sorted-map": {
                            if (!(argument instanceof java.util.Map))
                                throw Err.of("bad-tag-content",
                                    "boring: clojure/sorted-map must wrap a map");
                            // assoc entry by entry, NOT PersistentTreeMap.create(seq):
                            // that factory takes a flat alternating key/value seq,
                            // and a seq OF A MAP yields MapEntries. Handing it those
                            // built a map whose keys were entries -- which still had
                            // the right TYPE and so passed a type check, while being
                            // unequal to the input and in the wrong order.
                            Object m2 = clojure.lang.PersistentTreeMap.EMPTY;
                            for (Object o : ((java.util.Map) argument).entrySet()) {
                                java.util.Map.Entry e2 = (java.util.Map.Entry) o;
                                m2 = ((clojure.lang.Associative) m2).assoc(e2.getKey(), e2.getValue());
                            }
                            return m2;
                        }
                        case "clojure/sorted-set":
                            return clojure.lang.PersistentTreeSet.create(
                                clojure.lang.RT.seq(seqableContent(argument, "clojure/sorted-set")));
                        case "clojure/with-meta": {
                            java.util.List l2 = listContent(argument, 27, 2);
                            Object m = l2.get(0), v2 = l2.get(1);
                            if (!(m instanceof clojure.lang.IPersistentMap))
                                throw Err.of("bad-tag-content",
                                    "boring: clojure/with-meta first element must be a map");
                            if (!(v2 instanceof clojure.lang.IObj))
                                throw Err.of("bad-tag-content",
                                    "boring: clojure/with-meta value cannot carry metadata");
                            return ((clojure.lang.IObj) v2)
                                .withMeta((clojure.lang.IPersistentMap) m);
                        }
                        case "clojure/char": {
                            String cs = stringContent(argument, 27);
                            if (cs.codePointCount(0, cs.length()) != 1)
                                throw Err.of("bad-tag-content",
                                    "boring: clojure/char must wrap exactly one character");
                            // A char is a UTF-16 code UNIT, so an astral
                            // codepoint is two of them and cannot be one
                            // Character. Refused rather than truncated to the
                            // high surrogate, which would decode to a
                            // different character than was written.
                            if (cs.length() != 1)
                                throw Err.of("bad-tag-content",
                                    "boring: clojure/char cannot hold a non-BMP codepoint");
                            return Character.valueOf(cs.charAt(0));
                        }
                        case "clojure/ex-info": {
                            java.util.List l2 = listContent(argument, 27, 3);
                            Object msg = l2.get(0), data = l2.get(1), cause = l2.get(2);
                            if (!(data instanceof clojure.lang.IPersistentMap))
                                throw Err.of("bad-tag-content",
                                    "boring: clojure/ex-info data must be a map", "tag", 27L);
                            return new clojure.lang.ExceptionInfo(
                                msg == null ? "" : stringContent(msg, 27),
                                (clojure.lang.IPersistentMap) data,
                                cause instanceof Throwable ? (Throwable) cause : null);
                        }
                        case "java/throwable": {
                            java.util.List l2 = listContent(argument, 27, 3);
                            Object cls = l2.get(0), msg = l2.get(1), cause = l2.get(2);
                            // Rebuilt as an ExceptionInfo carrying the original
                            // class name, NOT as that class: instantiating a
                            // type named on the wire is the one thing this
                            // reader does not do.
                            return new clojure.lang.ExceptionInfo(
                                msg == null ? "" : stringContent(msg, 27),
                                clojure.lang.RT.map(
                                    clojure.lang.Keyword.intern("boring", "throwable-class"),
                                    stringContent(cls, 27)),
                                cause instanceof Throwable ? (Throwable) cause : null);
                        }
                        case "java/boolean-array": {
                            java.util.List l2 = (java.util.List) seqableContent(argument, "java/boolean-array");
                            boolean[] a = new boolean[l2.size()];
                            for (int i = 0; i < a.length; i++) {
                                Object o = l2.get(i);
                                if (!(o instanceof Boolean))
                                    throw Err.of("bad-tag-content",
                                        "boring: java/boolean-array element is not a boolean", "tag", 27L);
                                a[i] = (Boolean) o;
                            }
                            return a;
                        }
                        case "java/char-array":
                            return stringContent(argument, 27).toCharArray();
                        case "java/string-array": {
                            java.util.List l2 = (java.util.List) seqableContent(argument, "java/string-array");
                            String[] a = new String[l2.size()];
                            for (int i = 0; i < a.length; i++) {
                                Object o = l2.get(i);
                                if (o != null && !(o instanceof String))
                                    throw Err.of("bad-tag-content",
                                        "boring: java/string-array element is not a string", "tag", 27L);
                                a[i] = (String) o;
                            }
                            return a;
                        }
                        case "java/object-array": {
                            java.util.List l2 = (java.util.List) seqableContent(argument, "java/object-array");
                            return l2.toArray();
                        }
                        case "java/period": {
                            String ps = stringContent(argument, 27);
                            try {
                                return java.time.Period.parse(ps);
                            } catch (java.time.format.DateTimeParseException e) {
                                // Wrapped: java.time's own exception is not an
                                // ex-info, so a caller catching ExceptionInfo
                                // per SECURITY.md was bypassed by any
                                // attacker-chosen text here.
                                throw Err.of("bad-tag-content",
                                    "boring: java/period is not an ISO-8601 period: " + ps,
                                    "tag", 27L);
                            }
                        }
                        case "clojure/queue": {
                            Object q = clojure.lang.PersistentQueue.EMPTY;
                            for (clojure.lang.ISeq s2 =
                                     clojure.lang.RT.seq(seqableContent(argument, "clojure/queue"));
                                 s2 != null; s2 = s2.next())
                                q = ((clojure.lang.IPersistentCollection) q).cons(s2.first());
                            return q;
                        }
                        default: break;
                    }
                }
                // No Class.forName: an unregistered name yields a plain value,
                // never an arbitrary instantiation.
                if (ctor != null) return ctor.invoke(argument);
                // The fallback is chosen by PAYLOAD SHAPE, not by any claim
                // about the sender -- tag 27 carries no record/non-record bit,
                // and could not: its content is [type-name, *constructor-args],
                // so a third element would read as another argument to every
                // foreign decoder, and a frame from Python has no flag at all.
                //
                // UnknownRecord presents the payload as a map, which is sound
                // only when the payload IS a map. It used to be returned for
                // every shape, so a positional frame -- what a registered
                // write handler emits, e.g. datahike's Datom as [e a v tx
                // added] -- produced a value that claimed IPersistentMap and
                // then threw raw ClassCastException from `keys` and
                // IllegalArgumentException from `assoc`/`into`, while
                // `(get x :e)` answered nil. A broken contract, not a caller
                // error.
                //
                // Anything else becomes a clojure.lang.TaggedLiteral, which
                // offers :tag and :form and never promises map-ness, so the
                // same operations fail as ordinary "not a map" errors.
                if (argument instanceof java.util.Map) {
                    if (autoConstructRecords) {
                        Object built = tryConstructRecord(name, argument);
                        if (built != null) return built;
                    }
                    return Data.MAKE_UNKNOWN_RECORD.invoke(name, argument);
                }
                return clojure.lang.TaggedLiteral.create(
                    clojure.lang.Symbol.intern(name), argument);
            }
            case 1002: {                                     // duration, RFC 9581 4
                Object v = read();
                if (!(v instanceof java.util.Map))
                    throw Err.of("bad-tag-content", "boring: tag 1002 must wrap a map",
                                 "tag", 1002L);
                java.util.Map m = (java.util.Map) v;
                Object sec = m.get(1L), nano = m.get(-9L);
                if (sec == null)
                    throw Err.of("bad-tag-content",
                                 "boring: tag 1002 has no base value (key 1)", "tag", 1002L);
                // Checked, not cast. A string here produced a raw
                // ClassCastException that walked straight through the typed-
                // error contract SECURITY.md advertises.
                if (!(sec instanceof Number))
                    throw Err.of("bad-tag-content",
                                 "boring: tag 1002 base value must be a number", "tag", 1002L);
                if (nano != null && !(nano instanceof Number))
                    throw Err.of("bad-tag-content",
                                 "boring: tag 1002 fraction must be a number", "tag", 1002L);
                return java.time.Duration.ofSeconds(((Number) sec).longValue(),
                                                    nano == null ? 0 : ((Number) nano).longValue());
            }
            case 1004: {                                     // full-date, RFC 8943
                Object v = read();
                if (!(v instanceof String))
                    throw Err.of("bad-tag-content",
                                 "boring: tag 1004 must wrap an RFC 3339 full-date string",
                                 "tag", 1004L);
                try {
                    java.time.LocalDate d = java.time.LocalDate.parse((String) v);
                    return fullDateAsSqlDate ? java.sql.Date.valueOf(d) : d;
                } catch (java.time.format.DateTimeParseException e) {
                    throw Err.of("bad-tag-content",
                                 "boring: tag 1004 content is not a full-date: " + v,
                                 "tag", 1004L);
                }
            }
            case 32: {                                       // URI, RFC 8949
                Object v = read();
                if (!(v instanceof String))
                    throw Err.of("bad-tag-content", "boring: tag 32 must wrap a text string",
                                 "tag", 32L);
                try {
                    return java.net.URI.create((String) v);
                } catch (IllegalArgumentException e) {
                    // A malformed URI from the wire is data, not a bug in us.
                    // Fail with our own typed error rather than letting a raw
                    // IllegalArgumentException escape the read path.
                    throw Err.of("bad-tag-content",
                                 "boring: tag 32 content is not a valid URI: " + e.getMessage(),
                                 "tag", 32L);
                }
            }
            case 35: {                                       // regex, RFC 7049
                Object v = read();
                if (!(v instanceof String))
                    throw Err.of("bad-tag-content", "boring: tag 35 must wrap a text string",
                                 "tag", 35L);
                try {
                    return java.util.regex.Pattern.compile((String) v);
                } catch (java.util.regex.PatternSyntaxException e) {
                    // Tag 35 says nothing about WHICH regex dialect, so a
                    // pattern written by another language may not compile here.
                    // A typed error is the honest outcome.
                    throw Err.of("bad-tag-content",
                                 "boring: tag 35 content is not a valid java.util.regex pattern: "
                                 + e.getDescription(), "tag", 35L);
                }
            }
            case 40: {                                       // RFC 8746 multi-dim, row-major
                java.util.List l = listContent(read(), 40, 2);
                Object dimsRaw = l.get(0);
                if (!(dimsRaw instanceof java.util.List))
                    throw Err.of("bad-tag-content",
                        "boring: tag 40 first element must be a dimensions array", "tag", 40L);
                java.util.List dims = (java.util.List) dimsRaw;
                if (dims.size() != 2)
                    throw Err.of("bad-tag-content",
                        "boring: only 2-dimensional tag 40 arrays are supported, got "
                        + dims.size() + " dimensions", "tag", 40L);
                int rows = ((Number) dims.get(0)).intValue();
                int cols = ((Number) dims.get(1)).intValue();
                Object flat = l.get(1);
                if (rows < 0 || cols < 0)
                    throw Err.of("bad-tag-content", "boring: negative tag 40 dimension",
                                 "tag", 40L);
                int declared = java.lang.reflect.Array.getLength(flat);
                // The dimensions come from the wire and the payload length comes
                // from the wire; if they disagree the item is malformed, and
                // allocating rows*cols on the strength of the dimensions alone
                // would be an unchecked allocation.
                if ((long) rows * cols != declared)
                    throw Err.of("bad-tag-content",
                        "boring: tag 40 dimensions " + rows + "x" + cols
                        + " do not match the " + declared + "-element payload", "tag", 40L);
                Class<?> comp = flat.getClass().getComponentType();
                Object out = java.lang.reflect.Array.newInstance(comp, rows, cols);
                for (int r = 0; r < rows; r++)
                    System.arraycopy(flat, r * cols, ((Object[]) out)[r], 0, cols);
                return out;
            }
            case 37: {                                       // UUID
                Object v = read();
                if (!(v instanceof byte[]) || ((byte[]) v).length != 16)
                    throw Err.of("bad-tag-content", "boring: tag 37 must wrap a 16-byte byte string");
                byte[] b = (byte[]) v;
                return new java.util.UUID((long) LONG_BE.get(b, 0),
                                          (long) LONG_BE.get(b, 8));
            }
            // RFC 8746, read-only for the ones boring does not write. f128 (83/87)
            // has no JVM type and stays an inert TaggedValue rather than being
            // approximated as a double.
            case 64: return readTypedArray(64, 1);          // uint8
            case 68: return readTypedArray(68, 1);          // uint8 clamped
            case 72: return readTypedArray(72, 1);          // sint8
            case 65: return readTypedArray(65, 2);          // uint16 BE
            case 69: return readTypedArray(69, 2);          // uint16 LE
            case 73: return readTypedArray(73, 2);          // sint16 BE
            case 66: return readTypedArray(66, 4);          // uint32 BE
            case 70: return readTypedArray(70, 4);          // uint32 LE
            case 74: return readTypedArray(74, 4);          // sint32 BE
            case 67: return readTypedArray(67, 8);          // uint64 BE
            case 71: return readTypedArray(71, 8);          // uint64 LE
            case 75: return readTypedArray(75, 8);          // sint64 BE
            case 80: return readTypedArray(80, 2);          // float16 BE
            case 84: return readTypedArray(84, 2);          // float16 LE
            case 81: return readTypedArray(81, 4);          // f32 BE
            case 82: return readTypedArray(82, 8);          // f64 BE
            case 77: return readTypedArray(77, 2);    // sint16 LE
            case 78: return readTypedArray(78, 4);          // sint32 LE
            case 79: return readTypedArray(79, 8);          // sint64 LE
            case 85: return readTypedArray(85, 4);          // f32 LE
            case 86: return readTypedArray(86, 8);          // f64 LE
            case 4: {                                        // decimal fraction
                java.util.List l = listContent(read(), 4, 2);
                java.math.BigInteger expBi = integerContent(l.get(0), 4);
                if (expBi.bitLength() > 31)
                    throw Err.of("bad-tag-content",
                        "boring: tag 4 exponent out of range: " + expBi, "tag", 4L);
                int exp = expBi.intValue();
                java.math.BigInteger mant = integerContent(l.get(1), 4);
                return new java.math.BigDecimal(mant, -exp);
            }
            default: return unknownTag(tag);
        }
    }

    /** A tag with no built-in meaning: registry, then TaggedValue, then error. */
    private Object unknownTag(long tag) {
        clojure.lang.IFn handler = registry.readerFor(tag);
        if (handler != null) return handler.invoke(read());
        // Present the true unsigned value for tags above 2^63 rather than a
        // negative long.
        Object tagValue = tag >= 0 ? (Object) Long.valueOf(tag)
            : clojure.lang.BigInt.fromBigInteger(
                new java.math.BigInteger(Long.toUnsignedString(tag)));
        if (tolerateUnknownTags) return Data.MAKE_TAGGED.invoke(tagValue, read());
        throw Err.of("unregistered-tag", "boring: unregistered tag " + tagValue,
                     "tag", tagValue);
    }

    /**
     * Direct-mapped cache from raw identifier bytes to the interned
     * Keyword/Symbol.
     *
     * Motivated by a profile, not a guess: stringref's length threshold means
     * an index costs at least 3 octets, so 2-byte keywords (`:e`, `:a`, `:v`,
     * `:x` — ubiquitous in Clojure) are NEVER assigned one and were being
     * re-decoded and re-interned on every occurrence. That showed up as 25.7%
     * of decode time in `internIdent`, plus String construction and
     * ConcurrentHashMap lookups on top.
     *
     * The threshold is normative — encoder and decoder must assign indices
     * identically — so it cannot simply be lowered. This cache sidesteps it
     * locally with no wire change, and covers long identifiers too.
     */
    /**
     * Allocated lazily and sized from the input, NOT eagerly at 512 slots.
     *
     * Eager allocation cost every `boring.core/decode` ~4 KB of zeroed arrays
     * before reading a byte. On a 40-byte map that dwarfed the decode itself:
     * measured 1094 ns through `decode` against 564 ns through `decode-with`
     * on a reused reader — a 1.9x tax on the primary public API, paid for a
     * cache a one-shot small decode can never amortise.
     *
     * Sizing from `buf.length` keeps the hit rate where it earns its keep
     * (`identFromBytes` was 25.7% of a keyword-heavy decode) while a small
     * message allocates 32 slots instead of 512.
     */
    private byte[][] identKeys;
    private Object[] identVals;
    private int identMask;

    /** ~1 slot per 16 input bytes, clamped to [8, 512] and rounded to a
     *  power of two so the mask stays a single AND.
     *
     *  The floor was 32, which meant a fresh Reader decoding a small map
     *  allocated two 32-slot arrays to intern three keywords -- and the cache
     *  cannot pay for itself within one small message, because nothing repeats
     *  there. It pays across a REUSED reader and inside a large one. Sizing
     *  from the input keeps both cases: 8 slots for a 56-byte map, 512 for a
     *  large document. */
    /** Below this input size, a reader used ONCE cannot amortise the cache. */
    private static final int IDENT_CACHE_MIN_INPUT = 128;

    /** Set when the reader is handed a second document; see identFromBytes. */
    private boolean reused = false;

    private void allocIdentCache() {
        int want = 8;
        int target = buf.length >>> 4;
        while (want < target && want < 512) want <<= 1;
        identKeys = new byte[want][];
        identVals = new Object[want];
        identMask = want - 1;
    }

    private static boolean bytesMatch(byte[] key, byte[] b, int start, int n) {
        if (key.length != n) return false;
        for (int i = 0; i < n; i++) if (key[i] != b[start + i]) return false;
        return true;
    }

    /** Intern the identifier spelled by buf[start, start+n), via the cache. */
    private Object identFromBytes(int start, int n) {
        // Below the threshold, intern directly and allocate nothing.
        //
        // The cache pays when an identifier REPEATS -- across a reused reader,
        // or within a document that mentions :user/name two hundred times. It
        // cannot pay inside one small message, where each keyword appears once
        // and the cache costs two arrays plus a copyOfRange per distinct
        // ident. Measured: a fresh Reader decoding a 56-byte three-keyword map
        // allocated 784 B beyond the 80 B Reader object, all of it cache.
        //
        // Gated on REUSE or size, which between them cover both ways the
        // cache can pay. Gating on size alone was measured and rejected: it
        // fixed the fresh path (784 B of overhead down to 80) and made the
        // REUSED path worse (1096 -> 1560 B), because a reader looping over
        // small messages then re-interned every identifier forever.
        if (identKeys == null) {
            if (!reused && buf.length < IDENT_CACHE_MIN_INPUT)
                return internIdentDirect(start, n);
            allocIdentCache();
        }
        int h = n;
        for (int i = 0; i < n; i++) h = h * 31 + buf[start + i];
        int slot = (h ^ (h >>> 16)) & identMask;
        byte[] k = identKeys[slot];
        if (k != null && bytesMatch(k, buf, start, n)) return identVals[slot];
        Object ident = internIdent(new String(buf, start, n, StandardCharsets.UTF_8));
        identKeys[slot] = java.util.Arrays.copyOfRange(buf, start, start + n);
        identVals[slot] = ident;
        return ident;
    }

    /** Intern without touching the cache -- identical result, no allocation
     *  beyond the String the interning itself needs. */
    private Object internIdentDirect(int start, int n) {
        return internIdent(new String(buf, start, n, StandardCharsets.UTF_8));
    }

    private static Object internIdent(String s) {
        if (!s.isEmpty() && s.charAt(0) == ':') return Keyword.intern(s.substring(1));
        return Symbol.intern(s);
    }
}
