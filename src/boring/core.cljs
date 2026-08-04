(ns boring.core
  "ClojureScript surface. Mirrors the JVM `boring.core` API so the conformance
  suite in test/boring/ runs unmodified on both platforms.

  Platform differences that cannot be papered over, and are documented rather
  than hidden:

  - JS has one number type, so `1` and `1.0` are the same value. Integers
    encode as CBOR integers and everything else as f64; `:float-policy` has no
    meaning here beyond bignums.
  - Values past 2^53 decode to `BigInt` rather than silently losing precision
    (datahike's dump requirements); values inside it stay ordinary numbers.
  - `Date` has millisecond resolution, so nanosecond instants do not survive."
  (:require [boring.data :as data]
            [boring.reader :as rd]
            [boring.writer :as wr]))

;; :canonical selects :shortest — see the JVM boring.core for why. Deterministic
;; encoding requires the shortest float form that round-trips, so 65504.0 must
;; go out as `f97bff`. Both platforms must agree here or a signed document
;; verifies on one and not the other.
(def ^:private profile-defaults
  {:clojure   {:stringref true  :float-policy :preserve-width :canonical false
               :canonical-order :rfc8949}
   :interop   {:stringref false :float-policy :preserve-width :canonical false
               :shapes false :canonical-order :rfc8949}
   ;; :archival -- sorted keys AND fixed-width floats; see the JVM boring.core
   ;; for the reasoning. Kept in step with the JVM table deliberately: these two
   ;; maps are the same contract written twice, and a profile that exists on one
   ;; platform and not the other is a dump a browser peer cannot read.
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

;; The nil-options path is every default encode. Keeping this shared avoids a
;; merge/dissoc allocation per item in a reused-writer loop.
(def ^:private default-opts (:clojure profile-defaults))

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
   :archival          #{:canonical :canonical-order :stringref :shapes :float-policy}
   :canonical         #{:canonical :canonical-order :stringref :shapes :float-policy}
   :canonical-rfc7049 #{:canonical :canonical-order :stringref :shapes :float-policy}})

(defn- check-profile-conflicts! [profile base opts]
  (let [conflicts (into (sorted-set)
                        (filter (fn [k]
                                  (and (contains? opts k)
                                       (not= (get opts k) (get base k)))))
                        (profile-locked profile))]
    (when (seq conflicts)
      (throw (ex-info (str "boring: " (pr-str (vec conflicts))
                           " cannot be overridden under the " profile
                           " profile -- the profile defines "
                           (pr-str (select-keys base conflicts)))
                      {:type :boring/incompatible-options
                       :profile profile
                       :conflicts (vec conflicts)
                       :profile-values (select-keys base conflicts)})))))

(defn- resolve-opts [opts]
  (if (nil? opts)
    default-opts
    (let [profile (get opts :profile :clojure)
          base (or (profile-defaults profile)
                   (throw (ex-info "boring: unknown profile"
                                   {:type :boring/unknown-profile :profile profile})))
          _ (check-profile-conflicts! profile base opts)]
      (merge base (dissoc opts :profile)))))

(defn writer
  "Create a reusable writer. When supplied, `opts` are resolved once and used
  by the no-options arities of `encode-into!`, `encode-buffered!`, and
  `write-seq!`; an explicit options argument overrides them for that call."
  ([] (wr/writer 256))
  ([size] (wr/writer size))
  ([size opts]
   (let [w (wr/writer size)]
     (set! (.-opts ^wr/Writer w) (resolve-opts opts))
     w)))

(defn- writer-opts [^wr/Writer w]
  (or (.-opts w) default-opts))

(declare configure-reader!)

(defn reader
  "A reusable Reader. `opts` are the same map `decode` takes."
  ([bs] (rd/reader bs))
  ([bs opts] (configure-reader! (rd/reader bs) (resolve-opts opts))))

;; The `^wr/Writer` / `^rd/Reader` hints are not decoration: without them the
;; compiler cannot infer the target of these `set!`s, and every downstream
;; ClojureScript build with :infer-externs on (which is shadow-cljs's default)
;; reports ten :infer-warnings pointing into this file.
(defn unencodable
  "The default `:encode-fallback` placeholder -- see the JVM core."
  [x]
  (data/unknown-record "boring/unencodable"
                       {:type (str (type x)) :repr (pr-str x)}))

(defn- encode-fallback-fn [fb]
  (cond
    (nil? fb) nil
    (= :placeholder fb) unencodable
    (ifn? fb) fb
    :else (throw (ex-info "boring: :encode-fallback must be nil, :placeholder, or a function"
                          {:type :boring/bad-option :value fb}))))

(defn- configure-writer! [^wr/Writer w opts]
  (set! (.-stringref w) (boolean (:stringref opts)))
  (set! (.-inclMetadata w) (boolean (get opts :incl-metadata? true)))
  (set! (.-preserveWidth w) (= :preserve-width (:float-policy opts)))
  (set! (.-canonical w) (boolean (:canonical opts)))
  (set! (.-legacyCanonicalOrder w) (= :rfc7049 (:canonical-order opts)))
  (set! (.-shapes w) (boolean (:shapes opts)))
  (set! (.-registry w) (:registry opts))
  (set! (.-maxDepth w) (get opts :max-depth 1024))
  (set! (.-permitReservedSimpleValues w)
        (boolean (:permit-reserved-simple-values opts)))
  (set! (.-encodeFallback w) (encode-fallback-fn (:encode-fallback opts)))
  w)

(defn- write-root! [w v opts]
  (wr/reset! w)
  (configure-writer! w opts)
  (when (:stringref opts) (wr/write-stringref-namespace! w))
  (wr/write-value! w v)
  w)

(defn encode
  ([v] (encode v nil))
  ([v opts] (wr/to-bytes (write-root! (wr/writer 256) v (resolve-opts opts)))))

(defn encode-into!
  ([w v] (wr/to-bytes (write-root! w v (writer-opts w))))
  ([w v opts] (wr/to-bytes (write-root! w v (resolve-opts opts)))))

(defn encode-buffered!
  "Encode into `w` and return the byte count, copying nothing out. Reach the
  bytes with `buffer`; they are overwritten by the next encode."
  ([w v] (wr/position (write-root! w v (writer-opts w))))
  ([w v opts] (wr/position (write-root! w v (resolve-opts opts)))))

(defn buffer [w] (wr/buffer w))

(defn- configure-reader! [^rd/Reader r opts]
  (set! (.-tolerateUnknownTags r) (boolean (get opts :tolerate-unknown-tags true)))
  ;; No :instant-type here — CLJS has only js/Date, so the JVM's Date/Instant
  ;; choice has no counterpart. Silently accepting the option and ignoring it
  ;; would be worse than not offering it.
  ;;
  ;; :auto-construct-records? is refused for the same reason, LOUDLY. It cannot
  ;; work here: advanced compilation minifies constructor names and there is no
  ;; runtime `resolve`, so there is nothing to look a wire name up in. Accepting
  ;; it silently would mean a .cljc codebase got real records on the JVM and
  ;; UnknownRecords in the browser -- a platform divergence in DECODED VALUES,
  ;; which is the worst kind to debug.
  (when (:auto-construct-records? opts)
    (throw (ex-info (str "boring: :auto-construct-records? is JVM-only. "
                         "ClojureScript minifies record constructor names under "
                         "advanced compilation and has no runtime resolve, so a "
                         "wire name cannot be resolved to a constructor. Use "
                         "`register-record` with an explicit constructor, which "
                         "is portable.")
                    {:type :boring/unsupported-option
                     :option :auto-construct-records?})))
  (set! (.-maxDepth r) (get opts :max-depth 1024))
  (set! (.-validateUtf8 r) (boolean (get opts :validate-utf8 true)))
  ;; WIRED, having been documented and then never applied on either platform.
  ;; The field existed and defaulted to true, but nothing set it, so
  ;; `:check-duplicate-keys false` was silently ignored. See the JVM core.
  (set! (.-checkDuplicateKeys r) (boolean (get opts :check-duplicate-keys true)))
  ;; `:max-items` was accepted and silently ignored here -- the only
  ;; heap-amplification control doc/SECURITY.md names did not exist on this
  ;; platform at all. Validated rather than coerced: `-1` used to disable the
  ;; bound on the JVM and a non-integer raised a raw host exception.
  (let [mi (get opts :max-items 0)]
    (when-not (and (integer? mi) (not (neg? mi)))
      (throw (ex-info (str "boring: :max-items must be a non-negative integer "
                           "(0 means unlimited), got " (pr-str mi))
                      {:type :boring/bad-option :option :max-items :value mi})))
    (set! (.-maxItems r) mi))
  ;; ALWAYS set, never `when-let` -- a reusable reader kept the previous
  ;; call's registry. See the JVM core for the reproduction.
  (set! (.-registry r) (:registry opts))
  r)

(defn decode
  ([bs] (decode bs nil))
  ([bs opts] (rd/read! (configure-reader! (rd/reader bs) opts))))

(defn decode-with
  "Decode using a reusable Reader. With `opts`, every option is re-applied on
  this call; without them the reader keeps its previous configuration."
  ([r bs] (rd/reset! r bs) (rd/read! r))
  ([r bs opts]
   (rd/reset! r bs)
   (configure-reader! r (resolve-opts opts))
   (rd/read! r)))

(def ^:const index-name
  "Tag-27 type name for a sequence/container index. Must match boring.core's."
  "boring/index")

(defn- index-frame?
  "True for the tag-27 frame the JVM `seal-index!` appends, at offset `start`.

  CLJS had no recognition of this at all, so the library's OWN default JVM
  output decoded to N+1 items here and N on the JVM -- the same portable CBOR
  sequence, two different logical results. Writing the index is still
  JVM-only; reading past it must not be.

  Authenticity, not just the name: a six-element payload whose last element is
  the 8-byte back-pointer, and that pointer equal to where the frame starts,
  which it is by construction since it doubles as the length of the data
  section before it. A name collision must not delete a logical item."
  [v ^number start]
  (and (data/tagged-frame? v)
       (= index-name (data/frame-name v))
       (let [p (data/frame-payload v)]
         (and (sequential? p)
              (= 6 (count p))
              (let [ptr (nth (vec p) 5)]
                (and (instance? js/Uint8Array ptr)
                     (= 8 (.-length ptr))
                     (or (neg? start)
                         (= start (areduce ptr i acc 0
                                           (+ (* acc 256) (aget ptr i)))))))))))

(defn decode-seq
  "Lazily decode consecutive top-level CBOR items (RFC 8742 sequence).

  Memory is bounded by the item being realised, not by `bs` — but `bs` itself
  is already in memory. Use `decode-seq-from` to read a source larger than the
  heap."
  ([bs] (decode-seq bs nil))
  ([bs opts]
   (let [r (configure-reader! (rd/reader bs) opts)]
     ((fn step []
        (lazy-seq
         (when-not (rd/at-end? r)
           ;; The index frame is METADATA, not an item -- and only in the final
           ;; position, the only place the JVM sealer can put it.
           (let [start (rd/position r)
                 v (rd/read-next! r)]
             (if (and (rd/at-end? r) (index-frame? v start))
               nil
               (cons v (step)))))))))))

(defn- grow
  "A Uint8Array of at least `n` bytes holding `buf`'s contents."
  [buf n]
  (if (>= (.-length buf) n)
    buf
    (let [out (js/Uint8Array. n)]
      (.set out buf 0)
      out)))

(defn decode-seq-from
  "Lazily decode a CBOR sequence (RFC 8742) from a PULL SOURCE, in bounded
  memory.

  `pull` is `(fn [] -> Uint8Array | nil)`: return the next block of bytes, or
  nil at end of input. On Node that is a `fs.readSync` loop, which is
  synchronous and therefore composes with a lazy seq exactly as the JVM's
  `InputStream` arity does.

  This closes an asymmetry inside ClojureScript boring, not merely against the
  JVM: `write-seq!` has always streamed OUT — it takes a sink and never holds
  the document — while the read side took only a `Uint8Array`, so a sequence
  larger than the heap had no symmetric path in.

  Bounded memory means bounded by the LARGEST SINGLE ITEM plus the block size,
  not by the source. That is the real limit and it is not a compromise: an item
  has to fit in memory to be a value at all, so streaming can only ever mean a
  sequence of items — which is exactly what a datahike dump is.

  Deliberately NOT an async API. A pull function that returned a promise could
  not drive a lazy seq, and a push decoder for genuinely async sources is a
  different shape with different ergonomics; conflating them produces something
  bad at both. Files — the case this exists for — read synchronously on Node.

  `:chunk-size` (default 64 KiB) is how much is requested at a time — the same
  option name the JVM arity uses, since the point of this is symmetry. It must
  be a positive integer, and it is a hint: a `pull` that returns a larger block
  is fine, since nothing tells `pull` what was asked for.

  `nil` is the ONLY end-of-input signal. An empty `Uint8Array` means \"nothing
  right now\" and is retried; a source that keeps returning one raises
  `:boring/stalled-source` rather than hanging. A `fs.readSync` loop should
  therefore return `nil`, not a zero-length subarray, at EOF.

  The caller owns the source and closes it."
  ([pull] (decode-seq-from pull nil))
  ([pull opts]
   (let [o (resolve-opts opts)
         block (let [b (get opts :chunk-size 65536)]
                 ;; VALIDATED, not trusted. `:chunk-size 0` sized the buffer at
                 ;; zero and then copied the first block into it for a raw
                 ;; `RangeError: offset is out of bounds`; a negative one threw
                 ;; out of the `Uint8Array` constructor. Neither is a typed
                 ;; error, and both are option mistakes rather than bad data.
                 (when-not (and (number? b) (js/Number.isSafeInteger b) (pos? b))
                   (throw (ex-info (str "boring: :chunk-size must be a positive integer, got "
                                        (pr-str b))
                                   {:type :boring/bad-option :option :chunk-size :value b})))
                 b)
         r (configure-reader! (rd/reader (js/Uint8Array. 0)) o)
         state (atom {:buf (js/Uint8Array. block) :limit 0 :last-good 0 :eof? false})]
     ;; `last-good` is the offset just past the last COMPLETE item, NOT the
     ;; reader's current position. A failed read leaves the position somewhere
     ;; mid-item — and past the end of the valid bytes, since the byte reader
     ;; advances before it can discover it has run out — so compacting from the
     ;; position would copy a negative number of bytes. Rewind to the last item.
     (letfn [(pull-block!
               []
               ;; AN EMPTY BLOCK IS NOT END OF INPUT. It used to be converted to
               ;; `n = -1` and latched `eof?`, so a source that returned one --
               ;; a short read at a buffer boundary, an fd with nothing ready
               ;; yet -- silently DROPPED every byte after it and the sequence
               ;; ended early with no error at all. The contract names `nil`,
               ;; and only `nil`, as end of input.
               ;;
               ;; Skipping without a bound would spin forever on a source that
               ;; always returns empty, so a run of them is a typed error rather
               ;; than a hang. A pull built on `fs.readSync` should return `nil`
               ;; when it reads 0 bytes.
               (loop [tries 0]
                 (let [c (pull)]
                   (cond
                     (nil? c) nil
                     (pos? (.-length c)) c
                     (< tries 64) (recur (inc tries))
                     :else
                     (throw (ex-info
                             (str "boring: the pull source returned 65 empty blocks "
                                  "without reaching end of input; return nil for EOF")
                             {:type :boring/stalled-source}))))))
             (refill!
               []
               (let [{:keys [buf limit last-good eof?]} @state
                     rest-len (- limit last-good)
                     _ (when (pos? last-good)
                         (.set buf (.subarray buf last-good limit) 0))
                     ^js/Uint8Array chunk (when-not eof? (pull-block!))
                     n (if chunk (.-length chunk) 0)
                     ;; SIZED FROM THE BLOCK THAT ARRIVED, not from the
                     ;; configured chunk size. `pull` is handed no requested
                     ;; size and may legitimately return more than `block`;
                     ;; growing to `rest-len + block` first and copying
                     ;; afterwards threw a raw `RangeError` out of `.set` for
                     ;; every source that did.
                     buf (grow buf (+ rest-len (max n block)))
                     _ (when (pos? n) (.set buf chunk rest-len))
                     new-limit (+ rest-len n)]
                 (swap! state assoc :buf buf :limit new-limit :last-good 0
                        :eof? (or eof? (nil? chunk)))
                 (rd/reset! r (.subarray buf 0 new-limit))
                 (pos? n)))
             (step []
               (lazy-seq
                (if-not (rd/at-end? r)
                  (let [v (try
                            {:ok (rd/read-next! r)}
                            (catch :default e
                              ;; From INSIDE the reader, "the buffer ends
                              ;; mid-item" and "the document declares an
                              ;; impossible count" are indistinguishable: a
                              ;; declared count is validated against the bytes
                              ;; REMAINING, which on a partial buffer is exactly
                              ;; what a hostile count looks like. So both are
                              ;; retried while more data can still arrive — and
                              ;; the ORIGINAL exception is kept, to be rethrown
                              ;; if it cannot, rather than reporting every
                              ;; malformed document as a truncation.
                              (if (#{:boring/truncated-input :boring/bad-count}
                                   (:type (ex-data e)))
                                {:need-more e}
                                (throw e))))]
                    (cond
                      (contains? v :ok)
                      (do (swap! state assoc :last-good (rd/position r))
                          (cons (:ok v) (step)))
                      (refill!) (step)
                      :else (throw (:need-more v))))
                  (when (refill!) (step)))))]
       (refill!)
       (step)))))

(defn write-seq!
  "Encode each value as a consecutive top-level item, appending to `sink`, a
  function of one Uint8Array. Returns the total byte count.

  `sink` receives bytes it OWNS. This used to pass `.subarray` of the writer's
  reusable buffer, which is a view rather than a copy: a sink that retained the
  Uint8Array -- the natural reading of \"appending to sink\" -- saw every
  retained item overwritten by the next iteration, and a sink that finished
  asynchronously saw whatever the buffer held by then. The buffer hazard is
  documented for `buffer`, where the caller asks for it; here it was hidden
  inside a higher-level API that reads as safe.

  Use `encode-buffered!` with `buffer` if you want the borrowed view and will
  consume it synchronously."
  ([w values sink]
   (let [o (writer-opts w)]
     (reduce (fn [total v]
               (let [n (wr/position (write-root! w v o))]
                 (sink (.slice (wr/buffer w) 0 n))
                 (+ total n)))
             0 values)))
  ([w values sink opts]
   (let [o (resolve-opts opts)]
     (reduce (fn [total v]
               (let [n (wr/position (write-root! w v o))]
                 (sink (.slice (wr/buffer w) 0 n))
                 (+ total n)))
             0 values))))

;; ## Registries
;;
;; The JVM side has a Java TagRegistry; here a plain map of wire-name ->
;; constructor is enough. Security is the same: reading looks the name up, it
;; never resolves a symbol or evaluates anything from the wire.

(defn tag-registry
  "An empty registry. Shape:

    {:writers {Type   {:tag n :fn value->encodable}}
     :readers {tag-n  content->value}
     :records {\"wire.Name\" map->Record}}

  Pass as `:registry`. Security matches the JVM side: reading dispatches on a
  tag NUMBER or a record NAME looked up here — it never resolves a symbol or
  evaluates anything from the wire."
  [] {})

(defn register-tag
  "Teach a registry about `tag` for values of `type`. Returns a NEW registry.

  Same signature and same threading idiom as the JVM side — registries are
  immutable values on both platforms:

    (def registry
      (-> (boring/tag-registry)
          (boring/register-tag 40001 js/URL #(.-href %) #(js/URL. %))))

  The tag number is validated, as it is on the JVM. It used to be taken as a
  map key and never looked at, so `-1` and `1.5` were accepted here and refused
  there — and a reader registered under a tag number no document can carry is
  silently dead rather than wrong, which is the harder kind to notice."
  [reg tag type write-fn read-fn]
  (when-not (and (number? tag) (js/Number.isSafeInteger tag) (not (neg? tag)))
    (throw (ex-info (str "boring: tag numbers are unsigned integers; got " (pr-str tag))
                    {:type :boring/bad-tag-number :tag tag})))
  ;; Structural, not semantic -- see TagRegistry.checkTag on the JVM. Stringref
  ;; is resolved while the value is built, not at tag dispatch, so a reader
  ;; registered here would apply in some positions and not others.
  (when (or (== tag 25) (== tag 256))
    (throw (ex-info (str "boring: tag " tag " is structural (stringref) and cannot be"
                         " given a reader. Use :stringref false to turn it off.")
                    {:type :boring/bad-tag-number :tag tag})))
  (cond-> reg
    (and type write-fn) (assoc-in [:writers type] {:tag tag :fn write-fn})
    read-fn (assoc-in [:readers tag] read-fn)))

(defn register-record
  "Teach a registry how to rebuild a record from its field map, keyed by the
  name it carries on the wire. Returns the registry.

  Same signature and same threading idiom as the JVM's `register-record`, so
  registration code can live in a `.cljc` file:

    (defrecord Point [x y])

    (def registry
      (-> (boring/tag-registry)
          (boring/register-record \"my.ns.Point\" map->Point)))

  The constructor must be passed explicitly here: advanced compilation minifies
  constructor names, so there is nothing to reflect on. `record-type-name`
  munges ClojureScript's own name to match the JVM class name, so one
  registration serves data written on either platform.

  Both platforms are immutable, so `reg` is left untouched and the return value
  must be threaded. There is no process-global registry on either side."
  [reg wire-name map-ctor]
  (assoc-in reg [:records wire-name] map-ctor))

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
  [reg ctors]
  (update reg :records
          (fn [rs] (reduce-kv (fn [m k v] (assoc m (str k) v)) (or rs {}) ctors))))
