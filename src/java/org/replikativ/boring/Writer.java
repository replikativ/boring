package org.replikativ.boring;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CBOR writer over a growable byte[].
 *
 * The hot path is byte[] + static final VarHandles, NOT FFM MemorySegment.
 * Measured 1.477 ns/long and 0.327 ns/byte against 1.750 / 0.953 for a heap
 * MemorySegment. Requires JDK 9+, and no flags -- an FFM path would need
 * --enable-native-access on current JDKs.
 *
 * Re-measured since against a NATIVE segment, which that first round never
 * covered. Naively, big-endian access to an off-heap segment costs 5.63 ns
 * against 1.37 for byte[] on stock HotSpot 25 -- but that 4.1x is the JIT
 * declining to intrinsify a non-native ValueLayout, not a cost of off-heap
 * memory, and it vanishes if you access in native order and call
 * Long.reverseBytes (a bswap intrinsic): 1.38 ns, byte-identical output.
 *
 * So FFM is available at parity on stock HotSpot, and byte[] is kept for
 * reasons other than raw scalar speed: no --enable-native-access, JDK 9+
 * rather than 22+, endian-neutrality without a manual swap, and an encode path
 * that already measures 0 bytes/op allocated. Graal additionally penalises
 * native-segment WRITES 1.6x. doc/PERFORMANCE.md has the tables, and the
 * caveat that matters more than any of them: those figures come from a tight
 * loop over a constant layout, where the JIT hoists the bounds and liveness
 * checks out. A recursive decoder does not get that, and measured 14-50%
 * slower when it was actually built that way.
 *
 * This class comment used to end "prototype scope: bignums, ratios, instants,
 * typed arrays and the tag registry are not here yet." All of those ship. See
 * doc/COMPATIBILITY.md for the complete list of what is emitted.
 */
public final class Writer {

    private static final VarHandle SHORT_BE =
        MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT_BE =
        MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG_BE =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    // CBOR major types, pre-shifted into the header byte's high 3 bits.
    private static final int UINT   = 0 << 5;
    private static final int NINT   = 1 << 5;
    private static final int BYTES  = 2 << 5;
    private static final int TEXT   = 3 << 5;
    private static final int ARRAY  = 4 << 5;
    private static final int MAP    = 5 << 5;
    private static final int TAG    = 6 << 5;
    private static final int SIMPLE = 7 << 5;

    private static final int TAG_IDENTIFIER = 39;   // keywords / symbols
    private static final int TAG_STRINGREF  = 25;
    private static final int TAG_SR_NS      = 256;
    private static final int TAG_SET        = 258;

    private static final int TAG_POS_BIGNUM = 2;
    private static final int TAG_NEG_BIGNUM = 3;
    private static final int TAG_DATETIME   = 0;   // RFC 3339 string
    private static final int TAG_EPOCH      = 1;   // seconds since 1970
    private static final int TAG_RATIONAL   = 30;  // [numerator denominator]
    // Registered tags for JVM types that would otherwise have no portable
    // representation. All three are standard, so a foreign reader gets a real
    // URI or regex rather than an opaque blob -- which is the whole reason to
    // prefer them over inventing private numbers.
    private static final int TAG_URI        = 32;  // RFC 8949 3.4.5.3
    private static final int TAG_DURATION   = 1002; // RFC 9581 4
    private static final int TAG_FULL_DATE  = 1004; // RFC 8943, RFC 3339 full-date
    private static final int TAG_REGEX      = 35;  // RFC 7049 2.4.4.3
    private static final int TAG_UUID       = 37;
    private static final int TAG_GENERIC_OBJ = 27;  // records

    /**
     * Reserved tag-27 type names for Clojure collections CBOR cannot otherwise
     * distinguish. A sorted map is a CBOR map and a queue is a CBOR array, so
     * without a marker they come back as a plain map and a vector -- value-equal,
     * and the wrong type.
     *
     * The names carry a SLASH, which a JVM class name never does (they use
     * dots), so they cannot collide with a user record's wire name.
     *
     * A foreign reader with no idea what these are still gets the entries, in
     * order, under a self-describing name -- which is the whole argument for
     * tag 27 over a private tag.
     */
    private static final String NAME_SORTED_MAP = "clojure/sorted-map";
    private static final String NAME_SORTED_SET = "clojure/sorted-set";
    private static final String NAME_QUEUE      = "clojure/queue";
    private static final String NAME_WITH_META  = "clojure/with-meta";

    /**
     * Reserved names for types CBOR has no registered tag for, where the
     * untagged encoding would be value-WRONG rather than merely type-wide.
     *
     * The prefix names the runtime that owns the type: `clojure/` for Clojure's
     * own, `java/` for a JDK class. Character was previously written as a
     * one-character text string, so `{:c \\a}` came back as `{:c "a"}` -- and
     * `(= \\a "a")` is FALSE, so that is silent corruption, not widening.
     * ClojureScript has no character type at all and reads this back as the
     * one-character string, which is the closest thing that exists there.
     *
     * java.time.Period is a DATE amount (years/months/days), not a time
     * amount, so RFC 9581's tag 1002 (duration, seconds) cannot express it and
     * that RFC's tag 1003 means something else entirely -- a start/end
     * interval. The ISO-8601 form is what java.time itself parses.
     */
    private static final String NAME_CHAR       = "clojure/char";
    private static final String NAME_PERIOD     = "java/period";

    /**
     * Array types RFC 8746 does not cover. Its typed arrays are NUMERIC only,
     * so these four had no encoding at all and threw
     * :boring/unsupported-type -- while nippy carries every one of them.
     *
     * A plain CBOR array would lose the type: `(= (boolean-array [true])
     * [true])` is false, so returning a vector is the silent corruption a
     * Character becoming a String was, not the value-preserving widening
     * Byte -> Long is.
     *
     * char[] carries its content as a TEXT STRING rather than an array of
     * one-character frames: same information, a fraction of the bytes, and a
     * foreign reader sees something it can use.
     */
    /**
     * Exceptions as DATA, never as a serialized object.
     *
     * nippy carries a Throwable through Java serialization, which is the
     * mechanism behind the deserialization CVE family and produces bytes no
     * non-JVM reader can interpret. These carry the three fields that survive
     * a process boundary meaningfully -- type, message, cause -- as ordinary
     * CBOR, so a Python peer reads them.
     *
     * The STACK TRACE is deliberately dropped. It is large, it is meaningless
     * in the receiving process, and it leaks absolute source paths and
     * internal class names to whoever receives the message.
     *
     * An `ex-info` keeps its data map and reconstructs as a real ExceptionInfo.
     * Any other Throwable reconstructs as an ExceptionInfo too, carrying the
     * original class name under :boring/throwable-class -- reinstantiating an
     * arbitrary Throwable class would mean resolving a class name from the
     * wire, which is the one thing the reader does not do.
     */
    private static final String NAME_EX_INFO   = "clojure/ex-info";
    private static final String NAME_THROWABLE = "java/throwable";

    private static final String NAME_BOOLEAN_ARRAY = "java/boolean-array";
    private static final String NAME_CHAR_ARRAY    = "java/char-array";
    private static final String NAME_STRING_ARRAY  = "java/string-array";
    private static final String NAME_OBJECT_ARRAY  = "java/object-array";

    /**
     * Shaped array: an array whose elements are all maps sharing one key set,
     * encoded as [keys, [values-per-row...]] so the keys appear ONCE.
     *
     * Measured motivation: stripping per-record keys took datom-maps-200 CLJS
     * decode from 612 to 230 us, and 9952 to 4947 bytes.
     * Per-record key work was the entire remaining gap.
     *
     * Applied at the CONTAINER level rather than per record on purpose: a
     * per-record tag would cost 3-5 bytes 200 times over, one tag for the array
     * costs 3 bytes once.
     *
     * PROVISIONAL, pending IANA registration — see doc/COMPATIBILITY.md.
     *
     * This was 40000 until a check of the IANA registry found 40000 is ALREADY
     * ASSIGNED, to ur:known-value (Blockchain Commons). That was a live
     * collision, not a hypothetical one: two specifications claiming the same
     * tag means a document's meaning depends on who is reading it.
     *
     * 39649 was chosen to be hard to collide with rather than tidy. It is odd
     * and non-round, because registrants take round numbers and contiguous
     * blocks, and it sits mid-gap in 39000-39999 — the one clean First-Come-
     * First-Served span below the dense ur: cluster that occupies most of
     * 40000-40918.
     *
     * A decoder that does not know this tag surfaces a TaggedValue rather than
     * misreading anything, so a future collision degrades to "unrecognised
     * extension", never to silent corruption.
     */
    private static final int TAG_SHAPED_ARRAY = 39649;

    // RFC 8746 typed arrays, little-endian variants. LE is the zero-copy path
    // on both a JVM bulk write and a JS TypedArray view.
    /**
     * RFC 8746 multi-dimensional array, row-major: 40([[dims...], flat-array]).
     *
     * Exists because a 2D primitive array previously had NO encoding at all --
     * `double[][]` threw "no encoding for [[D". The registered form composes
     * with the typed arrays already emitted for the 1D case, so a matrix costs
     * one tag plus a dimensions array on top of a bulk little-endian copy.
     *
     * Only RECTANGULAR arrays take this path -- including a ZERO-ROW one, which
     * is 0x0 and whose element type is known from the array's own class. A
     * ragged array is not a multi-dimensional array in RFC 8746's sense, so it
     * falls back to a plain CBOR array of typed arrays.
     *
     * That fallback preserves the VALUES but not the type: a ragged
     * `double[][]` decodes as a vector of `double[]`, not as `double[][]`. The
     * comment here used to say "still round-trips", which is true of the
     * numbers and false of the type -- the distinction this library otherwise
     * takes care to keep. Declaring it rather than fixing it: a type-preserving
     * frame for ragged matrices would be a private tag, and RFC 8746 has
     * nothing to say about them.
     */
    private static final int TAG_MULTI_DIM_ROW = 40;

    private static final int TAG_ARR_S16_LE = 77;
    private static final int TAG_ARR_S32_LE = 78;
    private static final int TAG_ARR_S64_LE = 79;
    private static final int TAG_ARR_F32_LE = 85;
    private static final int TAG_ARR_F64_LE = 86;

    private static final VarHandle SHORT_LE =
        MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_LE =
        MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private byte[] buf;
    private int pos;

    /**
     * Type-preservation policy. When true (the default), a value is encoded in
     * the width of its source type rather than the narrowest form that fits:
     * a Double stays f64, a Float stays f32, a BigInt stays a tagged bignum.
     * When false, RFC 8949 preferred serialisation applies.
     *
     * This governs floats AND bignums — both are cases where the compact
     * encoding silently changes the type on the way back. See
     * datahike's dump requirements, and doc/COMPATIBILITY.md for which
     * profiles accept the loss.
     */
    public boolean preserveWidth = true;
    public boolean stringref = true;

    /**
     * Carry Clojure metadata, as `27(["clojure/with-meta", [meta value]])`.
     *
     * On by default, matching nippy's `*incl-metadata?*`, and for the same
     * reason it matters here: `=` IGNORES metadata in Clojure, so dropping it
     * passes every value-equality test. A limitation no test can see is the
     * dangerous kind -- it is the same shape as Character silently becoming a
     * String.
     *
     * Paid only when metadata is actually present, so a value without it is
     * byte-identical to before.
     *
     * The metadata map must itself be encodable. Attaching a function or a live
     * object to a value now makes that value fail to encode, where previously
     * the metadata was silently discarded. That is the intended trade: loud
     * beats silent. Set false to restore the old behaviour.
     */
    public boolean inclMetadata = true;

    /** Consumed on entry to writeValue, so the wrapper is not re-applied to the
     *  value it just wrapped -- while children still get their own metadata. */
    private boolean metaWrapped = false;

    /**
     * Canonical map key ordering: RFC 8949 §4.2.1, keys sorted bytewise
     * lexicographically by their *encoded* form.
     *
     * NOTE this differs from clj-cbor, which uses RFC 7049's length-first then
     * lexicographic rule (codec.clj:361). Both are deterministic; they are not
     * the same order. Reading old clj-cbor dumps is unaffected (ordering is a
     * writer concern), but a re-export will not be byte-identical to one
     * produced by clj-cbor.
     *
     * Implies stringref off — a stringref makes a key's encoding depend on what
     * was encoded before it, which is incompatible with sorting by encoding.
     * Buffers each key to compare it, so this is not the hot path.
     */
    public boolean canonical = false;

