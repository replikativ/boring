package org.replikativ.boring;

import clojure.lang.ExceptionInfo;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

/**
 * Every error this codec raises is a Clojure `ex-info` carrying a `:type`
 * keyword, so callers can dispatch on the kind of failure instead of matching
 * on a message string.
 *
 * datahike's dump requirements ask for exactly this: "Unknown or unregistered
 * tag should surface as a recoverable, identifiable error (ex-info with a
 * type/tag) rather than an exception from deep in the decode loop. Our importer
 * wants to say 'this dump requires tag N, which this version cannot interpret'
 * and fail precisely."
 */
public final class Err {

    private static final Keyword K_TYPE = Keyword.intern(null, "type");

    private Err() {}

    public static ExceptionInfo of(String type, String msg) {
        return new ExceptionInfo(msg,
            PersistentArrayMap.createAsIfByAssoc(
                new Object[]{K_TYPE, Keyword.intern("boring", type)}));
    }

    /** With a cause, for wrapping a checked exception from a sink. The cause is
     *  kept rather than flattened into the message so a caller can still see the
     *  IOException that a failed write actually raised. */
    public static ExceptionInfo of(String type, String msg, Throwable cause) {
        return new ExceptionInfo(msg,
            PersistentArrayMap.createAsIfByAssoc(
                new Object[]{K_TYPE, Keyword.intern("boring", type)}),
            cause);
    }

    public static ExceptionInfo of(String type, String msg, String k1, Object v1) {
        return new ExceptionInfo(msg,
            PersistentArrayMap.createAsIfByAssoc(
                new Object[]{K_TYPE, Keyword.intern("boring", type),
                             Keyword.intern(null, k1), v1}));
    }

    /**
     * What a CALLER-SUPPLIED handler threw, made typed.
     *
     * <p>A registered tag reader, record constructor, tag writer or
     * `:encode-fallback` is arbitrary user code, and boring invokes it on
     * ATTACKER-CONTROLLED CONTENT. The interesting case is not a buggy handler
     * but a correct one: a `java.net.URI` constructor is right to throw
     * `URISyntaxException` on `":://not a uri"`, and whoever wrote the bytes
     * chose that string. Unwrapped, that escaped `decode` raw and defeated a
     * caller's `catch ExceptionInfo` — guarantee 3 of doc/SECURITY.md, and the
     * "error-handling bypass" named in its own list of realistic harms.
     *
     * <p>THE HANDLER'S OWN TYPED ERROR SURVIVES. A handler that deliberately
     * raises `(ex-info ... {:type :my.app/bad-point})` is reporting something
     * its caller wants to catch specifically, and rewrapping that would bury
     * it. Only an untyped throw is converted.
     *
     * <p>Callers catch `Exception`, never `Throwable`: `OutOfMemoryError` is
     * deliberately not caught anywhere in this codec (see doc/SECURITY.md), and
     * `StackOverflowError` is handled once at the decode boundary.
     */
    public static ExceptionInfo fromHandler(String kind, Object id, Exception e) {
        if (e instanceof ExceptionInfo) {
            Object data = ((ExceptionInfo) e).getData();
            if (data instanceof clojure.lang.ILookup
                && ((clojure.lang.ILookup) data).valAt(K_TYPE) != null)
                return (ExceptionInfo) e;
        }
        return new ExceptionInfo(
            "boring: the " + kind + " registered for " + id + " threw "
            + e.getClass().getSimpleName()
            + (e.getMessage() == null ? "" : ": " + e.getMessage()),
            PersistentArrayMap.createAsIfByAssoc(
                new Object[]{K_TYPE, Keyword.intern("boring", "handler-failed"),
                             Keyword.intern(null, "handler"), kind,
                             Keyword.intern(null, "id"), id}),
            e);
    }

    public static ExceptionInfo of(String type, String msg,
                                   String k1, Object v1, String k2, Object v2) {
        return new ExceptionInfo(msg,
            PersistentArrayMap.createAsIfByAssoc(
                new Object[]{K_TYPE, Keyword.intern("boring", type),
                             Keyword.intern(null, k1), v1,
                             Keyword.intern(null, k2), v2}));
    }
}
