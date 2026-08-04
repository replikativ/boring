(ns boring.core
  "Thin Clojure surface over the Java hot path.

  The dispatch loop lives in Java on purpose: the per-value type test plus byte
  emission is where the time goes, and crossing the Clojure/Java boundary per
  value costs more than it saves. Measured, the Java hot path is worth 1.2-1.7x
  over the equivalent pure Clojure (5.8x on strings, where bulk VarHandle writes
  and a single-pass ASCII encode beat per-byte `aset`).

  Options (all optional):

    :profile        :clojure (default) | :interop | :archival | :canonical
    :float-policy   :preserve-width (default) | :shortest
    :stringref      true (default under :clojure) | false

  See doc/COMPATIBILITY.md for what each profile promises about the bytes.
  :float-policy exists because datahike's dumps must not narrow a double to a
  float -- the class, not just the value, has to survive.

  The four profiles answer four different questions:

    :clojure    round-trip fidelity within Clojure, smallest bytes.
    :interop    can any conformant CBOR reader read this? (no extensions)
    :archival   will two exports of the same data be identical, AND do JVM
                types survive? (sorted keys + fixed-width floats)
    :canonical  do these bytes agree octet-for-octet with other canonical
                encoders? (RFC 8949 4.2.2, which narrows floats)

  :archival and :canonical are NOT the same and cannot be: RFC determinism
  requires the shortest float form, which discards the Double/Float
  distinction. Pick by which of the two you actually need."
  (:require [boring.data :as data]
            [clojure.string :as str])
  (:import (java.nio ByteBuffer)
           (org.replikativ.boring Reader TagRegistry Writer)))

(set! *warn-on-reflection* true)

;; The Java hot path recognises boring.data's types without importing them (which
;; would make the Java uncompilable without the Clojure sources on the path
;; first). It resolves them itself, from a lazy holder class — see Writer.Data.
;;
;; This used to be a push from here: `Writer/registerDataTypes` assigned mutable
;; statics at ns load. Anything that reached Writer without loading this
;; namespace first then saw them null, and a SimpleValue encoded as a 33-byte
;; tag-27 record instead of `f8 c8` — silently, no exception. The holder makes
;; the fields `static final`, so unset is not an observable state.

;; :canonical selects :shortest, and must. Deterministic encoding requires the
;; shortest float form that round-trips (RFC 8949 §4.2.2, and unchanged in
;; draft-ietf-cbor-serialization), so 65504.0 has to go out as `f97bff`, not
;; `fb40effc0000000000`. Shipping :preserve-width here meant the profile whose
;; entire purpose is byte-for-byte agreement with other implementations agreed
;; with none of them: 8 of 9 float vectors mismatched.
;;
;; The cost is real and unavoidable: canonical output does NOT preserve float
;; width, so a Double may return as a narrower type. That is inherent to
;; determinism — you cannot both sign a document and keep a JVM type
;; distinction the wire has no room for. Use :clojure or :interop when
;; round-trip type fidelity matters more than a stable byte sequence.
(def ^:private profile-defaults
  {:clojure   {:stringref true  :float-policy :preserve-width :canonical false
               :canonical-order :rfc8949}
   :interop   {:stringref false :float-policy :preserve-width :canonical false
               :shapes false :canonical-order :rfc8949}
   ;; :archival differs from :interop in exactly one bit -- sorted map keys --
   ;; and from :canonical in exactly one -- float width. That is not an accident:
   ;; determinism and type identity are separate axes, and RFC 8949's
   ;; deterministic profile happens to pin both. A dump that must outlive the
   ;; database wants sorted keys (so two exports diff clean and one can be signed)
   ;; AND fixed-width floats (so a Double does not come back a Float). Before this
   ;; profile existed that combination was unreachable: :canonical locks
   ;; :float-policy and :interop locks :canonical, so the one thing datahike's
   ;; dumps actually need could not be said. It does NOT claim RFC deterministic
   ;; conformance -- that name belongs to :canonical alone.
   :archival  {:stringref false :float-policy :preserve-width :canonical true
               :shapes false :canonical-order :rfc8949}
   :canonical {:stringref false :float-policy :shortest       :canonical true
               :shapes false :canonical-order :rfc8949}
   ;; clj-cbor's length-first key order (RFC 7049 3.9), as its own profile
   ;; rather than a knob on :canonical.
   ;;
   ;; It was a free option, so `{:profile :canonical :canonical-order :rfc7049}`
   ;; produced non-RFC-8949 bytes under the name the README gives the signing
   ;; profile -- `{1000 :x, "a" :y}` begins a219 under one and a261 under the
   ;; other. A signer and a verifier who disagree about a sub-option that does
   ;; not appear in the profile name produce a mismatch nobody can see. Naming
   ;; it makes the choice impossible to make by accident.
   :canonical-rfc7049 {:stringref false :float-policy :shortest :canonical true
                       :shapes false :canonical-order :rfc7049}})

