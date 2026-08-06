package org.replikativ.boring;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * User-extensible tag handlers, in both directions.
 *
 * <p><b>Immutable.</b> Every {@code with*} method returns a NEW registry and
 * leaves the receiver untouched, so a registry is a value you build once and
 * hand to readers and writers.
 *
 * <p>This used to be a mutable object with a process-global {@code DEFAULT}
 * instance. Two problems, both silent:
 *
 * <ul>
 *   <li>Independent libraries in one JVM — datahike and konserve, say — both
 *       registered into that global. Two registrations of the same tag or the
 *       same record wire name meant last-writer-wins, with the winner decided
 *       by namespace load order.</li>
 *   <li>The ClojureScript side was already an immutable map, so registration
 *       code that mutated in place worked on the JVM and silently did nothing
 *       on ClojureScript. It compiled either way.</li>
 * </ul>
 *
 * <p>Copy-on-write fixes both and costs nothing at decode time: registration
 * happens at startup, lookup is what runs in the loop. It also removes the
 * non-atomic multi-map update the old {@code registerRecord} had, and makes
 * safe publication automatic — the maps are populated before the constructor
 * returns and reached through final fields, so a registry built on one thread
 * is fully visible to any thread it is handed to, with no synchronization.
 *
 * <p>Security: the read side dispatches on a tag NUMBER, and records on a
 * NAME, both looked up here. There is no path from wire content to class
 * loading — an unregistered tag surfaces as a TaggedValue or errors, it never
 * causes an arbitrary class to be instantiated. Registered callbacks run with
 * your process's privileges, so vet what you install.
 */
public final class TagRegistry {

    /** Class -> {tag, writer-fn}. Identity-keyed: exact class match only. */
    private final Map<Class<?>, TagWriter> writers;
    /** tag -> reader-fn */
    private final Map<Long, clojure.lang.IFn> readers;
    /** wire name -> map->Record constructor */
    private final Map<String, clojure.lang.IFn> recordCtors;
    /** Optional override of the wire name for a class. */
    private final Map<Class<?>, String> recordNames;
    /**
     * Wire names whose constructor is declared STRUCTURE-PRESERVING, so that
     * `boring.nav` may answer lookups from the field map on the wire instead of
     * building the record.
     *
     * <p>Opt-in because a constructor is an arbitrary IFn. It may rename fields,
     * drop them, or resolve state that is not in the bytes at all -- datahike's
     * `reconstruct-db` resolves storage roots -- and answering past one of those
     * returns a value the reader would never have produced. So the default is
     * that a registered name is opaque, and the handler's author, who is the
     * only one who knows, says otherwise.
     *
     * <p>The declaration is a CLAIM, and a wrong one returns wrong values
     * silently. `boring.nav-conformance/check-record` exists to test it.
     */
    private final Set<String> navigableRecords;

    /** The empty registry. Safe to share: nothing can mutate it. */
    public static final TagRegistry EMPTY = new TagRegistry(
        Map.of(), Map.of(), Map.of(), Map.of(), Set.of());

    private TagRegistry(Map<Class<?>, TagWriter> writers,
                        Map<Long, clojure.lang.IFn> readers,
                        Map<String, clojure.lang.IFn> recordCtors,
                        Map<Class<?>, String> recordNames,
                        Set<String> navigableRecords) {
        this.writers = writers;
        this.readers = readers;
        this.recordCtors = recordCtors;
        this.recordNames = recordNames;
        this.navigableRecords = navigableRecords;
    }

    /**
     * Declare that `name`'s constructor preserves structure: that looking a key
     * up in the realised value gives what looking it up in the field map gives,
     * and likewise for count and seq.
     *
     * <p>Returns a NEW registry. Declaring a name that is not registered is
     * harmless -- an unregistered name already decodes to an UnknownRecord over
     * the field map, which is navigable without any declaration.
     */
    public TagRegistry withNavigableRecord(String name) {
        Set<String> copy = new HashSet<>(navigableRecords);
        copy.add(name);
        return new TagRegistry(writers, readers, recordCtors, recordNames, copy);
    }

    /** Whether `name` was declared structure-preserving. */
    public boolean isNavigableRecord(String name) {
        return navigableRecords.contains(name);
    }

    private static <K, V> Map<K, V> plus(Map<K, V> m, K k, V v) {
        Map<K, V> copy = new HashMap<>(m);
        copy.put(k, v);
        return copy;
    }

