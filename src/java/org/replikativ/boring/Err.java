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

    public static ExceptionInfo of(String type, String msg,
                                   String k1, Object v1, String k2, Object v2) {
        return new ExceptionInfo(msg,
            PersistentArrayMap.createAsIfByAssoc(
                new Object[]{K_TYPE, Keyword.intern("boring", type),
                             Keyword.intern(null, k1), v1,
                             Keyword.intern(null, k2), v2}));
    }
}