    /**
     * Use clj-cbor's / RFC 7049's length-first-then-lexicographic key order
     * instead of RFC 8949 §4.2.1's bytewise order.
     *
     * Default is the RFC 8949 rule, because signature verification is the point
     * of canonical output and every non-Clojure CBOR canonicaliser implements
     * that rule — the legacy order would make a signed dump verifiable only
     * from Clojure. Set true only to reproduce clj-cbor's exact bytes.
     */
    public boolean legacyCanonicalOrder = false;

    /**
     * Permit emitting simple values 24-31, which RFC 8949 §3.3 forbids
     * ("an encoder MUST NOT issue two-byte sequences that start with 0xf8 and
     * continue with a byte less than 0x20"). Off by default. Turn on only to
     * round-trip a third-party document byte-identically.
     */
    public boolean permitReservedSimpleValues = false;

    /**
     * Emit shaped arrays where an array's elements all share a key set.
     * Off by default: it costs ~3 bytes on an array that turns out not to be
     * homogeneous-and-repetitive, and it is a wire feature a peer must
     * understand. datahike's chunked dumps are the case it exists for.
     */
    /**
     * Resolved encode options, for callers that create the writer once.
     *
     * Held as Object because they are a Clojure map and this class knows
     * nothing about them -- boring.core resolves them at writer construction
     * and reads them back on every 2-arity encode. The point is to stop
     * resolving them PER CALL: `resolve-opts` merges the caller's map over a
     * profile's defaults, which allocated ~230-300 B on every single encode.
     * A navigable log pays that on every event, because navigation requires
     * `:stringref false` and so cannot use the nil-opts fast path.
     */
    public Object opts;

    public boolean shapes = false;

    /**
     * Encode-side nesting cap. The reader has had one since §10j; the writer had
     * nothing, so a 200k-deep vector — or a self-referential java.util.List —
     * threw StackOverflowError, an Error that `catch Exception` does not stop.
     * Reachable whenever a decoded document is re-encoded.
     */
    public int maxDepth = 1024;
    /**
     * Called with a value that has no encoding; its result is written instead.
     * Null (the default) throws :boring/unsupported-type as before.
     */
    public clojure.lang.IFn encodeFallback = null;
    private boolean inFallback = false;

    /** Depth already consumed by the parent, for a canonical scratch writer. */
    private int depthOffset = 0;
    private int depth;

    /** User tag handlers consulted when nothing built-in matches. */
    public TagRegistry registry = TagRegistry.EMPTY;

    /** Reused across canonical maps — allocating one per map made canonical
     *  encoding 4.4x slower than plain on keyword-keyed maps. */
    private Writer canonicalScratch;

    // ---- container index, captured while encoding -------------------------
    //
    // The writer already knows every offset it is about to write to, and it
    // knows a container's entry count before emitting a byte of it. So the
    // index falls out of encoding rather than out of a second pass over the
    // result, which is what `boring.core/index-walk` does. That walk cannot
    // beat 31% of encode time no matter how it is written, because CBOR
    // containers are element-counted and stepping over a subtree means walking
    // it. Nothing here needs a subtree's LENGTH -- only where its entries
    // start -- which is why the same property that makes reading expensive
    // makes writing free.
    //
    // Off unless `setIndex` is called. `idxStride == 0` is the off switch and
    // is checked once per container, never per byte.
    //
    // DELIBERATELY NOT inherited by `canonicalSubWriter`: a scratch writer
    // encodes map keys into its OWN buffer, so any node it recorded would
    // carry offsets into bytes that get copied elsewhere. See the assertion
    // there.

    private int idxStride = 0;
    private int idxMinEntries = 16;
    /** Added to every recorded offset, so `write-seq!` gets file offsets. */
    private long idxBase = 0;

    private int[] idxOffs = new int[32];
    private int[] idxCnts = new int[32];
    private boolean[] idxSrt = new boolean[32];
    private int[][] idxSlots = new int[32][];
    private int idxN = 0;

    /**
     * Begin (or end) a capture session. `stride` of 0 turns capture off.
     *
     * Discards anything captured so far. Index state deliberately survives
     * {@link #reset()} -- `write-seq!` resets the writer once per item while
     * the nodes accumulate across the whole sequence -- so there has to be some
     * other point at which a fresh start is declared, and this is it. Without
     * that, a caller who enabled capture once and then encoded many unrelated
     * values accumulated nodes forever, with offsets from documents that no
     * longer exist.
     *
     * Use {@link #idxBase(long)} to move the base between items of one
     * sequence; that is the operation that must NOT discard.
     */
    public void setIndex(int stride, int minEntries, long base) {
        this.idxStride = stride;
        this.idxMinEntries = minEntries;
        this.idxBase = base;
        idxReset();
    }

    /** Move the offset base without disturbing what has been captured. */
    public void idxBase(long base) { this.idxBase = base; }

    // ---- sequence offsets -------------------------------------------------
    //
    // `write-seq!` also wants the offset of every Nth top-level ITEM, which it
    // knows from its own running byte count. Two earlier homes for that list
    // both cost more than this one:
    //
    //   java.util.ArrayList  boxes an Integer per item -- at stride 1 that is
    //                        one allocation per record
    //   a Clojure volatile   avoids the boxing and costs MORE: three volatile
    //   holding an int[]     reads and two volatile writes per anchor, and a
    //                        volatile write is a store barrier. Measured, this
    //                        was slower than the boxing it replaced.
    //
    // A plain Java field needs neither. The writer is already the mutable thing
    // in this picture and is not thread-safe anyway, so there is nothing for a
    // barrier to protect.

    private int[] idxSeq = new int[1024];
    private int idxSeqN = 0;
    private int idxItems = 0;
    private int idxItemCountdown = 1;

    /**
     * A top-level item begins at `off`. Records an anchor every `stride`th one
     * and counts them all, so the caller needs no per-item state of its own.
     */
    public void idxItem(long off) {
        if (idxStride <= 0) return;
        if (--idxItemCountdown == 0) {
            if (idxSeqN == idxSeq.length) idxSeq = java.util.Arrays.copyOf(idxSeq, idxSeqN * 2);
            idxSeq[idxSeqN++] = checkedOffset(off);   // already file-relative
            idxItemCountdown = idxStride;
        }
        idxItems++;
    }

    /** How many top-level items `idxItem` has seen. */
    public int idxItemTotal() { return idxItems; }

    /** Offsets of every `stride`th top-level item. */
    public int[] idxItemOffsets() { return java.util.Arrays.copyOf(idxSeq, idxSeqN); }

    public void idxReset() {
        // Null the slot references, do not merely forget the count. Each slot
        // is its own int[], so leaving them reachable pins one array per
        // indexed container for the life of the writer -- and a writer is meant
        // to be long-lived and reused. Same reasoning, and the same fix, as the
        // stringref key table below.
        if (idxN > 0) java.util.Arrays.fill(idxSlots, 0, idxN, null);
        idxN = 0;
        idxSeqN = 0;
        idxItems = 0;
        idxItemCountdown = 1;
    }
    public int idxCount() { return idxN; }
    public int[] idxContainers() { return java.util.Arrays.copyOf(idxOffs, idxN); }
    public int[] idxCounts()     { return java.util.Arrays.copyOf(idxCnts, idxN); }
    public Object[] idxSlots()   { return java.util.Arrays.copyOf(idxSlots, idxN); }
    public boolean[] idxSorted() { return java.util.Arrays.copyOf(idxSrt, idxN); }

    /** Anchors an indexed container of `n` entries needs at the current stride. */
    private int anchorCount(int n) {
        // An EMPTY container needs no anchors. `((0 - 1) / stride) + 1` is 1 in
        // Java, because integer division truncates toward zero -- so an empty
        // container claimed one anchor, the loop never wrote it, and the slot
        // kept a phantom offset of 0 that pointed at the start of the document.
        // Only reachable with `:index-min 0`, but it is a wrong answer waiting
        // rather than a missing optimisation.
        if (n <= 0) return 0;
        return idxStride == 1 ? n : ((n - 1) / idxStride) + 1;
    }

    private boolean indexing(int n) {
        return idxStride > 0 && n >= idxMinEntries;
    }

    /**
     * Claim this container's slot BEFORE its entries are written, and fill it
     * after.
     *
     * Order is the point. A node is only complete when its container ends, so
     * appending on completion yields post-order -- every child before its
     * parent -- and `read-index` binary-searches the container offsets, so it
     * needs them ascending. Claiming at the head instead makes the array
     * pre-order, and a pre-order DFS visits containers in strictly increasing
     * start offset: a container starts before all of its descendants, and
     * siblings ascend. So the array comes out sorted with no sort.
     */
    private int reserveNode() {
        if (idxN == idxOffs.length) {
            int m = idxN * 2;
            idxOffs = java.util.Arrays.copyOf(idxOffs, m);
            idxCnts = java.util.Arrays.copyOf(idxCnts, m);
            idxSrt = java.util.Arrays.copyOf(idxSrt, m);
            idxSlots = java.util.Arrays.copyOf(idxSlots, m);
        }
        return idxN++;
    }

    /**
     * An index offset, checked to fit the int the format stores it in.
     *
     * The index carries offsets as int32 -- `containers` and every slot are
     * int arrays on the wire. Past 2 GiB the cast silently produced NEGATIVE
     * offsets, which is the worst of all outcomes: `node-slot` binary-searches
     * `containers` and they stop ascending, sequence `nth` seeks to a negative
     * position, and past 4 GiB offsets collide outright. Nothing warned. The
     * back-pointer is 8 bytes and `nav` is long-clean throughout, so 2 GiB is a
     * limit of the index alone -- and `write-seq!` is explicitly for
     * long-running files, so it is reachable rather than theoretical.
     *
     * Refusing is right until the format carries wider offsets: an unindexed
     * file is correct and a wrongly-indexed one is not.
     */
    private int checkedOffset(long v) {
        if (v < 0 || v > Integer.MAX_VALUE)
            throw Err.of("index-offset-overflow",
                "boring: an index offset reached " + v + ", past the 2 GiB the index"
                + " format stores. Write this sequence without :index, or rotate the"
                + " file before it reaches 2 GiB.");
        return (int) v;
    }

    /** A buffer-relative position as a checked, file-relative index offset. */
    private int idxOffset(long off) { return checkedOffset(off + idxBase); }

    private void fillNode(int slot, long off, int n, int[] anchors, boolean sorted) {
        idxOffs[slot] = idxOffset(off);
        idxCnts[slot] = n;
        idxSlots[slot] = anchors;
        idxSrt[slot] = sorted;
    }

    private static final clojure.lang.Keyword K_N =
        clojure.lang.Keyword.intern(null, "n");
    private static final clojure.lang.Keyword K_TAG =
        clojure.lang.Keyword.intern(null, "tag");
    private static final clojure.lang.Keyword K_VALUE =
        clojure.lang.Keyword.intern(null, "value");

    /**
     * The boring.data types the encoder must recognise, resolved once at class
     * init of this holder.
     *
     * These used to be plain mutable statics assigned by boring.core at load
     * time. That was a data race on paper and a silent-corruption bug in
     * practice: anything reaching Writer without loading boring.core first
     * observed them null, `c == SIMPLE_VALUE_CLASS` was then simply false, and
     * a SimpleValue fell through to the IRecord branch below and encoded as a
     * 33-byte tag-27 record instead of `f8 c8`. No exception, wrong bytes.
     * `:reload-all` had the same effect, by rebinding the classes out from
     * under existing instances.
     *
     * Resolving them here instead makes them `static final`: safely published
     * without volatile, impossible to observe unset, immune to reload, and
     * constant-foldable by the JIT — which plain mutable statics are not. The
     * holder is a separate class so it initialises on first use rather than
     * with Writer, keeping load order irrelevant.
     */
    static final class Data {
        static final Class<?> SIMPLE_VALUE;
        static final Class<?> TAGGED_VALUE;
        static final Class<?> UNKNOWN_RECORD;
        /** UnknownRecord delegates ILookup to its fields, so its own
         *  type/fields must be read through accessors, not keyword lookup. */
        static final clojure.lang.IFn RECORD_TYPE;
        static final clojure.lang.IFn RECORD_FIELDS;