;; Keys a profile DEFINES. Passing a conflicting value asks for two
;; incompatible things at once, and the previous behaviour was to silently
;; honour the user's -- so `{:profile :canonical :stringref true}` emitted the
;; stringref extension from the profile whose entire purpose is agreement with
;; other implementations, and `{:profile :canonical :canonical false}` turned
;; determinism off while still calling itself canonical.
;;
;; `:canonical` is locked in EVERY profile: it is not a user knob, it is what
;; `:profile :canonical` MEANS. Accepting it separately gave two ways to say
;; the same thing that could disagree.
;;
;; `:stringref` stays free under :clojure -- kabel legitimately turns it off
;; while keeping the default profile -- and `:float-policy` stays free under
;; :interop, where either width is legal CBOR that every reader understands.
(def ^:private profile-locked
  {:clojure           #{:canonical :canonical-order}
   :interop           #{:canonical :canonical-order :stringref :shapes}
   ;; Everything is locked, as under :canonical: both bits are what the profile
   ;; MEANS. `:float-policy :shortest` here would just be :canonical spelled
   ;; oddly, and `:canonical false` would just be :interop -- two more ways to
   ;; say things that already have names.
   :archival          #{:canonical :canonical-order :stringref :shapes :float-policy}
   :canonical         #{:canonical :canonical-order :stringref :shapes :float-policy}
   :canonical-rfc7049 #{:canonical :canonical-order :stringref :shapes :float-policy}})

;; Allocation-free unless there IS a conflict.
;;
;; This runs on EVERY encode and decode. The first version built a sorted-set
;; through `into` whatever the input, so the overwhelmingly common call --
;; `(encode v)` with no opts at all, which cannot conflict with anything --
;; paid for a reduction and a set allocation to discover that. Measured at
;; +11% on small-map encode, where total work is 0.3 us and an allocation is a
;; visible fraction of it.
(defn- check-profile-conflicts! [profile base opts]
  (let [conflicts (when (seq opts)
                    (reduce (fn [acc k]
                              (if (and (contains? opts k)
                                       (not= (get opts k) (get base k)))
                                (conj (or acc []) k)
                                acc))
                            nil
                            (profile-locked profile)))]
    (when (seq conflicts)
      (throw (ex-info (str "boring: " (pr-str (vec conflicts))
                           " cannot be overridden under the " profile
                           " profile -- the profile defines "
                           (pr-str (select-keys base conflicts)))
                      {:type :boring/incompatible-options
                       :profile profile
                       :conflicts (vec conflicts)
                       :profile-values (select-keys base conflicts)})))))

;; The resolved options for `(encode v)` / `(decode bs)`, computed once.
;; nil opts is the dominant call shape and always resolves to the same map.
(def ^:private default-opts
  {:stringref true :float-policy :preserve-width :canonical false
   :canonical-order :rfc8949})

;; Error `:type`s are :boring/-qualified, NOT ::-qualified. `::foo` in this
;; namespace expands to :boring.core/foo, while ClojureScript's core.cljs spells
;; the same conditions :boring/foo -- so a portable `catch` dispatching on
;; :type matched on exactly one platform. Every other error in the codebase
;; already used :boring/.
(declare resolve-opts*)

(defn- resolve-opts
  [opts]
  (if (nil? opts)
    default-opts
    (resolve-opts* opts)))

(defn- resolve-opts*
  [opts]
  (let [profile (get opts :profile :clojure)
        base (or (profile-defaults profile)
                 (throw (ex-info "boring: unknown profile"
                                 {:type :boring/unknown-profile :profile profile})))
        _ (check-profile-conflicts! profile base opts)
        merged (merge base (dissoc opts :profile))]
    merged))

(defn writer
  "Create a reusable Writer. Not thread-safe; one per thread or per loop.

  With `opts`, they are resolved ONCE here and used by every 2-arity
  `encode-into!` / `encode-buffered!` / `write-to!` on this writer. Prefer this
  to passing the same map on every call: `resolve-opts` merges the caller's map
  over the profile defaults, which allocates ~230-300 B per call. On a log
  event that is the difference between 452 and 220 heap bytes, and it bites
  hardest exactly where it is least wanted -- a navigable file needs
  `:stringref false`, so it cannot use the nil-opts fast path.

  Passing opts explicitly to a 3-arity call still wins, and REPLACES these
  rather than merging with them -- one place to look for what a call used."
  (^Writer [] (writer 256))
  (^Writer [^long initial-size] (Writer. initial-size))
  (^Writer [^long initial-size opts]
   (doto (Writer. initial-size)
     (-> .-opts (set! (resolve-opts opts))))))

(defn- writer-opts
  "The writer's pre-resolved options, or the default set."
  [^Writer w]
  (or (.-opts w) default-opts))

(declare configure-reader!)

(defn reader
  "A reusable Reader. `opts` are the same map `decode` takes.

  The one-arity form left every option at its default, and `decode-with` had no
  opts arity at all, so the advertised reusable-reader path could not be given
  a registry, a depth cap or a date type without reaching into the Java fields.
  An API that is documented as the fast path has to accept the same
  configuration as the slow one."
  (^Reader [^bytes bs] (Reader. bs))
  (^Reader [^bytes bs opts] (configure-reader! (Reader. bs) (resolve-opts opts))))

(defn unencodable
  "The default `:encode-fallback` placeholder: a tag-27 frame naming the type
  that could not be encoded, with its `pr-str`.

  Readable by any CBOR implementation, and obviously a placeholder rather than
  a value that might be mistaken for the original."
  [x]
  (data/unknown-record "boring/unencodable"
                       {:type (.getName (class x)) :repr (pr-str x)}))

(defn- encode-fallback-fn
  "`:encode-fallback` is `nil` (throw, the default), `:placeholder` for
  `unencodable`, or a function of the offending value returning a replacement."
  [fb]
  (cond
    (nil? fb) nil
    (= :placeholder fb) unencodable
    (ifn? fb) fb
    :else (throw (ex-info "boring: :encode-fallback must be nil, :placeholder, or a function"
                          {:type :boring/bad-option :value fb}))))

(defn- configure!
  ^Writer [^Writer w opts]
  (set! (.-stringref w) (boolean (:stringref opts)))
  (set! (.-inclMetadata w) (boolean (get opts :incl-metadata? true)))
  (set! (.-preserveWidth w) (= :preserve-width (:float-policy opts)))
  (set! (.-canonical w) (boolean (:canonical opts)))
  (set! (.-legacyCanonicalOrder w) (= :rfc7049 (:canonical-order opts)))
  (set! (.-shapes w) (boolean (:shapes opts)))
  (set! (.-permitReservedSimpleValues w)
        (boolean (:permit-reserved-simple-values opts)))
  (set! (.-maxDepth w) (int (get opts :max-depth 1024)))
  (set! (.-encodeFallback w) (encode-fallback-fn (:encode-fallback opts)))
  ;; ALWAYS set, never `when-let`. A reusable writer kept the previous call's
  ;; registry: after one `encode-into!` with a custom registry, the next call
  ;; on the same writer with default options still used it, so a handler meant
  ;; for one tenant stayed active for the next. Every other field here is set
  ;; unconditionally; this one was the exception, which is how it survived.
  (set! (.-registry w) (or (:registry opts) TagRegistry/EMPTY))
  w)

;; ## User tags
;;
;; Security: the read side dispatches on a tag NUMBER looked up in this
;; registry — there is no path from wire content to class loading. An
;; unregistered tag yields a TaggedValue or an error, never an arbitrary
;; instantiation. Callbacks you register run with your process's privileges.

(defn tag-registry
  "The empty registry. Registries are immutable values — build one with
  `register-tag` / `register-record` and pass it as `:registry`.

  There is deliberately no process-global default. Independent libraries in one
  JVM would otherwise register into the same namespace, and two registrations
  of the same tag would resolve by namespace load order."
  ^TagRegistry [] TagRegistry/EMPTY)

(defn register-tag
  "Teach a registry about `tag` for values of `cls`. Returns a NEW registry.

    write-fn : (fn [value] -> encodable) — result is written as the tag content
    read-fn  : (fn [decoded-content] -> value)

  Registering only one direction is fine; pass nil for the other. Same
  signature and same threading idiom on both platforms:

    (def registry
      (-> (boring/tag-registry)
          (boring/register-tag 40001 java.net.URI str #(java.net.URI. %))))"
  ^TagRegistry [^TagRegistry reg tag cls write-fn read-fn]
  ;; Explicit lets rather than cond->: the threaded intermediate would lose its
  ;; type hint and each .with* call would reflect.
  (let [^TagRegistry r (if (and cls write-fn)
                         (.withWriter reg cls (long tag) write-fn)
                         reg)]
    (if read-fn (.withReader r (long tag) read-fn) r)))

(defn register-record
  "Teach the reader how to rebuild a record from its field map, keyed by the
  name it carries on the wire. Returns a NEW registry.

  **This is the portable form** — it has the same signature and the same
  threading idiom on the JVM and on ClojureScript, so registration code can
  live in a `.cljc` file:

    (defrecord Point [x y])

    (def registry
      (-> (boring/tag-registry)
          (boring/register-record \"my.ns.Point\" map->Point)))

  The wire name is `boring.data/record-type-name` of an instance — on the JVM the
  class name, and ClojureScript munges its own name to match, so a record
  written on either platform reads on the other under one registration.

  Writing needs no registration: a record always encodes with its own type
  name, so the type is never silently flattened to a map. Without a
  registration a record decodes to a `boring.data/UnknownRecord` carrying the
  same name and fields, which re-encodes to identical bytes.

  Security: the reader looks `wire-name` up in this registry. There is no
  `Class.forName` path, so a hostile document cannot cause an arbitrary class
  to be instantiated. See `register-record-class` for the JVM-only
  reflective convenience."
  ^TagRegistry [^TagRegistry reg wire-name map-ctor]
  (.withRecord reg wire-name map-ctor))

(defn register-records
  "Register many record constructors at once, from a map of wire name ->
  `map->Record`. Returns a NEW registry.

  Equivalent to threading `register-record` over the map, but one operation
  rather than N. On the JVM that matters: a registry copies its whole backing
  map per registration, which is the right trade for one built at startup and
  the wrong one for a caller that derives a registry per operation. konserve's
  serializer protocol hands you handlers per read, so the natural fold cost N
  map copies per read and overtook fressian past ~20 handlers.

  Keys are `str`ed, so a map keyed by symbols -- incognito's shape -- works
  directly.

  Still memoise if you derive a registry per operation; this only makes the
  un-memoised path O(1) copies instead of O(N)."
  ^TagRegistry [^TagRegistry reg ctors]
  (.withRecords reg ^java.util.Map ctors))

(defn register-record-class
  "JVM-only convenience: derive both the wire name and the `map->Name`
  constructor from `cls` by reflection. Returns a NEW registry.

    (defrecord Point [x y])

    (def registry
      (-> (boring/tag-registry)
          (boring/register-record-class Point)))

  Equivalent to `register-record` with the class name and map factory looked
  up for you. Not available on ClojureScript, where advanced compilation
  minifies constructor names — use `register-record` there (and in `.cljc`).

  Pass `wire-name` to override the name used on the wire, in both directions."
  (^TagRegistry [reg cls] (register-record-class reg cls (.getName ^Class cls)))
  (^TagRegistry [^TagRegistry reg cls wire-name]
   (let [n (.getName ^Class cls)
         idx (.lastIndexOf n ".")
         pkg (subs n 0 idx)
         sname (subs n (inc idx))
         ;; defrecord's map factory lives in the defining namespace as
         ;; map->Name; the package is the munged namespace.
         ctor (requiring-resolve
               (symbol (str/replace pkg "_" "-") (str "map->" sname)))]
     (when-not ctor
       (throw (ex-info "boring: could not find the map-> constructor for record"
                       {:type :boring/no-record-constructor :class cls})))
     (-> reg
         (.withRecord wire-name @ctor)
         (.withRecordName cls wire-name)))))

(defn- write-root!
  [^Writer w v opts]
  (.reset w)
  (configure! w opts)
  (when (:stringref opts)
    (.writeStringrefNamespace w))
  (.writeValue w v)
  w)

(defn encode
  "Encode `v` to a fresh byte[]: ONE CBOR data item, and never anything else.

  This is the interchange primitive. The bytes are `application/cbor`, any CBOR
  reader in any language consumes them whole, and nothing is appended -- an
  index frame would make the result stop being a single well-formed item (RFC
  8949 3, \"still has bytes remaining after the outermost encoded item\").

  IF YOU ARE STORING RATHER THAN SENDING, you probably want one of:

  - `write-seq!` for many values -- a CBOR sequence (RFC 8742), indexed by
    default, so `boring.nav/items` and `boring.mmap` can seek into it instead
    of scanning. Reaching the last of 200k items: 10.6 ms unindexed, 1-2 us
    indexed.
  - `encode-indexed` for ONE large value you will navigate into. Same idea, and
    the result is a two-item sequence rather than a single item.

  Neither is a better `encode`; they produce a different artifact. The reason
  this is not an option here is that `:index` would silently change what the
  return value IS -- one item becomes a sequence, `application/cbor` becomes
  `application/cbor-seq` -- which is a change of kind, not of setting.

  Note the size trade, which runs the other way: indexing forces
  `:stringref false`, because `boring.nav` cannot resolve a string reference
  from an offset. On a value holding many similar records that costs about 2x.
  Under a compressor it is noise -- zstd reaches 37x where stringref reaches
  2.09x -- so see doc/STORAGE.md before optimising this by hand."
  (^bytes [v] (encode v nil))
  (^bytes [v opts]
   (let [o (resolve-opts opts)]
     (.toByteArray ^Writer (write-root! (writer 256) v o)))))

(defn encode-into!
  "Encode `v` using the reusable writer `w`, returning a byte[]. Reusing `w`
  avoids reallocating the buffer and the stringref table between calls.

  Still allocates the returned array. For a fully allocation-free loop use
  `encode-buffered!` with `buffer`/`write-to!`."
  (^bytes [^Writer w v] (.toByteArray ^Writer (write-root! w v (writer-opts w))))
  (^bytes [^Writer w v opts]
   (.toByteArray ^Writer (write-root! w v (resolve-opts opts)))))

(defn encode-buffered!
  "Encode `v` into `w` and return the byte COUNT, without copying anything out.

  The bytes live in the writer's own buffer — reach them with `buffer` or hand
  them to a stream with `write-to!`. With a reused writer this makes the encode
  loop allocation-free apart from the buffer's own growth, which is the property
  hako reaches for with off-heap segments; on-heap gets there too as long as
  nobody insists on a freshly-allocated byte[] per message.

  The buffer is overwritten by the next encode. Do not retain it."
  (^long [^Writer w v] (.position ^Writer (write-root! w v (writer-opts w))))
  (^long [^Writer w v opts]
   (.position ^Writer (write-root! w v (resolve-opts opts)))))

(defn buffer
  "The writer's internal buffer. Valid bytes are [0, count) where `count` is
  what `encode-buffered!` returned.

  **This array must not outlive the call, and must not cross a thread or async
  boundary.** It is the writer's live buffer, not a copy: the next encode
  overwrites it in place. Handing it to a `go` block, a future, or an async
  write while the calling thread loops on the same writer corrupts the bytes
  mid-flight, with no exception anywhere — you get a bad blob on disk and no
  indication of why. This is the sharpest edge in the API and the one an async
  storage backend hits first.

  If the bytes outlive the call, use `encode-into!`, which copies."
  ^bytes [^Writer w]
  (.buffer w))

(defn trim!
  "Give a reused writer back everything one exceptional job grew. Returns the
  bytes of buffer released.

  Every growth in a writer is one-way — the byte buffer, the stringref symbol
  table, and the index-capture arrays all keep their PEAK size for the life of
  the writer. That is what makes reuse allocation-free, and it is the right
  default. It is also why a pooled writer that once encoded a 200 MB value pins
  a 256 MB buffer forever afterwards while it goes back to 200-byte datoms.

  Explicit rather than automatic, and that is the whole design: shrinking on a
  heuristic would put back exactly the per-message allocation reuse exists to
  remove. Call it after a known-large job — not in a loop.

  Throws `:boring/bad-argument` mid-stream, where the buffer still holds bytes
  that have not reached the sink. A trimmed writer is otherwise a usable
  writer, in whatever state `reset` would leave it."
  ^long [^Writer w]
  (.trim w))

(defn- write-to-resolved!
  "`write-to!` with options already resolved."
  ^long [^Writer w v ^java.io.OutputStream out o]
  (.beginStream w out)
  (try
    (write-root! w v o)
    (.endStream w)
    (catch Throwable t
      ;; Detach WITHOUT flushing: whatever already reached `out` stays there,
      ;; but the tail still in the buffer is half a value and must not follow it.
      (.abortStream w)
      (throw t))))

(defn write-to!
  "Encode `v` into `w` and write its bytes straight to `out`, with no
  intermediate array and in BOUNDED MEMORY.

  The writer's buffer is a chunk, not the whole encoding: when it fills, its
  contents go to `out` and encoding continues. So the memory a value needs is
  the buffer size you chose at `(writer n opts)`, not the size of the value.
  Encoding a 1 GB structure used to grow the buffer to 1 GB -- a second copy of
  something you already hold -- and now costs 64 KB or whatever you asked for.

  Two consequences worth knowing:

  - **Writes are not atomic.** Once a chunk has gone to `out` it cannot be
    recalled, so a value that throws part-way leaves a partial prefix on the
    stream. nippy's `freeze-to-out!` documents the same property for the same
    reason. If you need all-or-nothing, stage into a `ByteArrayOutputStream`.
  - **Large byte and text strings bypass the chunk.** A caller's byte array is
    handed directly to `out`, avoiding both buffer growth and a second memory
    copy. Text first allocates its complete UTF-8 representation, so it halves
    peak memory rather than making it constant. Primitive typed arrays and
    pinned indexed-map keys can still grow the writer buffer.

  Returns the byte count."
  ;; RESOLVED ONCE, by whichever arity was called -- the 3-arity's options come
  ;; from the writer and `writer-opts` has already resolved them and stripped
  ;; `:profile`. Delegating one arity to the other would re-resolve and throw
  ;; `:boring/incompatible-options` for the sorting profiles, which is exactly
  ;; the bug `write-seq!`'s 3-arity carried.
  ([^Writer w v ^java.io.OutputStream out]
   (write-to-resolved! w v out (writer-opts w)))
  ([^Writer w v ^java.io.OutputStream out opts]
   (write-to-resolved! w v out (resolve-opts opts))))

(defn write-to-buffer!
  "Encode `v` into `w` and copy its bytes into `bb`, returning the count.

  The NIO sibling of `write-to!`, for a channel, a socket or anything else that
  wants a `ByteBuffer` rather than an `OutputStream`. Writes at the buffer's
  current position and advances it; throws `BufferOverflowException` if `v` does
  not fit, which is the caller's cue to flush.

  This exists because hand-rolling it is a trap rather than because it is
  clever. The two-line version --

      (let [n (encode-buffered! w v)] (.put bb (buffer w) 0 n))

  -- goes REFLECTIVE unless every one of `bb`, the array and the int is hinted,
  and reflection here costs 22 KB per call against this function's ~0. Nothing
  fails; throughput just quietly collapses, and an allocation profile is the
  only place it shows up. Measured on a 40-byte value: 0 bytes/op into a heap
  buffer, 57 into a direct one, and 11 ns for the copy itself (312 ns/op
  against 301 for encode alone).

  There is no zero-copy version of this and there does not need to be. The copy
  is a bulk `memcpy` of a few dozen bytes; encoding is 27x its cost."
  (^long [^Writer w v ^ByteBuffer bb]
   (let [n (encode-buffered! w v)]
     (.put bb ^bytes (.buffer w) 0 (int n))
     n))
  (^long [^Writer w v ^ByteBuffer bb opts]
   (let [n (encode-buffered! w v opts)]
     (.put bb ^bytes (.buffer w) 0 (int n))
     n)))

(defmacro ^:private with-decode-errors
  "Reading past the end of the buffer surfaces as an ArrayIndexOutOfBounds from
  deep in the decode loop — the exact thing datahike's dump requirements ask us
  not to do. Converting at the boundary keeps the hot path free of per-read
  bounds checks while still giving callers a typed error."
  [& body]
  `(try
     ~@body
     (catch IndexOutOfBoundsException e#
       (throw (ex-info "boring: input ended mid-value (truncated or malformed)"
                       {:type :boring/truncated-input} e#)))))

(defn- max-items-opt
  "`:max-items`, validated. 0 means unlimited, which is the default.

  It used to go straight through `long`, so `-1` silently DISABLED the bound
  (the reader tests `maxItems > 0`), `1.5` truncated to 1, a bignum raised a raw
  IllegalArgumentException and a string a raw ClassCastException -- two untyped
  failures out of the option parser for the only heap-amplification control
  doc/SECURITY.md names."
  ^long [opts]
  (let [v (get opts :max-items 0)]
    (when-not (and (integer? v) (not (neg? v)) (<= v Long/MAX_VALUE))
      (throw (ex-info (str "boring: :max-items must be a non-negative integer "
                           "(0 means unlimited), got " (pr-str v))
                      {:type :boring/bad-option :option :max-items :value v})))
    (long v)))

(defn ^:no-doc configure-reader!
  "Apply decode options to a Reader. Public only so `boring.nav` can apply the
  SAME options its docstrings promise -- it realises values through this reader,
  so a differently-configured one would decode differently from `decode`.
  Not part of the supported API."
  ^Reader [^Reader r opts]
  (set! (.-tolerateUnknownTags r) (boolean (get opts :tolerate-unknown-tags true)))
  (set! (.-instantAsDate r) (not= :instant (get opts :instant-type :date)))
  (set! (.-fullDateAsSqlDate r) (= :sql-date (get opts :date-type :local-date)))
  (set! (.-maxDepth r) (int (get opts :max-depth 1024)))
  ;; 0 = unlimited, which is the default. See Reader.maxItems for why the budget
  ;; counts ITEMS rather than bytes.
  (set! (.-maxItems r) (max-items-opt opts))
  (set! (.-validateUtf8 r) (boolean (get opts :validate-utf8 true)))
  ;; WIRED, having been documented and then never applied. doc/SECURITY.md
  ;; describes `:check-duplicate-keys false` as the way to turn duplicate
  ;; rejection off; the Java field existed and defaulted to true, but no entry
  ;; point ever set it, so the option was silently ignored and a duplicate map
  ;; still threw with it set. A documented safety control that does nothing is
  ;; worse than one that does not exist.
  (set! (.-checkDuplicateKeys r) (boolean (get opts :check-duplicate-keys true)))
  (set! (.-autoConstructRecords r)
        (boolean (get opts :auto-construct-records? false)))
  ;; ALWAYS set, never `when-let` -- see `configure!`.
  (set! (.-registry r) (or (:registry opts) TagRegistry/EMPTY))
  r)

(defn decode
  "Decode the first CBOR item in `bs`.

  Errors are `ex-info` carrying a `:type` keyword — `:boring/truncated-input`,
  `:boring/invalid-utf8`, `:boring/bad-count`, `:boring/max-depth-exceeded`,
  `:boring/duplicate-map-key`, `:boring/unregistered-tag`, and so on.

  `:tolerate-unknown-tags` (default true) makes an unregistered tag surface as
  a `boring.data/TaggedValue`; false makes it an error, which is the closed-reader
  behaviour datahike's dump requirements ask for."
  ([^bytes bs] (decode bs nil))
  ([^bytes bs opts]
   (with-decode-errors (.read (configure-reader! (Reader. bs) opts)))))

(defn decode-with
  "Decode using a reusable Reader.

  With `opts`, every option is re-applied on this call. Without them the
  reader keeps whatever it was last configured with, which is what makes the
  two-arity form fast and also what makes it a state-leak hazard across
  tenants -- pass opts unless the reader is yours alone."
  ([^Reader r ^bytes bs] (.reset r bs) (with-decode-errors (.read r)))
  ([^Reader r ^bytes bs opts]
   (.reset r bs)
   (configure-reader! r (resolve-opts opts))
   (with-decode-errors (.read r))))

;; ## Streaming (datahike's dump requirements)
;;
;; A chunk is a CBOR sequence (RFC 8742): top-level items concatenated with no
;; framing. Each item carries its own stringref namespace, so it is independently
;; decodable — a truncated chunk loses only its final item, and memory is bounded
;; by the largest item rather than by the file.
;;
;; The alternative — one namespace shared across the whole chunk — compresses
;; better when attribute keywords repeat across records, but every item then
;; depends on everything before it, so the chunk must be read from the start and
;; cannot be split. Not implemented; chunk size is the knob for that tradeoff.

(declare seal-index! seal-index-with! scan-index scan-into! nodes->index
         build-index write-seq-resolved!)

(defn encode-indexed
  "Encode `v` and seal an index onto it, returning a byte[].

  The result is a two-item CBOR sequence -- the value, then the index -- so
  `decode` still returns the value and any CBOR reader consumes both. Pass it
  to `boring.nav/source` and lookups inside large containers become jumps.

  `:index` is the stride (default 16) and `:index-min` the smallest container
  worth a node (default 16). Sorted map keys -- `:canonical` or `:archival` --
  additionally allow binary search; without them a lookup still jumps anchor to
  anchor rather than entry to entry.

  `:stringref false` IS FORCED, because the sentence above would otherwise be
  false by default. The default profile writes stringref, and `boring.nav`
  categorically refuses a stringref document -- a stringref is an index into a
  table built from every preceding string, which a cursor holding only an offset
  cannot resolve. So `(nav/source (encode-indexed v))` threw
  `:boring/stringref-not-navigable` on the very shape this function's docstring
  recommends. Every test passed `{:stringref false}` or a sorting profile, so the
  advertised default was the one path never exercised.

  An index exists to be navigated; producing one that cannot be is not a
  trade-off worth offering. Pass `:stringref true` explicitly and it is honoured
  -- you simply get an index nothing can use."
  (^bytes [v] (encode-indexed v nil))
  (^bytes [v opts]
   (let [opts (if (contains? opts :stringref) opts (assoc opts :stringref false))
         ^bytes body (encode v opts)
         idx (build-index body opts)]
     (if-not idx
       body
       (let [w (writer (max 1024 (alength body)) opts)
             out (java.io.ByteArrayOutputStream. (+ (alength body) 256))]
         (.write out body)
         (seal-index! w out idx (alength body) (resolve-opts opts))
         (.toByteArray out))))))

(defn- index-opt
  "`:index` / `:index-min`, validated. Both were coerced with `long` and then
  narrowed to a Java int with no domain check, so `:index 1.5` silently became
  stride 1, `:index -1` silently turned indexing OFF although only 0 is
  documented as the off switch, `:index 2147483648` escaped as a raw
  ArithmeticException, and a non-numeric value escaped from host coercion."
  ^long [opts k default]
  (let [v (get opts k default)]
    (when-not (and (integer? v) (not (neg? v)) (<= v Integer/MAX_VALUE))
      (throw (ex-info (str "boring: " k " must be a non-negative integer no greater "
                           "than " Integer/MAX_VALUE
                           (when (= k :index) " (0 turns indexing off)")
                           ", got " (pr-str v))
                      {:type :boring/bad-option :option k :value v})))
    (long v)))

(def ^:const default-index-stride
  "Stride `write-seq!` indexes at unless told otherwise. See its docstring for
  why a sequence is indexed by default and a single `encode`d value never is."
  16)

(defn write-seq!
  "Encode each value in `values` to `out` as consecutive top-level CBOR items.
  Returns the number of bytes written. Bounded memory for the DATA: one value at
  a time, streamed, with byte and text payloads larger than the buffer going
  straight to `out` rather than growing it.

  NOT constant overall, and the difference matters at scale. Index capture is
  O(anchors + indexed containers) and is held until the frame is sealed; the
  frame itself is buffered while it is built; and `:canonical` stages every map
  key and set element to sort them. An earlier version of this docstring said
  `constant memory` flatly, which was false for the default indexed path. See
  doc/STORAGE.md.

  What the writer grew, it KEEPS -- buffer, symbol table and index arrays all
  hold their peak for the life of the writer, which is what makes reuse
  allocation-free. `trim!` is the way back after one exceptional job.

  A sequence cannot be indexed past `Integer/MAX_VALUE` items: the frame carries
  item counts in an int32 array, and widening that would change the wire format
  of every file for a limit no in-memory container can reach anyway. The writer
  refuses AT that item -- `:boring/index-too-large` -- rather than wrapping the
  count and sealing a footer that describes the wrong thing. Write such a
  sequence with `:index 0`.

  `:index N` seals the sequence with an offset index covering every Nth item,
  so `boring.nav/items` can jump to an item instead of skipping to it -- O(1)
  rather than O(n). N is the stride, and it is the size/speed knob: a lookup
  scans up to N-1 items, while every entry costs one to four bytes depending on
  how far apart the anchors fall, since offsets are stored as deltas in the
  narrowest typed array that holds them. On 200k ~37-byte items, stride 1 costs
  2.7% of the file and stride 16 costs 0.34% for roughly 14x the seek.

  IT DEFAULTS TO 16. `:index 0` turns it off. A sequence is the shape people
  memory-map, and an unindexed one cannot be seeked into at all -- reaching the
  last of 200k items costs 10.6 ms and faults in the whole file, against 1-2 us
  at stride 16. Since offsets are only knowable after the fact, a file written
  without an index can never gain one without a rewrite, so the default has to
  be the useful one or the feature is unreachable for anyone who did not plan
  ahead. It is close to free: +4.3% write time, +0.06% size on 50k ~200-byte
  records, and -1.5% from the `:stringref false` it forces, netting SMALLER.

  Unlike `encode`, this changes nothing about what the bytes ARE. A sequence is
  already `application/cbor-seq` (RFC 8742), where extra items are expected;
  appending a frame to a single `encode`d value would make it stop being a
  single well-formed CBOR item, which is why that is not done and will not be.

  `:index-min` (default 16) is a SEPARATE knob and gates CONTAINER nodes, not
  the frame: a map or array with fewer than that many entries gets no node of
  its own. Raising it to a number no container reaches gives an index over the
  ITEMS ONLY -- the right shape for a log you seek into but do not navigate
  within. Measured on 20 items of 40-entry maps: 6873 bytes at the default,
  6611 with `:index-min 1000`.

  It DOES decide whether a short sequence gets a frame at all. The sequence is
  itself a node, so the same threshold applies to it: fewer than `:index-min`
  items and no container clearing the bar means no frame and no ~37 bytes. This
  paragraph used to promise the opposite -- \"always costs ~37 bytes\" -- which
  stopped being true when `encode-indexed` and `write-seq!` were made to agree.

  `:index` FORCES `:stringref false`. `boring.nav` cannot resolve a string
  reference from an offset alone -- a stringref indexes a table built from every
  preceding string -- so the two options describe incompatible documents, and
  honouring both produced an index that nothing could read. Passing
  `:stringref true` alongside `:index` throws `:boring/incompatible-options`
  rather than silently dropping one. This costs nothing on a sequence: the
  stringref table resets per top-level item, so on 50k ~200-byte records
  stringref is a 1.5% size LOSS, where on the same data as one large value it is
  a 2.1x win. Sequences are the shape that wants an index and the shape that
  does not want stringref, which is a happier coincidence than it sounds.

  The index goes at the END, which is what makes it compatible with appending:
  offsets are only known after the items are written, so a leading index would
  mean buffering the whole sequence in memory. ZIP's central directory and
  Parquet's footer are at the end for the same reason. See `seal-index!` if you
  are writing items incrementally rather than from a seq."
  ;; The 3-arity uses the WRITER'S options, like `write-to!` and
  ;; `encode-into!`. It resolved nil instead, so a writer built
  ;; `(writer n {:stringref false})` -- the setting a navigable file requires --
  ;; silently emitted stringref output through this one entry point, and
  ;; `boring.nav` then refused to read the result.
  ;;
  ;; RESOLVED ONCE, by whichever arity was called. Delegating the 3-arity to the
  ;; 4-arity re-resolved options `writer-opts` had ALREADY resolved and stripped
  ;; `:profile` from, so `resolve-opts` saw `:canonical` without the profile
  ;; that licenses it and threw `:boring/incompatible-options` for `:archival`,
  ;; `:canonical` and `:canonical-rfc7049` -- three of five profiles, on a
  ;; public entry point. `encode-into!` passes `(writer-opts w)` straight to
  ;; `write-root!` without re-resolving; this now follows that model rather than
  ;; merely citing it.
  ;; INDEXING FORCES `:stringref false`, for the reason spelled out on
  ;; `encode-indexed`: `boring.nav` categorically refuses a stringref document,
  ;; because a stringref is an index into a table built from every preceding
  ;; string and a cursor holding only an offset cannot resolve it.
  ;;
  ;; Without this, `(write-seq! w items out {:index 16})` under the default
  ;; profile built the index, wrote the frame, charged for both -- and produced
  ;; a file `nav/items` then rejected with `:boring/stringref-not-navigable`.
  ;; The index was unreachable by construction. `encode-indexed` had already
  ;; been fixed for exactly this; this entry point had not.
  ;;
  ;; An EXPLICIT `:stringref true` alongside `:index` throws rather than being
  ;; overridden in silence -- the two options cannot both be honoured, so the
  ;; caller has to choose. The 3-arity cannot distinguish an explicit `true`
  ;; from the profile default (it sees already-resolved options), so there it
  ;; is forced; that is why the 4-arity is the one that can complain.
  (^long [^Writer w values ^java.io.OutputStream out]
   (let [o (writer-opts w)
         stride (index-opt o :index default-index-stride)]
     (write-seq-resolved! w values out
                          (cond-> o (pos? (long stride)) (assoc :stringref false))
                          stride (index-opt o :index-min 16))))
  (^long [^Writer w values ^java.io.OutputStream out opts]
   (let [stride (index-opt opts :index default-index-stride)]
     (when (and (pos? (long stride)) (true? (:stringref opts)))
       (throw (ex-info (str "boring: :stringref true cannot be combined with :index -- "
                            "boring.nav cannot resolve string references from an offset, "
                            "so the index would be unusable. Drop one of the two.")
                       {:type :boring/incompatible-options
                        :stringref true :index stride})))
     (write-seq-resolved! w values out
                          (cond-> (resolve-opts opts)
                            (pos? (long stride)) (assoc :stringref false))
                          stride (index-opt opts :index-min 16)))))

(defn- write-seq-resolved!
  "`write-seq!` with options already resolved. See the note on its 3-arity."
  [^Writer w values ^java.io.OutputStream out o stride min-entries]
  (let [stride (long stride)
        min-entries (long min-entries)
        indexing? (pos? stride)
         ;; Container nodes are captured BY THE WRITER as it encodes, not by
         ;; walking the bytes afterwards. The writer already knows the offset it
         ;; is about to write to and a container's entry count before emitting
         ;; it, so the nodes fall out of encoding. Walking instead cannot beat
         ;; ~31% of encode time however it is written, because CBOR containers
         ;; are element-counted and stepping over a subtree means walking it.
         ;;
         ;; `build-index` still exists and is still the reference implementation
         ;; -- it is the only way to index bytes somebody else wrote, and
         ;; `boring.writer-index-test` pins the two against each other.
         ;; `setIndex` starts a fresh capture; `idxBase` moves the base between
         ;; items without discarding what the earlier ones recorded.
        _ (when indexing? (.setIndex ^Writer w (int stride) (int min-entries) 0))
        ;; STREAMED, not encode-then-copy. The writer holds one chunk rather
        ;; than one whole item, so an item larger than the buffer no longer
        ;; grows it -- which is the difference between a sequence of ordinary
        ;; records and a sequence whose items are themselves large.
        ;;
        ;; `idxBase` is gone from this path. It existed to tell the writer where
        ;; the current item starts so recorded offsets came out file-relative;
        ;; `flushed` now tracks exactly that and spans items, so setting both
        ;; would double-count. `idxOffset` adds the two and relies on only one
        ;; being non-zero.
        _ (.beginStream ^Writer w out)
        total (try
                (doseq [v values]
                  ;; The item's own offset, taken BEFORE `write-root!` resets
                  ;; the writer. `totalWritten` is flushed + the unflushed tail
                  ;; of the previous item, which is exactly where this one
                  ;; begins.
                  (when indexing? (.idxItem ^Writer w (.totalWritten ^Writer w)))
                  (write-root! w v o))
                (.endStream ^Writer w)
                (catch Throwable t
                   ;; Capture off on the way out, or a writer whose encode threw
                   ;; stays in capture mode with a stale base -- and every later
                   ;; `encode-into!` or unindexed `write-seq!` on that
                   ;; (deliberately long-lived) writer keeps allocating and
                   ;; retaining a node per container, invisibly and forever.
                   ;;
                   ;; `abortStream` rather than `endStream`: bytes already sent
                   ;; cannot be recalled, but the half-item still in the buffer
                   ;; must not follow them onto the stream.
                  (.abortStream ^Writer w)
                  (when indexing? (.setIndex ^Writer w (int 0) (int 0) 0))
                  (throw t)))]
    (if (and indexing?
             ;; NO FRAME WHEN THERE IS NOTHING TO INDEX. `:index-min` gates
             ;; container nodes; the sequence node is a container too (at the
             ;; sentinel offset -1), so the same threshold decides whether it is
             ;; worth one. Without this a three-item sequence paid ~46 bytes for
             ;; an index that could only ever point at its own three items --
             ;; and since `write-seq!` indexes by DEFAULT, every small sequence
             ;; anyone wrote would have carried it.
             ;;
             ;; `encode-indexed` has always behaved this way: `build-index`
             ;; returns nil when no node clears the threshold, and the body is
             ;; returned unsealed. This makes the two agree.
             (or (>= (long (.idxItemTotal ^Writer w)) min-entries)
                 (pos? (alength ^longs (.idxContainers ^Writer w)))))
      (let [^longs cs (.idxContainers ^Writer w)
            ^ints ns (.idxCounts ^Writer w)
            sl (.idxSlots ^Writer w)
            so (.idxSorted ^Writer w)
            m (alength cs)
             ;; The sequence itself is a node at the sentinel offset -1: it has
             ;; no container header on the wire, but it behaves like one, and a
             ;; sentinel keeps a single uniform node list rather than two. It
             ;; sorts first, and the writer's own nodes are already ascending --
             ;; it claims each node's slot when it writes the container's head,
             ;; and a pre-order walk visits containers in increasing offset --
             ;; so prepending is all the ordering that is needed.
            ;; OFFSETS ARE 64-BIT in memory; `seal-index!` narrows them on the
            ;; wire when they fit, so a file under 2 GiB is byte-identical to
            ;; what it was before the widening.
            containers (long-array (inc m))
            counts (int-array (inc m))]
        (aset containers 0 (long -1))
        (aset counts 0 (int (.idxItemTotal ^Writer w)))
        (System/arraycopy cs 0 containers 1 m)
        (System/arraycopy ns 0 counts 1 m)
        (let [items (.idxItemOffsets ^Writer w)]
          (.setIndex ^Writer w (int 0) (int 0) 0)   ; capture off again
          (+ total
             (long (seal-index!
                    w out
                    {:stride stride
                     :containers containers
                     :counts counts
                     :slots (into [items] sl)
                     :sorted (into [false] so)}
                    total
                    o)))))
      ;; Capture must go off on THIS path too. It is switched on above whenever
      ;; `indexing?`, and the skip branch is now reachable with it on -- a
      ;; writer left in capture mode keeps allocating and retaining a node per
      ;; container on every later call, invisibly and forever. Same failure the
      ;; catch clause above exists to prevent.
      (do (when indexing? (.setIndex ^Writer w (int 0) (int 0) 0))
          total))))

(defn- write-indexed-resolved!
  "`write-indexed!` with options already resolved.

  No `^long` return hint: Clojure only supports primitive fns up to four args
  and this takes six."
  [^Writer w v ^java.io.OutputStream out o stride min-entries]
  (let [stride (long stride)
        indexing? (pos? stride)
        _ (when indexing? (.setIndex ^Writer w (int stride) (int min-entries) 0))
        _ (.beginStream ^Writer w out)
        total (try
                (write-root! w v o)
                (.endStream ^Writer w)
                (catch Throwable t
                  (.abortStream ^Writer w)
                  (when indexing? (.setIndex ^Writer w (int 0) (int 0) 0))
                  (throw t)))]
    (if (and indexing? (pos? (alength ^longs (.idxContainers ^Writer w))))
      ;; NO SENTINEL NODE HERE, unlike `write-seq!`. That node stands for the
      ;; sequence itself so `nav/items` can seek between top-level items; this
      ;; writes ONE value, which `nav/source` navigates into. Adding a node for
      ;; a sequence of one would claim a shape the file does not have.
      (let [containers (.idxContainers ^Writer w)
            counts (.idxCounts ^Writer w)
            sl (.idxSlots ^Writer w)
            so (.idxSorted ^Writer w)]
        (.setIndex ^Writer w (int 0) (int 0) 0)
        (+ total
           (long (seal-index! w out
                              {:stride stride :containers containers :counts counts
                               :slots (vec sl) :sorted (vec so)}
                              total o))))
      (do (when indexing? (.setIndex ^Writer w (int 0) (int 0) 0))
          total))))

(defn write-indexed!
  "Stream ONE value to `out` and seal it with a container index, in bounded
  memory. Returns the byte count.

  The single-value counterpart of `write-seq!`, and the streaming counterpart
  of `encode-indexed`. Where `encode-indexed` builds the whole byte array and
  then WALKS it to derive the index -- two full copies of the document in
  memory, plus a second pass over every byte -- this captures the index nodes
  as the writer emits them, so the DATA streams in bounded memory.

  Index capture is not bounded: it is O(anchors + indexed containers), held
  until the frame is sealed, and the frame is buffered while it is built. On a
  100 000-element vector at `{:index 1 :index-min 1}` that is real -- a 64-byte
  writer grows to about 131 KB during sealing even though the body streamed.
  An earlier version of this said it `never holds more than one chunk`, which
  was false.

  The result is a two-item CBOR sequence: the value, then the index frame. So
  `decode` still returns the value, `decode-seq` hides the frame, and a foreign
  reader consumes both. Hand it to `boring.nav/source` and lookups inside large
  containers become jumps.

  Same rules as `write-seq!`: `:index` is the stride (default 16), `:index-min`
  the smallest container worth a node (default 16), `:stringref false` is forced
  because `boring.nav` cannot resolve a string reference from an offset, and an
  explicit `:stringref true` alongside `:index` throws rather than one silently
  winning. No frame is written when no container clears the threshold.

  Note the size trade: on a value holding many similar records, giving up
  stringref costs about 2x. See doc/STORAGE.md -- under a compressor it is
  noise, but uncompressed it is not."
  (^long [^Writer w v ^java.io.OutputStream out]
   (let [o (writer-opts w)
         stride (index-opt o :index default-index-stride)]
     (write-indexed-resolved! w v out
                              (cond-> o (pos? (long stride)) (assoc :stringref false))
                              stride (index-opt o :index-min 16))))
  (^long [^Writer w v ^java.io.OutputStream out opts]
   (let [stride (index-opt opts :index default-index-stride)]
     (when (and (pos? (long stride)) (true? (:stringref opts)))
       (throw (ex-info (str "boring: :stringref true cannot be combined with :index -- "
                            "boring.nav cannot resolve string references from an offset, "
                            "so the index would be unusable. Drop one of the two.")
                       {:type :boring/incompatible-options
                        :stringref true :index stride})))
     (write-indexed-resolved! w v out
                              (cond-> (resolve-opts opts)
                                (pos? (long stride)) (assoc :stringref false))
                              stride (index-opt opts :index-min 16)))))

(def ^:const index-name
  "Tag-27 type name for a sequence/container index. See doc/SHAPES.md.

  A NAME under tag 27, not a tag number of its own. Tag 27 is CBOR's registered
  extension point for exactly this -- \"serialised language-independent object
  with type name and constructor arguments\" -- and boring already reserves
  slash-bearing names under it (`clojure/sorted-map`, `java/period`; see
  doc/INTEROP.md).

  This started as tag 39651 and was moved, because the index appears exactly
  ONCE per file: measured, a name costs 14 bytes, which is 0.05% of a 28 KB
  file. Against that it removes a registration obligation entirely, is
  self-describing to a foreign reader (`cbor2` sees the string, not an
  unregistered number), and narrows false-positive detection -- a stray file
  must now end in the right shape AND point at tag 27 AND carry this name.

  Tag 39649, shaped arrays, keeps its own number for the opposite reason: it
  occurs PER ARRAY, and a name would add up to 35% on documents with many small
  tables -- on a feature whose entire purpose is to shrink them."
  "boring/index")

(defn- index-frame?
  "True for the tag-27 frame `seal-index!` appends, at file offset `start`.

  Both fallback shapes count: the payload is an array, so it decodes to a
  `TaggedLiteral` rather than an `UnknownRecord`, but `frame-name` reads either.

  AUTHENTICITY, not just the name. This tested the name alone, so ANY final
  tag-27 item called `boring/index` was silently erased from `decode-seq` --
  including a malformed one, and including one somebody put there as data. A
  name collision should not delete a logical item from a sequence.

  The check mirrors the cheap half of what `boring.nav/read-index` does: the
  payload must be a six-element array whose last element is the 8-byte
  back-pointer, and that pointer must equal the offset the frame actually
  starts at -- which it does by construction, since it doubles as the length of
  the data section preceding it. `start` of -1 means the caller cannot supply
  an offset (the streaming decoder, whose positions are buffer-relative across
  refills); the shape check still applies."
  [v ^long start]
  (and (data/tagged-frame? v)
       (= index-name (data/frame-name v))
       (let [p (data/frame-payload v)]
         (and (sequential? p)
              (= 6 (count p))
              (let [ptr (nth (vec p) 5)]
                (and (bytes? ptr)
                     (= 8 (alength ^bytes ptr))
                     (or (neg? start)
                         (= start
                            (areduce ^bytes ptr i acc 0
                                     (+ (bit-shift-left acc 8)
                                        (bit-and (aget ^bytes ptr i) 0xFF)))))))))))

(declare index-walk index-walk*)

(defn- index-walk
  "Walk the value at `p`, returning where it ENDS, and accumulating index nodes
  into `acc` on the way back up.

  Returning the end offset is the whole trick. The previous version called
  `skipFrom` on each entry, and `skipFrom` is O(subtree) -- so every level
  re-walked everything beneath it and the scan was O(n^2) in nesting depth,
  measured at 486 ns/byte against a plain skip's 1.3-2. Here each byte is
  visited once: the descent that finds a container's end also collects its
  children's offsets, so the node is a by-product of a walk that had to happen.

  Tags are DESCENDED THROUGH, indexing their payload. The tag is a marker; the
  structure beneath it is ordinary CBOR, and much of what boring emits is
  tag-wrapped -- a set is tag 258 around an array, a record is tag 27 around
  [name, map], a shaped array is tag 39649 around [keys, rows]. Skipping them
  would leave exactly those uncovered.

  It is also close to free: `skipFrom` on a tag walks the whole subtree anyway,
  so descending touches the same bytes and differs only in allocating nodes for
  the large containers it finds. Whether `boring.nav` can USE a node inside a
  tag is a separate question -- it realises tags opaquely today, because a
  tag's reader is an arbitrary function -- but the offsets describe the wire,
  and the wire is what they describe accurately either way."
  [^Reader r p stride min-entries base ^java.util.ArrayList acc]
  (index-walk* r p stride min-entries base acc 0))

(defn- index-walk*
  [^Reader r p stride min-entries base ^java.util.ArrayList acc depth]
  ;; CONTAINER nesting is bounded too, not only the tag chain. `build-index` is
  ;; public and documented for "a file somebody else wrote", and ~1.2 KB of
  ;; `81 81 81 ...` was a StackOverflowError where `decode` on the same bytes
  ;; gives :boring/max-depth-exceeded. These positional reads never touch the
  ;; Reader's own depth, so its limit does not reach here.
  ;; 200, well below where the stack actually gives out, and NOT the decoder's
  ;; 1024. Two lessons are baked into that number.
  ;;
  ;; This is a Clojure recursion; its frames are fat enough that an isolated
  ;; measurement put the limit between 600 and 800 on a default -Xss. A bound of
  ;; 512 looked safe against that and was FLAKY IN THE SUITE -- roughly one run
  ;; in three -- because the test runner and preceding tests have already spent
  ;; stack, so the real headroom is smaller than any isolated measurement of it.
  ;; A bound calibrated against the best case is not a bound.
  ;;
  ;; The StackOverflowError catch in `build-index` is a backstop, not the
  ;; mechanism: catching one is unreliable, since the handler itself needs stack
  ;; to construct the exception and can overflow again. The deterministic check
  ;; has to fire first, so it is set where it comfortably does.
  ;;
  ;; A document nested deeper than this therefore DECODES but cannot be INDEXED.
  ;; Said in `build-index`'s docstring rather than left to be discovered.
  (when (> (long depth) 200)
    (throw (ex-info (str "boring: nesting deeper than the index walk's bound (200)."
                         " This document can be decoded but not indexed.")
                    {:type :boring/max-depth-exceeded :max-depth 200})))
  (let [p (long p) stride (long stride) min-entries (long min-entries) base (long base)
        ;; A CHAIN OF TAGS IS CONSUMED ITERATIVELY. Recursing once per tag
        ;; overflowed the stack on `c0 c0 c0 ... 00` -- legal CBOR, and reachable
        ;; from the public `build-index` on bytes somebody else wrote, so a
        ;; 20 000-byte input was a StackOverflowError rather than a typed error.
        ;; The comment this replaces claimed the recursion was bounded by the
        ;; decoder's maxDepth; it is not, because these positional reads do not
        ;; touch the Reader's depth at all. `Reader.skipStructural` had the same
        ;; defect and was fixed the same way.
        ;;
        ;; Collapsing the chain is equivalent to recursing through it: a tag's
        ;; extent IS its payload's extent, so the payload's end is the value's.
        p (long (loop [q p]
                  (if (= 6 (.majorAt r q)) (recur (long (.headEndAt r q))) q)))
        mj (.majorAt r p)]
    (if-not (or (= mj 4) (= mj 5))
      (.skipFrom r p)
      (let [n (.headArgAt r p)
            map? (= mj 5)]
        (if (neg? n)
          (.skipFrom r p)                       ; indefinite length: not indexable
            ;; Only containers we will KEEP get an array, and it holds one entry
            ;; per ANCHOR rather than one per entry. The old version allocated
            ;; `(int-array n)` for every container before testing `min-entries`,
            ;; then copied every stride-th element into a second array -- so a
            ;; document of small maps allocated one throwaway array per map and
            ;; threw it away, which is why raising :index-min barely helped.
          (let [keep? (>= n min-entries)
                m (if keep?
                      ;; An empty container needs no anchors. The `(max n 1)`
                      ;; this replaces yielded ONE for n=0, and the loop never
                      ;; wrote it, leaving a phantom offset pointing at the
                      ;; document's start. Same defect as Writer.anchorCount.
                    (cond (<= n 0) 0
                          (= stride 1) n
                          :else (inc (quot (dec n) stride)))
                    0)
                kept (when keep? (long-array m))
                  ;; EVERY adjacent key pair decides `sorted`, not the anchors.
                  ;; Comparing the anchor sample was unsound and returned WRONG
                  ;; ANSWERS: `sorted` licenses a binary search that then scans
                  ;; only the stride it lands in, which is valid only if the
                  ;; whole container is ordered. At the default stride a 20-key
                  ;; map has two anchors, so an unordered map was marked sorted
                  ;; about half the time and present keys came back nil.
                srt (when (and keep? map?) (doto (boolean-array 1) (aset 0 true)))
                end (loop [i 0 q (long (.headEndAt r p)) prev -1]
                      (if (= i n)
                        q
                        (do (when (and keep? (zero? (rem i stride)))
                              (aset ^longs kept (quot i stride) (long q)))
                            (when (and srt (aget ^booleans srt 0) (>= (long prev) 0)
                                       (>= (.compareItemsAt r (long prev) q) 0))
                              (aset ^booleans srt 0 false))
                            ;; index-walk*, CARRYING THE DEPTH. Calling the
                            ;; 6-arg wrapper here reset it to 0 at every level,
                            ;; so the bound never fired and the only thing
                            ;; stopping a deep document was the
                            ;; StackOverflowError catch -- which is exactly the
                            ;; unreliable path the bound exists to front-run,
                            ;; and which made the test flaky rather than the
                            ;; code safe.
                            (recur (inc i)
                                   (long (index-walk*
                                          r
                                          (if map?
                                            (long (index-walk* r q stride min-entries base acc
                                                               (inc (long depth))))
                                            q)
                                          stride min-entries base acc (inc (long depth))))
                                   q))))]
            (when keep?
                ;; Decided on RAW offsets, before `base` is folded in: the
                ;; Reader is positioned over this item's own buffer.
              (let [sorted (boolean (and srt (aget ^booleans srt 0)))]
                (when (pos? base)
                  (dotimes [k (alength ^ints kept)]
                    (aset ^ints kept k (int (+ base (aget ^ints kept k))))))
                (.add acc [(int (+ base p)) (int n) kept sorted])))
            end))))))

(defn- scan-into!
  "Append index nodes for [start, end) onto `acc`, and return nothing.

  Split out when `write-seq!` still called it once per item -- it no longer
  walks at all, the writer captures the index while encoding, and the only
  caller now is `scan-index` via `build-index`, always with `base` 0. The
  reasoning below is kept because it is why the split exists.

  Separate from `scan-index` because that caller invoked it once PER ITEM, and
  the result-shaping `scan-index` does -- a sort, four sequence traversals and a
  map -- is per-sequence work. Doing it per item allocated an ArrayList, two
  lazy seqs, two vectors and a four-entry map for every item in the log, almost
  always to describe zero nodes, since a typical record's containers sit below
  `:index-min`. That scaffolding, not the byte walk, was the bulk of indexing's
  write cost: `skipFrom` and `skipValue` together were 2% of the profile."
  [^Reader r start end stride min-entries base ^java.util.ArrayList acc]
  (loop [p (long start)]
    (when (< p (long end))
      (recur (long (index-walk r p stride min-entries base acc)))))
  nil)

(defn- nodes->index
  "Shape accumulated nodes into the map `seal-index!` takes. Once per sequence."
  [^java.util.ArrayList acc]
  (let [idx (vec (sort-by first acc))]
    {:containers (long-array (map #(nth % 0) idx))
     :counts (int-array (map #(nth % 1) idx))
     :slots (mapv #(nth % 2) idx)
     :sorted (mapv #(nth % 3) idx)}))

(defn- scan-index
  "Index nodes for every container of at least `min-entries` entries in
  [start, end).

  A node is the byte offsets of a container's entries. With those, reaching
  entry i is a jump rather than a walk: arrays index positionally in O(1), and
  maps whose keys are sorted binary-search in O(log n) without decoding a key.

  Derived by walking encoded bytes rather than by hooking the writer -- so the
  writer's hot path is untouched, and any already-encoded value can be indexed
  after the fact: re-index after a compaction, or index a file somebody else
  wrote.

  Recursion depth is the document's nesting depth, which the decoder already
  bounds at maxDepth."
  [^Reader r start end stride min-entries base]
  (let [acc (java.util.ArrayList.)]
    (scan-into! r start end stride min-entries base acc)
    (nodes->index acc)))

(defn build-index
  "Index nodes for the containers inside already-encoded `bs`.

  `opts` may carry `:index` (the stride, default 16) and `:index-min` (skip
  containers smaller than this, default 16).

  `:index-min` is the DOMINANT size knob, and 2 was a bad default. Real data is
  mostly small containers, and each one costs a container offset, a count, a
  typed-array slot and a flag whether or not it is worth searching. On 2 000
  records of two fields each, indexing every container cost **76%** of the file
  and indexing only containers of 8+ cost **1.3%** -- for a FASTER lookup,
  because the smaller index also fits in cache. A container of a handful of
  entries is already found in well under a microsecond by walking it.

  Returns a map ready for `seal-index!`, or nil if nothing was worth indexing."
  ([^bytes bs] (build-index bs nil))
  ([^bytes bs opts]
   (let [r (Reader. bs)
         stride (long (let [i (:index opts)] (if (and i (pos? (long i))) i 16)))
         min-entries (long (index-opt opts :index-min 16))
         idx (try
               (scan-index r 0 (alength bs) stride min-entries 0)
               (catch StackOverflowError _
                 ;; The depth bound above is deterministic, but it is calibrated
                 ;; against a default stack; a smaller -Xss can still reach the
                 ;; real limit first. A public entry point that takes bytes
                 ;; somebody else wrote may not answer with an Error.
                 (throw (ex-info "boring: index walk ran out of stack; this document is too deeply nested to index"
                                 {:type :boring/max-depth-exceeded}))))]
     (when (pos? (alength ^longs (:containers idx)))
       (assoc idx :stride stride)))))

(defn- delta-slot
  "A slot's entry offsets, as deltas in the narrowest typed array that holds them.

  Offsets inside a container ascend, so consecutive differences are small and
  nearly uniform while the absolutes are large and unbounded. That is the whole
  saving: on 50 000 ~66-byte log records the per-item deltas span 60..67, which
  is a byte, against int32 absolutes reaching into the millions.

  Widths, narrowest first -- a byte string (no tag at all), sint16 (tag 77),
  sint32 (tag 78). Every one of these already round-trips through the codec, so
  this adds NO format surface: the CBOR tag is the width declaration, which is
  why there is no per-entry flag of the kind Postgres needs for its JEntry
  array. Postgres reads offsets in place out of a TOAST'd datum; we materialise
  the index once, so we can afford a representation that must be expanded.

  The 0x7FFF bound, rather than 0xFFFF: tag 77 is SIGNED, and taking the last
  32 KiB of range back would mean masking on read for a band that only opens up
  when anchors are 32 KiB apart. Deltas that large take int32, which is what
  they would have cost anyway.

  `base` is what the first delta is measured from: the container's own offset,
  or 0 for the sequence node. So slot[0] is a header width rather than a file
  position, which keeps the first entry as narrow as the rest.

  Falls back to int32 on a negative delta. That cannot arise from a walk --
  entries ascend and no CBOR item is zero bytes -- but the narrow encodings
  cannot represent one, so the check is what makes that an assumption about the
  walk rather than about the file."
  [^longs offs ^long base]
  (let [n (alength offs)
        d (long-array n)]
    (loop [i 0 prev base mn Long/MAX_VALUE mx Long/MIN_VALUE]
      (if (= i n)
        (cond
          (or (zero? n) (and (>= mn 0) (<= mx 0xFF)))
          (let [b (byte-array n)]
            (dotimes [k n] (aset-byte b k (unchecked-byte (aget d k))))
            b)

          (and (>= mn 0) (<= mx 0x7FFF))
          (let [s (short-array n)]
            (dotimes [k n] (aset-short s k (unchecked-short (aget d k))))
            s)

          (and (>= mn Integer/MIN_VALUE) (<= mx Integer/MAX_VALUE))
          (let [a (int-array n)]
            (dotimes [k n] (aset-int a k (unchecked-int (aget d k))))
            a)

          ;; sint64 (tag 79), the fourth tier. Only reachable when two anchors
          ;; are more than 2 GiB apart, which needs items that large -- but the
          ;; tier costs nothing to have, because the CBOR tag declares the width
          ;; and a narrower slot is still emitted whenever one fits.
          :else d)
        (let [v (aget offs i)
              delta (- v prev)]
          (aset d i delta)
          (recur (inc i) v (min mn delta) (max mx delta)))))))

(defn- long->8-bytes* ^bytes [^long v]
  (let [b (byte-array 8)]
    (dotimes [i 8] (aset-byte b i (unchecked-byte (bit-shift-right v (* 8 (- 7 i))))))
    b))

(defn seal-index!
  "Write an index item to `out`, sealing everything written before it.

  `index` comes from `build-index`; `data-len` is how many bytes precede this
  item, which is also where it begins. The item is:

      tag 27 [ `boring/index`,
               [ stride, containers, counts, slots, sorted, <8-byte data-len> ] ]

  `containers` are the byte offsets of every indexed container, sorted, so a
  reader binary-searches them. `slots` holds each container's entry offsets and
  `sorted` says whether that container's keys ascend -- recorded rather than
  inferred, because the encoding profile is not on the wire.

  Slots go out as DELTAS, in the narrowest typed array that holds them (see
  `delta-slot`), and a reader expands them back to absolutes once when it loads
  the index. An int32 slot is therefore deltas too, not absolutes -- the two are
  indistinguishable on the wire, which is why this had to land before the format
  was published rather than after.

  The trailing element is a byte string of exactly 8 bytes, so it always encodes
  as `0x48` plus 8: a sealed file ends with 9 predictable bytes however large
  the index is. That is how it is found, since CBOR cannot be parsed backwards.

  Those 9 bytes are ordinary CBOR, not a magic trailer. The file stays a valid
  sequence that any reader consumes -- it just sees one extra tagged item.

  The pointer verifies as well as locates: it is both where the index starts and
  how long the data is, so a reader that seeks there and does not find a tag-27
  frame named `boring/index` knows the index is stale and scans instead."
  ([^Writer w ^java.io.OutputStream out index data-len]
   (seal-index! w out index data-len (writer-opts w)))
  ([^Writer w ^java.io.OutputStream out index data-len opts]
   (seal-index-with! w out index data-len opts)))

(defn- seal-index-with!
  [^Writer w ^java.io.OutputStream out index data-len opts]
  (letfn [(emit! [item]
            ;; `write-root!`, not `write-to!`: `opts` here are ALREADY RESOLVED,
            ;; and write-to!'s 3-arity resolves again -- which throws for every
            ;; profile that locks a key, the same double-resolution trap as
            ;; `write-seq!`'s 3-arity.
            (let [n (long (.position ^Writer (write-root! w item opts)))]
              (.write out (.buffer w) 0 (int n))
              n))]
    (let [{:keys [stride ^longs containers counts slots sorted]} index
          ;; NARROWEST TYPE THAT HOLDS THEM, exactly as the slot deltas do. A
          ;; file whose offsets fit in int32 emits int32 and is byte-identical
          ;; to what it was before offsets became 64-bit; one that does not
          ;; promotes to sint64, and the CBOR tag tells a reader which it got.
          wire-containers (if (and (pos? (alength containers))
                                   (let [mx (areduce containers i m Long/MIN_VALUE
                                                     (max m (aget containers i)))
                                         mn (areduce containers i m Long/MAX_VALUE
                                                     (min m (aget containers i)))]
                                     (or (< mx Integer/MIN_VALUE) (> mx Integer/MAX_VALUE)
                                         (< mn Integer/MIN_VALUE) (> mn Integer/MAX_VALUE))))
                            containers
                            (let [a (int-array (alength containers))]
                              (dotimes [i (alength containers)]
                                (aset-int a i (unchecked-int (aget containers i))))
                              a))
          packed (vec (map-indexed
                       (fn [i s]
                       ;; The sequence node's sentinel offset is -1, and a file
                       ;; position is never negative: its deltas start from 0.
                         (delta-slot s (max 0 (long (aget containers (int i))))))
                       slots))
          item (data/unknown-record
                index-name
                [(long stride) wire-containers counts packed (vec sorted)
                 (long->8-bytes* (long data-len))])]
    ;; The frame goes out under the SAME options as the data it describes.
    ;; `write-to!`'s 2-arity resolves the WRITER's options instead, so
    ;; `write-seq!`'s 4-arity wrote the data with the caller's opts and the
    ;; footer with the writer's -- and with a plain `(writer 4096)` that meant
    ;; the frame was emitted inside a stringref namespace, which `nav` refuses
    ;; to recognise. The index was then silently dead (27x slower lookups) AND
    ;; the frame came back as a phantom trailing data item in every log.
    ;;
    ;; Invisible to every test and doc example, because all of them happen to
    ;; build the writer with the same options they pass.
    ;;
      (emit! item))))

(defn- grow ^bytes [^bytes buf ^long need]
  (if (>= (alength buf) need)
    buf
    (java.util.Arrays/copyOf buf (int (max need (* 2 (alength buf)))))))

(defn decode-seq-from
  "Lazily decode a CBOR sequence (RFC 8742) from an `InputStream`, in bounded
  memory.

  The write side has streamed since the beginning (`write-to!`, `write-seq!`)
  while the read side took only a `byte[]`, so a dump larger than the heap had
  no symmetric read path -- `decode-seq`'s own docstring conceded the chunking
  workaround. This is that workaround, done once and correctly.

  Bounded memory means bounded by the LARGEST SINGLE ITEM plus the chunk size,
  not by the stream. That is the real limit and it is not a compromise: an item
  has to fit in memory to be a Clojure value at all, so streaming can only ever
  mean a sequence of items -- which is exactly what a datahike dump is.

  The reader's hot path is untouched. Refilling happens between items, not
  inside `u8()`, so this costs nothing when decoding from a byte array.

  `:chunk-size` (default 64 KiB) is how much is pulled from the stream at a
  time. The caller owns the stream and should close it."
  ([^java.io.InputStream in] (decode-seq-from in nil))
  ([^java.io.InputStream in opts]
   (let [o     (resolve-opts opts)
         chunk (let [c (get opts :chunk-size 65536)]
                 ;; VALIDATED. `:chunk-size 0` silently returned an EMPTY
                 ;; sequence for non-empty input -- data loss with no error, the
                 ;; worst way an option can be wrong -- and a negative value was
                 ;; a raw NegativeArraySizeException.
                 (when-not (and (integer? c) (pos? (long c)) (<= (long c) Integer/MAX_VALUE))
                   (throw (ex-info (str "boring: :chunk-size must be a positive integer, got "
                                        (pr-str c))
                                   {:type :boring/bad-option :chunk-size c})))
                 (int c))
         r     (Reader. (byte-array 0))
         state (volatile! {:buf (byte-array chunk) :limit 0 :last-good 0 :eof? false})]
     (configure-reader! r o)
     ;; `last-good` is the offset just past the last COMPLETE item, not the
     ;; reader's current position. A failed read leaves the position somewhere
     ;; mid-item -- and past the end of the valid bytes, since `u8` increments
     ;; before it can discover it has run out -- so rewinding to the position
     ;; would compact a negative number of bytes. Rewind to the last item.
     (letfn [(refill!
               []
               (let [{:keys [^bytes buf ^long limit ^long last-good eof?]} @state
                     rest-len (- limit last-good)
                     _ (when (pos? last-good)
                         (System/arraycopy buf last-good buf 0 rest-len))
                     ;; Hinted at the binding, not left to the return hint on
                     ;; `grow`: without this both `alength` and `copyOf` below
                     ;; went reflective, and the warnings shipped.
                     ^bytes buf (grow buf (+ rest-len chunk))
                     n (if eof?
                         -1
                         (.read in buf (int rest-len) (int (- (alength buf) rest-len))))
                     new-limit (if (pos? n) (+ rest-len n) rest-len)]
                 (vreset! state {:buf buf :limit new-limit :last-good 0
                                 :eof? (or eof? (neg? n))})
                 (.reset r (java.util.Arrays/copyOf buf (int new-limit)))
                 (pos? n)))
             (step []
               (lazy-seq
                (if-not (.atEnd r)
                  (let [v (try
                            {:ok (.readNext r)}
                            (catch clojure.lang.ExceptionInfo e
                              ;; From INSIDE the reader, "the buffer ends
                              ;; mid-item" and "the document declares an
                              ;; impossible count" are indistinguishable:
                              ;; checkCount validates a declared count against
                              ;; the bytes REMAINING, which on a partial buffer
                              ;; is exactly what a hostile count looks like. So
                              ;; both are retried while more data can still
                              ;; arrive -- and the ORIGINAL exception is kept,
                              ;; to be rethrown if it cannot, rather than
                              ;; reporting every malformed document as a
                              ;; truncation.
                              (if (#{:boring/truncated-input :boring/bad-count}
                                   (:type (ex-data e)))
                                {:need-more e}
                                (throw e)))
                            (catch IndexOutOfBoundsException e
                              {:need-more e}))]
                    (cond
                      (contains? v :ok)
                      (do (vswap! state assoc :last-good (.position r))
                          ;; See `decode-seq`: the trailing index frame is
                          ;; metadata, not an item. Here "final" needs the
                          ;; stream too, not just the buffer -- `.atEnd` only
                          ;; means the current chunk is exhausted, so a refill
                          ;; that succeeds proves the frame was not last.
                          (if (and (index-frame? (:ok v) -1) (.atEnd r) (not (refill!)))
                            nil
                            (cons (:ok v) (step))))
                      (refill!) (step)
                      ;; Out of data and still incomplete. The retained
                      ;; exception is rethrown as-is when it is already typed,
                      ;; but a raw IndexOutOfBoundsException gets the same
                      ;; conversion `with-decode-errors` applies at every other
                      ;; entry point. Without this, `decode-seq-from` was the
                      ;; ONE reader that leaked a bare
                      ;; "Index 19 out of bounds for length 19" for a truncated
                      ;; input, where `decode` and `decode-seq` both name it —
                      ;; so the caller reading a short dump was told nothing
                      ;; about what was wrong with it.
                      :else
                      (let [e (:need-more v)]
                        (throw (if (instance? IndexOutOfBoundsException e)
                                 (ex-info "boring: input ended mid-value (truncated or malformed)"
                                          {:type :boring/truncated-input} e)
                                 e)))))
                  (when (refill!) (step)))))]
       (refill!)
       (step)))))

(defn decode-seq
  "Lazily decode consecutive top-level CBOR items from `bs`.

  Memory is bounded by the item being realised, not by `bs` — but `bs` itself
  is already in memory. Use `decode-seq-from` to read a stream larger than the
  heap."
  ([^bytes bs] (decode-seq bs nil))
  ([^bytes bs opts]
   (let [r (Reader. bs)]
     (set! (.-tolerateUnknownTags r) (boolean (get opts :tolerate-unknown-tags true)))
     (set! (.-instantAsDate r) (not= :instant (get opts :instant-type :date)))
     (set! (.-fullDateAsSqlDate r) (= :sql-date (get opts :date-type :local-date)))
     (set! (.-maxDepth r) (int (get opts :max-depth 1024)))
  ;; 0 = unlimited, which is the default. See Reader.maxItems for why the budget
  ;; counts ITEMS rather than bytes.
     (set! (.-maxItems r) (max-items-opt opts))
     (set! (.-validateUtf8 r) (boolean (get opts :validate-utf8 true)))
  ;; WIRED, having been documented and then never applied. doc/SECURITY.md
  ;; describes `:check-duplicate-keys false` as the way to turn duplicate
  ;; rejection off; the Java field existed and defaulted to true, but no entry
  ;; point ever set it, so the option was silently ignored and a duplicate map
  ;; still threw with it set. A documented safety control that does nothing is
  ;; worse than one that does not exist.
     (set! (.-checkDuplicateKeys r) (boolean (get opts :check-duplicate-keys true)))
     (set! (.-autoConstructRecords r)
           (boolean (get opts :auto-construct-records? false)))
     (set! (.-registry r) (or (:registry opts) TagRegistry/EMPTY))
     ((fn step []
        (lazy-seq
         (when-not (.atEnd r)
            ;; Each item opens a fresh stringref namespace, so the reader's
            ;; per-message state must be cleared between items — but not its
            ;; ident cache, which is a pure function of bytes.
           (let [start (.position r)
                 v (with-decode-errors (.readNext r))]
             ;; THE INDEX FRAME IS NOT AN ITEM. `write-seq!` indexes by default,
             ;; so without this every caller of `decode-seq` would find a
             ;; phantom `#boring/index [...]` after their data. It is dropped
             ;; only in the final position, which is the only place `seal-index!`
             ;; can put it -- a frame of that name anywhere else is somebody
             ;; else's data and stays visible.
             (if (and (.atEnd r) (index-frame? v start))
               nil
               (cons v (step)))))))))))

;; ## Optional hasch integration, activated if hasch is present
;;
;; `boring.hasch` teaches hasch to content-address an unregistered tag-27 frame
;; as the value it stands for. Without it, `UnknownRecord` implements
;; IPersistentMap, so hasch walks it as a BARE MAP and drops the type name --
;; distinct types collide with each other and with a plain map, silently.
;;
;; Loaded here rather than left to the consumer because forgetting it is not a
;; loud failure: hashes simply come out wrong, and only if two peers disagree
;; about whether the record class is present does anyone notice. The same
;; reflective-optional pattern konserve uses for zstd.
;;
;; hasch stays OUT of :deps -- boring's only runtime dependency is Clojure, and
;; hasch is EPL-1.0 while boring is Apache-2.0. This activates only if the
;; consumer already has it.
;;
;; The catch is narrowed to "namespace not found". A hasch that is present but
;; whose integration throws must NOT be swallowed: that is a real error, and
;; hiding it would reproduce the silent-wrong-hash failure this exists to stop.
(defonce ^{:doc "True if the optional hasch integration is active."}
  hasch-integration?
  (try
    (require 'boring.hasch)
    true
    (catch java.io.FileNotFoundException _ false)
    (catch ClassNotFoundException _ false)))
