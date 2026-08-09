(ns boring.core
  "Thin Clojure surface over the Java hot path.

  The dispatch loop lives in Java on purpose: the per-value type test plus byte
  emission is where the time goes, and crossing the Clojure/Java boundary per
  value costs more than it saves. Measured, the Java hot path is worth 1.2-1.7x
  over the equivalent pure Clojure (5.8x on strings, where bulk VarHandle writes
  and a single-pass ASCII encode beat per-byte `aset`).

  Options (all optional):

    :profile        :clojure (default) | :interop | :archival | :canonical
                    | :canonical-rfc7049
    :float-policy   :preserve-width (default) | :shortest
    :stringref      true (default under :clojure) | false

  See doc/COMPATIBILITY.md for what each profile promises about the bytes.
  :float-policy exists because datahike's dumps must not narrow a double to a
  float -- the class, not just the value, has to survive.

  The five profiles answer five different questions:

    :clojure    round-trip fidelity within Clojure, smallest bytes.
    :interop    can any conformant CBOR reader read this? (no extensions)
    :archival   will two exports of the same data be identical, AND do JVM
                types survive? (sorted keys + fixed-width floats)
    :canonical  do these bytes agree octet-for-octet with other canonical
                encoders? (RFC 8949 4.2.2, which narrows floats)
    :canonical-rfc7049
                do they agree with a clj-cbor peer? (length-first key order,
                RFC 7049 3.9 -- which is also what Python cbor2's
                `canonical=True` and Rust ciborium produce)

  :archival and :canonical are NOT the same and cannot be: RFC determinism
  requires the shortest float form, which discards the Double/Float
  distinction. Pick by which of the two you actually need."
  (:require [boring.data :as data]
            [boring.errors :refer [with-decode-errors]]
            [boring.frame :as frame]
            [boring.options :as opt]
            [clojure.string :as str])
  (:import (java.nio ByteBuffer)
           (org.replikativ.boring ByteSource Reader TagRegistry Writer)))

(set! *warn-on-reflection* true)

;; `reader` and `decode` both build a Reader from a byte[] or a ByteSource, and
;; the shared constructor lives further down next to the other input guards.
(declare bytes! reader-of reset-reader!)

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
;; Profiles, validation and resolution live in `boring.options`, shared with
;; ClojureScript -- see that namespace for why. These are the names this file
;; used before the move; keeping them means the change is a deletion here
;; rather than a rewrite of every call site.
(def ^:private default-opts opt/default-opts)
(def ^:private resolve-opts opt/resolve-opts)

(defn writer
  "Create a reusable Writer. Not thread-safe; one per thread or per loop.

  `initial-size` is a BYTE COUNT for the internal buffer, not an options map.
  There is no `(writer opts)` arity, so `(writer {:stringref false})` -- the
  natural thing to type, and the shape `boring.nav` pushes callers toward --
  is a raw `ClassCastException` from the `^long` hint rather than anything
  typed. Write `(writer 256 {:stringref false})`.

  With `opts`, they are resolved ONCE here and used by every no-options arity
  on this writer: `encode-into!`, `encode-buffered!`, `write-to!`,
  `write-to-buffer!`, `write-seq!`, `write-indexed!` and `seal-index!`. All
  seven, which is worth listing rather than gesturing at -- the 3-arity
  `write-seq!` resolved nil instead for a while, so a writer built
  `(writer n {:stringref false})` silently emitted stringref through that one
  entry point. Prefer this
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
  (^Reader [src] (reader-of src "reader"))
  (^Reader [src opts] (configure-reader! (reader-of src "reader")
                                         (opt/check-opts opts))))

(def unencodable
  "The default `:encode-fallback` placeholder: a tag-27 frame naming the type
  that could not be encoded, with its `pr-str`.

  Readable by any CBOR implementation, and obviously a placeholder rather than
  a value that might be mistaken for the original."
  opt/unencodable)

;; `float-policy!`, `encode-fallback-fn`, `max-depth-opt`, `max-items-opt` and
;; `index-opt` used to live here, each validating its option at the point the
;; option was READ. That is why the answers disagreed: whether an option was
;; checked depended on which code path ran. `boring.options/resolve-opts`
;; checks all of them, once, at the one point every entry point passes through,
;; so everything below can simply `get` a value it knows is legal.

(defn- configure!
  ^Writer [^Writer w opts]
  (set! (.-stringref w) (boolean (:stringref opts)))
  (set! (.-inclMetadata w) (boolean (get opts :incl-metadata? true)))
  (set! (.-preserveWidth w) (= :preserve-width (get opts :float-policy :preserve-width)))
  (set! (.-canonical w) (boolean (:canonical opts)))
  (set! (.-legacyCanonicalOrder w) (= :rfc7049 (:canonical-order opts)))
  (set! (.-shapes w) (boolean (:shapes opts)))
  (set! (.-permitReservedSimpleValues w)
        (boolean (:permit-reserved-simple-values opts)))
  (set! (.-maxDepth w) (int (get opts :max-depth 1024)))
  (set! (.-encodeFallback w) (opt/fallback-fn (:encode-fallback opts)))
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
  ;; INTEGER, checked here rather than left to `(long tag)`. That coercion
  ;; TRUNCATES: `1.5` registered a handler under tag 1 -- a different tag than
  ;; the caller named, silently. `TagRegistry.checkTag` never saw the 1.5,
  ;; because by then it was a long. Found by a test written for the
  ;; ClojureScript half of the same defect.
  ;; RANGE TOO, not only integrality. `(long tag)` on a bignum past
  ;; Long/MAX_VALUE raises a raw IllegalArgumentException -- untyped, out of the
  ;; registration API, for the one tag number a caller is most likely to reach
  ;; for when testing the boundary. CBOR's tag domain is [0, 2^64-1]; this API
  ;; takes a long and says so rather than truncating.
  (when-not (and (integer? tag) (<= (bigint tag) (bigint Long/MAX_VALUE)))
    (throw (ex-info (str "boring: tag numbers are integers no greater than "
                         Long/MAX_VALUE "; got " (pr-str tag))
                    {:type :boring/bad-tag-number :tag tag})))
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

(defn declare-navigable-record
  "Declare that `wire-name`'s constructor PRESERVES STRUCTURE, letting
  `boring.nav` answer from the field map on the wire instead of building the
  record. Returns a NEW registry.

  The claim is precise, and it is three claims:

    (get (ctor fields) k)  =  (get fields k)     for every k
    (count (ctor fields))  =  (count fields)
    (seq (ctor fields))    =  (seq fields)       same entries, same order

  A `map->Record` factory satisfies all three, which is the common case.

  WHY THIS IS OPT-IN. A constructor is an arbitrary function. It may rename
  fields, drop them, or resolve state that is not in the bytes at all --
  datahike's `reconstruct-db` resolves storage roots through registered storage
  -- and answering past one of those returns a value the reader would never have
  produced. `boring.nav` therefore treats every REGISTERED name as opaque and
  realises it, which is always correct and sometimes slow. This is how the
  handler's author, who is the only one who knows, says otherwise.

  It is worth something: on a 2000-entry field map, a lookup that descends costs
  7.91 us against 577.18 us realising, and stays flat as the record grows.

  NO DECLARATION IS NEEDED FOR AN UNREGISTERED NAME. Without a constructor a
  record decodes to a `boring.data/UnknownRecord`, whose `get`, `count` and
  `seq` already delegate to the field map, so it is navigable by construction.

  THE DECLARATION IS A CLAIM, AND A WRONG ONE RETURNS WRONG VALUES SILENTLY --
  not an exception, not a slow path. Check it rather than assert it:

    (require '[boring.nav-conformance :as nc])
    (nc/check-record registry \"my.ns.Point\" [(->Point 1 2) (->Point 3 4)])

  JVM-only, because `boring.nav` is. A `.cljc` registration shared with
  ClojureScript should call this from a JVM-only branch."
  ^TagRegistry [^TagRegistry reg ^String wire-name]
  (.withNavigableRecord reg wire-name))

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
  ;; THE SAME WIRE NAME EVERY OTHER PATH WRITES. This defaulted to the raw
  ;; class name, so a type registered through here went on the wire as
  ;; `my_ns.My-Rec` while `encode` of the same type wrote `my-ns/My-Rec` --
  ;; two names for one type inside one version, which is worse than either
  ;; name being wrong. `TagRegistry.recordName` is the one place that decides.
  (^TagRegistry [reg cls]
   (register-record-class reg cls (.recordName TagRegistry/EMPTY ^Class cls)))
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

  The buffer is overwritten by the next encode. Do not retain it.

  The borrow is RECORDED on the writer, so `trim!` -- which replaces the buffer
  with a fresh, zeroed one -- refuses rather than pulling it out from under
  you. It used to do exactly that, and `{:id 7 :name \"hello\"}` came back as
  25 zero bytes that `decode` read as the integer 0."
  (^long [^Writer w v]
   (let [n (.position ^Writer (write-root! w v (writer-opts w)))]
     (set! (.-borrowed w) true)
     n))
  (^long [^Writer w v opts]
   (let [n (.position ^Writer (write-root! w v (resolve-opts opts)))]
     (set! (.-borrowed w) true)
     n)))

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

  Throws `:boring/bad-argument` in TWO states, and the second is the likelier
  one. Mid-stream, where the buffer still holds bytes that have not reached the
  sink. And after `encode-buffered!`, whose bytes live in the buffer this
  replaces -- so `(do (encode-buffered! w v) (trim! w))` refuses, which is the
  documented allocation-free loop and therefore exactly where a caller thinks
  to reclaim a peak. The way out is to consume the borrow first:
  `write-to!`, `write-to-buffer!` or `encode-into!` all end it, and the next
  `encode` clears it too. The Java message names the recovery; only this
  docstring was short.

  A trimmed writer is otherwise a usable writer, in whatever state `reset`
  would leave it."
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

  ON THAT OVERFLOW THE BORROW IS LEFT SET. This function ends the borrow
  `encode-buffered!` records, but the `.put` that throws happens first and the
  clearing `set!` is not in a `finally` -- so a writer that has just overflowed
  a `ByteBuffer` refuses `trim!` with `:boring/bad-argument` until something
  else consumes it. Any later `encode` clears it, and so does a retry of this
  call into a larger buffer, so the recovery is the thing you were going to do
  anyway; it is worth knowing only if you flush by trimming.

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
     (set! (.-borrowed w) false)          ; copied out -- see the 4-arity
     n))
  (^long [^Writer w v ^ByteBuffer bb opts]
   (let [n (encode-buffered! w v opts)]
     (.put bb ^bytes (.buffer w) 0 (int n))
     ;; The bytes are IN THE CALLER'S BUFFER now, so the borrow
     ;; `encode-buffered!` records is over. Left set, it made `trim!`
     ;; permanently unreachable after the documented allocation-free loop --
     ;; a guard against silent corruption turning into a guard against the
     ;; feature it was protecting.
     (set! (.-borrowed w) false)
     n)))