        static {
            try {
                clojure.lang.RT.var("clojure.core", "require")
                    .invoke(clojure.lang.Symbol.intern("boring.data"));
                SIMPLE_VALUE = clojure.lang.RT.classForName("boring.data.SimpleValue");
                TAGGED_VALUE = clojure.lang.RT.classForName("boring.data.TaggedValue");
                UNKNOWN_RECORD = clojure.lang.RT.classForName("boring.data.UnknownRecord");
                RECORD_TYPE = clojure.lang.RT.var("boring.data", "record-type");
                RECORD_FIELDS = clojure.lang.RT.var("boring.data", "record-fields");
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    // ---- per-namespace symbol table: open addressing, identity-keyed --------
    private Object[] srKeys;
    private int[] srVals;
    private int srMask;
    private int srCount;
    private int srNextIndex;

    public Writer(int initialSize) {
        this.buf = new byte[Math.max(64, initialSize)];
        this.pos = 0;
        // 16, not 256. The table grows by rehash and a reused writer keeps
        // whatever it grew to, so the only cost of starting small is a few
        // rehashes on the FIRST large document -- while starting at 256 cost
        // every writer 2080 B of Object[256] + int[256] whatever it encoded.
        // A fresh writer encoding a three-keyword map allocated 2432 B before
        // writing a byte, against hako's 792 B for the whole operation.
        initSymtab(16);
    }

    private void initSymtab(int cap) {
        srKeys = new Object[cap];
        srVals = new int[cap];
        srMask = cap - 1;
        srCount = 0;
        srNextIndex = 0;
    }

    /** Reset for reuse. Keeps the buffer; clears the stringref namespace. */
    public void reset() {
        pos = 0;
        depth = 0;
        metaWrapped = false;
        // srNextIndex is cleared UNCONDITIONALLY, outside the srCount guard.
        //
        // A byte string advances the index counter without adding a lookup key,
        // so srCount stays 0 while srNextIndex does not. Guarding the reset on
        // `srCount > 0` therefore left the counter set after a message whose
        // only qualifying string was a byte string, and the NEXT message's
        // references started at index 1 against a table that begins at 0 --
        // output that does not decode at all. `encode-into!` and `write-seq!`
        // are both advertised reuse paths, so this reached the streaming API.
        srNextIndex = 0;
        if (srCount > 0) {
            java.util.Arrays.fill(srKeys, null);
            srCount = 0;
        }
    }

    public int position() { return pos; }

    /**
     * The internal buffer. Valid bytes are [0, position()). Lets a caller write
     * straight to an OutputStream/channel instead of paying for toByteArray()'s
     * allocate-and-copy on every message — with a reused Writer that copy is
     * the only per-message allocation left, and it is what hako's off-heap
     * `encode-into!` exists to avoid. Contents are overwritten by the next
     * encode; do not retain.
     */
    public byte[] buffer() { return buf; }

    public byte[] toByteArray() {
        byte[] out = new byte[pos];
        System.arraycopy(buf, 0, out, 0, pos);
        return out;
    }

    /** Largest array this JVM will reliably allocate. */
    private static final int MAX_BUFFER = Integer.MAX_VALUE - 8;

    private void ensure(int n) {
        // `pos + n` in int wrapped negative for large values, so ensure()
        // silently skipped the grow and the following write ran off the end.
        long need = (long) pos + (long) n;
        if (need > buf.length) grow(need);
    }

    private void grow(long need) {
        if (need > MAX_BUFFER)
            throw Err.of("output-too-large",
                "boring: encoded output would exceed " + MAX_BUFFER + " bytes",
                "needed", need);
        // `cap <<= 1` wrapped to 0 past 2^31 and then looped forever at 100%
        // CPU — a wedged thread rather than an OOM or an exception.
        long cap = buf.length;
        while (cap < need) cap = Math.min(cap << 1, MAX_BUFFER);
        byte[] nb = new byte[(int) cap];
        System.arraycopy(buf, 0, nb, 0, pos);
        buf = nb;
    }

    // ---- primitive emission -------------------------------------------------

    private void u8(int b) {
        ensure(1);
        buf[pos++] = (byte) b;
    }

    /** Header byte for `major` carrying unsigned `val`, shortest form. */
    private void head(int major, long val) {
        // EVERY byte string on the wire consumes a stringref index, wherever it
        // appears -- a bignum magnitude, a UUID's 16 bytes and a typed-array
        // payload all count, which was verified against cbor2. Accounted here
        // rather than at each call site because there are six of them and the
        // failure mode of forgetting one is silent: our index space drifts from
        // the decoder's and a later reference resolves to the wrong entry.
        //
        // Major type 2 IS a byte string in CBOR, always, so this cannot
        // misfire on some other use of the same helper.
        if (major == BYTES) srConsumeIndex(val);
        if (val < 24L) {
            ensure(1);
            buf[pos++] = (byte) (major | (int) val);
        } else if (val < 0x100L) {
            ensure(2);
            buf[pos] = (byte) (major | 24);
            buf[pos + 1] = (byte) val;
            pos += 2;
        } else if (val < 0x10000L) {
            ensure(3);
            buf[pos] = (byte) (major | 25);
            SHORT_BE.set(buf, pos + 1, (short) val);
            pos += 3;
        } else if (val < 0x100000000L) {
            ensure(5);
            buf[pos] = (byte) (major | 26);
            INT_BE.set(buf, pos + 1, (int) val);
            pos += 5;
        } else {
            ensure(9);
            buf[pos] = (byte) (major | 27);
            LONG_BE.set(buf, pos + 1, val);
            pos += 9;
        }
    }

    public void writeLong(long n) {
        if (n >= 0) head(UINT, n);
        else head(NINT, -1L - n);
    }

    /** 8-byte argument form, treating `bits` as UNSIGNED. Needed for the top of
     *  the uint64 range, which does not fit a signed long. */
    private void headU64(int major, long bits) {
        ensure(9);
        buf[pos] = (byte) (major | 27);
        LONG_BE.set(buf, pos + 1, bits);
        pos += 9;
    }

    private static final java.math.BigInteger TWO_64 =
        java.math.BigInteger.ONE.shiftLeft(64);
    private static final java.math.BigInteger MAX_U64 =
        TWO_64.subtract(java.math.BigInteger.ONE);

    /**
     * Arbitrary-precision integer. RFC 8949 prefers the basic integer majors
     * wherever the value fits in their range, and only falls back to the
     * bignum tags (2/3) beyond it — so 2^64-1 is `1bffffffffffffffff`, not a
     * tagged bignum, while 2^64 is `c249010000000000000000`.
     */
    public void writeBigInteger(java.math.BigInteger v) {
        // Same tension as floats: RFC-preferred encoding is the most compact
        // form, but that erases the distinction between a BigInt and a Long on
        // the way back. Under :preserve-width we always tag, so the type
        // survives the round trip (and datahike's dump requirements ask for
        // tags 2/3 explicitly). Under :shortest we follow the RFC.
        if (v.signum() >= 0) {
            if (!preserveWidth && v.compareTo(MAX_U64) <= 0) {
                headReduced(UINT, v);
                return;
            }
            writeBignumTagged(TAG_POS_BIGNUM, v);
        } else {
            java.math.BigInteger m = v.negate().subtract(java.math.BigInteger.ONE); // -1 - v
            if (!preserveWidth && m.compareTo(MAX_U64) <= 0) {
                headReduced(NINT, m);
                return;
            }
            writeBignumTagged(TAG_NEG_BIGNUM, m);
        }
    }

    /**
     * Emit an argument in the range [0, 2^64) using the SHORTEST form.
     *
     * The reduction path used to call headU64 unconditionally, so a bignum that
     * fit in a byte still went out as nine: BigInteger 0 encoded as
     * `1b0000000000000000` and 255 as `1b00000000000000ff`, where canonical
     * requires `00` and `18ff`. That made the :canonical profile emit
     * non-canonical bytes — invisible until :canonical stopped preserving width
     * and this path became reachable at all.
     *
     * Values at or above 2^63 do not fit a signed long, but the 8-byte form IS
     * their shortest encoding, so headU64 is right for exactly those.
     */
    private void headReduced(int major, java.math.BigInteger magnitude) {
        if (magnitude.bitLength() < 64) head(major, magnitude.longValue());
        else headU64(major, magnitude.longValue());
    }

    private void writeBignumTagged(int tag, java.math.BigInteger magnitude) {
        head(TAG, tag);
        byte[] mag = magnitude.toByteArray();
        // BigInteger.toByteArray is two's-complement and may carry a leading
        // zero sign byte; CBOR wants the unsigned magnitude only.
        int off = (mag.length > 1 && mag[0] == 0) ? 1 : 0;
        head(BYTES, mag.length - off);
        ensure(mag.length - off);
        System.arraycopy(mag, off, buf, pos, mag.length - off);
        pos += mag.length - off;
    }

    private static final int TAG_DECIMAL = 4;

    /**
     * Tag 4, decimal fraction: [exponent, mantissa] denoting mantissa*10^exponent.
     *
     * BigDecimal's scale is carried as the negated exponent, so 1.50M and 1.5M
     * encode differently and both survive the round trip — datahike's dump requirements
     * §2 needs this, since 1.50M is not equal to 1.5M for their purposes.
     */
    public void writeBigDecimal(java.math.BigDecimal v) {
        head(TAG, TAG_DECIMAL);
        head(ARRAY, 2);
        writeLong(-v.scale());
        java.math.BigInteger unscaled = v.unscaledValue();
        // The tag already establishes this is a decimal, so the mantissa uses
        // the compact integer form whenever it fits regardless of :preserve-width.
        if (unscaled.bitLength() < 63) {
            writeLong(unscaled.longValue());
        } else if (unscaled.signum() >= 0) {
            writeBignumTagged(TAG_POS_BIGNUM, unscaled);
        } else {
            writeBignumTagged(TAG_NEG_BIGNUM,
                unscaled.negate().subtract(java.math.BigInteger.ONE));
        }
    }

    public void writeDouble(double d) {
        if (!preserveWidth) { writeShortestFloat(d); return; }
        writeF64(d);
    }

    private void writeF64(double d) {
        ensure(9);
        buf[pos] = (byte) (SIMPLE | 27);
        LONG_BE.set(buf, pos + 1, Double.doubleToRawLongBits(d));
        pos += 9;
    }

    public void writeFloat(float f) {
        if (!preserveWidth) { writeShortestFloat(f); return; }
        writeF32(f);
    }

    private void writeF32(float f) {
        ensure(5);
        buf[pos] = (byte) (SIMPLE | 26);
        INT_BE.set(buf, pos + 1, Float.floatToRawIntBits(f));
        pos += 5;
    }

    private void writeF16(short bits) {
        ensure(3);
        buf[pos] = (byte) (SIMPLE | 25);
        SHORT_BE.set(buf, pos + 1, bits);
        pos += 3;
    }

    /**
     * RFC 8949 §4.2.2 preferred serialisation: emit the narrowest of f16/f32/f64
     * that round-trips to the same value. Only used under :float-policy
     * :shortest — the default :preserve-width deliberately does NOT narrow,
     * because narrowing is what silently turns a Double into a Float
     * (datahike #633).
     */
    private void writeShortestFloat(double d) {
        // ONE representation for NaN, matching ClojureScript and RFC 8949's own
        // examples (0xf97e00).
        //
        // `toHalf` carried a float NaN's payload bits and its sign through, so
        // 7ff8000000000001 emitted f97e00 while 7ffaaaa000000000 emitted f97eaa
        // and a negative NaN emitted f9fe00 -- three encodings of one value
        // under the profile whose entire purpose is that the same value gives
        // the same bytes, on every platform. ClojureScript already normalised.
        //
        // Nothing is lost: boring exposes no NaN-payload or signalling-NaN
        // type, and decoding collapses every half NaN to Float.NaN, so those
        // bits were never observable as a value distinction -- only as a
        // determinism hole. RFC 8949 says a deterministic protocol without
        // intentional NaN-payload support should pick one form.
        //
        // Only on the :shortest path. `:preserve-width` still emits the exact
        // f64 bits, because preserving the width is what it is for.
        if (Double.isNaN(d)) { writeF16((short) 0x7E00); return; }
        float f = (float) d;
        if (Double.compare((double) f, d) != 0) { writeF64(d); return; }
        short h = toHalf(f);
        if (h != NOT_HALF && Float.compare(fromHalf(h), f) == 0) { writeF16(h); return; }
        writeF32(f);
    }

    private static final short NOT_HALF = (short) 0x7FFF; // sentinel: unrepresentable

    /** float -> IEEE binary16 bits, or NOT_HALF if not exactly representable. */
    private static short toHalf(float f) {
        int bits = Float.floatToRawIntBits(f);
        int sign = (bits >>> 16) & 0x8000;
        int exp = (bits >>> 23) & 0xFF;
        int mant = bits & 0x7FFFFF;

        if (exp == 0xFF) {                       // Inf / NaN
            if (mant == 0) return (short) (sign | 0x7C00);
            return (short) (sign | 0x7C00 | (mant >>> 13) | ((mant != 0) ? 0x200 : 0));
        }
        if (exp == 0) return (short) sign;       // +/-0 (subnormal floats: give up below)

        int newExp = exp - 127 + 15;
        if (newExp >= 0x1F) return NOT_HALF;                  // overflows f16
        if (newExp <= 0) {
            // Subnormal f16: no implicit leading 1, value is mant * 2^-24.
            // Reachable and required — vector f90001 is 2^-24.
            if (newExp < -10) return NOT_HALF;                // underflows to zero
            int m = mant | 0x800000;                          // restore implicit bit
            int shift = 14 - newExp;
            if (shift > 31) return NOT_HALF;
            if ((m & ((1 << shift) - 1)) != 0) return NOT_HALF; // would lose bits
            return (short) (sign | (m >>> shift));
        }
        if ((mant & 0x1FFF) != 0) return NOT_HALF;            // needs bits f16 lacks
        return (short) (sign | (newExp << 10) | (mant >>> 13));
    }

    private static float fromHalf(short h) {
        int hb = h & 0xFFFF;
        int sign = (hb >>> 15) & 0x1;
        int exp = (hb >>> 10) & 0x1F;
        int mant = hb & 0x3FF;
        float val;
        if (exp == 0)       val = (float) (mant * Math.pow(2, -24));
        else if (exp != 31) val = (float) ((mant + 1024) * Math.pow(2, exp - 25));
        else                val = (mant == 0) ? Float.POSITIVE_INFINITY : Float.NaN;
        return sign == 1 ? -val : val;
    }

    /** Simple value (major 7). Codes 0-23 inline, 32-255 in the following byte. */
    public void writeSimpleValue(int n) {
        // head()'s `val < 24` test is true for negatives, so a negative simple
        // value became a malformed header — (simple-value -1) emitted 0xFF, the
        // break code, producing a document this library then rejects.
        if (n < 0 || n > 255)
            throw Err.of("bad-simple-value",
                "boring: simple value must be 0-255, got " + n, "value", (long) n);
        if (n < 24) { u8(SIMPLE | n); return; }
        if (n < 32 && !permitReservedSimpleValues) throw Err.of("reserved-simple-value",
            "boring: RFC 8949 3.3 forbids encoding simple value " + n
            + " (0xf8 followed by a byte < 0x20); set :permit-reserved-simple-values "
            + "to emit it anyway for byte-identical passthrough", "value", (long) n);
        ensure(2);
        buf[pos] = (byte) (SIMPLE | 24);
        buf[pos + 1] = (byte) n;
        pos += 2;
    }

    public void writeTag(long tag) {
        if (tag < 0)
            throw Err.of("bad-tag",
                "boring: tag must be non-negative, got " + tag, "tag", tag);
        head(TAG, tag);
    }

    /**
     * A CBOR tag number is an unsigned 64-bit integer, so the top half of the
     * range does not fit in a Java long.
     *
     * The reader represents such a tag as a BigInteger, correctly. The writer
     * called `.longValue()` on it, which wrapped 2^64-1 to -1, and then
     * `writeTag` rejected -1 as negative -- so a TaggedValue the reader had
     * just produced could not be written back. An unknown tag from a foreign
     * encoder is exactly the value the passthrough exists to preserve.
     */
    private void writeTagNumber(Object tag) {
        // clojure.lang.BigInt, not java.math.BigInteger: that is what the
        // reader produces for a tag past Long.MAX_VALUE, and checking only for
        // BigInteger silently fell through to the .longValue() path this
        // method exists to avoid.
        if (tag instanceof clojure.lang.BigInt)
            tag = ((clojure.lang.BigInt) tag).toBigInteger();
        if (tag instanceof java.math.BigInteger) {
            java.math.BigInteger b = (java.math.BigInteger) tag;
            if (b.signum() < 0 || b.bitLength() > 64)
                throw Err.of("bad-tag",
                    "boring: tag must be an unsigned 64-bit integer, got " + b);
            if (b.bitLength() > 63) { headU64(TAG, b.longValue()); return; }
            head(TAG, b.longValue());
            return;
        }
        if (!(tag instanceof Number))
            throw Err.of("bad-tag", "boring: tag must be an integer, got "
                + (tag == null ? "nil" : tag.getClass().getSimpleName()));
        // FRACTIONAL tags were truncated, not refused: `longValue()` turned tag
        // 1.5 into tag 1, so `(tagged-value 1.5 0)` and `(tagged-value 1 0)`
        // both emitted `c1 00`. A tag number is an unsigned integer; anything
        // else is a mistake the caller should hear about, not a value to round.
        if (tag instanceof Double || tag instanceof Float
            || tag instanceof java.math.BigDecimal || tag instanceof clojure.lang.Ratio)
            throw Err.of("bad-tag",
                "boring: tag must be an integer, got " + tag);
        writeTag(((Number) tag).longValue());
    }

    /**
     * The shared key set of `l`, or null if its elements are not all maps with
     * the same keys. Membership is checked with containsKey rather than by
     * comparing iteration order, because two maps with equal key sets are not
     * required to iterate in the same order.
     */
    @SuppressWarnings("rawtypes")
    private Object[] homogeneousShape(List l) {
        int rows = l.size();
        if (rows < 2) return null;               // one row cannot amortise the keys
        Object first = l.get(0);
        if (!(first instanceof Map) || first instanceof clojure.lang.IRecord) return null;
        Map m0 = (Map) first;
        int n = m0.size();
        if (n == 0) return null;
        Object[] keys = m0.keySet().toArray();
        for (int i = 1; i < rows; i++) {
            Object o = l.get(i);
            if (!(o instanceof Map) || o instanceof clojure.lang.IRecord) return null;
            Map m = (Map) o;
            if (m.size() != n) return null;
            for (int k = 0; k < n; k++) if (!m.containsKey(keys[k])) return null;
        }
        return keys;
    }

    @SuppressWarnings("rawtypes")
    private void writeShapedArray(List l, Object[] keys) {
        head(TAG, TAG_SHAPED_ARRAY);
        head(ARRAY, 2);
        head(ARRAY, keys.length);
        for (int i = 0; i < keys.length; i++) writeValue(keys[i]);
        head(ARRAY, l.size());
        for (int r = 0; r < l.size(); r++) {
            Map m = (Map) l.get(r);
            head(ARRAY, keys.length);
            for (int i = 0; i < keys.length; i++) writeValue(m.get(keys[i]));
        }
    }

    /**
     * Tag 27, generic object: [type-name, constructor-argument].
     *
     * The spec leaves the shape to sender/receiver agreement ("There are no
     * rules that specify how to serialise or deserialise objects using this
     * tag"), so a single argument is legitimate — and a map is exactly what
     * Clojure's `map->Record` takes.
     *
     * A defrecord always arrives here with a map, written through
     * writeRecordFields so the record branch cannot recurse on it. An
     * UnknownRecord can arrive with anything, because the reader now accepts
     * any argument: a positional type such as datahike's Datom carries a
     * vector. Forcing the cast to Map made re-encoding such a value throw a
     * ClassCastException, which broke the lossless-passthrough guarantee that
     * is the whole reason UnknownRecord keeps the payload at all.
     */
    private void writeRecordFrame(String typeName, Object fields) {
        head(TAG, TAG_GENERIC_OBJ);
        head(ARRAY, 2);
        writeString(typeName);
        if (fields instanceof Map) writeRecordFields(fields);
        else writeValue(fields);
    }

    /**
     * Write a record's fields in DECLARATION order.
     *
     * java.util.Map.entrySet() on a defrecord is hash order, not declaration
     * order: for (defrecord P [x y]), (seq p) gives ([:x 3] [:y 4]) but
     * .entrySet() gives [[:y 4] [:x 3]]. Iterating entrySet made the JVM emit
     * record fields in an order ClojureScript — which uses declaration order —
     * did not reproduce, so the SAME record encoded to different bytes on the
     * two platforms. The golden corpus caught it on its first run; the
     * cross-platform byte-identity checks had not covered a multi-field record.
     *
     * Declaration order is also the only stable choice available. Hash order is
     * a function of the key set and of Clojure's internals, so it could shift
     * under a Clojure upgrade and silently change the wire format.
     */
    private static void requireDefaultComparator(java.util.Comparator<?> cmp, String what) {
        // RT.DEFAULT_COMPARATOR is what (sorted-map) installs; null means the
        // same thing on some construction paths.
        if (cmp != null && cmp != clojure.lang.RT.DEFAULT_COMPARATOR)
            throw Err.of("unsupported-type",
                "boring: cannot encode a " + what + " with a custom comparator -- the"
                + " comparator is code, and rebuilding with `compare` would sort"
                + " differently. Convert to a plain map/set, or register a handler.");
    }

    /**
     * A collection's reported size disagreed with what iterating it yielded.
     *
     * The head is written from `size()` BEFORE the entries, so a mismatch is a
     * malformed document either way -- a head claiming five elements with four
     * behind it. With an index it is worse: the anchor array is sized from the
     * same number, so an over-run walks off it, and an under-fill leaves the
     * following item to be swallowed as the missing element. Refusing beats
     * emitting bytes that do not decode.
     *
     * Reachable from a concurrently mutated map, a weakly consistent
     * collection, or a custom `size()` that lies.
     */
    private void countMismatch(int declared, int actual) {
        throw Err.of("collection-size-mismatch",
            "boring: a collection reported " + declared + " entries and yielded "
            + actual + ". It was probably mutated while being encoded.");
    }

    private void writeSeqAsArray(clojure.lang.ISeq s, int n) {
        int start = pos;
        head(ARRAY, n);
        int[] anchors = indexing(n) ? new int[anchorCount(n)] : null;
        int slot = anchors != null ? reserveNode() : -1;
        int a = 0, countdown = 1, seen = 0;
        for (; s != null; s = s.next()) {
            if (++seen > n) countMismatch(n, seen);
            if (anchors != null && --countdown == 0) {
                anchors[a++] = idxOffset(pos); countdown = idxStride;
            }
            writeValue(s.first());
        }
        if (seen != n) countMismatch(n, seen);
        if (anchors != null) fillNode(slot, start, n, anchors, false);
    }

    /** An array of `n` elements from any Iterable, indexed if it is big enough. */
    @SuppressWarnings("rawtypes")
    private void writeArrayOf(Iterable it, int n) {
        int start = pos;
        head(ARRAY, n);
        int[] anchors = indexing(n) ? new int[anchorCount(n)] : null;
        int slot = anchors != null ? reserveNode() : -1;
        int a = 0, countdown = 1, seen = 0;
        for (Object o : it) {
            if (++seen > n) countMismatch(n, seen);
            if (anchors != null && --countdown == 0) {
                anchors[a++] = idxOffset(pos); countdown = idxStride;
            }
            writeValue(o);
        }
        if (seen != n) countMismatch(n, seen);
        if (anchors != null) fillNode(slot, start, n, anchors, false);
    }

    @SuppressWarnings("rawtypes")
    private void writeRecordFields(Object fields) {
        Map m = (Map) fields;
        if (canonical) { writeMapCanonical(m); return; }
        int n = m.size();
        int start = pos;
        head(MAP, n);
        // Iterated as a SEQ rather than an entrySet, so this cannot delegate to
        // writeMapValue without changing field order on some record types.
        int[] anchors = indexing(n) ? new int[anchorCount(n)] : null;
        int slot = anchors != null ? reserveNode() : -1;
        boolean sorted = true;
        int prevK0 = -1, prevK1 = -1;
        int a = 0, countdown = 1;
        // Every adjacent pair, for the reason spelled out in writeMapValue: an
        // anchor sample does not establish that the container is ordered.
        int seen = 0;
        for (clojure.lang.ISeq s = clojure.lang.RT.seq(fields); s != null; s = s.next()) {
            Map.Entry e = (Map.Entry) s.first();
            if (++seen > n) countMismatch(n, seen);
            if (anchors != null && --countdown == 0) {
                anchors[a++] = idxOffset(pos); countdown = idxStride;
            }
            int k0 = pos;
            writeValue(e.getKey());
            if (anchors != null) {
                if (prevK0 >= 0 && sorted && cmpInBuf(prevK0, prevK1, k0, pos) >= 0)
                    sorted = false;
                prevK0 = k0; prevK1 = pos;
            }
            writeValue(e.getValue());
        }
        if (seen != n) countMismatch(n, seen);
        if (anchors != null) fillNode(slot, start, n, anchors, sorted);
    }

    /**
     * Bytewise comparison of two encoded items already sitting in `buf`,
     * matching {@link Reader#compareItemsAt}: shorter first when one is a
     * prefix of the other.
     */
    private int cmpInBuf(int a0, int a1, int b0, int b1) {
        int an = a1 - a0, bn = b1 - b0, n = Math.min(an, bn);
        for (int i = 0; i < n; i++) {
            int x = buf[a0 + i] & 0xFF, y = buf[b0 + i] & 0xFF;
            if (x != y) return x < y ? -1 : 1;
        }
        return Integer.compare(an, bn);
    }

    @SuppressWarnings("rawtypes")
    private void writeMapValue(Map m) {
        if (canonical) { writeMapCanonical(m); return; }
        int n = m.size();
        int start = pos;
        head(MAP, n);

        // CONTENT-EQUAL BYTE-STRING KEYS are one key in CBOR (RFC 8949 5.6) and
        // two keys on the JVM, where byte[] uses identity equality. So a
        // perfectly valid host map -- two distinct byte[] holding the same
        // bytes -- encoded to `a2 42 0102 6161 42 0102 6162`, a CBOR map with
        // the same key twice, which this library's own reader now rejects.
        //
        // Checked only when a byte-string key is actually present, so ordinary
        // maps pay one instanceof per key and nothing else. The canonical path
        // catches this already, by comparing encoded keys; the ORDINARY path
        // had no equivalent.
        checkByteStringKeys(m, n);

        if (!indexing(n)) {                     // the ordinary path
            int seen = 0;
            for (Object o : m.entrySet()) {
                Map.Entry e = (Map.Entry) o;
                if (++seen > n) countMismatch(n, seen);
                writeValue(e.getKey());
                writeValue(e.getValue());
            }
            if (seen != n) countMismatch(n, seen);
            return;
        }

        int[] anchors = new int[anchorCount(n)];
        int slot = reserveNode();
        // EVERY adjacent key pair, not just the anchors.
        //
        // Comparing anchors was unsound and produced wrong answers, not merely
        // missed optimisations. `sorted` licenses `lookup-map` to binary-search
        // the anchors and then scan ONLY the stride it lands in; that is valid
        // just when the whole container is ordered. Ascending anchors do not
        // imply it -- they are a sample. With the default stride of 16 a map of
        // 17-32 entries has two anchors, so an unordered map had a ~50% chance
        // of being marked sorted, and then a present key returned nil.
        // Measured: 105 of 200 random 20-entry maps had at least one such key.
        //
        // The cost is one comparison per entry over key bytes, and only for
        // containers big enough to be indexed at all.
        boolean sorted = true;
        int prevK0 = -1, prevK1 = -1;
        int a = 0, countdown = 1, seen = 0;

        for (Object o : m.entrySet()) {
            Map.Entry e = (Map.Entry) o;
            if (++seen > n) countMismatch(n, seen);
            if (--countdown == 0) { anchors[a++] = idxOffset(pos); countdown = idxStride; }
            int k0 = pos;
            writeValue(e.getKey());
            if (prevK0 >= 0 && sorted && cmpInBuf(prevK0, prevK1, k0, pos) >= 0)
                sorted = false;
            prevK0 = k0; prevK1 = pos;
            writeValue(e.getValue());
        }
        if (seen != n) countMismatch(n, seen);
        fillNode(slot, start, n, anchors, sorted);
    }

    // ---- time, uuid, rational ----------------------------------------------

    /**
     * Instants use tag 0 (RFC 3339 string), not tag 1 (epoch).
     *
     * datahike's dump requirements offer either. Tag 0 wins on two counts that
     * matter more than compactness: it is lossless (tag 1 as a float has ~0.5µs
     * resolution at current epoch values, so java.time.Instant's nanoseconds
     * would be silently truncated — the same class of bug as float narrowing),
     * and it is friendlier to non-Clojure readers.
     *
     * Note java.util.Date (ms) and java.time.Instant (ns) both encode to tag 0
     * and cannot be told apart on the way back. The reader's :instant-type
     * option picks which one to produce. CBOR has one time concept; the
     * distinction is a JVM artifact.
     */
    public void writeInstant(java.time.Instant t) {
        head(TAG, TAG_DATETIME);
        writeString(java.time.format.DateTimeFormatter.ISO_INSTANT.format(t));
    }

    public void writeUUID(java.util.UUID u) {
        head(TAG, TAG_UUID);
        head(BYTES, 16);
        ensure(16);
        LONG_BE.set(buf, pos, u.getMostSignificantBits());
        LONG_BE.set(buf, pos + 8, u.getLeastSignificantBits());
        pos += 16;
    }

    /** Tag 30, rational number: [numerator, denominator]. */
    public void writeRatio(clojure.lang.Ratio r) {
        head(TAG, TAG_RATIONAL);
        head(ARRAY, 2);
        writeBigIntegerCompact(r.numerator);
        writeBigIntegerCompact(r.denominator);
    }

    /** Integer in the most compact valid form, ignoring :preserve-width — used
     *  inside composite tags where the wrapper already fixes the type. */
    private void writeBigIntegerCompact(java.math.BigInteger v) {
        if (v.bitLength() < 63) { writeLong(v.longValue()); return; }
        boolean neg = v.signum() < 0;
        java.math.BigInteger m = neg ? v.negate().subtract(java.math.BigInteger.ONE) : v;
        if (m.compareTo(MAX_U64) <= 0) { headU64(neg ? NINT : UINT, m.longValue()); return; }
        writeBignumTagged(neg ? TAG_NEG_BIGNUM : TAG_POS_BIGNUM, m);
    }

    // ---- RFC 8746 typed arrays ---------------------------------------------

    private void typedArrayHeader(int tag, long byteLen) {
        // `ensure((int) byteLen)` truncated while head(BYTES, byteLen) wrote the
        // true length, and the element loops index with int shifts that wrap.
        if (byteLen > MAX_BUFFER - pos)
            throw Err.of("output-too-large",
                "boring: typed array of " + byteLen + " bytes exceeds the buffer limit",
                "bytes", byteLen);
        head(TAG, tag);
        head(BYTES, byteLen);
        ensure((int) byteLen);
    }

    public void writeLongArray(long[] a) {
        typedArrayHeader(TAG_ARR_S64_LE, (long) a.length * 8);
        for (int i = 0; i < a.length; i++) LONG_LE.set(buf, pos + (i << 3), a[i]);
        pos += a.length << 3;
    }

    public void writeIntArray(int[] a) {
        typedArrayHeader(TAG_ARR_S32_LE, (long) a.length * 4);
        for (int i = 0; i < a.length; i++) INT_LE.set(buf, pos + (i << 2), a[i]);
        pos += a.length << 2;
    }

    public void writeShortArray(short[] a) {
        typedArrayHeader(TAG_ARR_S16_LE, (long) a.length * 2);
        for (int i = 0; i < a.length; i++) SHORT_LE.set(buf, pos + (i << 1), a[i]);
        pos += a.length << 1;
    }

    /**
     * Classes the writer emits BEFORE consulting the registry, so a handler
     * registered for one of them can never run.
     *
     * These are the hottest scalars, dispatched first on purpose. The registry
     * lookup was moved above the other built-ins precisely so that an explicit
     * registration wins -- but it cannot be moved above these without putting a
     * map lookup in front of every string and every long.
     *
     * So the registration is REFUSED instead. Silently doing nothing is the one
     * unacceptable state: the same API worked or did not depending only on
     * which class you named, and nothing said which.
     */
    public static boolean isRegisterableClass(Class<?> c) {
        return !(c == String.class || c == Long.class || c == Integer.class
                 || c == Short.class || c == Byte.class
                 || c == Double.class || c == Float.class
                 || c == Boolean.class || c == byte[].class
                 || c == clojure.lang.Keyword.class
                 || c == clojure.lang.Symbol.class);
    }

    /**
     * Refuse a map whose byte-string keys are content-equal. See writeMapValue.
     * O(k^2) in the number of BYTE-STRING keys only, which is nearly always 0.
     */
    @SuppressWarnings("rawtypes")
    private void checkByteStringKeys(Map m, int n) {
        byte[][] seen = null;
        int c = 0;
        for (Object o : m.entrySet()) {
            Object k = ((Map.Entry) o).getKey();
            if (!(k instanceof byte[])) continue;
            if (seen == null) seen = new byte[n][];
            byte[] b = (byte[]) k;
            for (int i = 0; i < c; i++)
                if (java.util.Arrays.equals(seen[i], b))
                    throw Err.of("duplicate-map-key",
                        "boring: two map keys are byte strings with the same content,"
                        + " which is ONE key in CBOR (RFC 8949 5.6). This map cannot"
                        + " be encoded without producing a duplicate key.");
            seen[c++] = b;
        }
    }

    /** Length of a primitive row, without knowing which primitive it is. */
    private static int rowLen(Object row) { return java.lang.reflect.Array.getLength(row); }

    /**
     * A rectangular 2D primitive array as RFC 8746 tag 40; a ragged one as a
     * plain array of rows.
     */
    private void writeMatrix(Object[] rows, Class<?> rowType) {
        // `rows[0]` is inspected only AFTER it is known non-null. The length was
        // read before the loop's own null check, so a null FIRST row threw a raw
        // NullPointerException and the documented null-row fallback was
        // unreachable for exactly the row most likely to be null.
        boolean rectangular = rows.length > 0 && rows[0] != null;
        int cols = rectangular ? rowLen(rows[0]) : 0;
        if (rectangular) {
            for (Object r : rows) {
                // A null row is not a shape, and a differing length is not a
                // rectangle. Either way tag 40 does not apply.
                if (r == null || rowLen(r) != cols) { rectangular = false; break; }
            }
        }
        // A ZERO-ROW matrix takes the fallback, NOT tag 40.
        //
        // I had made it rectangular so its type survived -- and that emitted
        // `d8 28 82 82 00 00 ...`, dimensions [0,0], which RFC 8746 3.1.1
        // forbids: dimensions must be unsigned integers DISTINCT FROM ZERO.
        // Our own reader accepted it, so a round-trip test blessed output that
        // violates the registered tag's content rules -- the worst way to be
        // wrong, because the suite says you are right.
        //
        // There is no standard tag-40 encoding of a zero extent, so the type is
        // the thing that has to give. It is a 0x0 matrix: it carries no values
        // to lose, and inventing a private tag to preserve its class would be a
        // poor trade against emitting invalid CBOR.
        if (cols == 0) rectangular = false;      // dims must be non-zero, both of them
        if (!rectangular) {
            head(ARRAY, rows.length);
            for (Object r : rows) writeValue(r);
            return;
        }
        head(TAG, TAG_MULTI_DIM_ROW);
        head(ARRAY, 2);
        head(ARRAY, 2);                 // dimensions, row-major
        writeLong(rows.length);
        writeLong(cols);
        // The flat payload is one typed array, so the whole matrix is a single
        // bulk copy per row rather than a value-at-a-time encode.
        // long before narrowing: `rows.length * cols` could wrap negative and slip
        // past the size check in typedArrayHeader, which takes a long.
        long n = (long) rows.length * cols;
        if (n > Integer.MAX_VALUE)
            throw Err.of("value-too-large",
                "boring: matrix of " + rows.length + "x" + cols
                + " elements exceeds what one typed array can hold");
        if (rowType == double[].class) {
            typedArrayHeader(TAG_ARR_F64_LE, n * 8);
            for (Object r : rows) { double[] d = (double[]) r;
                for (int i = 0; i < cols; i++) { LONG_LE.set(buf, pos, Double.doubleToRawLongBits(d[i])); pos += 8; } }
        } else if (rowType == long[].class) {
            typedArrayHeader(TAG_ARR_S64_LE, n * 8);
            for (Object r : rows) { long[] d = (long[]) r;
                for (int i = 0; i < cols; i++) { LONG_LE.set(buf, pos, d[i]); pos += 8; } }
        } else if (rowType == int[].class) {
            typedArrayHeader(TAG_ARR_S32_LE, n * 4);
            for (Object r : rows) { int[] d = (int[]) r;
                for (int i = 0; i < cols; i++) { INT_LE.set(buf, pos, d[i]); pos += 4; } }
        } else if (rowType == float[].class) {
            typedArrayHeader(TAG_ARR_F32_LE, n * 4);
            for (Object r : rows) { float[] d = (float[]) r;
                for (int i = 0; i < cols; i++) { INT_LE.set(buf, pos, Float.floatToRawIntBits(d[i])); pos += 4; } }
        } else {
            typedArrayHeader(TAG_ARR_S16_LE, n * 2);
            for (Object r : rows) { short[] d = (short[]) r;
                for (int i = 0; i < cols; i++) { SHORT_LE.set(buf, pos, d[i]); pos += 2; } }
        }
    }

    public void writeDoubleArray(double[] a) {
        typedArrayHeader(TAG_ARR_F64_LE, (long) a.length * 8);
        for (int i = 0; i < a.length; i++)
            LONG_LE.set(buf, pos + (i << 3), Double.doubleToRawLongBits(a[i]));
        pos += a.length << 3;
    }

    public void writeFloatArray(float[] a) {
        typedArrayHeader(TAG_ARR_F32_LE, (long) a.length * 4);
        for (int i = 0; i < a.length; i++)
            INT_LE.set(buf, pos + (i << 2), Float.floatToRawIntBits(a[i]));
        pos += a.length << 2;
    }

    /** RFC 8949 §4.2.1: bytewise lexicographic, shorter-is-a-prefix first. */
    private static int compareBytes(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) return d;
        }
        return a.length - b.length;
    }