    /**
     * @param cls     the exact class to match on encode
     * @param tag     the CBOR tag number to emit
     * @param writeFn (fn [value] -> value-to-encode) — returns a value the codec
     *                already knows how to write; it is written as the tag content
     */
    /**
     * A tag number the wire can carry.
     *
     * The registry accepted NEGATIVE tags, and the writer's registered branch
     * emitted them through the unchecked head path -- so a handler registered
     * as tag -1 wrote `ff`, the CBOR break byte, followed by its content. No
     * exception, just malformed output that no reader can parse.
     *
     * CBOR's tag domain is [0, 2^64-1]; this API takes a long, so it can offer
     * [0, Long.MAX_VALUE] and says so rather than truncating.
     */
    private static long checkTag(long tag) {
        if (tag < 0)
            throw Err.of("bad-tag-number",
                "boring: tag numbers are unsigned; got " + tag
                + ". This API accepts 0 to " + Long.MAX_VALUE + ".");
        // 25 and 256 are STRUCTURAL, not semantic: they are how the format
        // encodes string repetition, and the reader resolves them while
        // building the value rather than at the tag dispatch a registered
        // reader hooks. So an override was honoured for a bare `25(0)` and
        // silently ignored for the same reference inside a tag-39 identifier --
        // the same table entry meaning two different things depending on where
        // it appeared. Refusing to register is the only answer that is true
        // everywhere.
        if (tag == 25 || tag == 256)
            throw Err.of("bad-tag-number",
                "boring: tag " + tag + " is structural (stringref) and cannot be"
                + " given a reader; it is resolved while the value is built,"
                + " not at tag dispatch. Use :stringref false to turn it off.");
        return tag;
    }

    public TagRegistry withWriter(Class<?> cls, long tag, clojure.lang.IFn writeFn) {
        checkTag(tag);
        if (cls != null && !Writer.isRegisterableClass(cls))
            throw Err.of("unregisterable-class",
                "boring: " + cls.getName() + " is written before the registry is"
                + " consulted, so a handler for it could never run. Registering one"
                + " silently did nothing. Wrap the value in a type of your own, or"
                + " use :encode-fallback.");
        return new TagRegistry(plus(writers, cls, new TagWriter(tag, writeFn)),
                               readers, recordCtors, recordNames, navigableRecords);
    }

    /** @param readFn (fn [decoded-content] -> value) */
    public TagRegistry withReader(long tag, clojure.lang.IFn readFn) {
        checkTag(tag);
        return new TagRegistry(writers, plus(readers, tag, readFn),
                               recordCtors, recordNames, navigableRecords);
    }

    // ---- records (tag 27) --------------------------------------------------
    //
    // Only the READ direction needs registration: the writer emits the record's
    // own class name, so the type is never lost on the way out. Reading looks
    // the name up here — there is deliberately no Class.forName path, so a
    // hostile document cannot cause an arbitrary class to be instantiated.

    public TagRegistry withRecord(String name, clojure.lang.IFn ctor) {
        return new TagRegistry(writers, readers,
                               plus(recordCtors, name, ctor), recordNames,
                               navigableRecords);
    }

    /** Override the wire name emitted for `cls`, for the encode direction. */
    /**
     * Register many record constructors in ONE copy of the backing map.
     *
     * <p>`withRecord` copies the whole map per call, which is the right trade
     * for a registry built once at startup and the wrong one for a caller that
     * folds a handler map in per operation -- konserve's serializer protocol
     * hands you handlers per read, so the natural implementation cost N copies
     * per read and overtook fressian past ~20 handlers.
     *
     * <p>Callers who derive a registry per operation should still memoise it;
     * this makes the un-memoised path O(1) copies instead of O(N).
     */
    public TagRegistry withRecords(Map<?, ?> ctors) {
        if (ctors == null || ctors.isEmpty()) return this;
        Map<String, clojure.lang.IFn> copy = new HashMap<>(recordCtors);
        // Keys are stringified HERE rather than by the caller, so a symbol-keyed
        // map -- incognito's shape, and what konserve hands us -- needs no
        // intermediate map built just to change the key type.
        for (Map.Entry<?, ?> e : ctors.entrySet()) {
            copy.put(String.valueOf(e.getKey()), (clojure.lang.IFn) e.getValue());
        }
        return new TagRegistry(writers, readers, copy, recordNames, navigableRecords);
    }

    public TagRegistry withRecordName(Class<?> cls, String name) {
        return new TagRegistry(writers, readers, recordCtors,
                               plus(recordNames, cls, name), navigableRecords);
    }

