(ns boring.core
  "Thin Clojure surface over the Java hot path.

  The dispatch loop lives in Java on purpose: the per-value type test plus byte
  emission is where the time goes, and crossing the Clojure/Java boundary per
  value costs more than it saves. Measured, the Java hot path is worth 1.2-1.7x
  over the equivalent pure Clojure (5.8x on strings, where bulk VarHandle writes
  and a single-pass ASCII encode beat per-byte `aset`).

  Options (all optional):

    :profile        :clojure (default) | :interop | :canonical
    :float-policy   :preserve-width (default) | :shortest
    :stringref      true (default under :clojure) | false

  See doc/COMPATIBILITY.md for what each profile promises about the bytes.
  :float-policy exists because datahike's dumps must not narrow a double to a
  float -- the class, not just the value, has to survive."
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
  "Create a reusable Writer. Not thread-safe; one per thread or per loop."
  (^Writer [] (writer 256))
  (^Writer [^long initial-size] (Writer. initial-size)))

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
  (^bytes [^Writer w v] (encode-into! w v nil))
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
  (^long [^Writer w v] (encode-buffered! w v nil))
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
  ([^Writer w v ^java.io.OutputStream out] (write-to! w v out nil))
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

(defn write-seq!
  "Encode each value in `values` to `out` as consecutive top-level CBOR items.
  Returns the number of bytes written. Constant memory: one value at a time,
  through the writer's own buffer, with no intermediate array per item."
  (^long [^Writer w values ^java.io.OutputStream out] (write-seq! w values out nil))
  (^long [^Writer w values ^java.io.OutputStream out opts]
   (let [o (resolve-opts opts)]
     (reduce (fn [^long total v]
               (let [n (long (.position ^Writer (write-root! w v o)))]
                 (.write out (.buffer w) 0 (int n))
                 (+ total n)))
             0
             values))))

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
                      :else (throw (:need-more v))))
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
