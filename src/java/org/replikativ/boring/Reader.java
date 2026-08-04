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
 * CBOR reader over a byte[] or a {@link ByteSource}. Companion to Writer.
 *
 * <p><b>One parser, two accessors.</b> This class serves heap arrays and
 * mmap'ed files from a single head parser, because two parsers is how a
 * decoder and its navigator drift apart -- silently, since a skip that lands
 * one byte off does not throw, it returns a plausible wrong value. The
 * STRUCTURE below is single-source. Only the loads branch, on whether `arr` is
 * present.
 *
 * <p>That branch is not a micro-optimisation. Reading everything through a
 * MemorySegment was implemented and measured: decode ran 14-50% slower across
 * every payload, and used ~2.5x the stack per recursive level. A
 * microbenchmark of the same access pattern predicted PARITY, because it times
 * a tight loop over a constant layout where the JIT hoists the bounds and
 * liveness checks out; a recursive, branchy decoder does not get that. With
 * the branch, decode is back to its byte[] numbers and the segment path still
 * exists.
 *
 * <p><b>No FFM type is named here</b>, deliberately. Naming one would force
 * {@code --release 22} and emit a class file JDK 21 cannot load, stranding the
 * incumbent LTS. The segment implementation lives in
 * {@code org.replikativ.boring.ffm.SegmentSource}, compiled separately against
 * JDK 22; a JDK 9 process never loads it. See {@link ByteSource}.
 */
public final class Reader {

    /**
     * Big-endian VarHandles over a byte[]. These are NOT the segment path --
     * the typed-array reader (RFC 8746) stages its payload into a byte[] and
     * decodes elements from there, so it keeps the array-view handles. See
     * the segment layouts below for everything else.
     */
    private static final VarHandle SHORT_BE =
        MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT_BE =
        MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG_BE =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The off-heap / mmap source, or null when decoding a plain byte[].
     *
     * Typed as ByteSource rather than MemorySegment on purpose: naming an FFM
     * type here would pin the whole class to JDK 22 and emit a class file JDK
     * 21 cannot load. See ByteSource.
     */
    private ByteSource src;
    private long limit;
    private long pos;

    /**
     * The backing array when {@code seg} wraps a whole byte[], else null.
     *
     * Used ONLY where the JDK demands a byte[] -- String construction, the
     * typed-array VarHandle path, the ident cache. Scalar reads always go
     * through the segment, so the hot path stays branch-free.
     *
     * Requiring the segment to cover the ENTIRE array is what makes offset 0
     * safe to assume: heapBase() hands back the array but not the segment's
     * offset within it, so a slice would alias the wrong bytes. ofArray always
     * satisfies this, which is the path decode(byte[]) takes.
     */
    private byte[] arr;

    /** Staging for bulk reads out of a native segment. Grows, never shrinks. */
    private byte[] scratch;
    /** Offset within the array {@link #arrayFor} returned. */
    private int scratchOff;

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
     * decoder.
     *
     * <p><b>The cap only works while it sits BELOW the depth at which the stack
     * actually dies.</b> Above it, the cap is decoration and the caller gets
     * the StackOverflowError this field exists to prevent. That is not
     * hypothetical: an interim version of this reader took every load through
     * a MemorySegment, which inlines a deep call tree into the recursive frame
     * and cost ~2.5x the stack per level -- the real limit fell from ~1500-2000
     * to ~600-700 and this default became unreachable. The conformance test
     * caught it INTERMITTENTLY, because the interpreted path survives deeper
     * than the JIT-compiled one.
     *
     * <p>Keeping FFM out of this class (see {@link ByteSource}) restored it.
     * Measured warm, the limit is now ~1400-1500 against ~1500-2000 before,
     * so 1024 has roughly the margin it always had. If you raise it, measure
     * -- 1024 is far beyond any legitimate Clojure value, and the headroom
     * above it is thinner than the number suggests.
     */
    public int maxDepth = 1024;

    /**
     * Cumulative decoded-ITEM budget, or 0 for unlimited (the default).
     *
     * `maxDepth` bounds how DEEP a document goes and `checkCount` bounds each
     * container against the bytes that remain, but nothing bounded the TOTAL --
     * so a valid document could amplify further than doc/SECURITY.md claimed.
     *
     * ITEMS, not bytes, because heap tracks OBJECT COUNT rather than payload
     * size. Measured, wire bytes to retained heap:
     *
     *   1 MB byte string          1.0x    one array, whatever its size
     *   long[] typed array        1.0x
     *   array of distinct ints    6.5x    one boxed Long per element
     *   short strings             7.8x
     *   map of 50 000 entries    11.7x
     *   many 2-element vectors   23.1x    the worst shape found
     *
     * The pattern is the point, and it is why a byte budget would be the wrong
     * instrument: bulk payloads do not amplify at all, while a ONE-BYTE
     * container head that becomes an object is the worst case. Amplification
     * tracks how many objects the document asks for, so that is what to count.
     */
    public long maxItems = 0;

    /** Items charged so far. Public so the sequence decoders and `boring.nav`
     *  can save and restore it around reading boring's OWN index frame, which
     *  must not spend the caller's budget. */
    public long items;

