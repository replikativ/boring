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
  (:import (org.replikativ.boring Reader TagRegistry Writer)))

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
  "Encode `v` to a fresh byte[]."
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

(defn write-to!
  "Encode `v` into `w` and write its bytes straight to `out`, with no
  intermediate array."
  ([^Writer w v ^java.io.OutputStream out]
   (let [n (encode-buffered! w v)]
     (.write out (.buffer w) 0 (int n))
     n))
  ([^Writer w v ^java.io.OutputStream out opts]
   (let [n (encode-buffered! w v opts)]
     (.write out (.buffer w) 0 (int n))
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

(defn- configure-reader!
  ^Reader [^Reader r opts]
  (set! (.-tolerateUnknownTags r) (boolean (get opts :tolerate-unknown-tags true)))
  (set! (.-instantAsDate r) (not= :instant (get opts :instant-type :date)))
  (set! (.-fullDateAsSqlDate r) (= :sql-date (get opts :date-type :local-date)))
  (set! (.-maxDepth r) (int (get opts :max-depth 1024)))
  (set! (.-validateUtf8 r) (boolean (get opts :validate-utf8 true)))
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

(declare seal-index! scan-index build-index)

(defn encode-indexed
  "Encode `v` and seal an index onto it, returning a byte[].

  The result is a two-item CBOR sequence -- the value, then the index -- so
  `decode` still returns the value and any CBOR reader consumes both. Pass it
  to `boring.nav/source` and lookups inside large containers become jumps.

  `:index` is the stride (default 16) and `:index-min` the smallest container
  worth a node (default 16). Sorted map keys -- `:canonical` or `:archival` --
  additionally allow binary search; without them a lookup still jumps anchor to
  anchor rather than entry to entry."
  (^bytes [v] (encode-indexed v nil))
  (^bytes [v opts]
   (let [^bytes body (encode v opts)
         idx (build-index body opts)]
     (if-not idx
       body
       (let [w (writer (max 1024 (alength body)) opts)
             out (java.io.ByteArrayOutputStream. (+ (alength body) 256))]
         (.write out body)
         (seal-index! w out idx (alength body))
         (.toByteArray out))))))

(defn write-seq!
  "Encode each value in `values` to `out` as consecutive top-level CBOR items.
  Returns the number of bytes written. Constant memory: one value at a time,
  through the writer's own buffer, with no intermediate array per item.

  `:index N` additionally seals the sequence with an offset index covering
  every Nth item, so `boring.nav/items` can jump to an item instead of skipping
  to it -- O(1) rather than O(n). N is the stride, and it is the size/speed
  knob: every entry costs 4 bytes, while a lookup scans up to N-1 items. On
  200k ~40-byte items, stride 1 cost 10% of the file and stride 16 cost 0.6%
  for roughly 2.5x the lookup. The right N depends on item size, so it is a
  parameter rather than a default.

  The index goes at the END, which is what makes it compatible with appending:
  offsets are only known after the items are written, so a leading index would
  mean buffering the whole sequence in memory. ZIP's central directory and
  Parquet's footer are at the end for the same reason. See `seal-index!` if you
  are writing items incrementally rather than from a seq."
  (^long [^Writer w values ^java.io.OutputStream out] (write-seq! w values out nil))
  (^long [^Writer w values ^java.io.OutputStream out opts]
   (let [o (resolve-opts opts)
         stride (long (or (:index opts) 0))
         indexing? (pos? stride)
         min-entries (long (or (:index-min opts) 16))
         ;; Each item is scanned in the WRITER'S OWN BUFFER, right after it is
         ;; encoded and before it is handed to the stream. That keeps write-seq!
         ;; streaming -- no need to re-read the output, which may be a file --
         ;; while still deriving the index from encoded bytes rather than from
         ;; the writer's internals.
         scan-rdr (when indexing? (Reader. (byte-array 1)))
         nodes (when indexing? (java.util.ArrayList.))
         offs (when indexing? (java.util.ArrayList.))
         n-items (volatile! 0)
         total (reduce (fn [^long total v]
                         (when (and indexing? (zero? (rem (long @n-items) stride)))
                           (.add ^java.util.ArrayList offs (int total)))
                         (vswap! n-items inc)
                         (let [n (long (.position ^Writer (write-root! w v o)))]
                           (when indexing?
                             (.reset ^Reader scan-rdr (buffer w))
                             (let [sub (scan-index scan-rdr 0 n stride min-entries total)]
                               (dotimes [k (alength ^ints (:containers sub))]
                                 (.add ^java.util.ArrayList nodes
                                       [(aget ^ints (:containers sub) k)
                                        (aget ^ints (:counts sub) k)
                                        (nth (:slots sub) k)
                                        (nth (:sorted sub) k)]))))
                           (.write out (.buffer w) 0 (int n))
                           (+ total n)))
                       0
                       values)]
     (if indexing?
       (let [;; The sequence itself is a node at the sentinel offset -1: it has
             ;; no container header on the wire, but it behaves like one, and a
             ;; sentinel keeps a single uniform node list rather than two.
             all (cons [-1 (int @n-items) (int-array offs) false] (vec nodes))
             sorted-nodes (vec (sort-by first all))]
         (+ total
            (long (seal-index!
                   w out
                   {:stride stride
                    :containers (int-array (map #(nth % 0) sorted-nodes))
                    :counts (int-array (map #(nth % 1) sorted-nodes))
                    :slots (mapv #(nth % 2) sorted-nodes)
                    :sorted (mapv #(nth % 3) sorted-nodes)}
                   total))))
       total))))

(def ^:const index-tag
  "CBOR tag for a sequence offset index. See doc/SHAPES.md.

  39651, not 39650: 39650 is already specified for the scattered-shape case.
  Both sit in 38000-39999, which doc/IANA-REGISTRATION.md surveyed as empty and
  clear of the dense `ur:` cluster at 40000-40918. Provisional, like 39649 --
  see that document for the registration this owes."
  39651)

(declare index-walk)

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
  (let [p (long p) stride (long stride) min-entries (long min-entries) base (long base)
        mj (.majorAt r p)]
    (if (= mj 6)
      (index-walk r (.headEndAt r p) stride min-entries base acc)
      (if-not (or (= mj 4) (= mj 5))
        (.skipFrom r p)
        (let [n (.headArgAt r p)
              map? (= mj 5)]
          (if (neg? n)
            (.skipFrom r p)                       ; indefinite length: not indexable
            (let [starts (int-array n)
                  end (loop [i 0 q (long (.headEndAt r p))]
                        (if (= i n)
                          q
                          (do (aset starts i (int q))
                              (recur (inc i)
                                     (long (index-walk
                                            r
                                            (if map?
                                              (long (index-walk r q stride min-entries base acc))
                                              q)
                                            stride min-entries base acc))))))]
              (when (>= n min-entries)
                (let [kept (if (= stride 1)
                             starts
                             (let [m (inc (quot (dec (max n 1)) stride))
                                   a (int-array m)]
                               (dotimes [j m] (aset a j (aget starts (* j stride))))
                               a))
                      sorted (boolean
                              (and map?
                                   (loop [k 1]
                                     (cond (>= k (alength kept)) true
                                           (>= (.compareItemsAt r (aget kept (dec k))
                                                                (aget kept k)) 0) false
                                           :else (recur (inc k))))))]
                  (when (pos? base)
                    (dotimes [k (alength kept)]
                      (aset kept k (int (+ base (aget kept k))))))
                  (.add acc [(int (+ base p)) (int n) kept sorted])))
              end)))))))

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
    (loop [p (long start)]
      (when (< p (long end))
        (recur (long (index-walk r p stride min-entries base acc)))))
    (let [idx (vec (sort-by first acc))]
      {:containers (int-array (map #(nth % 0) idx))
       :counts (int-array (map #(nth % 1) idx))
       :slots (mapv #(nth % 2) idx)
       :sorted (mapv #(nth % 3) idx)})))

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
         min-entries (long (or (:index-min opts) 16))
         idx (scan-index r 0 (alength bs) stride min-entries 0)]
     (when (pos? (alength ^ints (:containers idx)))
       (assoc idx :stride stride)))))

(defn- long->8-bytes* ^bytes [^long v]
  (let [b (byte-array 8)]
    (dotimes [i 8] (aset-byte b i (unchecked-byte (bit-shift-right v (* 8 (- 7 i))))))
    b))

(defn seal-index!
  "Write an index item to `out`, sealing everything written before it.

  `index` comes from `build-index`; `data-len` is how many bytes precede this
  item, which is also where it begins. The item is:

      tag 39651 [ stride, containers, counts, slots, sorted, <8-byte data-len> ]

  `containers` are the byte offsets of every indexed container, sorted, so a
  reader binary-searches them. `slots` holds each container's entry offsets and
  `sorted` says whether that container's keys ascend -- recorded rather than
  inferred, because the encoding profile is not on the wire.

  The trailing element is a byte string of exactly 8 bytes, so it always encodes
  as `0x48` plus 8: a sealed file ends with 9 predictable bytes however large
  the index is. That is how it is found, since CBOR cannot be parsed backwards.

  Those 9 bytes are ordinary CBOR, not a magic trailer. The file stays a valid
  sequence that any reader consumes -- it just sees one extra tagged item.

  The pointer verifies as well as locates: it is both where the index starts and
  how long the data is, so a reader that seeks there and finds no tag 39651
  knows the index is stale and scans instead."
  [^Writer w ^java.io.OutputStream out index data-len]
  (let [{:keys [stride containers counts slots sorted]} index
        item (data/tagged-value
              index-tag
              [(long stride) containers counts (vec slots) (vec sorted)
               (long->8-bytes* (long data-len))])]
    (write-to! w item out)))

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
         chunk (int (get opts :chunk-size 65536))
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
                          (cons (:ok v) (step)))
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
     (set! (.-validateUtf8 r) (boolean (get opts :validate-utf8 true)))
     (set! (.-autoConstructRecords r)
           (boolean (get opts :auto-construct-records? false)))
     (set! (.-registry r) (or (:registry opts) TagRegistry/EMPTY))
     ((fn step []
        (lazy-seq
         (when-not (.atEnd r)
            ;; Each item opens a fresh stringref namespace, so the reader's
            ;; per-message state must be cleared between items — but not its
            ;; ident cache, which is a pure function of bytes.
           (let [v (with-decode-errors (.readNext r))]
             (cons v (step))))))))))

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