(defn- bytes!
  "The input, or a typed error. `(Reader. nil)` is a raw NullPointerException,
  and doc/SECURITY.md's third guarantee says none escapes -- `nav/source` was
  the only read path that honoured it for a nil argument. A nil here is a
  caller mistake rather than bad data, but the guarantee does not distinguish
  and a caller's `catch ExceptionInfo` should not have to either."
  ^bytes [bs entry]
  (when (nil? bs)
    (throw (ex-info (str "boring: " entry " needs a byte array, got nil")
                    {:type :boring/bad-argument :entry entry})))
  bs)

(defn- reset-reader!
  "Rebind `r` to `src`, a `byte[]` or a `ByteSource`.

  The ByteSource arm is what makes a reusable Reader usable off-heap: LMDB
  hands out a fresh MemorySegment per read over its own mapping, so the
  efficient shape is one Reader rebound per value rather than one Reader per
  value. `Reader.reset` has had both overloads all along."
  [^Reader r src entry]
  (cond
    (nil? src) (bytes! src entry)
    (bytes? src) (.reset r ^bytes src)
    (instance? ByteSource src) (.reset r ^ByteSource src)
    :else (throw (ex-info (str "boring: " entry " needs a byte array or a "
                               "ByteSource, got " (pr-str (type src)))
                          {:type :boring/bad-argument :entry entry}))))

(defn- reader-of
  "A `Reader` over `src`, which is a `byte[]` or a `ByteSource`.

  The Java Reader has taken a ByteSource since `boring.nav` needed one, but
  the Clojure API here did not, so a caller holding off-heap bytes -- an
  mmap'ed file, or the MemorySegment LMDB hands out over its own mapping --
  had to copy into a `byte[]` first to decode them at all. Navigation could
  read a source and a whole-value decode could not, which is backwards: a
  store's normal case is wanting the whole value.

  Nothing is gated on JDK 22 by this. `ByteSource` is deliberately a JDK-9
  interface that names no FFM type -- see its javadoc -- so only the caller
  who CONSTRUCTS a segment-backed source needs the newer JDK."
  ^Reader [src entry]
  (cond
    (nil? src) (bytes! src entry)                   ; one typed nil error
    (bytes? src) (Reader. ^bytes src)
    (instance? ByteSource src) (Reader. ^ByteSource src)
    :else (throw (ex-info (str "boring: " entry " needs a byte array or a "
                               "ByteSource, got " (pr-str (type src)))
                          {:type :boring/bad-argument :entry entry}))))