    /** RFC 7049 / clj-cbor: shorter keys sort first, then bytewise. */
    private static int compareBytesLengthFirst(byte[] a, byte[] b) {
        if (a.length != b.length) return a.length - b.length;
        for (int i = 0; i < a.length; i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) return d;
        }
        return 0;
    }

    /**
     * Canonical sets: elements sorted by their canonical encoded bytes.
     *
     * RFC 8949 §4.2 defines deterministic ordering for MAP KEYS and says
     * nothing about tag 258, because tag 258 is not part of core CBOR. That
     * left set elements in iteration order, so two sets that are `=` -- a
     * LinkedHashSet built [1,2] and one built [2,1] -- produced DIFFERENT
     * canonical bytes, and a persistent set could iterate differently across
     * platforms. The profile's whole promise is that equal values encode
     * identically, which is what a signature over the bytes rests on.
     *
     * The rule has to be defined by this library since no RFC defines it. It
     * is the one already used for map keys, applied to elements, so there is
     * one canonical ordering rather than two.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void writeSetCanonical(Set s) {
        int n = s.size();
        final byte[][] encoded = new byte[n][];
        Object[] items = new Object[n];

        Writer scratch = canonicalSubWriter();
        int i = 0;
        for (Object o : s) {
            if (i >= n) countMismatch(n, i + 1);    // staging arrays are size()d
            scratch.reset();
            scratch.writeValue(o);
            encoded[i] = scratch.toByteArray();
            items[i] = o;
            i++;
        }
        if (i != n) countMismatch(n, i);

        Integer[] order = new Integer[n];
        for (int j = 0; j < n; j++) order[j] = j;
        final boolean legacy = legacyCanonicalOrder;
        java.util.Arrays.sort(order, (p, q) -> legacy
            ? compareBytesLengthFirst(encoded[p], encoded[q])
            : compareBytes(encoded[p], encoded[q]));

        // Two DISTINCT host values can encode identically under canonical
        // reduction -- 1 and 1N both become `01`. Emitting both would produce
        // a CBOR set with a repeated element, which a strict decoder rejects
        // and a lenient one silently collapses, so the two sides would
        // disagree about the set's size.
        for (int j = 1; j < n; j++)
            if (compareBytes(encoded[order[j - 1]], encoded[order[j]]) == 0)
                throw Err.of("canonical-duplicate",
                    "boring: two set elements encode identically under :canonical ("
                    + items[order[j - 1]] + " and " + items[order[j]] + ")");

        head(TAG, TAG_SET);
        int start = pos;                       // the array, not the tag
        head(ARRAY, n);
        int[] anchors = indexing(n) ? new int[anchorCount(n)] : null;
        int slot = anchors != null ? reserveNode() : -1;
        int a = 0, countdown = 1;
        for (int j = 0; j < n; j++) {
            if (anchors != null && --countdown == 0) {
                anchors[a++] = idxOffset(pos); countdown = idxStride;
            }
            // The STAGED bytes, not a second `writeValue`. Re-encoding asked a
            // registered handler for the value twice, and a handler may be
            // stateful or read the clock -- so the bytes that decided the sort
            // order need not be the bytes emitted, and a canonical set could go
            // out DESCENDING. Canonical maps already copy their staged keys;
            // this is the same fix, and it also restores one handler call per
            // value.
            byte[] eb = encoded[order[j]];
            ensure(eb.length);
            System.arraycopy(eb, 0, buf, pos, eb.length);
            pos += eb.length;
        }
        if (anchors != null) fillNode(slot, start, n, anchors, false);
    }

    /**
     * The shared scratch writer, configured to encode a canonical sub-item.
     *
     * EVERY behaviour-affecting option is inherited. Three were not, and each
     * omission turned an option into a lie under `:profile :canonical`:
     * `:incl-metadata? false` still emitted the metadata wrapper on a map key,
     * and `:max-depth` was not enforced inside a key at all -- the scratch
     * writer used the 1024 default, so a nesting cap the caller set as a
     * SECURITY bound was silently bypassed by putting the deep value in key
     * position.
     *
     * The parent's current depth carries over too, so a deep value does not get
     * a fresh budget merely for being a map key.
     */
    private Writer canonicalSubWriter() {
        if (canonicalScratch == null) canonicalScratch = new Writer(256);
        Writer scratch = canonicalScratch;
        scratch.preserveWidth = this.preserveWidth;
        scratch.canonical = true;
        scratch.stringref = false;
        scratch.registry = this.registry;
        scratch.legacyCanonicalOrder = this.legacyCanonicalOrder;
        scratch.permitReservedSimpleValues = this.permitReservedSimpleValues;
        scratch.inclMetadata = this.inclMetadata;
        scratch.maxDepth = this.maxDepth;
        // ACCUMULATE the parent's own offset. A scratch writer made by a scratch
        // writer -- a canonical map nested inside a canonical map key -- reset
        // the budget to the inner parent's local depth, so nesting through key
        // position got a fresh allowance each time.
        scratch.depthOffset = this.depth + this.depthOffset;
        // The claim above is "EVERY behaviour-affecting option is inherited",
        // and this one was not: an :encode-fallback configured to rescue an
        // unsupported value worked everywhere except in a map KEY or a set
        // ELEMENT under :canonical, where those are pre-encoded here. The
        // option silently did not apply exactly where the document was hardest
        // to fix by hand.
        scratch.encodeFallback = this.encodeFallback;
        // And the RE-ENTRY GUARD with it. `inFallback` is what stops a fallback
        // whose result still contains the unsupported value from recursing
        // forever; copying the fallback without it meant every hop into a
        // scratch writer handed back a fresh, un-guarded budget. A fallback
        // returning a map keyed by the very object it was called for then
        // overflowed the stack instead of raising the typed unsupported-value
        // error -- a failure introduced by inheriting the fallback at all.
        scratch.inFallback = this.inFallback;
        // The index fields are the one group DELIBERATELY not inherited, and
        // this is checked rather than commented because getting it wrong is
        // silent: the scratch writer encodes keys into its own buffer, so any
        // node it recorded would carry offsets into bytes that are then copied
        // somewhere else entirely -- a plausible-looking index pointing at the
        // wrong places, which no round-trip test would catch.
        if (scratch.idxStride != 0)
            throw Err.of("index-scratch-leak",
                "boring: the canonical scratch writer must never index");
        return scratch;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void writeMapCanonical(Map m) {
        int n = m.size();
        final byte[][] encodedKeys = new byte[n][];
        Object[] keys = new Object[n];
        Object[] vals = new Object[n];

        Writer scratch = canonicalSubWriter();

        int i = 0;
        for (Object o : m.entrySet()) {
            Map.Entry e = (Map.Entry) o;
            // Checked BEFORE indexing the staging arrays, which are sized from
            // size(): a map yielding more entries than it reported threw a raw
            // ArrayIndexOutOfBoundsException from here rather than the typed
            // error the other container paths give.
            if (i >= n) countMismatch(n, i + 1);
            scratch.reset();
            scratch.writeValue(e.getKey());
            encodedKeys[i] = scratch.toByteArray();
            keys[i] = e.getKey();
            vals[i] = e.getValue();
            i++;
        }
        if (i != n) countMismatch(n, i);

        Integer[] order = new Integer[n];
        for (int j = 0; j < n; j++) order[j] = j;
        final boolean legacy = legacyCanonicalOrder;
        java.util.Arrays.sort(order, (p, q) -> legacy
            ? compareBytesLengthFirst(encodedKeys[p], encodedKeys[q])
            : compareBytes(encodedKeys[p], encodedKeys[q]));

        // Distinct keys can encode identically -- Long 1 and BigInteger.ONE
        // both reduce to `01` -- and a map with two identical CBOR keys is
        // output that boring's OWN decoder rejects as :boring/duplicate-map-key.
        // Canonical SETS have always checked this; maps did not, so the same
        // hazard produced an unreadable document instead of an error. Only
        // reachable from a map that considers such keys distinct (an
        // IdentityHashMap, a custom comparator), which is why it survived.
        for (int j = 1; j < n; j++)
            if (compareBytes(encodedKeys[order[j - 1]], encodedKeys[order[j]]) == 0)
                throw Err.of("canonical-duplicate",
                    "boring: two map keys encode identically under :canonical ("
                    + keys[order[j - 1]] + " and " + keys[order[j]] + ")");

        int start = pos;
        head(MAP, n);
        // Sorted by construction -- but only under the RFC 8949 comparator.
        //
        // `legacyCanonicalOrder` (:profile :canonical-rfc7049) sorts LENGTH
        // FIRST, while every reader here compares plain bytewise, so under that
        // profile this method's output is NOT in the order a binary search
        // assumes. Claiming otherwise silently lost keys: measured 11 of 20 on
        // a map mixing short text keys with large integer keys, where the two
        // orderings genuinely diverge.
        //
        // No comparisons on either branch: the sort just ran, so its comparator
        // is the whole answer.
        boolean sorted = !legacyCanonicalOrder;
        int[] anchors = indexing(n) ? new int[anchorCount(n)] : null;
        int slot = anchors != null ? reserveNode() : -1;
        int a = 0, countdown = 1;
        for (int j = 0; j < n; j++) {
            int k = order[j];
            if (anchors != null && --countdown == 0) {
                anchors[a++] = idxOffset(pos); countdown = idxStride;
            }
            ensure(encodedKeys[k].length);
            System.arraycopy(encodedKeys[k], 0, buf, pos, encodedKeys[k].length);
            pos += encodedKeys[k].length;
            writeValue(vals[k]);
        }
        if (anchors != null) fillNode(slot, start, n, anchors, sorted);
    }

    public void writeBoolean(boolean b) { u8(SIMPLE | (b ? 21 : 20)); }
    public void writeNull()             { u8(SIMPLE | 22); }

    public void writeBytes(byte[] bs) {
        head(BYTES, bs.length);
        ensure(bs.length);
        System.arraycopy(bs, 0, buf, pos, bs.length);
        pos += bs.length;
    }

    /**
     * Write a String as a CBOR text string, participating in the stringref
     * namespace: a repeat emits tag 25(n) instead of the literal.
     *
     * The index space covers EVERY text string above the length threshold, not
     * just keywords — the decoder registers on every literal it reads, so the
     * encoder must register on every literal it writes or the two diverge.
     */
    public void writeString(String s) {
        if (!stringref) { writeStringLiteral(s, -1); return; }

        // Single probe for lookup-or-insert. The previous version probed twice
        // — srLookup, then srRegister after writing — which is the dominant
        // cost when strings do not repeat (measured: string-100 encode 2.5x
        // behind hako, datom-vec-1k 1.5x, both payloads with unique strings).
        int h = s.hashCode();                  // String caches its hash
        h ^= (h >>> 16);
        int i = h & srMask;
        while (true) {
            Object cur = srKeys[i];
            if (cur == null) break;            // miss; `i` is the insert slot
            if (cur == s || cur.equals(s)) {
                head(TAG, TAG_STRINGREF);
                head(UINT, srVals[i]);
                return;
            }
            i = (i + 1) & srMask;
        }
        writeStringLiteral(s, i);
    }

    /** Insert `s` at the slot a miss landed on, if it earns an index. */
    private void srInsertAt(int slot, String s, int byteLen) {
        if (byteLen < minLenForIndex(srNextIndex)) return;
        if (srCount + 1 > (srMask + 1) * 3 / 4) {
            rehash();                          // slot is stale after a rehash
            srRegister(s, byteLen);
            return;
        }
        srKeys[slot] = s;
        srVals[slot] = srNextIndex++;
        srCount++;
    }

    private void writeStringLiteral(String s, int slot) {
        int n = s.length();
        // Speculate ASCII: reserve worst-case-for-ASCII and bail out if wrong.
        ensure(n + 5);
        int hdrStart = pos;
        int hdrLen = headLen(n);
        int p = hdrStart + hdrLen;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 0x80) { writeStringSlow(s, slot); return; }
            buf[p++] = (byte) c;
        }
        // ASCII: byte length == char length, so the reserved header size is right.
        pos = hdrStart;
        head(TEXT, n);
        pos = p;
        if (slot >= 0) srInsertAt(slot, s, n);
    }

    private static int headLen(long v) {
        if (v < 24L) return 1;
        if (v < 0x100L) return 2;
        if (v < 0x10000L) return 3;
        if (v < 0x100000000L) return 5;
        return 9;
    }

    /**
     * Reject UTF-16 that cannot be encoded as UTF-8.
     *
     * `String.getBytes(UTF_8)` REPLACES an unpaired surrogate with '?' rather
     * than failing, so a string holding a lone U+D800 encoded as the one-byte
     * text string "?" and decoded as U+003F -- a different string, silently.
     * ClojureScript's TextEncoder does the same substitution with U+FFFD, so
     * the two platforms silently disagreed about the same input as well.
     *
     * A lone surrogate is not a character; it is half of one. There is no
     * correct UTF-8 for it, so the only options are to corrupt or to refuse.
     */
    private void checkWellFormedUtf16(String s) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 0xD800 || c > 0xDFFF) continue;
            if (Character.isHighSurrogate(c) && i + 1 < n
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;                                  // a well-formed pair
                continue;
            }
            throw Err.of("invalid-utf16",
                "boring: string contains an unpaired UTF-16 surrogate (0x"
                + Integer.toHexString(c) + ") at index " + i
                + " and has no UTF-8 encoding",
                "index", (long) i);
        }
    }

    private void writeStringSlow(String s, int slot) {
        checkWellFormedUtf16(s);
        byte[] utf = s.getBytes(StandardCharsets.UTF_8);
        head(TEXT, utf.length);
        ensure(utf.length);
        System.arraycopy(utf, 0, buf, pos, utf.length);
        pos += utf.length;
        // Byte length differs from char length here, so the slot from the
        // char-length probe is still valid but the threshold uses utf.length.
        if (slot >= 0) srInsertAt(slot, s, utf.length);
    }

    // ---- stringref ----------------------------------------------------------

    /**
     * Register `s` against the next index, if its encoded length pays for the
     * reference. The threshold is the stringref spec's: index 0-23 needs >=3
     * octets, 24-255 needs >=4, and so on. The decoder applies the identical
     * rule, so the two index spaces stay in lockstep.
     */
    private void srRegister(String s, int byteLen) {
        if (!stringref) return;
        if (byteLen < minLenForIndex(srNextIndex)) return;
        if (srCount + 1 > (srMask + 1) * 3 / 4) rehash();
        int h = s.hashCode();
        h ^= (h >>> 16);
        int i = h & srMask;
        while (srKeys[i] != null) {
            if (srKeys[i].equals(s)) return;   // already present
            i = (i + 1) & srMask;
        }
        srKeys[i] = s;
        srVals[i] = srNextIndex++;
        srCount++;
    }

    /**
     * Advance the index space for a byte string, which we never add to the
     * lookup table: emitting a back-reference to a repeated byte string is a
     * size optimisation with no correctness content, and hashes and UUIDs do
     * not repeat. The COUNTER still has to move, because the decoder registers
     * every byte string it reads.
     *
     * The threshold test must be identical to `srRegister`'s or the two sides
     * disagree about whether this string took an index at all.
     */
    private void srConsumeIndex(long byteLen) {
        if (!stringref) return;
        if (byteLen < minLenForIndex(srNextIndex)) return;
        srNextIndex++;
    }

    /** stringref: index 0-23 needs >=3 octets, 24-255 needs >=4, etc. */
    private static int minLenForIndex(int idx) {
        if (idx < 24) return 3;
        if (idx < 256) return 4;
        if (idx < 65536) return 5;
        return 7;
    }

    private void rehash() {
        Object[] ok = srKeys;
        int[] ov = srVals;
        // Doubling. Growing 4x was tried, on the reasoning that a table
        // starting at 16 rehashes more on the way up and fewer steps would
        // cost less. Measured, it was far worse -- datom-maps-200 went from
        // 34.8 KB to 67.2 KB allocated on a fresh writer and from 8.1 KB to
        // 48.0 KB on a reused one -- so the reasoning is recorded as wrong
        // rather than repeated.
        int newCap = (srMask + 1) << 1;
        srKeys = new Object[newCap];
        srVals = new int[newCap];
        srMask = newCap - 1;
        srCount = 0;
        for (int j = 0; j < ok.length; j++) {
            if (ok[j] != null) {
                int h = ok[j].hashCode();
                h ^= (h >>> 16);
                int i = h & srMask;
                while (srKeys[i] != null) i = (i + 1) & srMask;
                srKeys[i] = ok[j];
                srVals[i] = ov[j];
                srCount++;
            }
        }
    }

    /** Open a stringref namespace: tag 256 wrapping the next value. */
    public void writeStringrefNamespace() {
        head(TAG, TAG_SR_NS);
    }

    /**
     * Keyword/symbol as tag 39 over its string form, with the payload replaced
     * by tag 25(n) on repeat. Verified byte-identical to what Python cbor2
     * emits — see doc/INTEROP.md.
     */
    private void writeIdent(String s) {
        head(TAG, TAG_IDENTIFIER);
        writeString(s);   // writeString handles the stringref substitution
    }

    // ---- dispatch -----------------------------------------------------------

    /**
     * Per-value dispatch. Ordered by measured frequency in datom-shaped data:
     * Long and Keyword dominate, then String.
     */
    @SuppressWarnings("rawtypes")
    public void writeValue(Object x) {
        if (x == null) { writeNull(); return; }

        // Consume the flag on ENTRY. Resetting it here rather than after the
        // inner write is what keeps children eligible for their own metadata:
        // leaving it set would suppress meta on every nested value too.
        boolean wrapped = metaWrapped;
        metaWrapped = false;
        if (inclMetadata && !wrapped && x instanceof clojure.lang.IObj) {
            clojure.lang.IPersistentMap m = ((clojure.lang.IObj) x).meta();
            if (m != null && m.count() > 0) {
                head(TAG, TAG_GENERIC_OBJ);
                head(ARRAY, 2);
                writeString(NAME_WITH_META);
                head(ARRAY, 2);
                writeValue(m);
                metaWrapped = true;
                writeValue(x);
                return;
            }
        }
        if (++depth + depthOffset > maxDepth) {
            depth = 0;
            throw Err.of("max-depth-exceeded",
                "boring: value nested deeper than maxDepth (" + maxDepth + ")",
                "max-depth", (long) maxDepth);
        }
        // No try/finally. The exception table around EVERY value write is not
        // free -- this is the hottest method in the encoder, 25.5% of samples
        // on datom-maps-200 -- and the invariant it protects is already
        // guaranteed elsewhere: `write-root!` calls reset() before every
        // encode, and reset() sets depth = 0. A writer whose encode threw is
        // therefore clean before it is used again.
        //
        // That is the same invariant the ClojureScript reset! was fixed to
        // uphold, after a depth left raised by a failed encode poisoned every
        // later call on a reused writer.
        writeValueInner(x);
        depth--;
    }

    @SuppressWarnings("rawtypes")
    private void writeValueInner(Object x) {
        Class<?> c = x.getClass();

        if (c == Long.class)    { writeLong((Long) x); return; }
        if (c == String.class)  { writeString((String) x); return; }
        if (c == clojure.lang.Keyword.class) {
            clojure.lang.Keyword k = (clojure.lang.Keyword) x;
            writeIdent(k.toString());   // ":ns/name"; Keyword caches this
            return;
        }
        if (c == Double.class)  { writeDouble((Double) x); return; }
        if (c == Boolean.class) { writeBoolean((Boolean) x); return; }

        if (c == Integer.class) { writeLong(((Integer) x).longValue()); return; }
        if (c == Short.class)   { writeLong(((Short) x).longValue()); return; }
        if (c == Byte.class)    { writeLong(((Byte) x).longValue()); return; }
        if (c == Float.class)   {
            if (preserveWidth) writeFloat((Float) x);
            else writeDouble(((Float) x).doubleValue());
            return;
        }
        if (c == clojure.lang.Symbol.class) {
            clojure.lang.Symbol s = (clojure.lang.Symbol) x;
            writeIdent(s.toString());
            return;
        }
        if (c == byte[].class)  { writeBytes((byte[]) x); return; }

        // The registry is consulted BEFORE the built-in concrete types below,
        // not after. It used to sit at the bottom, which meant a caller who
        // registered a handler for a type boring happens to know natively --
        // UUID, Instant, and now URI and Pattern -- had that registration
        // SILENTLY IGNORED. That is the same defect already fixed once for
        // types implementing Set or IRecord, in a branch that was missed.
        //
        // An explicit registration is an instruction, so it wins. Nobody can be
        // broken by this who was not already being ignored.
        Object[] handler = registry.writerFor(c);
        if (handler != null) {
            writeTag(((Number) handler[0]).longValue());   // validated, not raw head
            writeValue(((clojure.lang.IFn) handler[1]).invoke(x));
            return;
        }

        // Arbitrary-precision integers. clojure.lang.BigInt wraps either a long
        // or a BigInteger; normalise to BigInteger.
        if (c == java.math.BigInteger.class) { writeBigInteger((java.math.BigInteger) x); return; }
        if (c == clojure.lang.BigInt.class) {
            writeBigInteger(((clojure.lang.BigInt) x).toBigInteger());
            return;
        }
        if (c == java.math.BigDecimal.class) {
            writeBigDecimal((java.math.BigDecimal) x);
            return;
        }
        if (c == clojure.lang.Ratio.class) { writeRatio((clojure.lang.Ratio) x); return; }

        // RFC 8746 typed arrays — bulk little-endian copy, no per-element boxing
        if (c == long[].class)   { writeLongArray((long[]) x); return; }
        if (c == double[].class) { writeDoubleArray((double[]) x); return; }
        if (c == double[][].class) { writeMatrix((Object[]) x, double[].class); return; }
        if (c == long[][].class)   { writeMatrix((Object[]) x, long[].class); return; }
        if (c == int[][].class)    { writeMatrix((Object[]) x, int[].class); return; }
        if (c == float[][].class)  { writeMatrix((Object[]) x, float[].class); return; }
        if (c == short[][].class)  { writeMatrix((Object[]) x, short[].class); return; }
        if (c == int[].class)    { writeIntArray((int[]) x); return; }
        if (c == float[].class)  { writeFloatArray((float[]) x); return; }
        if (c == short[].class)  { writeShortArray((short[]) x); return; }

        if (c == java.util.UUID.class) { writeUUID((java.util.UUID) x); return; }
        if (c == java.time.Instant.class) { writeInstant((java.time.Instant) x); return; }
        if (c == java.util.Date.class) {
            writeInstant(java.time.Instant.ofEpochMilli(((java.util.Date) x).getTime()));
            return;
        }
        if (c == java.time.Duration.class) {
            // RFC 9581 4: structurally a tag-1001 time value, keyed map. Key 1 is
            // the base in seconds; key -9 the nanosecond fraction, omitted when
            // zero so a whole-second duration costs one entry rather than two.
            java.time.Duration d = (java.time.Duration) x;
            head(TAG, TAG_DURATION);
            int nanos = d.getNano();
            head(MAP, nanos == 0 ? 1 : 2);
            writeLong(1); writeLong(d.getSeconds());
            if (nanos != 0) { writeLong(-9); writeLong(nanos); }
            return;
        }
        if (c == java.time.LocalDate.class) {
            head(TAG, TAG_FULL_DATE);
            writeString(x.toString());               // ISO-8601, == RFC 3339 full-date
            return;
        }
        if (c == java.sql.Date.class) {
            // A calendar date, not an instant: java.sql.Date's time-of-day is
            // meaningless and its toInstant() throws. Tag 1004 is the registered
            // full-date form, so it goes there rather than through tag 0.
            head(TAG, TAG_FULL_DATE);
            writeString(((java.sql.Date) x).toLocalDate().toString());
            return;
        }
        if (c == java.net.URI.class) {
            head(TAG, TAG_URI);
            writeString(x.toString());
            return;
        }
        if (c == java.util.regex.Pattern.class) {
            // Tag 35 carries the pattern SOURCE, not the flags. Java's
            // toString() is the source alone, so a Pattern compiled with
            // CASE_INSENSITIVE round-trips as a case-SENSITIVE one. Documented
            // rather than silently papered over: the alternative is inventing a
            // private tag for flags, and tag 35 is what a foreign reader
            // understands.
            head(TAG, TAG_REGEX);
            writeString(x.toString());
            return;
        }
        if (c == Character.class) {
            head(TAG, TAG_GENERIC_OBJ);
            head(ARRAY, 2);
            writeString(NAME_CHAR);
            writeString(String.valueOf(((Character) x).charValue()));
            return;
        }
        if (x instanceof Throwable) {
            Throwable t = (Throwable) x;
            boolean exInfo = x instanceof clojure.lang.ExceptionInfo;
            head(TAG, TAG_GENERIC_OBJ); head(ARRAY, 2);
            writeString(exInfo ? NAME_EX_INFO : NAME_THROWABLE);
            head(ARRAY, exInfo ? 3 : 3);
            if (exInfo) {
                writeString(t.getMessage() == null ? "" : t.getMessage());
                writeValue(((clojure.lang.ExceptionInfo) t).getData());
            } else {
                writeString(c.getName());
                if (t.getMessage() == null) writeNull(); else writeString(t.getMessage());
            }
            Throwable cause = t.getCause();
            if (cause == null || cause == t) writeNull(); else writeValue(cause);
            return;
        }
        if (c == boolean[].class) {
            boolean[] a = (boolean[]) x;
            head(TAG, TAG_GENERIC_OBJ); head(ARRAY, 2);
            writeString(NAME_BOOLEAN_ARRAY);
            head(ARRAY, a.length);
            for (boolean b : a) writeBoolean(b);
            return;
        }
        if (c == char[].class) {
            head(TAG, TAG_GENERIC_OBJ); head(ARRAY, 2);
            writeString(NAME_CHAR_ARRAY);
            writeString(new String((char[]) x));
            return;
        }
        if (c == String[].class) {
            String[] a = (String[]) x;
            head(TAG, TAG_GENERIC_OBJ); head(ARRAY, 2);
            writeString(NAME_STRING_ARRAY);
            head(ARRAY, a.length);
            for (String v : a) { if (v == null) writeNull(); else writeString(v); }
            return;
        }
        if (c == Object[].class) {
            Object[] a = (Object[]) x;
            head(TAG, TAG_GENERIC_OBJ); head(ARRAY, 2);
            writeString(NAME_OBJECT_ARRAY);
            head(ARRAY, a.length);
            for (Object v : a) writeValue(v);
            return;
        }
        if (c == java.time.Period.class) {
            head(TAG, TAG_GENERIC_OBJ);
            head(ARRAY, 2);
            writeString(NAME_PERIOD);
            writeString(x.toString());               // ISO-8601, e.g. "P1Y1M1D"
            return;
        }

        // Sorted collections and queues, before the structural Map/Set/List
        // branches below would flatten them.
        //
        // A custom comparator is REFUSED rather than dropped. `(sorted-map-by
        // my-cmp ...)` is a map plus a function, and the function is code: no
        // encoding can carry it. Writing the entries and silently rebuilding
        // with `compare` would produce a collection that sorts differently from
        // the one that was stored, which is worse than not storing it.
        if (x instanceof clojure.lang.PersistentTreeMap) {
            requireDefaultComparator(((clojure.lang.PersistentTreeMap) x).comparator(), "sorted-map");
            head(TAG, TAG_GENERIC_OBJ);
            head(ARRAY, 2);
            writeString(NAME_SORTED_MAP);
            writeMapValue((Map) x);
            return;
        }
        if (x instanceof clojure.lang.PersistentTreeSet) {
            requireDefaultComparator(((clojure.lang.PersistentTreeSet) x).comparator(), "sorted-set");
            head(TAG, TAG_GENERIC_OBJ);
            head(ARRAY, 2);
            writeString(NAME_SORTED_SET);
            writeSeqAsArray(clojure.lang.RT.seq(x), ((java.util.Set) x).size());
            return;
        }
        if (x instanceof clojure.lang.PersistentQueue) {
            head(TAG, TAG_GENERIC_OBJ);
            head(ARRAY, 2);
            writeString(NAME_QUEUE);
            writeSeqAsArray(clojure.lang.RT.seq(x), ((java.util.Collection) x).size());
            return;
        }

        // CBOR types with no Clojure counterpart. These MUST be tested before
        // the Map branch: they are defrecords, so they are also java.util.Maps,
        // and would otherwise silently encode as maps of their own fields.
        if (c == Data.SIMPLE_VALUE) {
            Object n = ((clojure.lang.ILookup) x).valAt(K_N);
            writeSimpleValue(((Number) n).intValue());
            return;
        }
        if (c == Data.TAGGED_VALUE) {
            clojure.lang.ILookup l = (clojure.lang.ILookup) x;
            writeTagNumber(l.valAt(K_TAG));
            writeValue(l.valAt(K_VALUE));
            return;
        }

        // A TaggedLiteral is what an unregistered tag-27 frame with a
        // NON-map payload decodes to. It has to re-encode to the same frame or
        // the passthrough guarantee holds for record-shaped frames only.
        if (x instanceof clojure.lang.TaggedLiteral) {
            clojure.lang.TaggedLiteral tl = (clojure.lang.TaggedLiteral) x;
            writeRecordFrame(tl.tag.toString(), tl.form);
            return;
        }

        // An UnknownRecord round-trips as the record it stood for, so a
        // decode/encode passthrough of an unregistered type is byte-identical.
        if (c == Data.UNKNOWN_RECORD) {
            writeRecordFrame((String) Data.RECORD_TYPE.invoke(x), Data.RECORD_FIELDS.invoke(x));
            return;
        }

        // User handlers beat STRUCTURAL inference AND the record branch.
        //
        // This sat below BOTH for a while, then was hoisted above only the
        // structural branches -- which left the identical defect in place for
        // defrecords: a registered write handler for a record type silently
        // did nothing, because IRecord matched first and emitted a tag-27
        // frame of the record's raw fields.
        //
        // That is not academic. A handler for a record is usually registered
        // precisely to TRANSFORM the value before writing -- stripping live
        // caches, connections or lazy roots that must not go on a wire. Being
        // ignored there means shipping exactly the state the handler existed
        // to remove, with no error.
        //
        // This lookup used to sit below the instanceof cascade, so registering
        // a handler for any type that happens to implement Map, Set,
        // Collection or List did nothing at all -- silently. PSS's
        // PersistentSortedSet implements java.util.Set, so a root handler for
        // it was ignored and the root encoded as tag 258, a set of its
        // elements: structurally valid CBOR, completely wrong value, and no
        // error anywhere.
        //
        // An explicit registration is a statement about a type that structure
        // cannot override. It stays BELOW the concrete-class fast paths above
        // (Keyword, PersistentVector, and friends), so the hot path is
        // unchanged -- this costs one hash lookup only for foreign types that
        // were about to take a generic branch anyway.
        // Records before Maps: a defrecord IS a java.util.Map, and would
        // otherwise flatten to a plain map and lose its type — the exact
        // failure mode incognito exists to work around.
        if (x instanceof clojure.lang.IRecord) {
            writeRecordFrame(registry.recordName(c), x);
            return;
        }

        // These four delegated to inline copies of the loops in writeMapValue
        // and writeSeqAsArray. Duplicated loops meant the index hook had to be
        // written four more times, and the first attempt missed exactly this
        // one -- plain Clojure maps never reached the instrumented method, so
        // every map came back unindexed while the named method looked correct.
        if (x instanceof Map) { writeMapValue((Map) x); return; }
        if (x instanceof Set) {
            Set s = (Set) x;
            if (canonical) { writeSetCanonical(s); return; }
            head(TAG, TAG_SET);
            // The node describes the ARRAY, not the tag: the byte walk descends
            // tags and indexes the container beneath, so `start` is taken after
            // the tag head or the two would disagree about this container's
            // offset.
            writeArrayOf(s, s.size());
            return;
        }
        if (x instanceof List) {
            List l = (List) x;
            if (shapes && !canonical) {
                Object[] shape = homogeneousShape(l);
                if (shape != null) { writeShapedArray(l, shape); return; }
            }
            int n = l.size();
            int start = pos;
            head(ARRAY, n);
            int[] anchors = indexing(n) ? new int[anchorCount(n)] : null;
            int slot = anchors != null ? reserveNode() : -1;
            int a = 0, countdown = 1, seen = 0;
            if (x instanceof java.util.RandomAccess) {
                // Indexed by position, so it cannot yield more or fewer than n.
                for (int i = 0; i < n; i++) {
                    if (anchors != null && --countdown == 0) {
                        anchors[a++] = idxOffset(pos); countdown = idxStride;
                    }
                    writeValue(l.get(i));
                }
            } else {
                for (Object o : l) {
                    if (++seen > n) countMismatch(n, seen);
                    if (anchors != null && --countdown == 0) {
                        anchors[a++] = idxOffset(pos); countdown = idxStride;
                    }
                    writeValue(o);
                }
                if (seen != n) countMismatch(n, seen);
            }
            if (anchors != null) fillNode(slot, start, n, anchors, false);
            return;
        }
        if (x instanceof java.util.Collection) {
            java.util.Collection col = (java.util.Collection) x;
            writeArrayOf(col, col.size());
            return;
        }
        // A fallback turns "one bad field kills the document" into "one bad
        // field is replaced". On a wire that is usually the better trade: a
        // message that arrives with a placeholder beats a message that does
        // not arrive.
        //
        // Guarded against recursion: if the REPLACEMENT is also unencodable we
        // throw rather than calling the fallback again, because a fallback
        // that returns something unencodable would otherwise loop forever.
        if (encodeFallback != null && !inFallback) {
            inFallback = true;
            try {
                writeValue(encodeFallback.invoke(x));
                return;
            } finally {
                inFallback = false;
            }
        }
        throw Err.of("unsupported-type", "boring: no encoding for " + c.getName(), "class", c.getName());
    }
}