    /**
     * The registered writer for `cls`, or null.
     *
     * Returns an IMMUTABLE holder, not the registry's own array. It used to
     * hand back the live `Object[]`, so a Java caller could rewrite the tag or
     * the function after publication -- contradicting this class's whole
     * immutability claim, and doing it through a data race rather than an
     * assignment anyone could see. A defensive copy would also close it, but it
     * would allocate on every encode of a registered type; final fields cost
     * nothing and cannot be written at all.
     */
    public static final class TagWriter {
        public final long tag;
        public final clojure.lang.IFn fn;
        TagWriter(long tag, clojure.lang.IFn fn) { this.tag = tag; this.fn = fn; }
    }

    public TagWriter writerFor(Class<?> cls) { return writers.get(cls); }
    /** True when encode dispatch can possibly find a user handler. */
    public boolean hasWriters() { return !writers.isEmpty(); }
    public clojure.lang.IFn readerFor(long tag) { return readers.get(tag); }
    public clojure.lang.IFn recordCtor(String name) { return recordCtors.get(name); }

    /**
     * Wire name for `cls` — the registered override, else its TRUE Clojure
     * name, with the namespace un-munged.
     *
     * Clojure builds a record's class name with `namespace-munge`, which is
     * `(.replace (str ns) \- \_)`, so `(defrecord My-Rec ...)` in `my-test-ns`
     * becomes the class `my_test_ns.My-Rec`. ClojureScript has no such step:
     * its `pr-str` reports `#my-test-ns.My-Rec{...}`, the name as written. So
     * the JVM was the lossy side, and boring used to "fix" that by munging
     * ClojureScript down to match — throwing away information the browser
     * still had, to agree with a platform that had lost it.
     *
     * The munge is invertible by lookup rather than by guessing: scan the
     * loaded namespaces for the one whose munged form is this package. A
     * record instance's namespace is loaded by construction, so the lookup
     * always has its answer.
     *
     * AMBIGUITY, and how it is resolved: `my-ns` and `my_ns` both munge to
     * `my_ns`. A namespace whose name already equals the package wins, since
     * it needs no inversion; failing that a single candidate is taken; and
     * anything else falls back to the class name rather than guessing. In
     * practice `my_ns` as a Clojure namespace is vanishingly rare.
     *
     * SLASH, not dot, between namespace and name. A dot is ambiguous -- given
     * `a.b.c.D` you cannot tell whether the namespace is `a.b.c` or `a.b`
     * without guessing -- while `/` is legal in neither part, so the split is
     * exact. It is also the separator boring's own reserved tag-27 names
     * already use: `clojure/sorted-map`, `java/period`, `boring/index`.
     *
     * Cached per class — the scan is O(loaded namespaces) and would otherwise
     * run per encoded record.
     */
    public String recordName(Class<?> cls) {
        String n = recordNames.get(cls);
        if (n != null) return n;
        String c = trueNames.get(cls);
        if (c != null) return c;
        c = unmungedName(cls);
        // ONLY A RESOLVED NAME IS CACHED. The lookup inverts the namespace
        // munge by scanning loaded namespaces, so its answer depends on what
        // was loaded when it first ran -- and caching the FALLBACK made that
        // permanent: the same record encoded as `wn-ns.core/R` in one JVM and
        // `wn_ns.core/R` in another, and reloading the namespace did not fix
        // it. Wire bytes must not depend on class-load order.
        //
        // A fallback is a "not resolved yet" rather than an answer, so it is
        // recomputed until the namespace is there. Once resolved the name
        // cannot change, so caching from then on is sound.
        if (c.indexOf('_') < 0) trueNames.put(cls, c);
        return c;
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, String> trueNames =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static String unmungedName(Class<?> cls) {
        String cn = cls.getName();
        int i = cn.lastIndexOf('.');
        if (i <= 0) return cn;
        String pkg = cn.substring(0, i), simple = cn.substring(i + 1);
        if (pkg.indexOf('_') < 0) return pkg + "/" + simple;   // nothing was munged
        try {
            Object nss = clojure.lang.RT.var("clojure.core", "all-ns").invoke();
            clojure.lang.IFn nsName = clojure.lang.RT.var("clojure.core", "ns-name");
            String only = null; int hits = 0;
            for (Object ns : (Iterable<?>) clojure.lang.RT.seq(nss)) {
                String name = nsName.invoke(ns).toString();
                if (name.equals(pkg)) return pkg + "/" + simple;  // exact: no inversion
                if (name.replace('-', '_').equals(pkg)) { only = name; hits++; }
            }
            return (hits == 1 ? only : pkg) + "/" + simple;
        } catch (Throwable t) {
            return pkg + "/" + simple;                 // never fail an encode over a name
        }
    }
}
