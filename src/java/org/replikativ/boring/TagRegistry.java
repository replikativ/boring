package org.replikativ.boring;

import java.util.HashMap;
import java.util.Map;

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
    private final Map<Class<?>, Object[]> writers;
    /** tag -> reader-fn */
    private final Map<Long, clojure.lang.IFn> readers;
    /** wire name -> map->Record constructor */
    private final Map<String, clojure.lang.IFn> recordCtors;
    /** Optional override of the wire name for a class. */
    private final Map<Class<?>, String> recordNames;

    /** The empty registry. Safe to share: nothing can mutate it. */
    public static final TagRegistry EMPTY = new TagRegistry(
        Map.of(), Map.of(), Map.of(), Map.of());

    private TagRegistry(Map<Class<?>, Object[]> writers,
                        Map<Long, clojure.lang.IFn> readers,
                        Map<String, clojure.lang.IFn> recordCtors,
                        Map<Class<?>, String> recordNames) {
        this.writers = writers;
        this.readers = readers;
        this.recordCtors = recordCtors;
        this.recordNames = recordNames;
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
    public TagRegistry withWriter(Class<?> cls, long tag, clojure.lang.IFn writeFn) {
        return new TagRegistry(plus(writers, cls, new Object[]{tag, writeFn}),
                               readers, recordCtors, recordNames);
    }

    /** @param readFn (fn [decoded-content] -> value) */
    public TagRegistry withReader(long tag, clojure.lang.IFn readFn) {
        return new TagRegistry(writers, plus(readers, tag, readFn),
                               recordCtors, recordNames);
    }

    // ---- records (tag 27) --------------------------------------------------
    //
    // Only the READ direction needs registration: the writer emits the record's
    // own class name, so the type is never lost on the way out. Reading looks
    // the name up here — there is deliberately no Class.forName path, so a
    // hostile document cannot cause an arbitrary class to be instantiated.

    public TagRegistry withRecord(String name, clojure.lang.IFn ctor) {
        return new TagRegistry(writers, readers,
                               plus(recordCtors, name, ctor), recordNames);
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
        return new TagRegistry(writers, readers, copy, recordNames);
    }

    public TagRegistry withRecordName(Class<?> cls, String name) {
        return new TagRegistry(writers, readers, recordCtors,
                               plus(recordNames, cls, name));
    }

    public Object[] writerFor(Class<?> cls) { return writers.get(cls); }
    public clojure.lang.IFn readerFor(long tag) { return readers.get(tag); }
    public clojure.lang.IFn recordCtor(String name) { return recordCtors.get(name); }

    /** Wire name for `cls` — the registered override, else its class name. */
    public String recordName(Class<?> cls) {
        String n = recordNames.get(cls);
        return n != null ? n : cls.getName();
    }
}