(defn ^:no-doc reader-config
  "The eleven decode options a Reader carries, resolved ONCE into an array.

  Split out of `configure-reader!` because those eleven `get`s are per SOURCE,
  and a caller that opens one source per document -- which is every scan over a
  document store -- repeats them per document for an answer fixed before the
  scan began. 4000 blobs meant 44 000 map lookups. Worse than it looks: a small
  options map is a PersistentArrayMap, whose `get` is a linear scan, so each
  lookup walks the map.

  `configure-reader!` still goes through here, so there is ONE definition of
  which option lands in which field. Callers holding a config across documents
  -- `boring.nav/context` -- resolve once and apply many times.

  Not part of the supported API."
  ^objects [opts]
  (doto (object-array 11)
    (aset 0 ^Object (Boolean/valueOf (boolean (get opts :tolerate-unknown-tags true))))
    (aset 1 (get opts :on-unknown-record :fallback))
    (aset 2 (let [it (get opts :instant-type :date)]
              (when-not (#{:date :instant} it) it)))
    (aset 3 ^Object (Boolean/valueOf (not= :instant (get opts :instant-type :date))))
    (aset 4 ^Object (Boolean/valueOf (= :sql-date (get opts :date-type :local-date))))
    (aset 5 ^Object (Integer/valueOf (int (get opts :max-depth 1024))))
    (aset 6 ^Object (Long/valueOf (long (get opts :max-items 0))))
    (aset 7 ^Object (Boolean/valueOf (boolean (get opts :validate-utf8 true))))
    (aset 8 ^Object (Boolean/valueOf (boolean (get opts :check-duplicate-keys true))))
    (aset 9 ^Object (Boolean/valueOf (boolean (get opts :auto-construct-records? false))))
    (aset 10 (or (:registry opts) TagRegistry/EMPTY))))

(defn ^:no-doc apply-reader-config! ^Reader [^Reader r ^objects c]
  (set! (.-tolerateUnknownTags r) (boolean (aget c 0)))
  (set! (.-onUnknownRecord r) (aget c 1))
  (set! (.-instantFn r) (aget c 2))
  (set! (.-instantAsDate r) (boolean (aget c 3)))
  (set! (.-fullDateAsSqlDate r) (boolean (aget c 4)))
  (set! (.-maxDepth r) (int (aget c 5)))
  (set! (.-maxItems r) (long (aget c 6)))
  (set! (.-validateUtf8 r) (boolean (aget c 7)))
  (set! (.-checkDuplicateKeys r) (boolean (aget c 8)))
  (set! (.-autoConstructRecords r) (boolean (aget c 9)))
  (set! (.-registry r) ^TagRegistry (aget c 10))
  r)

(defn ^:no-doc configure-reader!
  "Apply decode options to a Reader. Public only so `boring.nav` can apply the
  SAME options its docstrings promise -- it realises values through this reader,
  so a differently-configured one would decode differently from `decode`.
  Not part of the supported API.

  DELEGATES, so there is exactly one definition of which option lands in which
  field. It used to be a second, independent implementation, defended on the
  grounds that going through `reader-config` would put an array allocation on
  the `decode` path. Measured: `configure-reader!` 11.6 ns, the delegating shape
  12.1 ns -- 0.5 ns, against 140 ns for a `decode` of the smallest realistic
  document. That is 0.4% of one decode, and the thing it bought was a second
  place for an option to be honoured on one path and ignored on the other, which
  is the defect shape this codebase keeps finding."
  ^Reader [^Reader r opts]
  (apply-reader-config! r (reader-config opts)))

(defn decode
  "Decode the first CBOR item in `bs`.

  Errors are `ex-info` carrying a `:type` keyword — `:boring/truncated-input`,
  `:boring/invalid-utf8`, `:boring/bad-count`, `:boring/max-depth-exceeded`,
  `:boring/duplicate-map-key`, `:boring/unregistered-tag`, and so on.

  `:tolerate-unknown-tags` (default true) makes an unregistered tag surface as
  a `boring.data/TaggedValue`; false makes it an error, which is the closed-reader
  behaviour datahike's dump requirements ask for.

  `:on-unknown-record` is the same choice for a tag-27 NAME no registry
  resolves — `:fallback` (default) for the `UnknownRecord`/`TaggedLiteral`
  carrier, `:error` for `:boring/unregistered-record`, or `(fn [name payload])`
  whose return value is used:

      ;; records or nothing
      (boring/decode bs {:registry reg :on-unknown-record :error})

      ;; warn and carry on -- `frame-for` is the default rule, so a handler
      ;; that only wants to log does not reimplement it
      (boring/decode bs {:on-unknown-record
                         (fn [nm payload]
                           (log/warn \"unregistered record\" nm)
                           (boring.data/frame-for nm payload))})

  The default is lossless passthrough, which is why the carrier exists: a relay
  must be able to carry a type it has no constructor for. `:error` is for the
  opposite case — a registration that can never match is otherwise SILENT, and
  a record simply arrives as an `UnknownRecord`. Reserved marker names
  (`clojure/char`, `java/period`, …) are known names and never reach this."
  ([src] (decode src nil))
  ([src opts]
   (with-decode-errors (.read (configure-reader! (reader-of src "decode")
                                                 (opt/check-opts opts))))))

(defn decode-at
  "Decode the CBOR item starting at byte offset `off` in `src`.

  `decode` is this with `off` 0. The companion of `boring.nav/cursor`, for
  the same reason: a format that puts a header, a length or another item in
  front of the one you want should not have to nest it in a container purely so
  it can be found. See `boring.nav/cursor` for the case that motivated both.

  `off` is TRUSTED. A wrong offset lands mid-item and decodes whatever the
  bytes there happen to encode -- bounded by the source, so it cannot read past
  the end, but perfectly capable of returning a plausible wrong value. Derive
  offsets from your format, not from input."
  ([src off] (decode-at src off nil))
  ([src off opts]
   (with-decode-errors
     (.readFrom (configure-reader! (reader-of src "decode-at") (opt/check-opts opts))
                (long off)))))

(defn decode-with
  "Decode using a reusable Reader.

  With `opts`, every option is re-applied on this call. Without them the
  reader keeps whatever it was last configured with, which is what makes the
  two-arity form fast and also what makes it a state-leak hazard across
  tenants -- pass opts unless the reader is yours alone."
  ([^Reader r src] (reset-reader! r src "decode-with") (with-decode-errors (.read r)))
  ([^Reader r src opts]
   (reset-reader! r src "decode-with")
   (configure-reader! r (opt/check-opts opts))
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
         build-index write-seq-resolved! write-indexed-resolved!
         derive-stringref-pointers default-index-stride)

(defn encode-indexed
  "Encode `v` and seal an index onto it, returning a byte[].

  The result is a two-item CBOR sequence -- the value, then the index -- so
  `decode` still returns the value and any CBOR reader consumes both. Pass it
  to `boring.nav/source` and lookups inside large containers become jumps.

  UNLESS NOTHING CLEARS `:index-min`, in which case there is no index and the
  result is the PLAIN ENCODING, one item: `(alength (encode-indexed [1 2 3]))`
  is 4, the same 4 bytes `(encode [1 2 3] {:stringref false})` gives, and
  `decode-seq` sees one item rather than two. Nothing is lost and every reader
  still reads it -- but a caller who branches on \"is this a sequence\" cannot
  assume. `write-indexed!` and the ClojureScript `encode-indexed` both state
  this; the sentence above used to be unconditional here only.

  `:index` is the stride (default 16) and `:index-min` the smallest container
  worth a node (default 16). Sorted map keys -- `:canonical` or `:archival` --
  additionally allow binary search; without them a lookup still jumps anchor to
  anchor rather than entry to entry.

  `:index 0` is the documented OFF switch on `write-seq!` and `write-indexed!`,
  and `boring.options`' own validator message says so for every entry point
  (\"0 turns indexing off\"). HERE IT IS A `:boring/bad-option`, and so is it on
  `build-index`, because an `encode-indexed` that does not index is just
  `encode` -- silently returning an unindexed single item would change what the
  return value IS, from a two-item sequence to one item. Call `encode` instead.

  `:stringref false` IS FORCED, because the sentence above would otherwise be
  false by default. The default profile writes stringref, and `boring.nav`
  categorically refuses a stringref document -- a stringref is an index into a
  table built from every preceding string, which a cursor holding only an offset
  cannot resolve. So `(nav/root (encode-indexed v))` threw
  `:boring/stringref-not-navigable` on the very shape this function's docstring
  recommends. Every test passed `{:stringref false}` or a sorting profile, so the
  advertised default was the one path never exercised.

  An index exists to be navigated; producing one that cannot be is not a
  trade-off worth offering -- so an EXPLICIT `:stringref true` is now REFUSED
  with `:boring/incompatible-options` rather than honoured. It used to be
  honoured, producing a file whose index `boring.nav` refuses outright, which
  is the same silent-useless-output this function's siblings already reject:
  `write-seq!` and `write-indexed!` have raised on that combination all along.
  Three functions, one rule, and this was the one that did not follow it."
  (^bytes [v] (encode-indexed v nil))
  (^bytes [v opts]
   ;; THIS IS `write-indexed!` INTO A BYTE ARRAY, and it used to be its own
   ;; implementation: encode the whole value, then WALK the finished bytes to
   ;; derive the index. That is two full passes and two copies of the document,
   ;; where the streaming writer already captures every node as it encodes --
   ;; it knows a container's offset and entry count before it emits the head,
   ;; so the nodes fall out of encoding for free.
   ;;
   ;; Measured, byte-identical output either way: 200 konserve-shaped records
   ;; 3.214 ms -> 0.584 ms, a flat 2000 records 2.973 ms -> 0.717 ms. Four to
   ;; five and a half times faster for strictly less code.
   ;;
   ;; AND IT REMOVES A SECOND BUILDER FROM THIS PATH, which matters more than
   ;; the speed. Two implementations of "where do the index nodes go" have
   ;; disagreed before -- see #30 and #34 -- and each divergence was found by a
   ;; test comparing them rather than by either one being obviously wrong.
   ;; There is now one builder for every value boring encodes itself.
   ;;
   ;; `build-index` KEEPS the byte walk, because it has a job this does not: it
   ;; indexes bytes somebody else wrote, or re-indexes after a compaction, and
   ;; there is no writer in either story.
   ;; `:stringref` IS STILL FORCED OFF HERE, and lifting it is a SEPARATE
   ;; change from this one. Delegating is a pure refactor -- byte-identical
   ;; output, verified -- while letting the default profile's `:stringref true`
   ;; through alters what this function emits, which moves sizes, makes the
   ;; frame seven elements, and is visible to `boring.frame`'s footer gate. Two
   ;; changes at once cost 25 test failures that took a bisect to attribute.
   (when (true? (:stringref opts))
     (throw (ex-info (str "boring: :stringref true cannot be combined with an index -- "
                          "boring.nav cannot resolve string references from an offset, "
                          "so the index would be unusable. Drop one of the two.")
                     {:type :boring/incompatible-options :stringref true})))
   (let [o (resolve-opts opts)
         stride (long (get o :index default-index-stride))
         ;; `:index 0` IS REFUSED HERE, not treated as "off". It means off
         ;; everywhere else, but sealing an index with no index is not a thing
         ;; this function can do, and silently substituting the default made the
         ;; documented off switch produce a LARGER file than omitting the
         ;; option. The refusal used to come from `build-index`; delegating to
         ;; the streaming writer, which legitimately reads 0 as off, would have
         ;; dropped it -- so it moves here rather than disappearing.
         _ (when (zero? stride)
             (throw (ex-info (str "boring: :index 0 turns indexing off, which "
                                  "encode-indexed cannot do; use `encode` instead")
                             {:type :boring/bad-option :option :index :value 0})))
         o (cond-> o (pos? stride) (assoc :stringref false))
         w (writer 1024 o)
         out (java.io.ByteArrayOutputStream. 1024)]
     (write-indexed-resolved! w v out o stride (long (get o :index-min 16)))
     (.toByteArray out))))

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
  its own. Raising it ABOVE the largest container but NO HIGHER THAN the item
  count gives an index over the ITEMS ONLY -- the right shape for a log you
  seek into but do not navigate within. Measured on 100 items of 40-entry maps
  (`{:k0 0 ... :k39 39}`): 34 167 bytes at the default, 32 861 with
  `:index-min 41`, against 32 800 unindexed. The frame is one node of 100
  counts; the default's is 101 nodes.

  BOTH BOUNDS MATTER, and this paragraph used to state only the first --
  \"raising it to a number no container reaches\", illustrated with 20 items at
  `:index-min 1000`. That configuration produces NO INDEX AT ALL, which is the
  opposite of items-only, and it contradicted the paragraph below. Measured:
  20 items, 6873 bytes at the default and 6560 with `:index-min 1000`, with no
  frame in the second.

  It DOES decide whether a short sequence gets a frame at all. The sequence is
  itself a node, so the same threshold applies to it: fewer than `:index-min`
  items and no container clearing the bar means no frame and no ~37 bytes. This
  paragraph used to promise the opposite -- \"always costs ~37 bytes\" -- which
  stopped being true when `encode-indexed` and `write-seq!` were made to agree.

  WHAT IT REALLY PRICES IS NODE COUNT, not container size. Opening an index
  costs about 25 ns PER NODE -- a slots entry, a memo cell and two verdict
  bytes each -- and that is paid once per `nav/source`, while a lookup visits
  only the containers on ONE path. So a node is a certain cost against a
  possible saving, and lowering the bar buys nodes that are never visited.

  Measured, reaching the last element with `:trust-index :trusted`:

    4096 x 4-key maps   :index-min 4    4097 nodes   105.14 us    1.7x
    4096 x 4-key maps   :index-min 16      1 node      3.03 us   54.9x
     256 x 64-key maps  :index-min 16    257 nodes    19.71 us    7.4x
     256 x 64-key maps  :index-min 65      1 node     13.72 us   10.6x

  The per-node figure held to within 10% across all three shapes. So the
  failure mode of a LOW `:index-min` is not a slightly bigger file, it is a
  35x slower lookup -- and the default of 16 is not conservative, it is
  roughly where store-shaped data stops adding nodes it will not use.

  Raising it further is safe and sometimes better: 16, 32, 64, 128 and 512 were
  within noise of each other on 2000 record-shaped documents (~30-33x), because
  none of them changed the node count for that data.

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
         stride (long (get o :index default-index-stride))]
     (write-seq-resolved! w values out
                          (cond-> o (pos? (long stride)) (assoc :stringref false))
                          stride (long (get o :index-min 16)))))
  (^long [^Writer w values ^java.io.OutputStream out opts]
   ;; RESOLVED FIRST, because resolution is what validates. `:index` was read
   ;; and coerced with `long` off the RAW map here, so `{:index "x"}` was a raw
   ;; ClassCastException out of this line -- the exact defect the option
   ;; validator says it closed, reintroduced by reading the option before the
   ;; gate rather than after it.
   ;;
   ;; The `:stringref` test below still reads the raw map, and must: a resolved
   ;; map carries the profile's `:stringref true`, so testing the resolved one
   ;; would refuse every default indexed write.
   (let [o (resolve-opts opts)
         stride (long (get o :index default-index-stride))]
     (when (and (pos? (long stride)) (true? (:stringref opts)))
       (throw (ex-info (str "boring: :stringref true cannot be combined with :index -- "
                            "boring.nav cannot resolve string references from an offset, "
                            "so the index would be unusable. Drop one of the two.")
                       {:type :boring/incompatible-options
                        :stringref true :index stride})))
     (write-seq-resolved! w values out
                          (cond-> o
                            (pos? (long stride)) (assoc :stringref false))
                          stride (long (get o :index-min 16))))))

(defn- write-seq-resolved!
  "`write-seq!` with options already resolved. See the note on its 3-arity."
  [^Writer w values ^java.io.OutputStream out o stride min-entries]
  ;; A SEQUENCE CANNOT CARRY STRINGREF POINTERS, and this is a property of the
  ;; format rather than a gap in the implementation.
  ;;
  ;; `write-root!` resets the writer per item, so EVERY top-level item opens its
  ;; own namespace numbered from zero. The frame carries ONE table keyed by
  ;; stringref index, so it can describe at most one of them -- and it described
  ;; the last, because the symbol table only still holds that item's strings
  ;; when the frame is sealed. Item 1's references then resolved against item
  ;; N's literals: `{:alpha 1 :beta 2 :gamma {:alpha 3 :beta 4}}` read back as
  ;; `{:alpha 1 :beta 2 :gamma {:zulu 3 :yankee 4}}`, silently.
  ;;
  ;; REFUSED HERE, not only at the public arity, because the public guard is
  ;; going to be lifted for the single-value writers once their builder can
  ;; emit pointers -- and a lift that reached this path would reintroduce
  ;; exactly that wrong answer. Single values are what the pointer table is for
  ;; and what a document store needs: a konserve blob is one top-level item.
  ;;
  ;; Supporting sequences would mean an item dimension in the element and a
  ;; two-level lookup, for a shape nothing has asked for.
  (when (and (pos? (long stride)) (:stringref o))
    (throw (ex-info (str "boring: :stringref cannot be combined with :index in "
                         "write-seq! -- each top-level item restarts the "
                         "stringref namespace at index 0, so one index frame "
                         "cannot describe them all. Use write-indexed! or "
                         "encode-indexed for a single value, or pass "
                         ":stringref false.")
                    {:type :boring/incompatible-options
                     :stringref true :index stride})))
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
        (let [items (.idxItemOffsets ^Writer w)
              ;; READ BEFORE `seal-index!`, which calls `write-root!` and so
              ;; `.reset` -- and reset clears the stringref namespace. Read
              ;; afterwards this is always empty, and silently so.
              srp (.stringrefPointers ^Writer w)]
          (.setIndex ^Writer w (int 0) (int 0) 0)   ; capture off again
          (+ total
             (long (seal-index!
                    w out
                    {:stride stride
                     :containers containers
                     :counts counts
                     :slots (into [items] sl)
                     :sorted (into [false] so)
                     :stringrefs srp}
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
            so (.idxSorted ^Writer w)
            ;; Before `seal-index!`, for the reason `write-seq!` gives: sealing
            ;; resets the writer, and reset clears the stringref namespace.
            srp (.stringrefPointers ^Writer w)]
        (.setIndex ^Writer w (int 0) (int 0) 0)
        (+ total
           (long (seal-index! w out
                              {:stride stride :containers containers :counts counts
                               :slots (vec sl) :sorted (vec so)
                               :stringrefs srp}
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
         stride (long (get o :index default-index-stride))]
     (write-indexed-resolved! w v out
                              (cond-> o (pos? (long stride)) (assoc :stringref false))
                              stride (long (get o :index-min 16)))))
  (^long [^Writer w v ^java.io.OutputStream out opts]
   ;; RESOLVED FIRST, because resolution is what validates. `:index` was read
   ;; and coerced with `long` off the RAW map here, so `{:index "x"}` was a raw
   ;; ClassCastException out of this line -- the exact defect the option
   ;; validator says it closed, reintroduced by reading the option before the
   ;; gate rather than after it.
   ;;
   ;; The `:stringref` test below still reads the raw map, and must: a resolved
   ;; map carries the profile's `:stringref true`, so testing the resolved one
   ;; would refuse every default indexed write.
   (let [o (resolve-opts opts)
         stride (long (get o :index default-index-stride))]
     (when (and (pos? (long stride)) (true? (:stringref opts)))
       (throw (ex-info (str "boring: :stringref true cannot be combined with :index -- "
                            "boring.nav cannot resolve string references from an offset, "
                            "so the index would be unusable. Drop one of the two.")
                       {:type :boring/incompatible-options
                        :stringref true :index stride})))
     (write-indexed-resolved! w v out
                              (cond-> o
                                (pos? (long stride)) (assoc :stringref false))
                              stride (long (get o :index-min 16))))))

(def ^:const index-name
  "Tag-27 type name for a sequence/container index. See doc/SHAPES.md.

  A NAME under tag 27, not a tag number of its own. Tag 27 is CBOR's registered
  extension point for exactly this -- \"serialised language-independent object
  with type name and constructor arguments\" -- and boring already reserves
  slash-bearing names under it (`clojure/sorted-map`, `java/period`; see
  doc/INTEROP.md).

  The name itself lives in `boring.frame`, with the byte prefix derived from
  it, so the two cannot drift apart. Re-exported here because it is public API."
  frame/index-name)
(declare index-walk index-walk*)

;; `index-frame?`, `frame-prefix`, `frame-prefix-at?` and `footer-start` lived
;; here, and ClojureScript had a fourth, weaker copy of the first. They are one
;; namespace now -- `boring.frame` -- for the reasons its docstring records: the
;; weakest copy returned 40 of 82 items from a valid file.

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
  ;; THE RUNNING ITEM COUNT, in a one-slot array.
  ;;
  ;; `walk` needs, for each entry of a container, how many items precede it
  ;; inside that container. Returning a per-entry count would mean every
  ;; recursive call handing back two values, which in Clojure means allocating
  ;; a tuple per ENTRY of the whole document -- on the walk whose entire design
  ;; note is that it visits each byte once and allocates only for nodes it
  ;; keeps.
  ;;
  ;; A monotone running total costs nothing and gives the same answer by
  ;; subtraction: the items before entry j are (total now - total when the
  ;; container started). It is exactly what `Writer.idxItemsWritten` does on
  ;; the other side, which is also why the two agree by construction rather
  ;; than by coincidence.
  (index-walk* r p stride min-entries base acc 0 (long-array 1) false))

(def ^:private ^:const walk-threshold
  "Where a binary search starts to repay the frame, in mean prefix items.
  MUST equal `Writer.WALK_THRESHOLD` -- the two index builders decide with it
  independently and must decide the same way."
  64)

(def ^:private ^:const unsorted-anchor-budget
  "What an unsorted map's anchors may cost, as a divisor of its own bytes.
  MUST equal `Writer.UNSORTED_ANCHOR_BUDGET` -- see it for the measurements
  and for why this branch is gated on SIZE rather than on `walk`."
  10)

(defn- keep-node?
  "Whether a container is worth an index node. Mirrors `Writer.keepNode`.

  An ARRAY or SORTED MAP is binary-searched, and that repays the frame from
  `walk-threshold` mean prefix items, so among containers that reach here
  `walk` decides and the entry count does not. `:index-min` still gates what
  reaches here -- see `Writer.keepNode` for why that floor is a capture guard
  doing policy work, and why changing it is a separate decision.

  An UNSORTED MAP cannot be binary-searched, so at a stride above 1
  `boring.nav` REFUSES the node -- an anchor loop over an unordered map visits
  every entry exactly as a plain scan does. Writing one is pure cost, measured
  at 0.43x-0.81x. At stride 1 it IS usable, and then costs one anchor per
  entry, so it is gated on the share of the container's own bytes those
  anchors take -- see `Writer.UNSORTED_ANCHOR_BUDGET`."
  ;; NO PRIMITIVE HINTS: a Clojure fn taking primitives is limited to four
  ;; arguments, and this takes six. Boxing four longs at one call per
  ;; container is not on any hot path -- the walk it gates is.
  [map? sorted walk stride n bytes]
  ;; ONE ANCHOR CANNOT ACCELERATE ANYTHING -- see `Writer.keepNode`. A
  ;; container of n <= stride entries gets a single anchor, which is its own
  ;; first entry, so the search lands where the reader already was.
  ;; Inlined rather than calling `anchor-count`, which is defined further down
  ;; -- and must stay in agreement with it and with `Writer.anchorCount`.
  ;; AT THE NODE'S OWN STRIDE -- see `Writer.keepNode`. An unsorted map is
  ;; written at stride 1 and has `n` anchors, so asking at the file's stride
  ;; refused every unsorted map of `stride` entries or fewer.
  (and (>= (long (let [ns (if (and map? (not sorted)) 1 (long stride))]
                   (if (<= (long n) 0) 0
                       (if (= ns 1) (long n)
                           (inc (quot (dec (long n)) ns)))))) 2)
       (if (or (not map?) sorted)
         (>= (long walk) walk-threshold)
         ;; An unsorted map is written at stride 1 whatever the file's
         ;; stride, so only the size budget decides.
         (<= (* unsorted-anchor-budget (long n)) (long bytes)))))

(defn- frame-payload-array?
  "Is the container at `p` the 2-element `[name, args]` array of a TAG-27 FRAME?

  If it is, it gets no index node. `boring.nav` never descends a tag
  structurally, so a node for this array can never be used -- and the writer's
  index capture does not emit one, so emitting it here made the byte walk and
  the writer disagree about the same value: 306 bytes against 295 for a
  40-entry `sorted-map` at `:index-min 2`, containers `[2 22]` against `[22]`.
  The contents are still descended into and still get their nodes; it is only
  the wrapper that is dropped.

  `q0` is where the tag chain started and `p` where it ended, so the whole
  check costs nothing for an untagged container -- which is nearly all of them,
  and why the chain is re-walked here rather than threaded out of the loop that
  collapsed it. It needs `:index-min` <= 2 to be reachable at all; the default
  of 16 is why this was never seen."
  [^Reader r q0 p mj n]
  (and (= mj 4) (= 2 (long n)) (not= (long p) (long q0))
       (= 27 (long (loop [q (long q0) t -1]
                     (if (= 6 (.majorAt r q))
                       (recur (long (.headEndAt r q)) (long (.headArgAt r q)))
                       t))))))

(defn- index-walk*
  [^Reader r p stride min-entries base ^java.util.ArrayList acc depth ^longs items
   suppress?]
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
        q0 (long p)
        ;; EACH TAG IS ITS OWN ITEM, so the chain is counted as it collapses.
        ;; The writer emits one `head(TAG, ..)` per tag; counting the chain as
        ;; one would make the two builders disagree on any tagged value, which
        ;; is most of what boring emits -- a set is tag 258, a record tag 27, a
        ;; shaped array tag 39649.
        ;; PRIMITIVE LOCALS. This loop is walked for EVERY value, and most of
        ;; what boring emits is tagged -- a set is 258, a record 27, a shaped
        ;; array 39649 -- so an Object-typed `q` here boxed once per tagged
        ;; value and cost +14.7% of the walk's allocation on a tag-heavy
        ;; document (2000 sets: 657 KB against 754 KB).
        ;;
        ;; Two loops rather than one that returns both. Packing the count and
        ;; the offset into a single long would save the second pass, and would
        ;; silently corrupt any file past the packing width -- boring carries
        ;; 64-bit offsets on purpose, and a walk that is right up to 1 TiB is
        ;; not right.
        ntags (long (loop [q (long p) t 0]
                      (if (= 6 (.majorAt r q))
                        (recur (long (.headEndAt r q)) (inc (long t)))
                        t)))
        ;; A SET IS INDEXED NOWHERE -- see `Writer.writeValue`'s Set branch.
        ;; `boring.nav` realises a set whole, so no offset inside one is
        ;; reachable, and the writer emits nothing there. Detected by walking
        ;; the tag chain again, which costs nothing for the untagged container
        ;; that nearly every container is.
        set? (boolean (loop [q (long p)]
                        (if (= 6 (.majorAt r q))
                          (if (= 258 (long (.headArgAt r q)))
                            true
                            (recur (long (.headEndAt r q))))
                          false)))
        suppress? (boolean (or suppress? set?))
        p (long (loop [q (long p)]
                  (if (= 6 (.majorAt r q)) (recur (long (.headEndAt r q))) q)))
        mj (.majorAt r p)]
    (if-not (or (= mj 4) (= mj 5))
      ;; `skipCountingFrom`, not `skipFrom`: the items inside a scalar or a
      ;; string are part of the enclosing container's walk, and a byte string
      ;; of any size is ONE of them.
      (let [e (.skipCountingFrom r p)]
        (aset items 0 (+ (aget items 0) ntags (.skipItemCount r)))
        e)
      (let [n (.headArgAt r p)
            map? (= mj 5)]
        (if (neg? n)
          ;; Indefinite length: not indexable, but still walked and still
          ;; counted -- its items are part of its PARENT's walk.
          (let [e (.skipCountingFrom r p)]
            (aset items 0 (+ (aget items 0) ntags (.skipItemCount r)))
            e)
            ;; Only containers we will KEEP get an array, and it holds one entry
            ;; per ANCHOR rather than one per entry. The old version allocated
            ;; `(int-array n)` for every container before testing `min-entries`,
            ;; then copied every stride-th element into a second array -- so a
            ;; document of small maps allocated one throwaway array per map and
            ;; threw it away, which is why raising :index-min barely helped.
          (let [;; THE DECLARED COUNT IS CHECKED AGAINST THE BYTES THAT
                ;; REMAIN, before anything is sized from it. `ba 7fffffff 01`
                ;; -- six bytes -- declares 2^31-1 map pairs, and `m` below
                ;; then asked for a long[] of that many entries:
                ;; `OutOfMemoryError` out of a public entry point, on bytes
                ;; somebody else wrote. `Reader.checkCount` has always done
                ;; this for the decoder; the index walk sizes an array from a
                ;; wire count too and was never given the same guard.
                ;;
                ;; A map pair costs at least two bytes and an array element at
                ;; least one, so anything larger than that cannot be present
                ;; however the rest of the document is arranged.
                ;; `>` against a HALVED budget rather than a doubled count.
                ;; `(* 2 n)` is checked arithmetic, so a map declaring 2^63-1
                ;; pairs overflowed and threw a raw ArithmeticException out of
                ;; the guard whose job is to make this input typed -- 37 of
                ;; 60000 fuzz probes, and introduced by the guard itself.
                avail (- (.size r) (long (.headEndAt r p)))
                _ (let [budget (if map? (quot avail 2) avail)]
                    (when (> n budget)
                      (throw (ex-info (str "boring: container at " p " declares " n
                                           (if map? " pairs" " elements")
                                           " but only " avail " bytes remain")
                                      {:type :boring/bad-count :count n :offset p}))))
                keep? (and (not suppress?)
                           (>= n min-entries)
                           (not (frame-payload-array? r q0 p mj n)))
                ;; A MAP CAPTURES ONE ANCHOR PER ENTRY, whatever the file's
                ;; stride, and is narrowed below once `sorted` is known -- an
                ;; unsorted map is usable only at stride 1 and a sorted one is
                ;; better served by the file's stride, and which it is cannot
                ;; be known until the last key has been compared. Same order as
                ;; `Writer.downsample`.
                ;; STRIDE 1 ONLY WHERE IT COULD BE KEPT. Capturing a map at
                ;; stride 1 costs `n` longs, and `build-index` is public and
                ;; takes bytes somebody else wrote: a minimal map pair is two
                ;; bytes, so a well-formed 60 MB file declared 30M pairs and
                ;; the walk allocated 240 MB before `keep-node?` refused the
                ;; node. `OutOfMemoryError` is an Error, so it escaped
                ;; `build-index`'s catch entirely.
                ;;
                ;; `bytes` is not known until the container ends, but `avail`
                ;; -- what is left in the file -- bounds it. So when the anchor
                ;; budget already fails against `avail` it must fail against
                ;; `bytes` too, the unsorted branch is refused whatever the
                ;; keys turn out to be, and capturing finer than the file's
                ;; stride cannot change a single decision.
                ;; AGAINST THE SAME SPAN THE WRITER MEASURES. `avail` starts
                ;; after this container's HEAD, because it exists to bound the
                ;; payload for the allocation guard above. `Writer.keepNode`
                ;; takes `absOffset() - off`, which INCLUDES the head -- so the
                ;; two differed by the head's width, and at the exact boundary
                ;; they chose different strides for the same container. A
                ;; 9-entry unsorted map spanning exactly 90 bytes: the writer
                ;; saw 90 <= 90 and captured at stride 1 (nine anchors), the
                ;; walk saw 90 <= 89 and captured at the file's stride (one).
                ;; Same node, same count, same `sorted`, different anchors --
                ;; two builders writing different files, which is the one thing
                ;; the differential test exists to prevent. Found by that test,
                ;; intermittently, because only the exact boundary shows it.
                span-bound (- (.size r) (long p))
                cap-stride (if (and map? (<= (* unsorted-anchor-budget (long n))
                                             (long span-bound)))
                             1 stride)
                m (if keep?
                      ;; An empty container needs no anchors. The `(max n 1)`
                      ;; this replaces yielded ONE for n=0, and the loop never
                      ;; wrote it, leaving a phantom offset pointing at the
                      ;; document's start. Same defect as Writer.anchorCount.
                    (cond (<= n 0) 0
                          (= cap-stride 1) n
                          :else (inc (quot (dec n) cap-stride)))
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
                ;; The tag chain and this container's OWN head, charged before
                ;; any entry -- so `at-start` below is the count with the head
                ;; already paid, and no entry's prefix includes it. The Java
                ;; side captures `itemsAtStart` after `head(..)` for the same
                ;; reason.
                _ (aset items 0 (+ (aget items 0) ntags 1))
                at-start (aget items 0)
                walk-acc (long-array 1)
                end (loop [i 0 q (long (.headEndAt r p)) prev -1]
                      (if (= i n)
                        q
                        (do (when (and keep? (zero? (rem i cap-stride)))
                              (aset ^longs kept (quot i cap-stride) (long q)))
                            (when (and srt (aget ^booleans srt 0) (>= (long prev) 0)
                                       (>= (.compareItemsAt r (long prev) q) 0))
                              (aset ^booleans srt 0 false))
                            ;; BEFORE the entry is walked, not in the `recur`
                            ;; form: `recur`'s arguments are evaluated left to
                            ;; right and the position argument contains the
                            ;; recursive calls, so a prefix computed there
                            ;; would already include the entry it precedes.
                            ;;
                            ;; One prefix per ENTRY -- for a map that is the
                            ;; key and value together, since a scan reaching
                            ;; entry j crosses both halves of every earlier one.
                            (when keep?
                              (aset walk-acc 0 (+ (aget walk-acc 0)
                                                  (- (aget items 0) at-start))))
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
                                            ;; THE KEY, WITH NODES SUPPRESSED.
                                            ;; `boring.nav` realises a map key
                                            ;; -- an entry is
                                            ;; `MapEntry(realize(k), cursor(v))`
                                            ;; -- so nothing inside one is
                                            ;; reachable. The writer suspends
                                            ;; capture over exactly this span.
                                            (long (index-walk* r q stride min-entries base acc
                                                               (inc (long depth)) items true))
                                            q)
                                          stride min-entries base acc (inc (long depth))
                                          items suppress?))
                                   q))))]
            (when keep?
                ;; Decided on RAW offsets, before `base` is folded in: the
                ;; Reader is positioned over this item's own buffer.
              (let [sorted (boolean (and srt (aget ^booleans srt 0)))
                    walk (if (pos? n) (quot (aget walk-acc 0) n) 0)
                    ;; NARROWED HERE, now that `sorted` is known. See
                    ;; `Writer.downsample`.
                    ;; `(pos? n)` because this runs BEFORE the keep decision,
                    ;; and an empty container derives `mm` of 1 from a
                    ;; zero-length capture -- `(quot -1 16)` truncates to 0.
                    ;; It is refused a line later, but not before this reads
                    ;; anchor 0 of nothing.
                    ;; `(= cap-stride 1)` because narrowing reads
                    ;; `kept[a*stride]`, which only exists if the capture was
                    ;; at stride 1. When the anchor budget already failed
                    ;; against `avail` the capture was taken at the file's
                    ;; stride and is already the right shape.
                    ^longs kept (if (and map? sorted (pos? n)
                                         (= (long cap-stride) 1) (> (long stride) 1))
                                  (let [mm (inc (quot (dec n) (long stride)))
                                        out (long-array mm)]
                                    (dotimes [a mm]
                                      (aset out a (aget ^longs kept (* a (long stride)))))
                                    out)
                                  kept)
                    ;; SLOTS ARE REBASED TOO, and the container offset alone was
                    ;; not enough. A node is (container-offset, count, entry
                    ;; offsets), and every one of those is a position in the
                    ;; reader's buffer -- so folding `base` into the first and
                    ;; not the rest produced a node pointing at the right
                    ;; container and the wrong entries.
                    ;;
                    ;; This branch existed once, carried an `^ints` hint that
                    ;; went stale when `kept` widened to a long[], and was
                    ;; removed as dead code on the grounds that `base` is always
                    ;; 0. It is no longer always 0: an item embedded at an
                    ;; offset -- konserve-lmdb's split blob puts its value after
                    ;; a five-byte header -- needs its index expressed in the
                    ;; enclosing buffer's coordinates, or `nav/cursor` finds
                    ;; a frame whose every offset is wrong by the prefix.
                    ^longs kept (if (zero? base)
                                  kept
                                  (let [^longs k kept
                                        out (long-array (alength k))]
                                    (dotimes [i (alength k)]
                                      (aset out i (+ base (aget k i))))
                                    out))]
                ;; FLOOR DIVISION, matching `Writer.fillNode`. `walk-acc` is
                ;; the sum of per-entry prefixes; `walk` is their mean.
                ;; `(- end p)` is the container's own byte span, in the
                ;; reader's own coordinates -- `base` belongs to the node's
                ;; OFFSET, not to its length.
                (when (keep-node? map? sorted walk stride n (- (long end) (long p)))
                  (.add acc [(int (+ base p)) (int n) kept sorted walk]))))
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
     :sorted (mapv #(nth % 3) idx)
     ;; `walk` NEVER REACHES THE WIRE. `seal-index-with!` writes the six frozen
     ;; payload elements and this is not one of them; it is a decision input,
     ;; carried here only so the writer's capture and this walk can be held to
     ;; the same number before either is allowed to act on it.
     :walk (mapv #(nth % 4) idx)}))

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
  ([^bytes bs] (build-index bs nil 0))
  ([^bytes bs opts] (build-index bs opts 0))
  ([^bytes bs opts base]
   ;; RESOLVED, like every other public entry point, even though nothing here
   ;; encodes. It read `:index` its own way -- `(if (and i (pos? (long i))) i 16)`
   ;; -- so `:index 0`, the documented off switch, silently became stride 16;
   ;; `:index 1.5` became stride 1; `:index -1` became 16; and `:index "x"` was
   ;; a raw ClassCastException. Going through the one gate is what makes that
   ;; class of drift impossible rather than fixed.
   (let [opts (resolve-opts opts)
         r (Reader. (bytes! bs "build-index"))
         stride (let [i (long (get opts :index default-index-stride))]
                  ;; 0 means "no index" everywhere else, and building an index
                  ;; with no index is not a thing this function can do -- it
                  ;; used to silently substitute 16, so the documented off
                  ;; switch produced a LARGER file than omitting the option.
                  ;; Naming `encode` is more use than a stride nobody asked for.
                  (when (zero? i)
                    (throw (ex-info (str "boring: :index 0 turns indexing off, which "
                                         "build-index cannot do; use `encode` instead")
                                    {:type :boring/bad-option :option :index :value 0})))
                  i)
         min-entries (long (get opts :index-min 16))
         idx (try
               (scan-index r 0 (alength bs) stride min-entries (long base))
               (catch StackOverflowError _
                 ;; The depth bound above is deterministic, but it is calibrated
                 ;; against a default stack; a smaller -Xss can still reach the
                 ;; real limit first. A public entry point that takes bytes
                 ;; somebody else wrote may not answer with an Error.
                 (throw (ex-info "boring: index walk ran out of stack; this document is too deeply nested to index"
                                 {:type :boring/max-depth-exceeded}))))]
     (when (pos? (alength ^longs (:containers idx)))
       (cond-> (assoc idx :stride stride)
         ;; ONLY FOR A DOCUMENT THAT OPENS A NAMESPACE, which is the test
         ;; `derive-stringref-pointers` makes first -- three bytes, and it is
         ;; false for every non-stringref document, so nothing that does not
         ;; use the feature walks anything twice.
         (.hasStringrefRoot r)
         (assoc :stringrefs (derive-stringref-pointers r (alength bs))))))))

(def ^:no-doc ^:const slot-layout-v2
  "Version nibble for a DENSE start table -- one entry per node.

  v1 was sparse, one entry every 16 nodes, on the reasoning that a bounded
  block walk was the same win an order of magnitude cheaper in bytes. Measured
  on a 770-node frame, that was wrong: a block-aligned node resolves in
  0.073 us and one at the end of its block in 0.376 -- the walk costs ~0.30 us,
  FIVE TIMES the base, and averages ~0.22 across a block. Every number that
  made sparse look free had been taken on node 0, which is block-aligned, so
  the walk never ran.

  Dense costs 1542 bytes against sparse's 98 on that frame -- but 1.85% of the
  BLOB, which is the denominator that matters, against the +84%-of-`slots`
  figure the sparse decision was anchored on. It is also simpler: `start_i` is
  one read, there is no block loop, and `counts` stops being needed to locate a
  node at all.

  The layout byte is what makes this cost nothing to change, which is the
  reason it exists."
  2)

(defn- slot-width-code
  "The narrowest of four widths that holds every delta in `d`: 0 = u8, 1 = u16,
  2 = i32, 3 = i64.

  On 50 000 ~66-byte log records the per-item deltas span 60..67, which is a
  byte, against int32 absolutes reaching into the millions -- so the width is
  worth choosing, and worth choosing PER NODE. See `pack-slots`."
  ^long [^longs d]
  (let [n (alength d)]
    (loop [i 0 mn Long/MAX_VALUE mx Long/MIN_VALUE]
      (if (= i n)
        (cond
          (or (zero? n) (and (>= mn 0) (<= mx 0xFF))) 0
          (and (>= mn 0) (<= mx 0xFFFF)) 1
          (and (>= mn Integer/MIN_VALUE) (<= mx Integer/MAX_VALUE)) 2
          ;; The fourth tier. Only reachable when two anchors are more than
          ;; 2 GiB apart, which needs items that large -- but it costs nothing
          ;; to have, because a narrower node is still emitted beside it.
          :else 3)
        (let [v (aget d i)]
          (recur (inc i) (min mn v) (max mx v)))))))

(defn- anchor-count
  "How many anchors a container of `n` entries carries at `stride`.

  The reader derives every node's segment length from this rather than reading
  it, which is what keeps `slots` a single flat byte string. It must agree
  EXACTLY with `Writer.anchorCount` and with what the walk emits -- a
  disagreement would silently shift every subsequent node's anchors. Both index
  builders were checked against it across strides 1/4/16 and five document
  shapes before the derivation replaced the stored lengths.

  An EMPTY container needs no anchors: `((0 - 1) / stride) + 1` is 1 under
  truncating division, which would claim a phantom anchor at offset 0."
  ^long [^long n ^long stride]
  (if (<= n 0) 0 (if (= stride 1) n (inc (quot (dec n) stride)))))

(defn- pack-slots
  "Every node's anchor deltas, as ONE byte string.

      [ layout byte | 2-bit width code per node | dense start table |
        node 0's deltas | node 1's deltas | ... ]

  The width codes come first, `(quot (+ n 3) 4)` bytes of them, node `i` at bit
  `(* 2 (rem i 4))` of byte `(+ 1 (quot i 4))` -- the layout byte is byte 0: 0 = u8, 1 = u16, 2 = i32, 3 = i64,
  little-endian. Then each node's deltas back to back at its own width. A node's
  segment LENGTH is not stored -- it is `anchor-count` of that node's entry
  count, which the frame already carries.

  This replaced one typed array PER NODE. That shape cost three ways and this
  costs none of them: every small array carried its own CBOR head (a byte a
  node, 33% of the slots on small deltas), decoding them all materialised a Java
  array per container to serve a lookup that visits one or two, and locating
  them positionally instead meant a head-read and a jump per node before any
  lookup could start. Measured on a 767-node frame, the two together were 53.5
  of the 92 ns per node it cost to open an index; this is 5.3.

  A SINGLE FLAT TYPED ARRAY was the obvious alternative and is the wrong one.
  It is faster still -- 0.53 ns/node, since it needs no width dispatch -- but
  one array has one width, so a single container with 32 KiB between anchors
  promotes every other node to int32. Measured: 33% SMALLER than the old shape
  when all deltas are small, 166% LARGER when one node among 767 is wide. That
  is not a corner: a map of small values beside one large blob is the ordinary
  shape of scraped data. Per-node widths keep the narrow case narrow and make
  the wide case cost only the node that is wide.

  Unsigned u8 and u16 rather than the byte-string/sint16 pair the typed arrays
  forced. The old second tier was SIGNED and so capped at 0x7FFF, sending
  32 KiB..64 KiB deltas to int32; nothing here is a CBOR typed array, so the
  full 16 bits are usable and that band stays two bytes wide."
  ^bytes [slots ^longs containers ^ints counts ^long stride]
  (let [n (count slots)
        ds (object-array n)
        ws (byte-array n)]
    (dotimes [i n]
      (let [^longs offs (nth slots i)
            m (alength offs)
            d (long-array m)]
        ;; THE INVARIANT THE WHOLE LAYOUT RESTS ON, checked where it is
        ;; established rather than assumed where it is used. No segment length
        ;; goes on the wire: the reader slices `packed` by `anchor-count` of
        ;; each node's entry count, so a walk that ever emitted a different
        ;; number of anchors would not corrupt one node, it would shift every
        ;; node after it -- silently, into other nodes' deltas. Cheap: one
        ;; integer per node, on the writing side, which is the expensive side.
        ;; EITHER OF THE TWO LEGAL STRIDES. Stride is per node now: an unsorted
        ;; map is only usable at 1, everything else takes the file's. So a
        ;; node's anchor count must match `anchor-count` at ONE of them, and
        ;; which one is not stored -- the reader derives it, because a node
        ;; whose anchors equal its entries can only have been written at 1.
        ;;
        ;; Still the invariant the layout rests on, just widened by one case:
        ;; no segment length goes on the wire, so a walk that emitted some
        ;; other number would shift every node after it.
        (when-not (or (= m (anchor-count (aget counts i) stride))
                      (= m (anchor-count (aget counts i) 1)))
          (throw (ex-info (str "boring: index node " i " has " m " anchors but "
                               (aget counts i) " entries implies "
                               (anchor-count (aget counts i) stride) " at stride "
                               stride " or " (anchor-count (aget counts i) 1)
                               " at stride 1."
                               " The index walk and `anchor-count` disagree.")
                          {:type :boring/bad-index :node i :anchors m
                           :entries (aget counts i) :stride stride})))
        ;; Offsets inside a container ascend, so consecutive differences are
        ;; small and nearly uniform while the absolutes are large and
        ;; unbounded. That is the whole saving. `base` is the container's own
        ;; offset, or 0 for the sequence node, whose sentinel offset is -1 --
        ;; so slot[0] is a header width rather than a file position, which
        ;; keeps the first delta as narrow as the rest.
        (loop [k 0 prev (max 0 (aget containers i))]
          (when (< k m)
            (let [v (aget offs k)]
              (aset d k (- v prev))
              (recur (inc k) v))))
        (aset ds i d)
        ;; A negative delta cannot arise from a walk -- entries ascend and no
        ;; CBOR item is zero bytes -- but u8 and u16 cannot represent one, so
        ;; `slot-width-code` promotes to a signed tier rather than wrapping.
        ;; That makes it an assumption about the walk, not about the file.
        (aset-byte ws i (byte (slot-width-code d)))))
    (let [wbytes (quot (+ n 3) 4)
          seg (fn ^long [^long i] (* (alength ^longs (aget ds i))
                                     (bit-shift-left 1 (aget ws i))))
          deltas (loop [i 0 t 0] (if (= i n) t (recur (inc i) (+ (long t) (long (seg i))))))
          entries (inc n)          ; DENSE: start_0 .. start_n
          ;; W depends on the total, and the total depends on W. Two iterations
          ;; settle it: widening the table can only push the total up by
          ;; `entries * 2`, never back down.
          w-start (loop [sw 2]
                    (let [tot (+ 1 wbytes (* entries sw) deltas)]
                      (if (or (= sw 4) (< tot 0x10000)) sw (recur 4))))
          header (+ 1 wbytes (* entries w-start))
          total (+ header deltas)
          out (byte-array total)
          put! (fn [^long o ^long v ^long sz]
                 (dotimes [j sz]
                   (aset-byte out (+ o j) (unchecked-byte (bit-shift-right v (* 8 j))))))]
      ;; The layout byte: version in the low nibble, the start table's entry
      ;; width in the high nibble. It is what makes a frame in the PREVIOUS
      ;; shape refusable exactly rather than probabilistically -- a reader that
      ;; expects this layout and meets the old one reads a width code where a
      ;; version should be, and says so, instead of computing nonsense offsets
      ;; and hoping the length check catches them.
      (aset-byte out 0 (unchecked-byte (bit-or slot-layout-v2
                                               (bit-shift-left (if (= w-start 4) 1 0) 4))))
      (dotimes [i n]
        (let [b (+ 1 (quot i 4))]
          (aset-byte out b (unchecked-byte
                            (bit-or (bit-and 0xFF (aget out b))
                                    (bit-shift-left (aget ws i) (* 2 (rem i 4))))))))
      ;; THE DENSE START TABLE, one entry per node plus a final entry holding
      ;; the total. Node i's deltas begin at entry i -- ONE READ, where this
      ;; was a prefix sum over every earlier node, which is what let the reader
      ;; stop materialising `slot-starts` at all.
      ;;
      ;; The final entry is also the structural gate: it must equal the byte
      ;; string's own length, which is one read instead of a sum over N.
      (let [tbase (+ 1 wbytes)]
        (loop [i 0 p (long header)]
          (put! (+ tbase (* i w-start)) p w-start)
          (when (< i n) (recur (inc i) (+ (long p) (long (seg i)))))))
      (loop [i 0 p (long header)]
        (if (= i n)
          out
          (let [^longs d (aget ds i)
                sz (bit-shift-left 1 (aget ws i))
                m (alength d)]
            (dotimes [k m] (put! (+ p (* k sz)) (aget d k) sz))
            (recur (inc i) (+ p (* m sz)))))))))

(defn- pack-sorted
  "One bit per node -- does this container's keys ascend -- as a byte string,
  node `i` at bit `(rem i 8)` of byte `(quot i 8)`.

  This was an array of CBOR booleans, one item each. Measured on a 767-node
  frame: 770 bytes and 8.1 us to decode, against 98 bytes and 0.04 us here.
  Two hundred times faster and eight times smaller, for a bit vector that CBOR
  had no compact form for. The byte length is exact -- `(quot (+ n 7) 8)` --
  which is also what lets the reader check it against the node count."
  ^bytes [sorted]
  (let [v (vec sorted)
        n (count v)
        out (byte-array (quot (+ n 7) 8))]
    (dotimes [i n]
      (when (nth v i)
        (let [b (quot i 8)]
          (aset-byte out b (unchecked-byte
                            (bit-or (bit-and 0xFF (aget out b))
                                    (bit-shift-left 1 (rem i 8))))))))
    out))

(def ^:no-doc ^:const stringref-layout-v1
  "Version nibble for the stringref pointer table -- payload element 6.

  Its own version rather than the frame's, for the reason every other element
  carries one: elements are optional and independently extensible, so a reader
  that meets a shape it does not know must be able to say which element it
  failed on."
  1)

(defn- unsigned-width-code
  "The narrowest unsigned width holding `mx`: 0 = u8, 1 = u16, 2 = u32, 3 = u64.

  UNSIGNED, unlike `slot-width-code`, whose upper two tiers are signed because
  a delta can in principle be negative. Neither a stringref index nor a file
  offset can be, so the sign bit is a byte-eighth this can spend on range --
  and it is the difference between a 64 KiB and a 4 GiB document keeping
  two-byte offsets."
  ^long [^long mx]
  (cond (< mx 0x100)        0
        (< mx 0x10000)      1
        (< mx 0x100000000)  2
        :else               3))

(defn- pack-stringrefs
  "The referenced stringref slots, as ONE byte string.

      [ layout byte | (index, offset) | (index, offset) | ... ]

  The layout byte is version in the low nibble, the index width code in bits
  4-5 and the offset width code in bits 6-7. Pairs follow back to back,
  ascending by index, each field little-endian at its own width. THE COUNT IS
  NOT STORED: it is `(quot (dec len) (+ iw ow))`, and the structural gate is
  that the division is exact -- one modulo, no sum over N, exactly as the slot
  table's final entry gates that layout.

  WHY THIS EXISTS. A stringref is an index into a table built by decoding every
  preceding string, so a reader holding nothing but an offset cannot resolve
  one -- which is why `:stringref` and `:index` were mutually exclusive. Given
  (index -> defining offset) for the entries something actually references, the
  reader resolves by jumping instead of by remembering, and the two compose.

  ONLY REFERENCED ENTRIES. A registered-but-unreferenced slot is one nothing
  will ever ask for, and on konserve-shaped data most slots are exactly that:
  200 records carry ~256 registered strings and ~56 referenced ones. Dense
  offsets would be 768 bytes where these are 224.

  TWO WIDTHS, NOT ONE. Indices are small and dense from zero while offsets span
  the document, so a single width would promote every index to the offset's
  tier -- the same mistake `pack-slots` records for a single flat typed array,
  in miniature.

  `pairs` is the interleaved `long[]` from `Writer.stringrefPointers`, already
  ascending; empty in means nil out, and the element is then omitted entirely."
  ^bytes [^longs pairs]
  (when (pos? (alength pairs))
    (let [n (quot (alength pairs) 2)
          mx-i (loop [k 0 m 0] (if (= k n) m (recur (inc k) (max m (aget pairs (* 2 k))))))
          mx-o (loop [k 0 m 0] (if (= k n) m (recur (inc k) (max m (aget pairs (inc (* 2 k)))))))
          ic (unsigned-width-code mx-i)
          oc (unsigned-width-code mx-o)
          iw (bit-shift-left 1 ic)
          ow (bit-shift-left 1 oc)
          out (byte-array (+ 1 (* n (+ iw ow))))
          put! (fn [^long o ^long v ^long sz]
                 (dotimes [j sz]
                   (aset-byte out (+ o j) (unchecked-byte (bit-shift-right v (* 8 j))))))]
      (aset-byte out 0 (unchecked-byte (bit-or stringref-layout-v1
                                               (bit-shift-left ic 4)
                                               (bit-shift-left oc 6))))
      (dotimes [k n]
        (let [p (+ 1 (* k (+ iw ow)))]
          (put! p (aget pairs (* 2 k)) iw)
          (put! (+ p iw) (aget pairs (inc (* 2 k))) ow)))
      out)))

(defn- min-len-for-index
  "The shortest encoded length that earns stringref index `idx`.

  Must agree EXACTLY with `Writer.minLenForIndex` and `Reader.minLenForIndex`:
  all three decide independently whether a given string took an index at all,
  and a disagreement shifts every later index rather than one."
  ^long [^long idx]
  (cond (< idx 24)    3
        (< idx 256)   4
        (< idx 65536) 5
        :else         7))

(defn- derive-stringref-pointers
  "The pointer table for an already-encoded document, or nil.

  The counterpart of `Writer.stringrefPointers` for the byte-walk builder, and
  it exists because the two builders must write the SAME FILE. `write-indexed!`
  streams through the Writer and takes the pointers from its symbol table;
  `encode-indexed` and `build-index` walk finished bytes and have no symbol
  table, so they re-derive the numbering from the only thing that defines it --
  the order literals appear in.

  A FLAT LOOP OVER ITEM HEADS, with no recursion and no structure. CBOR is
  prefix-encoded, so a container's elements follow its head IMMEDIATELY in the
  byte stream: stepping past each head in turn therefore visits every item in
  exactly the order a decoder reads them, which is exactly the order the
  stringref index space is defined by. Only a string's PAYLOAD has to be
  stepped over, because payload bytes are not items.

  That is also why this needs no depth bound and cannot overflow a stack, where
  the container walk in `index-walk*` needs both -- it has to know the shape,
  and this does not.

  nil for anything that cannot reproduce the decoder's numbering: a document
  that opens no namespace, an indefinite-length item, or a NESTED tag 256,
  which resets the table so that one index space no longer describes the
  document. nil means no pointer table, and such a document is then refused as
  unnavigable -- the honest outcome, never a table that is subtly wrong."
  ^longs [^Reader r ^long end]
  (when (.hasStringrefRoot r)
    (let [offs (java.util.ArrayList.)
          refs (java.util.HashSet.)
          ok (loop [p (long (.headEndAt r 0))]
               (if (>= p end)
                 true
                 (let [info (.infoAt r p)]
                   (if (= info 31)
                     false                        ; indefinite: not reproduced
                     (let [major (.majorAt r p)
                           after (long (.headEndAt r p))]
                       (case major
                         (2 3) (let [n (long (.headArgAt r p))]
                                 ;; THE HEAD IS RECORDED, not the payload: that
                                 ;; is what a reader jumps to and reads from.
                                 (when (>= n (min-len-for-index (.size offs)))
                                   (.add offs (Long/valueOf p)))
                                 (recur (+ after n)))
                         6 (let [tag (long (.headArgAt r p))]
                             (cond
                               ;; A nested namespace shadows the outer one, so
                               ;; every index below it means something else.
                               (= 256 tag) false
                               (= 25 tag) (if (zero? (.majorAt r after))
                                            (do (.add refs (Long/valueOf (.headArgAt r after)))
                                                (recur after))
                                            false)
                               :else (recur after)))
                         ;; Containers advance past the head only -- their
                         ;; elements are the next items. Scalars are their head.
                         (recur after)))))))]
      (when ok
        (let [ks (sort (filter #(< (long %) (.size offs)) refs))
              out (long-array (* 2 (count ks)))]
          (loop [i 0 ks ks]
            (when (seq ks)
              (let [k (long (first ks))]
                (aset out i k)
                (aset out (inc i) (long (.get offs (int k))))
                (recur (+ i 2) (next ks)))))
          out)))))

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

  BOTH ARE BYTE STRINGS, not CBOR arrays. `slots` is every node's anchor deltas
  concatenated, each node at its own width, behind a packed table of 2-bit width
  codes (`pack-slots`); `sorted` is a bit per node (`pack-sorted`). Neither
  carries per-node framing, and a node's segment length is DERIVED from the
  entry count already in `counts`.

  Measured on 768 nodes of 20 entries at stride 1, the same document through
  both layouts: opening the index went from 91.6 ns per node to 22.2, a little
  over fourfold. The frame shrank by 1.0% -- 124312 bytes to 123061 -- which is
  the part NOT to generalise from: the saving is one CBOR head per node plus
  seven eighths of `sorted`, so it is proportional to the node count and not to
  the document, and it is worth most exactly where the old shape was worst.
  Speed is the reason to do this; size is a rebate.

  Slots go out as DELTAS and a reader expands them back to absolutes on first
  use. Deltas and absolutes are indistinguishable on the wire, which is why this
  had to land before the format was published rather than after.

  THE PAYLOAD IS SIX ELEMENTS, which is what `boring.frame/prefix-bytes`
  emits. Readers accept six THROUGH FIFTEEN -- see
  `boring.frame/payload-count-bytes` -- so that a future widening is
  RECOGNISED rather than republished as a trailing data item; nothing here
  writes more than six. A reader too old for this shape
  finds a byte string where it expects an array, fails its own structure check
  and scans, which is the documented answer for an index it cannot use. A reader
  new enough finds an array where it expects a byte string and does the same.
  Neither direction can answer WRONGLY; both lose the index and keep the file.

  The trailing element is a byte string of exactly 8 bytes, so it always encodes
  as `0x48` plus 8: a sealed file ends with 9 predictable bytes however large
  the index is. That is how it is found, since CBOR cannot be parsed backwards.

  Those 9 bytes are ordinary CBOR, not a magic trailer. The file stays a valid
  sequence that any reader consumes -- it just sees one extra tagged item.

  The pointer verifies as well as locates: it is both where the index starts and
  how long the data is, so a reader that seeks there and does not find a tag-27
  frame named `boring/index` knows the index is stale and scans instead."
  ([^Writer w ^java.io.OutputStream out index data-len]
   ;; The writer's options were resolved when the writer was built, so this
   ;; arity is already past the gate.
   (if (nil? index) 0 (seal-index-with! w out index data-len (writer-opts w))))
  ([^Writer w ^java.io.OutputStream out index data-len opts]
   ;; RESOLVED, like every other public entry point. This was the one that was
   ;; not: every option reached `configure!` unchecked, so `{:float-policy
   ;; :nope}` silently selected :shortest, `{:encode-fallback :placehodler}`
   ;; installed the keyword as the fallback function, and `{:max-depth "5"}`
   ;; left as a raw ClassCastException. The frame itself carries no floats and
   ;; no maps, so no wire corruption was reachable through it -- but a public
   ;; entry point that accepts garbage and fails untyped is exactly what the
   ;; option gate exists to make impossible, and `boring.options` claims in its
   ;; own docstring that resolution is the one thing every entry point does.
   ;; NIL IS THE DOCUMENTED "nothing was worth indexing" from `build-index`,
   ;; and the two are documented as a pair -- so the pairing NPE'd on any
   ;; document whose containers all sat below `:index-min`, which is the
   ;; common case for small values. Sealing nothing writes nothing.
   (if (nil? index)
     0
     (seal-index-with! w out index data-len (resolve-opts opts)))))

(defn- seal-index-with!
  [^Writer w ^java.io.OutputStream out index data-len opts]
  (letfn [(emit! [item]
            ;; `write-root!`, not `write-to!`: `opts` here are ALREADY RESOLVED,
            ;; and write-to!'s 3-arity resolves again -- which throws for every
            ;; profile that locks a key, the same double-resolution trap as
            ;; `write-seq!`'s 3-arity.
            ;;
            ;; THE FRAME IS NEVER WRITTEN INSIDE A STRINGREF NAMESPACE, whatever
            ;; the data's options are. `write-root!` opens one whenever
            ;; `:stringref` is set, and that prepends `d9 01 00` to the frame --
            ;; which shifts the whole 17-byte prefix `read-index` compares
            ;; against, so the frame stops being recognised and the index is
            ;; SILENTLY DEAD. The comment below records this happening once
            ;; before, when the frame picked up the writer's options instead of
            ;; the caller's; now that `:stringref` and `:index` can be combined
            ;; it would happen to every indexed document rather than to a
            ;; mismatched few.
            ;;
            ;; Nothing is lost: a frame's only text is `boring/index`, which
            ;; occurs once, and the namespace resets per top-level item anyway,
            ;; so the data's own references are unaffected by this.
            (let [n (long (.position ^Writer (write-root! w item (assoc opts :stringref false))))]
              (.write out (.buffer w) 0 (int n))
              n))]
    (let [{:keys [stride ^longs containers counts slots sorted stringrefs]} index
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
          ;; OMITTED WHEN THERE IS NOTHING TO POINT AT, which is every document
          ;; written without a stringref namespace and every one whose strings
          ;; never repeat. Existing files stay byte-identical rather than
          ;; gaining an empty element.
          srp (when stringrefs (pack-stringrefs stringrefs))
          ;; `data-end` IS LAST, AND THAT IS LOAD-BEARING -- it is not merely
          ;; the sixth thing in a list. `nav/read-index` finds the whole frame
          ;; by reading the FINAL NINE BYTES of the file: an 8-byte byte string
          ;; always encodes as 0x48 plus its eight bytes, so those nine are a
          ;; recognisable trailer holding a back-pointer to the frame's start.
          ;; CBOR cannot be parsed backwards; this is the only way in.
          ;;
          ;; So a new element APPENDED after it is not the harmless extra that
          ;; #20's 6-15 prefix widening makes it look like. Tried, and the file
          ;; then ended in pointer-table bytes: the trailer no longer sits at
          ;; the end, `read-index` finds nothing, and every indexed document
          ;; silently falls back to scanning.
          ;;
          ;; Optional elements therefore go BETWEEN `sorted` and `data-end`,
          ;; and the rule for readers is "data-end is the LAST element", never
          ;; "data-end is element 5". nav does not read it positionally at all
          ;; -- `payload-offsets` stops at `sorted` and the back-pointer IS the
          ;; data end -- so shifting it costs existing readers nothing.
          item (data/unknown-record
                index-name
                (-> [(long stride) wire-containers counts
                     (pack-slots slots containers counts (long stride))
                     (pack-sorted sorted)]
                    (cond-> srp (conj srp))
                    (conj (long->8-bytes* (long data-len)))))]
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

  Bounded memory means bounded by the LARGEST SINGLE ITEM, not by the stream.
  That is the real limit and it is not a compromise: an item has to fit in
  memory to be a Clojure value at all, so streaming can only ever mean a
  sequence of items -- which is exactly what a datahike dump is.

  The CONSTANT on that bound is more than one, and it rises with item size.
  Measured as bytes allocated per byte of file, over 32 MiB of byte-string
  items, against `decode-seq` on the same bytes in the same run:

      item size    decode-seq-from     decode-seq (control)
      1 MiB              1.13x                1.00x
      4 MiB              1.50x                1.00x
     16 MiB              3.00x                1.00x

  Linear in the file, not quadratic -- I looked for that specifically -- but
  tracking the LARGEST ITEM rather than the chunk, because that is what `grow`
  sizes the buffer for.

  `refill!` used to end with `(.reset r (Arrays/copyOf buf new-limit))`,
  allocating a full second copy of that already-doubled buffer on EVERY refill,
  purely because `Reader.reset` had no length form and an array's own length
  was the only limit it could take. `Reader.reset(byte[], int)` removes the
  copy -- the reader is pointed at the first `new-limit` bytes of the buffer it
  already has. That is what ClojureScript's refill always did with
  `(.subarray buf 0 new-limit)`, so the two now agree in shape as well as in
  result. (The figures above are after the change; the ones this docstring
  carried before it were 3.2x, 3.6x and 5.5x, from a separate run.)

  The reader's hot path is untouched. Refilling happens between items, not
  inside `u8()`, so this costs nothing when decoding from a byte array.

  `:chunk-size` (default 64 KiB) is how much is pulled from the stream at a
  time. The caller owns the stream and should close it."
  ([^java.io.InputStream in] (decode-seq-from in nil))
  ([^java.io.InputStream in opts]
   (let [o     (opt/check-opts opts)
         ;; `:chunk-size 0` silently returned an EMPTY sequence for non-empty
         ;; input -- data loss with no error -- and a negative value was a raw
         ;; NegativeArraySizeException. Checked in `boring.options` now, with
         ;; every other option, so this is the only entry point that reads it
         ;; rather than the only one that validates it.
         chunk (int (get opts :chunk-size 65536))
         r     (Reader. (byte-array 0))
         state (volatile! {:buf (byte-array chunk) :limit 0 :last-good 0 :base 0
                           :eof? false})]
     (configure-reader! r o)
     ;; `last-good` is the offset just past the last COMPLETE item, not the
     ;; reader's current position. A failed read leaves the position somewhere
     ;; mid-item -- and past the end of the valid bytes, since `u8` increments
     ;; before it can discover it has run out -- so rewinding to the position
     ;; would compact a negative number of bytes. Rewind to the last item.
     (letfn [(refill!
               []
               (let [{:keys [^bytes buf ^long limit ^long last-good eof? ^long base]} @state
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
                 ;; `base` is how many bytes were discarded by earlier
                 ;; compactions, so `base + last-good` is a position in the
                 ;; FILE rather than in the current buffer. Without it the
                 ;; streaming reader had no absolute offset at all, so it
                 ;; passed -1 to `index-frame?` -- which by construction skips
                 ;; the back-pointer test. Concatenate two sealed files and the
                 ;; trailing frame's pointer is relative to the second one, so
                 ;; it is stale for the whole; `decode-seq` and `nav` both
                 ;; publish it as data, and this reader silently deleted it.
                 ;; 123, 123, 122 on the same bytes.
                 (vreset! state {:buf buf :limit new-limit :last-good 0
                                 :base (+ base last-good)
                                 :eof? (or eof? (neg? n))})
                 ;; The buffer itself, bounded -- not a copy of it. Anything
                 ;; past `new-limit` is the previous refill's leftovers, and
                 ;; the length argument is what keeps the reader from reading
                 ;; them as though they were data.
                 (.reset r buf (int new-limit))
                 (pos? n)))
             (step []
               (lazy-seq
                (if-not (.atEnd r)
                  (let [;; CAPTURED BEFORE THE READ. `last-good` is advanced to
                        ;; the position AFTER the item in the `:ok` branch
                        ;; below, and the frame test runs after that -- so
                        ;; reading it there gives the frame's END, and the
                        ;; back-pointer comparison failed on every genuine
                        ;; sealed file. 60 items came back 61.
                        item-start (+ (long (:base @state)) (long (:last-good @state)))
                        v (try
                            ;; The boundary goes around the READ and nothing
                            ;; else, so the retry logic below sees typed errors
                            ;; and dispatches on `:type` exactly as it does for
                            ;; the ones the reader raises itself. Wrapped
                            ;; outside the retry it would convert a truncation
                            ;; into a hard failure; wrapped here a stack
                            ;; overflow arrives as `:boring/max-depth-exceeded`
                            ;; -- which this path already knows is definitive,
                            ;; since more bytes cannot make a value shallower.
                            {:ok (with-decode-errors (.readNext r))}
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
                              (cond
                                (#{:boring/truncated-input :boring/bad-count}
                                 (:type (ex-data e)))
                                {:need-more e}
                                ;; BORING'S OWN FOOTER, here too. `decode-seq`
                                ;; gained this and the streaming arity did not,
                                ;; so `write-seq!`'s default output still could
                                ;; not be read back through the path documented
                                ;; for dumps larger than the heap.
                                ;;
                                ;; There is no file length to check a
                                ;; back-pointer against on a stream, so the gate
                                ;; is END OF INPUT instead: retry from where this
                                ;; item began, with the budgets lifted, and
                                ;; accept the result only if it is a genuine
                                ;; frame AND nothing follows it. A budget error
                                ;; is definitive -- more bytes cannot make a
                                ;; value shallower -- so retrying immediately is
                                ;; sound where retrying a truncation is not.
                                (#{:boring/max-depth-exceeded
                                   :boring/max-items-exceeded} (:type (ex-data e)))
                                ;; SKIPPED, NOT DECODED -- see `decode-seq`.
                                ;; Reading the footer under lifted budgets is
                                ;; what let a forged frame allocate 102 MB
                                ;; there; the streaming arity had the same
                                ;; shape. If the seventeen frame bytes are at
                                ;; this item's start and nothing follows, the
                                ;; rest of the input is the footer and the
                                ;; sequence is over. Nothing is materialised.
                                (if (and (frame/prefix-at? (:buf @state)
                                                           (long (:last-good @state)))
                                         (not (refill!)))
                                  {:frame true}
                                  (throw e))
                                :else (throw e)))
                            ;; Kept behind the boundary, not in front of it:
                            ;; everything the reader itself raises is typed by
                            ;; the wrap above, so this now only catches an
                            ;; out-of-bounds from `refill!`'s own array
                            ;; arithmetic. Rare, and still a "need more" rather
                            ;; than a decode failure.
                            (catch IndexOutOfBoundsException e
                              {:need-more e}))]
                    (cond
                      (contains? v :frame) nil
                      (contains? v :ok)
                      (do (vswap! state assoc :last-good (.position r))
                          ;; See `decode-seq`: the trailing index frame is
                          ;; metadata, not an item. Here "final" needs the
                          ;; stream too, not just the buffer -- `.atEnd` only
                          ;; means the current chunk is exhausted, so a refill
                          ;; that succeeds proves the frame was not last.
                          ;; THE ITEM'S OWN FILE OFFSET, not -1. `index-frame?`
                          ;; treats -1 as "the caller cannot supply one" and
                          ;; skips the back-pointer test entirely -- the only
                          ;; check that tells a frame describing THIS file from
                          ;; one carried in from another. `base + last-good` is
                          ;; that offset; `last-good` was captured before this
                          ;; item was read, so it is where the item starts.
                          (if (and (frame/index-frame? (:ok v) item-start)
                                   (.atEnd r) (not (refill!)))
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
   ;; This inlined `configure-reader!` -- nine `set!`s in the same order, which
   ;; happened to still match. A copy that agrees today is a copy that can stop
   ;; agreeing tomorrow, silently, since nothing compares them.
   (let [opts (opt/check-opts opts)
         r (configure-reader! (Reader. (bytes! bs "decode-seq")) opts)]
     ((fn step [^long frame-at]
        (lazy-seq
         (when-not (.atEnd r)
            ;; Each item opens a fresh stringref namespace, so the reader's
            ;; per-message state must be cleared between items — but not its
            ;; ident cache, which is a pure function of bytes.
           (let [start (.position r)
                 ;; THE FOOTER IS NOT DECODED AT ALL. It is being SKIPPED, not
                 ;; used, so reading its value was never necessary -- and reading
                 ;; it under lifted budgets is what made a forged frame a bomb:
                 ;; the prefix gate ran first, then the payload was materialised,
                 ;; and `index-frame?` only judged the result afterwards. A file
                 ;; whose frame slot held a nested-array payload allocated 102 MB
                 ;; under `{:max-items 100}` and returned its two honest items
                 ;; with no error.
                 ;;
                 ;; If the file's own back-pointer names this offset and the
                 ;; seventeen frame bytes are here, the rest of the file is the
                 ;; footer by construction. Stopping is the whole answer, and it
                 ;; allocates nothing whatever the payload claims to be.
                 footer? (= start frame-at)
                 v (when-not footer? (with-decode-errors (.readNext r)))]
             ;; THE INDEX FRAME IS NOT AN ITEM. `write-seq!` indexes by default,
             ;; so without this every caller of `decode-seq` would find a
             ;; phantom `#boring/index [...]` after their data. It is dropped
             ;; only in the final position, which is the only place `seal-index!`
             ;; can put it -- a frame of that name anywhere else is somebody
             ;; else's data and stays visible.
             (cond
               footer? nil
               (and (.atEnd r) (frame/index-frame? v start)) nil
               :else (cons v (step frame-at))))))) (frame/footer-start bs)))))

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
    ;; HASCH ITSELF IS PROBED FIRST, not `boring.hasch`. The catch below reads
    ;; as "the integration namespace is absent", and that was the same thing
    ;; only while `boring/hasch.cljc` was missing from the jar -- which it was,
    ;; silently, in every release. Shipping it made the two different: the
    ;; require now FINDS the namespace and fails inside it on `hasch.benc`,
    ;; wrapped in a Compiler$CompilerException that neither catch matches, so
    ;; `(require 'boring.core)` threw for every consumer without hasch.
    ;;
    ;; Probing the optional dependency directly is what the comment above
    ;; always claimed this did.
    (require 'hasch.benc)
    (require 'boring.hasch)
    true
    (catch java.io.FileNotFoundException _ false)
    (catch ClassNotFoundException _ false)
    (catch Exception _ false)))