    /** Whether `v` parses as an ordinary RFC 3339 instant. Used to validate the
     *  non-leap part of a leap-second timestamp before preserving it. */
    private static boolean parsesAsInstant(String v) {
        try {
            java.time.OffsetDateTime.parse(v);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * RFC 3339 5.6 `date-time`, as a grammar rather than as whatever the host
     * parser tolerates. Kept character-for-character in step with the regex in
     * `boring.reader`'s tag-0 handler: the two platforms must agree on which
     * documents exist before they can agree on what they mean.
     *
     * Ranges are checked here where they are cheap and unambiguous (hour 00-23,
     * minute 00-59, second 00-60 for the leap second); the calendar itself --
     * whether 2020-02-30 exists -- is left to the date parser below, which is
     * the thing that actually knows.
     */
    private static final java.util.regex.Pattern RFC3339 =
        java.util.regex.Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[Tt]"
            + "(?:[01]\\d|2[0-3]):[0-5]\\d:(?:[0-5]\\d|60)"
            + "(?:\\.\\d{1,9})?"
            + "(?:[Zz]|[+-](?:(?:0\\d|1[0-7]):[0-5]\\d|18:00))");

    /**
     * RFC 3339 5.6 `full-date`, for tag 1004.
     *
     * `LocalDate.parse` is a java.time parser and accepts java.time's expanded
     * years, so `1004("+10000-01-01")` decoded to a LocalDate here and was
     * refused on ClojureScript. The same defect tag 0 had, in the tag next to
     * it, missed because a reproducer used an 11-byte length header for a
     * 12-character string and so tested a truncated string instead.
     */
    private static final java.util.regex.Pattern RFC3339_DATE =
        java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** `:60` in the seconds field -- valid RFC 3339, unrepresentable as an Instant. */
    private static boolean isLeapSecond(String v) {
        return secondsColon(v) >= 0;
    }

    /** Index of the colon before the SECONDS field, or -1. */
    private static int secondsColon(String v) {
        int i = v.indexOf(':');
        if (i < 0) return -1;
        int j = v.indexOf(':', i + 1);
        return (j >= 0 && j + 2 < v.length()
                && v.charAt(j + 1) == '6' && v.charAt(j + 2) == '0') ? j : -1;
    }

    /**
     * The timestamp with its leap second lowered to :59, for validation only.
     *
     * ONE occurrence, at a known index -- `String.replace(":60", ":59")` is
     * GLOBAL, so it repaired an impossible minute or UTC offset on the way past
     * and then validated the repair. `2016-12-31T23:60:60Z` (minute 60) and
     * `2016-12-31T23:59:60+00:60` (offset minute 60) were both preserved as
     * inert tag-0 values; ClojureScript's `.replace` takes only the first
     * occurrence and rejected all of them, so this was a parser differential on
     * top of accepting impossible dates.
     */
    private static String withoutLeapSecond(String v, int j) {
        return v.substring(0, j + 1) + "59" + v.substring(j + 3);
    }

    /**
     * Charge `n` items at once, for host objects a decoder BUILDS rather than
     * reads.
     *
     * `countItem` is called from `read()`, so it only ever sees things that
     * arrived as their own data item. A tag-40 payload arrives as ONE byte
     * string and is then expanded into one host object per element, which is
     * exactly the amplification the budget exists to bound and exactly what it
     * could not see: a 500 018-byte document declaring dimensions
     * [500000, 1, 1] built 71 MB of nested vectors -- 149x -- with
     * `{:max-items 100}` set and honoured.
     */
    private void countItems(long n) {
        if (maxItems > 0 && (items += n) > maxItems)
            throw Err.of("max-items-exceeded",
                "boring: decoded more than " + maxItems + " items",
                "max-items", maxItems);
    }

    private void countItem() {
        if (maxItems > 0 && ++items > maxItems)
            throw Err.of("max-items-exceeded",
                "boring: decoded more than " + maxItems + " items",
                "max-items", maxItems);
    }

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

    public Reader(byte[] b) { bindArray(b); }

    public Reader(ByteSource s) { bind(s); }

    /**
     * Point at `s`. The byte[] fast path is taken when the source reports it IS
     * an array -- see ByteSource.heapArray for why a partial view must not.
     */
    private void bind(ByteSource s) {
        this.src = s;
        this.limit = s.size();
        this.pos = 0;
        this.arr = s.heapArray();
    }

    /**
     * Bind a byte[] without going through {@link #bind}.
     *
     * Two allocations per reset hide in the generic path, and a decode loop
     * pays both: `MemorySegment.ofArray` wraps the array, and `heapBase()`
     * boxes its answer in an Optional. Neither is needed here -- ofArray
     * always covers the whole array, so `arr` is known without asking. Decode
     * of a small map measured 1128 B/op through the generic path against 976
     * before the segment migration; this is what closes that gap.
     */
    private void bindArray(byte[] b) {
        this.src = null;
        this.arr = b;
        this.limit = b.length;
        this.pos = 0;
    }

    public void reset(byte[] b) {
        // Re-binding the SAME array -- a loop over one scratch buffer, which is
        // exactly what reading items out of a mapping does -- skips the wrapper
        // entirely.
        if (arr != b) bindArray(b); else pos = 0;
        resetState();
    }

    public void reset(ByteSource s) {
        bind(s);
        resetState();
    }

    private void resetState() {
        items = 0;
        this.reused = true;
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
        if (identKeys != null && (limit >>> 4) > identKeys.length
                && identKeys.length < 512) {
            identKeys = null;
        }
    }

    public long position() { return pos; }
    public boolean atEnd() { return pos >= limit; }

    /** Total addressable size. A navigator walking a CBOR sequence needs it to
     *  know when it has run out of items. */
    public long size() { return limit; }

    /** Rewind to an absolute offset. The navigator descends with this. */
    public void seek(long p) { this.pos = p; }

    /**
     * Advance past the value at the cursor without building it.
     *
     * This is the inner loop of any lazy navigation over the wire format: to
     * reach the fifth key of a map you step over four values you do not want.
     * Measured 3-11x cheaper than decoding structure, and 19x for a bytestring
     * -- those are length-prefixed, so skipping one is a jump rather than a
     * walk. See `clojure -M:bench -m nav skip`.
     *
     * <p><b>Why it delegates when a stringref namespace is open.</b> A string
     * inside a namespace is a table entry whether or not anybody decodes it,
     * and a later reference is an INDEX into that table -- so skipping past
     * strings without registering them shifts every later index and silently
     * resolves the wrong string. Rather than duplicate the registration rules
     * here, where they could drift from the ones in readTextRaw, this falls
     * back to `read()` and discards the result: slower, and correct by
     * construction because it IS the reference implementation.
     *
     * <p>When no namespace is open the structural skip is safe even across a
     * nested tag-256 subtree, because such a namespace is scoped to that
     * subtree -- skip the whole thing and nothing outside can reference into
     * it.
     */
    public void skipValue() {
        if (srActive) { read(); return; }
        skipStructural();
    }

    private void skipStructural() {
        int h = u8();
        int major = h >>> 5;
        int info = h & 0x1F;
        switch (major) {
            case 0: case 1:
                arg(info);
                return;
            case 2: case 3: {
                if (info == 31) { skipIndefiniteChunks(major); return; }
                // NOT `pos += checkCount(arg(info), 1)`. Java saves the left
                // operand of += BEFORE evaluating the right, and arg() advances
                // pos to read a multi-byte length -- so the compound form threw
                // that advance away and landed one to eight bytes short. Only
                // for lengths >= 24, which is why it survived every small
                // fixture and was caught by the differential property test.
                int n = checkCount(arg(info), 1);
                pos += n;
                return;
            }
            // SKIP'S BOUND IS ABOUT STACK, NOT SEMANTICS. See enterSkip().
            //
            // Three attempts tried to make this agree with `read` exactly, and
            // each broke a different value: charging for tags refused `#{}`;
            // not charging for empty containers accepted `(sorted-map)` where
            // decode refused; charging per container refuses a SHAPED ARRAY,
            // whose reader consumes the outer, keys, rows and row heads inline
            // and charges only for the tag.
            //
            // That last one generalises: a tag reader that parses its payload
            // inline charges nothing for the containers inside it, while a
            // generic skip must recurse into them. The gap is unbounded -- three
            // levels per nested shaped array -- so no constant slack closes it
            // and no amount of case analysis will. Mirroring `read` is not a
            // reachable goal for a generic walker.
            case 4: {
                if (info == 31) {
                    enterSkip();
                    try { while (!consumeBreak()) skipStructural(); } finally { skipDepth--; }
                    return;
                }
                int n = checkCount(arg(info), 1);
                if (n == 0) return;
                enterSkip();
                try { for (int i = 0; i < n; i++) skipStructural(); } finally { skipDepth--; }
                return;
            }
            case 5: {
                if (info == 31) {
                    enterSkip();
                    try { while (!consumeBreak()) { skipStructural(); skipStructural(); } }
                    finally { skipDepth--; }
                    return;
                }
                int n = checkCount(arg(info), 2);
                enterSkip();
                try { for (int i = 0; i < n; i++) { skipStructural(); skipStructural(); } }
                finally { skipDepth--; }
                return;
            }
            case 6: {
                // A TAG CHAIN IS CONSUMED ITERATIVELY, and bounded by its
                // length. `c0 c0 c0 ... 00` is legal CBOR, and recursing once
                // per tag overflowed the stack -- the DoS maxDepth exists to
                // prevent, and the one nesting it did not bound.
                //
                // The obvious fix, charging each tag to the depth budget like
                // `read` does, was WRONG and the property test caught it:
                // several tag readers parse their payload's head inline without
                // calling enter() -- `readTagged(258)` reads the set's array
                // that way -- so read charges for the tag but not the container
                // under it, while a mirrored skip charged for both. `#{}` was
                // then refused at a depth decode accepted, and navigation
                // rejects documents that decode fine. Mirroring read's
                // accounting case by case is not maintainable; there are as
                // many rules as there are tag readers.
                //
                // Bounding the chain instead is safe by construction: it can
                // only ever make skip LAXER than read, never stricter, so a
                // decodable document always skips. And it removes the stack
                // recursion outright rather than capping it.
                arg(info);
                int chain = 1;
                while (true) {
                    int h2 = u8();
                    if ((h2 >>> 5) != 6) { pos--; break; }    // not a tag: put it back
                    if (++chain > skipLimit())
                        throw Err.of("max-depth-exceeded",
                            "boring: tag chain longer than the skip bound ("
                            + skipLimit() + ")", "max-depth", (long) skipLimit());
                    arg(h2 & 0x1F);
                }
                skipStructural();
                return;
            }
            default:                       // major 7: arg() covers the width
                if (info == 31)
                    throw Err.of("unexpected-break",
                        "boring: break code outside an indefinite-length item");
                arg(info);
                return;
        }
    }

    // ---- positional queries, for the navigator ---------------------------
    //
    // The cursor addresses the document by absolute offset, so these answer
    // "what is at p" without disturbing anything a caller was doing. Each
    // restores `pos`, because arg() advances it.

    /** Major type (0-7) of the item at `p`. */
    public int majorAt(long p) { return b(p) >>> 5; }

    /** Additional-info nibble of the item at `p`. */
    public int infoAt(long p) { return b(p) & 0x1F; }

    /**
     * The head's argument at `p`: element count for an array, pair count for a
     * map, byte length for a string. -1 for an indefinite-length item, whose
     * count is not on the wire.
     */
    public long headArgAt(long p) {
        int info = infoAt(p);
        if (info == 31) return -1;
        long save = pos;
        try { pos = p + 1; return arg(info); }
        finally { pos = save; }
    }

    /** Offset just past the head at `p` -- where its content begins. */
    public long headEndAt(long p) {
        int info = infoAt(p);
        long save = pos;
        try { pos = p + 1; if (info >= 24 && info < 28) arg(info); return pos; }
        finally { pos = save; }
    }

    /**
     * BEST-EFFORT concurrent-use detector, not a lock.
     *
     * A Reader is single-threaded, and `boring.nav` shares one across every
     * cursor from a source -- so two threads navigating one source produced
     * silently WRONG documents: 200 parallel passes gave 6 plausible-but-wrong
     * answers and no exception at all. Every signal on nav's surface says
     * otherwise (a "read-only" namespace, a reducible, a deliberately shared
     * mmap arena), so the failure had to become loud.
     *
     * Deliberately NOT thread affinity: building a cursor on one thread and
     * using it on another, without overlap, is a legitimate handoff that
     * affinity would reject -- pushing callers back to rebuilding a Nav, which
     * costs 145 us against a fork's 175 ns.
     *
     * Non-volatile on purpose. A volatile write on every positional read would
     * cost more than the bug does, and this only has to catch overlap often
     * enough to name it. A smoke alarm, not a mutex: `boring.nav/fork` is the
     * fix and doc/SECURITY.md states the rule.
     */
    private boolean busy = false;

    private RuntimeException concurrentUse() {
        return Err.of("concurrent-use",
            "boring: this Reader was used from two threads at once. A boring.nav"
            + " source is single-threaded -- call boring.nav/fork for a per-thread"
            + " view, which shares the decoded index.");
    }

    /**
     * Offset just past the whole value at `p`.
     *
     * Restores `depth` as well as `pos`. These positional entry points are the
     * navigator's whole interface to the Reader, and it calls them on values
     * that may legitimately fail -- a probe into a malformed index, a
     * too-deep subtree. Leaving depth raised made the NEXT, unrelated call
     * fail too.
     */
    public long skipFrom(long p) {
        if (busy) throw concurrentUse();
        busy = true;
        long save = pos; int d = depth, sd = skipDepth;
        try { pos = p; skipDepth = 0; skipValue(); return pos; }
        finally { pos = save; depth = d; skipDepth = sd; busy = false; }
    }

    /** Decode the value at `p`. Does not disturb the caller's position or depth. */
    public Object readFrom(long p) {
        if (busy) throw concurrentUse();
        busy = true;
        // `items` is saved and CLEARED, like depth. A positional read is an
        // independent lookup -- `boring.nav` shares one Reader across every
        // one of them -- so without this two navigations consumed each other's
        // budget and the tenth `get` on a large document failed because of the
        // nine before it.
        long save = pos; int d = depth; long it = items;
        try { pos = p; items = 0; return read(); }
        finally { pos = save; depth = d; items = it; busy = false; }
    }

    /**
     * Whether the `probe.length` bytes at `p` equal `probe`.
     *
     * This is how the navigator matches a map key: encode the sought key once,
     * then compare bytes. Encoding is deterministic for a given profile, so
     * byte equality is value equality -- and no key is ever decoded, which is
     * the entire point of not materialising what you did not ask for.
     */
    public boolean bytesEqualAt(long p, byte[] probe) {
        int n = probe.length;
        if (p < 0 || n > limit - p) return false;   // no overflow-prone addition
        for (int i = 0; i < n; i++) if (sb(p + i) != probe[i]) return false;
        return true;
    }

    /**
     * Lexicographic comparison of the ENCODED items at `a` and `b`.
     *
     * RFC 8949 §4.2.1 orders canonical map keys by their encoded bytes, so a
     * navigator can binary-search a sorted map without decoding a single key.
     * Bytewise, with the shorter encoding first when one is a prefix of the
     * other.
     */
    public int compareItemsAt(long a, long b) {
        long an = skipFrom(a) - a, bn = skipFrom(b) - b;
        long n = Math.min(an, bn);
        for (long i = 0; i < n; i++) {
            int x = b(a + i), y = b(b + i);
            if (x != y) return x < y ? -1 : 1;
        }
        return Long.compare(an, bn);
    }

    /** Same ordering, against an already-encoded probe. */
    public int compareItemToBytes(long p, byte[] probe) {
        long pn = skipFrom(p) - p;
        long n = Math.min(pn, probe.length);
        for (long i = 0; i < n; i++) {
            int x = b(p + i), y = probe[(int) i] & 0xFF;
            if (x != y) return x < y ? -1 : 1;
        }
        return Long.compare(pn, probe.length);
    }

    /** Copy the bytes in [start, end) out. Used to lift a blob or stage a span. */
    /** Slots for `n` key/value pairs, refusing a product that cannot be an array. */
    private static int kvSlots(int n) {
        long slots = (long) n * 2;
        if (slots > Integer.MAX_VALUE - 8)
            throw Err.of("bad-count",
                "boring: a map of " + n + " pairs needs " + slots
                + " slots, more than one array can hold");
        return (int) slots;
    }

    /**
     * Bytes in [start, end). RANGE-CHECKED, because these are a public Java
     * entry point: `(int)(end - start)` on a reversed or out-of-range pair was
     * a raw NegativeArraySizeException or ArrayIndexOutOfBoundsException rather
     * than the typed failure the rest of the reader promises.
     */
    public byte[] bytesBetween(long start, long end) {
        if (start < 0 || end < start || end > limit)
            throw Err.of("bad-range",
                "boring: byte range [" + start + ", " + end + ") is not within"
                + " [0, " + limit + ")");
        long n = end - start;
        if (n > Integer.MAX_VALUE - 8)
            throw Err.of("bad-range",
                "boring: byte range of " + n + " is larger than one array can hold");
        return freshBytes(start, (int) n);
    }

    /** True if this document opens a stringref namespace at its root. */
    public boolean hasStringrefRoot() {
        return limit >= 3 && b(0) == 0xD9 && b(1) == 0x01 && b(2) == 0x00;
    }

    /** True if the byte at the cursor is a break, consuming it if so. */
    private boolean consumeBreak() {
        if (atBreak()) { pos++; return true; }
        return false;
    }

    /** Definite-length chunks of `major` up to the break, all skipped. */
    private void skipIndefiniteChunks(int major) {
        while (!consumeBreak()) {
            int h = u8();
            if ((h >>> 5) != major)
                throw Err.of("bad-indefinite-chunk",
                    "boring: indefinite-length item contains a chunk of major " + (h >>> 5));
            int n = checkCount(arg(h & 0x1F), 1);   // see skipStructural: not `pos +=`
            pos += n;
        }
    }

    /**
     * Read the next top-level item of a CBOR sequence (RFC 8742), clearing the
     * per-message stringref namespace first but keeping the ident cache, which
     * is a pure function of bytes and stays valid across items.
     */
    public Object readNext() {
        this.reused = true;
        depth = 0;
        // A FRESH ITEM BUDGET PER TOP-LEVEL ITEM. `items` used to carry across
        // them, so a CBOR sequence spent one cumulative budget for the whole
        // file -- and because `reset()` on a streaming refill DOES clear it,
        // acceptance depended on the chunk size: the same five items decoded at
        // :chunk-size 2 and failed at 65536. A limit whose meaning changes with
        // an unrelated buffering knob cannot be the right one.
        //
        // Per-item matches what the streaming API already promises: retained
        // memory is bounded by the largest single item, not by the file.
        items = 0;
        srActive = false;
        if (srCount > 0) {
            java.util.Arrays.fill(srStrings, 0, srCount, null);
            java.util.Arrays.fill(srIdents, 0, srCount, null);
            srCount = 0;
        }
        return read();
    }

    // ---- accessors -------------------------------------------------------
    //
    // ONE parser, TWO accessors. The structural logic above and below is
    // single-source -- which is the whole point of the segment migration, since
    // a second head parser is what drifts -- but the loads themselves branch on
    // whether the source is a heap array.
    //
    // Going through the segment unconditionally was measured and rejected:
    // decode ran 14-50% slower across every payload and used ~2.5x the stack
    // per recursive level. A microbenchmark of the same access pattern
    // predicted parity -- it measures a tight loop over a constant layout,
    // where the JIT hoists the per-access checks out, and the real decoder is
    // recursive and branchy and does not get that. `arr` is a loop-invariant
    // null check that predicts perfectly, and it keeps the byte[] path on
    // plain array loads.

    /**
     * Unsigned byte at an absolute offset.
     *
     * The explicit limit check is what makes a POSITIONAL read report
     * `:boring/truncated-input` like every other read, instead of a raw
     * ArrayIndexOutOfBoundsException. `read` gets typed truncation from its own
     * checks; `skipStructural` and the `*At` accessors had none, so the same
     * corrupted byte gave `decode` a typed error and `boring.nav` an untyped
     * one -- 415 of 5360 mutations of an UNINDEXED document, so this is the
     * navigator's contract with damaged data, not anything to do with the index.
     *
     * The array bound was always checked here; only by the JVM, and only to
     * throw the wrong type. Measured at no cost: the JIT can prove its own
     * check redundant once this one dominates it.
     */
    private int b(long p) {
        if (p < 0 || p >= limit) throw truncated(p);
        return (arr != null ? arr[(int) p] : src.at(p)) & 0xFF;
    }

    private RuntimeException truncated(long p) {
        return Err.of("truncated-input",
            "boring: read past the end of the input at offset " + p
            + " (size " + limit + ")");
    }

    /** Signed byte. The ident hash below folds raw bytes, so it must stay signed
     *  to keep producing the same slots it did over a byte[]. */
    private byte sb(long p) {
        return arr != null ? arr[(int) p] : src.at(p);
    }

    private int s16(long p) {
        if (p < 0 || p + 2 > limit) throw truncated(p);
        return (arr != null ? (short) SHORT_BE.get(arr, (int) p) : src.i16(p)) & 0xFFFF;
    }

    private int s32(long p) {
        if (p < 0 || p + 4 > limit) throw truncated(p);
        return arr != null ? (int) INT_BE.get(arr, (int) p) : src.i32(p);
    }

    private long s64(long p) {
        if (p < 0 || p + 8 > limit) throw truncated(p);
        return arr != null ? (long) LONG_BE.get(arr, (int) p) : src.i64(p);
    }

    private int u8()  { return b(pos++); }
    private int u16() { int v = s16(pos); pos += 2; return v; }
    private long u32(){ long v = s32(pos) & 0xFFFFFFFFL; pos += 4; return v; }
    private long u64(){ long v = s64(pos); pos += 8; return v; }

    private long remaining() { return limit - pos; }

    /**
     * An array holding the `n` bytes at `p`, with the start index left in
     * {@link #scratchOff}. Zero-copy when the source is a heap byte[]; one copy
     * into reusable scratch otherwise.
     *
     * The returned array is only valid until the next call. Anything that
     * OUTLIVES the call -- a stringref table entry, an ident cache key -- must
     * use {@link #freshBytes} instead, or it aliases scratch and every later
     * read rewrites it.
     */
    private byte[] arrayFor(long p, int n) {
        if (arr != null) { scratchOff = (int) p; return arr; }
        if (scratch == null || scratch.length < n)
            scratch = new byte[Math.max(n, 256)];
        src.copyTo(p, scratch, 0, n);
        scratchOff = 0;
        return scratch;
    }

    /** A private copy of the `n` bytes at `p`. Safe to retain. */
    private byte[] freshBytes(long p, int n) {
        byte[] out = new byte[n];
        if (arr != null) System.arraycopy(arr, (int) p, out, 0, n);
        else src.copyTo(p, out, 0, n);
        return out;
    }

    private String stringAt(long p, int n, java.nio.charset.Charset cs) {
        byte[] a = arrayFor(p, n);
        return new String(a, scratchOff, n, cs);
    }

    /** Recursion depth of the CURRENT skipStructural walk. Reset by skipFrom. */
    private int skipDepth = 0;

    /**
     * The bound on skip recursion -- a STACK bound, deliberately not `maxDepth`.
     *
     * `maxDepth` is a semantic limit that `read` applies, and skip cannot
     * reproduce read's accounting: a tag reader that consumes its payload's
     * containers inline charges nothing for them, while a generic walker must
     * recurse. Shaped arrays cost three such levels each, and they nest, so the
     * discrepancy is unbounded and no constant slack closes it.
     *
     * Making skip stricter than read is the failure that matters -- navigation
     * then refuses a document that decodes -- so skip is deliberately LAXER:
     * never below 1024, and above that whatever the caller allowed read. It
     * exists only to keep a pathological document from exhausting the stack,
     * which is what `read` gets from maxDepth as a side effect. A document read
     * cannot handle without overflowing its own stack is not one skip needs to
     * accept.
     */
    private int skipLimit() { return Math.max(maxDepth, 1024); }

    private void enterSkip() {
        if (++skipDepth > skipLimit())
            throw Err.of("max-depth-exceeded",
                "boring: nesting deeper than the skip bound (" + skipLimit() + ")",
                "max-depth", (long) skipLimit());
    }

    private void enter() {
        // Check BEFORE incrementing. `++depth > maxDepth` left depth raised on
        // the throwing path, and only array/map have a `finally { exit(); }` to
        // unwind it -- so a rejected read permanently consumed a level of the
        // budget on a Reader that callers reuse. Two positional reads later, a
        // perfectly shallow value was refused because of an earlier one that
        // failed. `boring.nav` shares one Reader across every lookup, so this
        // reached the navigation path.
        if (depth + 1 > maxDepth)
            throw Err.of("max-depth-exceeded",
                "boring: nesting deeper than maxDepth (" + maxDepth + ")",
                "max-depth", (long) maxDepth);
        depth++;
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
    /**
     * The same content, but never null -- for the markers that cast to List and
     * call size()/toArray(). `seqableContent` admits nil because nil IS seqable
     * in Clojure, which is right for the callers that seq it and wrong for the
     * ones that dereference it: `27(["java/boolean-array", null])` was a raw
     * NullPointerException out of a well-formed frame, against the typed-only
     * guarantee doc/SECURITY.md makes.
     */
    private static java.util.List listMarkerContent(Object argument, String name) {
        Object c = seqableContent(argument, name);
        if (!(c instanceof java.util.List))
            throw Err.of("bad-tag-content",
                "boring: " + name + " must wrap a list, got "
                + (c == null ? "nil" : c.getClass().getSimpleName()), "tag", 27L);
        return (java.util.List) c;
    }

    private static Object seqableContent(Object argument, String name) {
        // A LIST OR A SET, not anything Seqable. `Seqable` admits maps and
        // records, and `RT.seq` of a map yields MAP ENTRIES -- so
        // `27(["clojure/sorted-set", simple(172)])` decoded to `#{[:n 172]}`
        // and `27(["clojure/sorted-set", {1: 2}])` to `#{[1 2]}`: a silently
        // WRONG VALUE built out of a payload that is not an array at all.
        //
        // Sets are admitted because ClojureScript's `seq-content` admits them
        // (`sequential?` or `set?`), and a map is not `sequential?` there --
        // so this was also the lenient half of a parser differential.
        //
        // null still passes: `RT.seq(null)` is an empty collection on both
        // platforms, and that parity predates this.
        if (argument == null
                || argument instanceof java.util.List
                || argument instanceof java.util.Set)
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
    private boolean validateUtf8(long start, int n) {
        long i = start;
        long end = start + n;

        boolean ascii = true;
        while (i + 8 <= end) {                       // bulk ASCII scan
            // The mask has the same bit in every byte, so this test is
            // endian-independent and needs no swap.
            if ((s64(i) & 0x8080808080808080L) != 0L) break;
            i += 8;
        }
        while (i < end) {
            int b = b(i);
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
                int c = b(i + j);
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
        String s = stringAt(pos, n,
            ascii ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
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

    private TagRegistry identFastPathFor;
    private boolean identFastPathOk;

    /**
     * Whether the five-byte identifier shortcut may be taken at all.
     *
     * It returns an interned identifier WITHOUT reaching `readTagged`, which is
     * where `registry.readerFor(tag)` is consulted -- so a caller who
     * registered a reader for tag 39 or tag 25 had it silently ignored for the
     * five-byte form and honoured for `D8 27 D9 0019 00`, the same value under
     * a wider stringref index. Acceptance depended on which legal encoding the
     * producer chose, and ClojureScript honoured the override for both.
     *
     * That is exactly the defect the comment on `readTagged` says was fixed
     * ("registering a reader for a tag boring knows natively was SILENTLY
     * IGNORED"); the optimisation reintroduced it for the one tag a consumer
     * with its own symbol type is most likely to override.
     *
     * Cached against the registry's IDENTITY rather than recomputed: the field
     * is public and assigned directly, so there is no setter to hook, and a
     * reference compare plus a boolean is what the hot path can afford.
     */
    private boolean identFastPath() {
        // Tag 25 is NOT tested here, and that is deliberate rather than an
        // omission. It is a structural tag -- stringref is how the format
        // encodes repetition, not a value type -- and `TagRegistry` now refuses
        // to register a reader for it at all, so there is nothing to bypass.
        // The clause that used to be here could not have an effect: the slow
        // path reads tag 25 inside tag 39 by hand too, so disabling the
        // shortcut changed nothing.
        if (registry != identFastPathFor) {
            identFastPathFor = registry;
            identFastPathOk = registry.readerFor(39) == null;
        }
        return identFastPathOk;
    }

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
        countItem();
        int b0 = b(pos);

        // Repeated keyword/symbol: D8 27 D8 19 <idx>
        if (b0 == 0xD8 && pos + 4 < limit
                && s32(pos) == KW_SYMREF_PREFIX
                && identFastPath()) {
            int idx = b(pos + 4);
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
                if (arr != null) System.arraycopy(arr, (int) pos, bs, 0, n);
                else src.copyTo(pos, bs, 0, n);
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
                // CHECKED before narrowing: `checkCount(n, 2)` can pass for an
                // n whose `n * 2` overflows signed int on a source larger than
                // 2 GiB, which was a raw NegativeArraySizeException. Even
                // below that, the product may exceed any usable heap.
                Object[] kvs = new Object[kvSlots(n)];
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
                    // `f8 00` .. `f8 1f` are REJECTED. RFC 8949 3.3: "An encoder
                    // MUST NOT issue two-byte sequences that start with 0xf8 ...
                    // and continue with a byte less than 0x20 (32 decimal). Such
                    // sequences are not well-formed." That last sentence makes
                    // this a well-formedness rule binding on the DECODER too, not
                    // the encoder-only rule it reads as at first glance --
                    // Appendix C's pseudocode calls fail(), and Appendix F.1
                    // enumerates f800/f801/f818/f81f among the not-well-formed.
                    //
                    // This was once accepted here, on the grounds that Appendix A
                    // lists `f818` as decodable simple(24). It does not: that row
                    // is RFC 7049's, deleted from RFC 8949 by Erratum 5917. The
                    // vector in test/boring/vectors.cljc had outlived its spec.
                    //
                    // Rejecting is also the majority behaviour -- jackson,
                    // fxamacker, node-cbor, cbor2-JS, and cbor2-Python's 6.x
                    // rewrite all refuse it. Of those that accept, ciborium and
                    // cbor-x decode `f814` as plain `false`, laundering a
                    // malformed encoding into a valid-looking value.
                    case 24: {
                        int sv = u8();
                        if (sv < 32)
                            throw Err.of("malformed-simple-value",
                                "boring: two-byte simple value 0x" + Integer.toHexString(sv)
                                    + " is not well-formed (RFC 8949 3.3 reserves f8 00..f8 1f)",
                                "value", (long) sv);
                        return Data.MAKE_SIMPLE.invoke(Long.valueOf(sv));
                    }
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
        return pos < limit && b(pos) == 0xFF;
    }

    private Object readOrBreak() {
        if (atBreak()) { pos++; return BREAK; }
        return read();
    }

    /** One definite-length chunk of major type `major`, or BREAK. */
    private Object readChunk(int major) {
        if (atBreak()) { pos++; return BREAK; }
        int header = b(pos);
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
     * Small maps are built once through createAsIfByAssoc and rejected when
     * the resulting count is smaller than the declared pair count. Array keys
     * get a bounded content scan because host maps compare them by identity.
     * Large maps use the content-aware HashSet path below. This avoids a
     * HashSet plus one wrapper per key on every five-key datom map.
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

    /**
     * A map key or set element wrapped so that ARRAYS compare by CONTENT.
     *
     * Host array equality is identity on the JVM, so two content-equal
     * `short[]` keys -- or `byte[]`, or a tag-40 payload -- were two distinct
     * keys, and a document with duplicate CBOR keys decoded to a map with more
     * entries than the wire described. doc/SECURITY.md says duplicate detection
     * compares encoded key bytes; for everything but byte strings it did not.
     *
     * Wrapping also replaces the two O(n^2) pair scans this used to do -- one
     * of them specifically for byte-string keys, and unbounded above the
     * array-map threshold, which made it attacker-controlled work on read. A
     * HashSet of these is one pass.
     *
     * Non-array keys delegate to Clojure's own hasheq/equiv, so `1` and `1.0`
     * stay distinct per RFC 8949 5.6.1 and a stringref-resolved string still
     * collides with its literal spelling -- which comparing raw encoded bytes
     * would have missed.
     */
    private static final class KeyProbe {
        final Object k;
        private final int hash;
        KeyProbe(Object k) { this.k = k; this.hash = hashOf(k); }

        private static int hashOf(Object o) {
            if (o instanceof byte[])    return java.util.Arrays.hashCode((byte[]) o);
            if (o instanceof short[])   return java.util.Arrays.hashCode((short[]) o);
            if (o instanceof int[])     return java.util.Arrays.hashCode((int[]) o);
            if (o instanceof long[])    return java.util.Arrays.hashCode((long[]) o);
            if (o instanceof double[])  return java.util.Arrays.hashCode((double[]) o);
            if (o instanceof float[])   return java.util.Arrays.hashCode((float[]) o);
            if (o instanceof char[])    return java.util.Arrays.hashCode((char[]) o);
            if (o instanceof boolean[]) return java.util.Arrays.hashCode((boolean[]) o);
            if (o instanceof Object[])  return java.util.Arrays.deepHashCode((Object[]) o);
            return clojure.lang.Util.hasheq(o);
        }

        private static boolean eq(Object a, Object b) {
            if (a instanceof byte[] && b instanceof byte[])
                return java.util.Arrays.equals((byte[]) a, (byte[]) b);
            if (a instanceof short[] && b instanceof short[])
                return java.util.Arrays.equals((short[]) a, (short[]) b);
            if (a instanceof int[] && b instanceof int[])
                return java.util.Arrays.equals((int[]) a, (int[]) b);
            if (a instanceof long[] && b instanceof long[])
                return java.util.Arrays.equals((long[]) a, (long[]) b);
            if (a instanceof double[] && b instanceof double[])
                return java.util.Arrays.equals((double[]) a, (double[]) b);
            if (a instanceof float[] && b instanceof float[])
                return java.util.Arrays.equals((float[]) a, (float[]) b);
            if (a instanceof char[] && b instanceof char[])
                return java.util.Arrays.equals((char[]) a, (char[]) b);
            if (a instanceof boolean[] && b instanceof boolean[])
                return java.util.Arrays.equals((boolean[]) a, (boolean[]) b);
            if (a instanceof Object[] && b instanceof Object[])
                return java.util.Arrays.deepEquals((Object[]) a, (Object[]) b);
            return clojure.lang.Util.equiv(a, b);
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) {
            return o instanceof KeyProbe && eq(k, ((KeyProbe) o).k);
        }
    }

    /** Below this, a pairwise scan beats a hash set: at most 28 comparisons and
     *  NOTHING allocated, against a HashSet plus a KeyProbe per key.
     *
     *  Not a guess -- measured. Hashing every map unconditionally cost 16% of
     *  datom-maps-200 decode (56.9 -> 66.0 us, against an unchanged hako in the
     *  same A/B run), because a datom map is five keys and the allocation
     *  dominates the comparisons it saves. Small maps are the common case by a
     *  wide margin, so the threshold is where the work goes. */
    private static final int DUP_SCAN_MAX = 8;

    /** Content-aware, and O(n) above the threshold. Throws on the first
     *  duplicate.
     *
     *  The quadratic branch is BOUNDED, which is what makes it safe: the pair
     *  scan this replaced ran over maps of any size, so a large map was
     *  attacker-controlled work on read. */
    private void checkDistinct(Object[] kvs, int n, int stride, String what, String errType) {
        if (n <= DUP_SCAN_MAX) {
            for (int i = 1; i < n; i++) {
                Object a = kvs[i * stride];
                for (int j = 0; j < i; j++)
                    if (KeyProbe.eq(a, kvs[j * stride]))
                        throw Err.of(errType, "boring: duplicate " + what + ": " + a,
                                     "key", a);
            }
            return;
        }
        java.util.HashSet<KeyProbe> seen = new java.util.HashSet<>(Math.max(4, n * 2));
        for (int i = 0; i < n; i++) {
            Object k = kvs[i * stride];
            if (!seen.add(new KeyProbe(k)))
                throw Err.of(errType, "boring: duplicate " + what + ": " + k, "key", k);
        }
    }

    private static boolean anyArrayKey(Object[] kvs, int n) {
        for (int i = 0; i < n; i++) {
            Object k = kvs[i * 2];
            if (k != null && k.getClass().isArray()) return true;
        }
        return false;
    }

    /** Allocation-free content check for maps whose size is bounded by the
     * array-map threshold. Called only when at least one key is an array; for
     * ordinary keys createAsIfByAssoc already performs the needed equality. */
    private static void checkDistinctSmall(Object[] kvs, int n) {
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (KeyProbe.eq(kvs[i * 2], kvs[j * 2])) {
                    Object k = kvs[i * 2];
                    throw Err.of("duplicate-map-key",
                        "boring: duplicate map key: " + k, "key", k);
                }
            }
        }
    }

    private Object buildMap(Object[] kvs, int n) {
        if (n == 0) return clojure.lang.PersistentArrayMap.EMPTY;
        final boolean fits = n <= ARRAY_MAP_PAIRS
            || (n <= ARRAY_MAP_KW_PAIRS && allKeywordKeys(kvs, n));
        if (fits) {
            // Build once. For ordinary keys the resulting count is the
            // duplicate check, avoiding a HashSet and one KeyProbe allocation
            // per key on every small map. Array identity is the one exception,
            // handled by a bounded, allocation-free content scan.
            if (checkDuplicateKeys && n > 1 && anyArrayKey(kvs, n))
                checkDistinctSmall(kvs, n);
            clojure.lang.IPersistentMap m =
                clojure.lang.PersistentArrayMap.createAsIfByAssoc(kvs);
            if (checkDuplicateKeys && m.count() != n) {
                checkDistinctSmall(kvs, n); // finds the key for typed ex-data
                throw Err.of("duplicate-map-key", "boring: duplicate map key");
            }
            return m;
        }
        if (checkDuplicateKeys) {
            // The large path stays one-pass and content-aware; unlike the old
            // pair scan, its work is not attacker-controlled O(n^2).
            checkDistinct(kvs, n, 2, "map key", "duplicate-map-key");
        }
        // WITH THE CHECK OFF, LAST-WINS -- never a corrupt map.
        //
        // `new PersistentArrayMap(kvs)` ADOPTS the array without inspecting it,
        // so duplicate keys produced a map with two equal keys: `count` said 2
        // and `get` returned the first, which is not a valid Clojure map and is
        // the same defect class as adopting an oversized vector tail. RFC 8949
        // 5.6 offers three approaches -- reject, keep one, or hand duplicates to
        // the application -- and a corrupt host map is none of them.
        //
        // `createAsIfByAssoc` is last-wins, matching what the hash-map branch
        // below has always done, so the two sizes now agree instead of
        // differing at the array-map threshold.
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

    /** `:tag`/`:value` of a `boring.data.TaggedValue`, looked up rather than cast.
     *  The record is defined in Clojure, which compiles AFTER this class, so
     *  `instanceof` is not available here -- `ILookup` is. */
    private static final clojure.lang.Keyword KW_TAG = clojure.lang.Keyword.intern("tag");
    private static final clojure.lang.Keyword KW_VALUE = clojure.lang.Keyword.intern("value");

    /**
     * A vector from a fully-populated array, valid at ANY length.
     *
     * `PersistentVector.adopt(Object[])` takes its argument as the vector's
     * TAIL, so it is only correct through 32 elements. Past that the result is
     * a vector with a plausible `count` whose root is null: `nth` throws
     * NullPointerException below index 32 and returns the wrong element above
     * it. Decoding SUCCEEDS and hands back a corrupt structure, which is worse
     * than any rejection -- a caller has no way to notice.
     *
     * The array reader has always had the cutoff (see case 4). Two newer paths
     * -- wide uint64 typed arrays and tag-40 reconstruction -- called `adopt`
     * unconditionally, so this is the shared helper all three now use rather
     * than a rule each site has to remember.
     */
    private static clojure.lang.IPersistentVector vectorFromArray(Object[] items) {
        if (items.length == 0) return PersistentVector.EMPTY;
        if (items.length <= 32) return PersistentVector.adopt(items);
        ITransientCollection tv = PersistentVector.EMPTY.asTransient();
        for (Object o : items) tv = tv.conj(o);
        return (clojure.lang.IPersistentVector) tv.persistent();
    }

    /** A Number as an exact BigInteger, or null if it is not integral.
     *
     *  `longValue()` narrows silently, which is how a tag-1002 key of 2^64+1
     *  became 1 and aliased the base key. Every integral CBOR type this reader
     *  produces is covered; a float or anything else returns null. */
    private static java.math.BigInteger exactInteger(Object o) {
        if (o instanceof clojure.lang.BigInt) return ((clojure.lang.BigInt) o).toBigInteger();
        if (o instanceof java.math.BigInteger) return (java.math.BigInteger) o;
        if (o instanceof Long || o instanceof Integer
            || o instanceof Short || o instanceof Byte)
            return java.math.BigInteger.valueOf(((Number) o).longValue());
        return null;
    }

    /** 10^e for the small e RFC 9581's decimal scale factors use. */
    private static long pow10(int e) {
        long r = 1;
        while (e-- > 0) r *= 10;
        return r;
    }

    /** A tag-258 set, with the duplicate-element check both content paths need.
     *  Maps reject duplicate keys as an anti-differential measure; sets used to
     *  collapse them silently. Same rule, same reason. */
    private clojure.lang.IPersistentSet makeSet(Object[] items, int n) {
        if (n == 0) return PersistentHashSet.EMPTY;
        // CONTENT-AWARE, like map keys. `PersistentHashSet.create` compares
        // arrays by identity, so tag 258 holding two content-equal byte strings
        // came back as a two-element set -- more elements than the wire
        // describes, and the same defect the map path had.
        if (checkDuplicateKeys && n > 1)
            checkDistinct(items, n, 1, "tag 258 element", "duplicate-set-element");
        clojure.lang.IPersistentSet set = PersistentHashSet.create(items);
        if (checkDuplicateKeys && set.count() != n)
            throw Err.of("duplicate-set-element",
                "boring: tag 258 declared " + n + " elements but "
                + set.count() + " are distinct", "declared", (long) n);
        return set;
    }

    /** One element of a tag-40 payload, which may be a typed array or a CBOR array. */
    private static Object elementAt(Object flat, int i) {
        if (flat instanceof java.util.List) return ((java.util.List) flat).get(i);
        return java.lang.reflect.Array.get(flat, i);
    }

    /** Nested vectors for a tag-40 payload of any dimensionality, row-major.
     *
     *  `count` is the number of elements in the block starting at `offset`, so
     *  each level divides rather than re-multiplying the remaining shape. The
     *  product was range-checked against the payload length before the first
     *  call, which is what makes the indexing here total. */
    private static Object nestDims(Object flat, int[] shape, int total) {
        // ITERATIVE, deliberately. This recursed once per DIMENSION, and the
        // dimensions are a flat array, so :max-depth never charged for them: a
        // structurally shallow 20 KB item declaring 20 000 dimensions of 1 blew
        // the host stack on both platforms, with no ex-data. RFC 8746 does not
        // bound dimensionality, so the count cannot just be capped at some
        // arbitrary number -- it has to be built without recursion.
        //
        // Necessary but not sufficient: the RESULT is still nested as deeply as
        // there are dimensions, so `=` or `hash` on it would overflow later in
        // the CALLER. The dimension count is charged against :max-depth at the
        // call site for that, which is what the budget is for.
        int k = shape.length;
        int inner = shape[k - 1];
        Object[] level = new Object[total / inner];
        for (int g = 0; g < level.length; g++) {
            Object[] row = new Object[inner];
            for (int i = 0; i < inner; i++) row[i] = elementAt(flat, g * inner + i);
            level[g] = vectorFromArray(row);
        }
        for (int d = k - 2; d >= 0; d--) {
            int len = shape[d];
            Object[] next = new Object[level.length / len];
            for (int o = 0; o < next.length; o++) {
                Object[] grp = new Object[len];
                System.arraycopy(level, o * len, grp, 0, len);
                next[o] = vectorFromArray(grp);
            }
            level = next;
        }
        return level[0];
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
            // Goes into the stringref table, so it must outlive this call and
            // cannot be the shared scratch buffer.
            b = freshBytes(pos, blen);
            off = 0;
            srPut(b);
        } else {
            // Still zero-copy on a heap source, exactly as before; staged into
            // scratch for a native one. The switch below consumes it before
            // anything else can touch scratch.
            b = arrayFor(pos, blen);
            off = scratchOff;
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
                // short[], though `byte` holds -128..127 exactly and is the
                // narrower primitive. byte[] is OVERLOADED on the write side:
                // it is how a plain CBOR byte string decodes, so it re-encodes
                // as major type 2. A peer that sent an ARRAY OF THREE SIGNED
                // INTEGERS got a BYTE STRING back -- the only typed array that
                // changed the CBOR data model rather than merely widening.
                // Widening to short[] costs a byte per element and keeps it an
                // array (tag 77 on the way out).
                short[] a = new short[n];
                for (int i = 0; i < n; i++) a[i] = b[off + i];
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
                // Above 2^63 a uint64 has no lossless long, and handing back the
                // negative that the bits happen to spell would be a wrong value.
                // boring used to REFUSE the whole array for it -- conforming RFC
                // 8746 input rejected because of a host-type limit, and rejected
                // DATA-DEPENDENTLY, so the same producer's tag 67 worked until
                // the day a value crossed 2^63.
                //
                // Scanned first so the common case still returns a primitive
                // long[] with no boxing; only an array that actually needs the
                // width pays for a vector of BigInt, which is what a CBOR bignum
                // decodes to everywhere else in this reader.
                boolean wide = false;
                for (int i = 0; i < n; i++) {
                    long u = tag == 67 ? (long) LONG_BE.get(b, off + (i << 3))
                                       : (long) LONG_LE.get(b, off + (i << 3));
                    if (u < 0) { wide = true; break; }
                }
                if (!wide) {
                    long[] a = new long[n];
                    for (int i = 0; i < n; i++)
                        a[i] = tag == 67 ? (long) LONG_BE.get(b, off + (i << 3))
                                         : (long) LONG_LE.get(b, off + (i << 3));
                    return a;
                }
                // CHARGED, because THIS branch is the one that amplifies. A
                // primitive long[] above is one object for the whole payload --
                // 1.0x, which is what doc/SECURITY.md's table says about typed
                // arrays. A vector of boxed BigInt is one object per element:
                // 1 MB of `ff` bytes under tag 67 retained 11 MB, 12.5x, with
                // `{:max-items 100}` set and honoured. The budget must see the
                // objects the decoder BUILDS, and it could not see these because
                // the payload arrived as a single byte string.
                countItems(n);
                Object[] w = new Object[n];
                for (int i = 0; i < n; i++) {
                    long u = tag == 67 ? (long) LONG_BE.get(b, off + (i << 3))
                                       : (long) LONG_LE.get(b, off + (i << 3));
                    w[i] = u >= 0
                        ? (Object) Long.valueOf(u)
                        : clojure.lang.BigInt.fromBigInteger(
                              new java.math.BigInteger(Long.toUnsignedString(u)));
                }
                return vectorFromArray(w); }
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
                long save = pos;
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
                if ((th >>> 5) != 3 || (th & 0x1F) == 31) {
                    // NOT AN ERROR. IANA registers tag 39's data item as
                    // "multiple", and the defining spec (lucas-clemente/
                    // cbor-specs id.md) says it "can be applied to multiple
                    // types to indicate that the tagged object has identifier
                    // semantics". Throwing here failed the WHOLE DOCUMENT over
                    // a foreign identifier boring simply has no mapping for;
                    // carrying it as an inert TaggedValue is the same
                    // degradation every other uninterpreted tag gets.
                    //
                    // An indefinite-length text string lands here too (the head
                    // read above only recognises the definite form), and must
                    // still become an identifier -- hence the String case
                    // rather than a blanket TaggedValue.
                    pos = save;
                    Object v = read();
                    if (v instanceof String) return internIdent((String) v);
                    return Data.MAKE_TAGGED.invoke(Long.valueOf(39), v);
                }
                int n = checkCount(arg(th & 0x1F), 1);
                long start = pos;
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
                    srPut(stringAt(start, n, StandardCharsets.UTF_8));
                    srIdents[srCount - 1] = ident;
                }
                return ident;
            }
            case 258: {                                      // set
                long save258 = pos;
                int h = u8();
                if ((h >>> 5) != 4)
                    throw Err.of("bad-tag-content",
                        "boring: tag 258 must wrap an array, got major " + (h >>> 5),
                        "tag", 258L);
                if ((h & 0x1F) == 31) {
                    // INDEFINITE-LENGTH ARRAY. Tag 258 is registered against
                    // "array", and 3.2.2 makes the indefinite form an array;
                    // neither the registration nor cbor-sets-spec restricts it.
                    // Hand-rolling the head rejected `d9 0102 9f ... ff` with
                    // :boring/reserved-info -- conforming input refused, under
                    // an error that was also wrong, since ai 31 is not reserved
                    // for major type 4. Route it through the ordinary reader,
                    // as tags 2/3/27/30 already do.
                    pos = save258;
                    Object content = read();
                    if (!(content instanceof java.util.List))
                        throw Err.of("bad-tag-content",
                            "boring: tag 258 must wrap an array", "tag", 258L);
                    java.util.List cl = (java.util.List) content;
                    return makeSet(cl.toArray(), cl.size());
                }
                int n = checkCount(arg(h & 0x1F), 1);
                if (n == 0) return PersistentHashSet.EMPTY;
                Object[] items = new Object[n];
                for (int i = 0; i < n; i++) items[i] = read();
                return makeSet(items, n);
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
                // RFC 3339's `date-fullyear` is exactly four digits. There is
                // no expanded-year production in the grammar, and RFC 8949
                // 3.4.1 defers to RFC 3339 -- but `Instant.parse` accepts
                // java.time's own `+10000-01-01T00:00:00Z` extension, so the
                // JVM took a document ClojureScript's grammar refuses. Rejected
                // here rather than left to the parser, since the parser is the
                // thing being too generous.
                // RFC 3339 5.6's grammar, enforced where java.time is looser
                // than it. `Instant.parse` is a java.time parser, not an RFC
                // 3339 one, and the three places it differs all let a document
                // through that ClojureScript refuses:
                //
                //   expanded years   `+10000-01-01T00:00:00Z`  (no such production)
                //   empty secfrac    `...T00:00:00.Z`          (time-secfrac = "." 1*DIGIT)
                //   hour 24          `...T24:00:00Z`           (time-hour = 00-23, and
                //                                               it silently ROLLED FORWARD
                //                                               to the next day)
                if (!RFC3339.matcher(v).matches())
                    throw Err.of("bad-tag-content",
                        "boring: tag 0 content is not a valid RFC 3339 instant: " + v,
                        "tag", 0L, "value", v);
                // A LEAP SECOND is preserved rather than normalised.
                //
                // RFC 3339 permits `time-second = 60`, and `Instant.parse`
                // accepts the spelling but silently rewrites it to :59 -- so
                // "2016-12-31T23:59:60Z" and "...:59Z" decoded to the SAME
                // instant, losing a distinction the wire carried. ClojureScript
                // mostly rejects the spelling outright, so the platforms
                // disagreed too.
                //
                // Neither host type can hold a leap second, so the choice is
                // reject or preserve. Preserve, as an inert TaggedValue -- the
                // same treatment f128 gets for the same reason: boring does not
                // discard what it cannot represent, it hands it back
                // untouched. Silently normalising was the one option that loses
                // data without saying so.
                //
                // VALIDATED FIRST, not merely recognised. This returned as soon
                // as it found `:60` after the second colon, before the real date
                // parser ever ran -- so `9999-99-99T99:99:60Z` was accepted and
                // preserved. Preserving a legal leap second does not make an
                // impossible month, day, hour or minute legal.
                //
                // The check is the ordinary parser on a copy with `:60` replaced
                // by `:59`: everything except the leap second itself has to be a
                // real timestamp. If that fails, control falls through to the
                // normal tag-0 path, which reports the malformed date.
                int lsColon = secondsColon(v);
                if (lsColon >= 0 && parsesAsInstant(withoutLeapSecond(v, lsColon)))
                    return Data.MAKE_TAGGED.invoke(Long.valueOf(0), v);
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
                    // CHECKED before narrowing: `checkCount(n, 2)` can pass for an
                // n whose `n * 2` overflows signed int on a source larger than
                // 2 GiB, which was a raw NegativeArraySizeException. Even
                // below that, the product may exceed any usable heap.
                Object[] kvs = new Object[kvSlots(n)];
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
                            //
                            // Wrapped, because the default comparator throws a
                            // raw ClassCastException on keys it cannot order --
                            // "Default comparator requires nil, Number, or
                            // Comparable" -- and a SINGLE BYTE FLIP reaches it:
                            // change one key's head to 0xF8 and an ordinary
                            // document becomes a sorted-map of simple values.
                            // That was 8502 of 8872 untyped throwables in an
                            // exhaustive single-byte sweep, and it escaped
                            // `decode`, `decode-seq` and `nav/value` alike.
                            // Random-byte fuzzing never builds a tag-27 frame
                            // carrying a valid name, which is why it survived.
                            Object m2 = clojure.lang.PersistentTreeMap.EMPTY;
                            try {
                                for (Object o : ((java.util.Map) argument).entrySet()) {
                                    java.util.Map.Entry e2 = (java.util.Map.Entry) o;
                                    m2 = ((clojure.lang.Associative) m2).assoc(e2.getKey(), e2.getValue());
                                }
                            } catch (ClassCastException e) {
                                throw Err.of("bad-tag-content",
                                    "boring: clojure/sorted-map keys are not mutually"
                                    + " comparable (" + e.getMessage() + ")");
                            }
                            return m2;
                        }
                        case "clojure/sorted-set":
                            // Same hazard, same one-byte reach: see sorted-map.
                            try {
                                return clojure.lang.PersistentTreeSet.create(
                                    clojure.lang.RT.seq(seqableContent(argument, "clojure/sorted-set")));
                            } catch (ClassCastException e) {
                                throw Err.of("bad-tag-content",
                                    "boring: clojure/sorted-set elements are not mutually"
                                    + " comparable (" + e.getMessage() + ")");
                            }
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
                            java.util.List l2 = listMarkerContent(argument, "java/boolean-array");
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
                            java.util.List l2 = listMarkerContent(argument, "java/string-array");
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
                            java.util.List l2 = listMarkerContent(argument, "java/object-array");
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
                // RFC 9581's map rules, ENFORCED rather than assumed.
                //
                // Both numbers used to go through longValue(), which silently
                // TRUNCATED: `{1 1.5}` is a perfectly valid one-and-a-half
                // second duration and decoded as one second. A wrong value is
                // the worst outcome available here, and the writer's own
                // `{1 seconds, -9 nanos}` subset hid it because round trips
                // never produce the other forms.
                //
                // The rules below are the RFC's. What boring does not represent
                // -- decimal-fraction and bigfloat bases (keys 4 and 5), and
                // the scaled-fraction keys other than -9 -- is REFUSED with a
                // typed error naming the key, rather than reported as "no base
                // value" or ignored. Refusing a conforming form we cannot carry
                // losslessly is honest; truncating it is not.
                Object sec = m.get(1L);
                Object nano = null;
                int fracScale = 0;                  // 3, 6, 9, 12, 15 or 18
                for (Object k : m.keySet()) {
                    // A TEXT KEY IS ELECTIVE and skipped -- see the negative-key
                    // note below; RFC 9581 3 groups the two together.
                    if (!(k instanceof Number)) continue;
                    // EXACT, not narrowed. `longValue()` wrapped, so a key of
                    // 2^64+1 came out as 1 and ALIASED the base key -- an
                    // unknown critical key evading the rule that is supposed to
                    // reject it. A key too wide for a long is by definition one
                    // we do not implement: unsigned means critical (error),
                    // negative means elective (ignore).
                    java.math.BigInteger kb = exactInteger(k);
                    if (kb == null || kb.bitLength() > 63) {
                        if (kb != null && kb.signum() < 0) continue;   // elective
                        throw Err.of("bad-tag-content",
                            "boring: tag 1002 has unknown critical key " + k, "tag", 1002L);
                    }
                    long kk = kb.longValueExact();
                    if (kk == 4 || kk == 5)
                        throw Err.of("bad-tag-content",
                            "boring: tag 1002 base key " + kk + " (decimal fraction /"
                            + " bigfloat) is not a form boring represents", "tag", 1002L);
                    // Unsigned keys are CRITICAL in RFC 9581: a reader that does
                    // not understand one must fail rather than ignore it.
                    if (kk >= 0 && kk != 1)
                        throw Err.of("bad-tag-content",
                            "boring: tag 1002 has unknown critical key " + kk, "tag", 1002L);
                    if (kk == -3 || kk == -6 || kk == -9
                        || kk == -12 || kk == -15 || kk == -18) {
                        // RFC 9581 3.3: "Each extended time data item MUST NOT
                        // contain more than one of these keys."
                        if (nano != null)
                            throw Err.of("bad-tag-content",
                                "boring: tag 1002 has more than one decimally scaled"
                                + " fraction key", "tag", 1002L);
                        fracScale = (int) -kk;
                        nano = m.get(k);
                    }
                    // EVERY OTHER NEGATIVE KEY IS IGNORED, which RFC 9581 3
                    // requires: "For negative integer keys and text string values
                    // of the key, implementations MUST ignore key/value pairs they
                    // do not understand; these keys are 'elective', as the
                    // extended time as a whole is still usable without the
                    // information they carry".
                    //
                    // boring threw on all of them. That refused conforming
                    // durations outright -- `{1: 5, -1: 0}` (timescale UTC, the
                    // DEFAULT) and `{1: 5, -13: ...}` among them -- and inverted
                    // a MUST while the comment above claimed "the rules below are
                    // the RFC's". The unsigned half was right and stays.
                }
                if (sec == null)
                    throw Err.of("bad-tag-content",
                                 "boring: tag 1002 has no base value (key 1)", "tag", 1002L);
                // Checked, not cast. A string here produced a raw
                // ClassCastException that walked straight through the typed-
                // error contract SECURITY.md advertises.
                if (!(sec instanceof Number))
                    throw Err.of("bad-tag-content",
                                 "boring: tag 1002 base value must be a number", "tag", 1002L);
                boolean fracBase = (sec instanceof Double) || (sec instanceof Float);
                // A BIGNUM base is checked, not narrowed. The float case was
                // fixed and this one was not: `longValue()` on a BigInteger
                // wraps silently, so a 20-digit second count came back as
                // PT2157299897625622H45M19S -- a wrong Duration from valid
                // input, which is the same defect the fractional case had.
                // Any integral type WIDER than long, whichever class carries it:
                // a CBOR bignum decodes to clojure.lang.BigInt here, not
                // java.math.BigInteger, so checking one class missed the case
                // entirely -- which is how the first attempt at this fix still
                // returned PT2157299897625622H45M19S.
                if (!fracBase && !(sec instanceof Long) && !(sec instanceof Integer)
                    && !(sec instanceof Short) && !(sec instanceof Byte)) {
                    java.math.BigInteger bi;
                    try { bi = new java.math.BigInteger(sec.toString()); }
                    catch (NumberFormatException e) {
                        throw Err.of("bad-tag-content",
                            "boring: tag 1002 base value is not an integer", "tag", 1002L);
                    }
                    if (bi.bitLength() >= 64)
                        throw Err.of("bad-tag-content",
                            "boring: tag 1002 base value " + bi
                            + " does not fit a java.time.Duration", "tag", 1002L);
                    sec = Long.valueOf(bi.longValueExact());
                }
                if (nano == null) {
                    if (!fracBase)
                        return java.time.Duration.ofSeconds(((Number) sec).longValue());
                    // A fractional base with no scaled fraction: exact, or refused.
                    double d = ((Number) sec).doubleValue();
                    if (Double.isNaN(d) || Double.isInfinite(d))
                        throw Err.of("bad-tag-content",
                                     "boring: tag 1002 base value is not finite", "tag", 1002L);
                    // EXACT OR REFUSED, which is what the comment always said
                    // and the arithmetic did not do. `Math.round` plus a 1e-9
                    // tolerance ACCEPTED 5.0e-10 and rounded it to one
                    // nanosecond -- a value the tolerance was meant to catch,
                    // since its whole error is below the threshold -- and the
                    // final `(long) secs` SATURATED, so 1.0e20 came back as
                    // Long.MAX_VALUE seconds instead of being refused.
                    //
                    // BigDecimal.valueOf uses Double.toString, i.e. the
                    // shortest decimal that round-trips, so this asks "is the
                    // value as written a whole number of nanoseconds". 1.5 and
                    // 0.1 are; 5.0e-10 is not. `new BigDecimal(double)` would
                    // use the exact binary value and reject 0.1, which is too
                    // strict for a duration somebody actually wrote down.
                    java.math.BigDecimal nanosDec =
                        java.math.BigDecimal.valueOf(d).movePointRight(9);
                    java.math.BigInteger totalNanos;
                    try {
                        totalNanos = nanosDec.toBigIntegerExact();
                    } catch (ArithmeticException e) {
                        throw Err.of("bad-tag-content",
                            "boring: tag 1002 base value " + d
                            + " is not representable to nanosecond precision", "tag", 1002L);
                    }
                    java.math.BigInteger[] qr = totalNanos.divideAndRemainder(
                        java.math.BigInteger.valueOf(1000000000L));
                    if (qr[0].bitLength() > 63)
                        throw Err.of("bad-tag-content",
                            "boring: tag 1002 base value " + d
                            + " does not fit a java.time.Duration", "tag", 1002L);
                    return java.time.Duration.ofSeconds(qr[0].longValueExact(),
                                                        qr[1].longValueExact());
                }
                // With a scaled fraction present, RFC 9581 requires an INTEGER
                // base and an UNSIGNED fraction. Both were accepted and
                // truncated before.
                if (fracBase)
                    throw Err.of("bad-tag-content",
                        "boring: tag 1002 base value must be an integer when a"
                        + " scaled fraction is present", "tag", 1002L);
                if (!(nano instanceof Number) || nano instanceof Double || nano instanceof Float)
                    throw Err.of("bad-tag-content",
                                 "boring: tag 1002 fraction must be an integer", "tag", 1002L);
                java.math.BigInteger fb = exactInteger(nano);
                if (fb == null || fb.bitLength() > 63)
                    throw Err.of("bad-tag-content",
                        "boring: tag 1002 fraction " + nano
                        + " does not fit a java.time.Duration", "tag", 1002L);
                long fv = fb.longValueExact();
                if (fv < 0)
                    throw Err.of("bad-tag-content",
                        "boring: tag 1002 fraction " + fv + " must be unsigned", "tag", 1002L);
                // SCALED TO NANOSECONDS. -3 is milliseconds (Java time), -6
                // microseconds (old UNIX), -9 nanoseconds (new UNIX) -- all three
                // exact in a Duration, and boring used to refuse two of them.
                long nn;
                if (fracScale <= 9) {
                    long mul = pow10(9 - fracScale);
                    if (fv > 999999999L / mul)
                        throw Err.of("bad-tag-content",
                            "boring: tag 1002 fraction " + fv + "e-" + fracScale
                            + " is a second or more", "tag", 1002L);
                    nn = fv * mul;
                } else {
                    // -12/-15/-18 are picoseconds and finer. Accepted when they
                    // land on a whole nanosecond, refused otherwise: a Duration
                    // has no room below 1 ns, and silently dropping the remainder
                    // would be the truncation this tag handler exists to prevent.
                    long div = pow10(fracScale - 9);
                    if (fv % div != 0)
                        throw Err.of("bad-tag-content",
                            "boring: tag 1002 fraction " + fv + "e-" + fracScale
                            + " is finer than the nanosecond a java.time.Duration holds",
                            "tag", 1002L);
                    nn = fv / div;
                    if (nn > 999999999L)
                        throw Err.of("bad-tag-content",
                            "boring: tag 1002 fraction " + fv + "e-" + fracScale
                            + " is a second or more", "tag", 1002L);
                }
                return java.time.Duration.ofSeconds(((Number) sec).longValue(), nn);
            }
            case 1004: {                                     // full-date, RFC 8943
                Object v = read();
                if (!(v instanceof String))
                    throw Err.of("bad-tag-content",
                                 "boring: tag 1004 must wrap an RFC 3339 full-date string",
                                 "tag", 1004L);
                if (!RFC3339_DATE.matcher((String) v).matches())
                    throw Err.of("bad-tag-content",
                                 "boring: tag 1004 content is not a full-date: " + v,
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
                // ANY DIMENSIONALITY. RFC 8746 3.1.1 describes "an array ... of
                // dimensions, which are unsigned integers distinct from zero"
                // and never bounds how many. Demanding exactly two rejected the
                // RFC's own 3-D examples -- conforming input, refused.
                if (dims.isEmpty())
                    throw Err.of("bad-tag-content",
                        "boring: tag 40 dimensions array must not be empty", "tag", 40L);
                // TYPES CHECKED BEFORE THEY ARE USED. Casting the dimensions
                // straight to Number and asking `Array.getLength` for the
                // payload's length let a WELL-FORMED tag with wrong-shaped
                // content escape as a raw ClassCastException or
                // IllegalArgumentException -- contradicting doc/SECURITY.md's
                // typed-failure guarantee. The byte fuzzer rarely builds a
                // valid tag around invalid content, which is the limitation
                // that document already names.
                int nd = dims.size();
                // The decoded value nests once per dimension, so the dimension
                // COUNT is nesting and belongs to the same budget. Without this
                // a flat dims array bought unbounded nesting for free, and a
                // 20 KB item declaring 20 000 dimensions blew the host stack.
                if (depth + nd > maxDepth)
                    throw Err.of("max-depth-exceeded",
                        "boring: tag 40 with " + nd + " dimensions nests deeper than "
                        + maxDepth, "tag", 40L, "depth", (long) nd);
                int[] shape = new int[nd];
                long total = 1;
                for (int i = 0; i < nd; i++) {
                    Object d = dims.get(i);
                    if (!(d instanceof Number) || d instanceof Double || d instanceof Float
                        || (d instanceof java.math.BigInteger
                            && ((java.math.BigInteger) d).bitLength() > 31)
                        // clojure.lang.BigInt, NOT just BigInteger: a CBOR
                        // bignum decodes to the former, so checking only the
                        // latter let longValue() narrow 2^64+1 to 1.
                        || (d instanceof clojure.lang.BigInt
                            && ((clojure.lang.BigInt) d).bitLength() > 31))
                        throw Err.of("bad-tag-content",
                            "boring: tag 40 dimensions must be integers", "tag", 40L);
                    long dv = ((Number) d).longValue();
                    // "distinct from zero" is the RFC's wording, so 0 is not a
                    // conforming dimension -- and boring already declines to
                    // EMIT a zero-row matrix as tag 40 for the same reason.
                    if (dv <= 0)
                        throw Err.of("bad-tag-content",
                            "boring: tag 40 dimension " + dv + " is not an unsigned integer "
                            + "distinct from zero", "tag", 40L);
                    if (dv > Integer.MAX_VALUE)
                        throw Err.of("bad-tag-content",
                            "boring: tag 40 dimension " + dv
                            + " exceeds the largest array this platform can build", "tag", 40L);
                    shape[i] = (int) dv;
                    total *= dv;
                    if (total > Integer.MAX_VALUE)
                        throw Err.of("bad-tag-content",
                            "boring: tag 40 dimensions " + dims
                            + " exceed the largest array this platform can build", "tag", 40L);
                }
                Object flat = l.get(1);
                // THREE PAYLOAD SHAPES, all conforming per 3.1.1: "any one of a
                // CBOR array of major type 4, a Typed Array, or a Homogeneous
                // Array". boring accepted only the middle one, which is why
                // Figure 2 of the defining RFC did not decode here.
                if (flat instanceof clojure.lang.ILookup
                    && Long.valueOf(41L).equals(clojure.lang.RT.get(flat, KW_TAG)))
                    flat = clojure.lang.RT.get(flat, KW_VALUE);
                int declared;
                boolean typed = flat != null && flat.getClass().isArray()
                                && !(flat instanceof Object[]);
                if (typed) declared = java.lang.reflect.Array.getLength(flat);
                else if (flat instanceof java.util.List) declared = ((java.util.List) flat).size();
                else throw Err.of("bad-tag-content",
                        "boring: tag 40 payload must be a CBOR array, a typed array or a "
                        + "homogeneous array, got "
                        + (flat == null ? "nil" : flat.getClass().getName()), "tag", 40L);
                // The dimensions come from the wire and the payload length comes
                // from the wire; if they disagree the item is malformed, and
                // allocating the product on the strength of the dimensions alone
                // would be an unchecked allocation.
                if (total != declared)
                    throw Err.of("bad-tag-content",
                        "boring: tag 40 dimensions " + dims
                        + " do not match the " + declared + "-element payload", "tag", 40L);
                // CHARGED BEFORE IT IS BUILT. Everything below turns one byte
                // string into `total` host objects plus the vectors holding
                // them, and none of that goes through `read()`, so the item
                // budget never saw it. `total` is the element count and the
                // nesting adds the intermediate vectors on top, which is why
                // the charge is made here rather than per element inside
                // nestDims: the point is to refuse BEFORE allocating.
                countItems(total);
                // A 2-D typed array keeps its dedicated primitive matrix type
                // (double[][], long[][]), which is what boring writes and what
                // `boring.core` dispatches on. Everything else -- any other
                // dimensionality, or a plain CBOR array payload -- becomes
                // nested vectors, the only shape that generalises.
                if (nd == 2 && typed) {
                    Class<?> comp = flat.getClass().getComponentType();
                    Object out = java.lang.reflect.Array.newInstance(comp, shape[0], shape[1]);
                    for (int r = 0; r < shape[0]; r++)
                        System.arraycopy(flat, r * shape[1], ((Object[]) out)[r], 0, shape[1]);
                    return out;
                }
                return nestDims(flat, shape, (int) total);
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
        int target = (int) (limit >>> 4);
        while (want < target && want < 512) want <<= 1;
        identKeys = new byte[want][];
        identVals = new Object[want];
        identMask = want - 1;
    }

    private boolean bytesMatch(byte[] key, long start, int n) {
        if (key.length != n) return false;
        for (int i = 0; i < n; i++) if (key[i] != sb(start + i)) return false;
        return true;
    }

    /** Intern the identifier spelled by buf[start, start+n), via the cache. */
    private Object identFromBytes(long start, int n) {
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
            if (!reused && limit < IDENT_CACHE_MIN_INPUT)
                return internIdentDirect(start, n);
            allocIdentCache();
        }
        int h = n;
        for (int i = 0; i < n; i++) h = h * 31 + sb(start + i);
        int slot = (h ^ (h >>> 16)) & identMask;
        byte[] k = identKeys[slot];
        if (k != null && bytesMatch(k, start, n)) return identVals[slot];
        Object ident = internIdent(stringAt(start, n, StandardCharsets.UTF_8));
        // A cache key outlives this call, so it cannot alias scratch.
        identKeys[slot] = freshBytes(start, n);
        identVals[slot] = ident;
        return ident;
    }

    /** Intern without touching the cache -- identical result, no allocation
     *  beyond the String the interning itself needs. */
    private Object internIdentDirect(long start, int n) {
        return internIdent(stringAt(start, n, StandardCharsets.UTF_8));
    }

    private static Object internIdent(String s) {
        if (!s.isEmpty() && s.charAt(0) == ':') return Keyword.intern(s.substring(1));
        return Symbol.intern(s);
    }
}
