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
  (let [profile (get opts :profile :clojure)
        base (or (profile-defaults profile)
                 (throw (ex-info "boring: unknown profile"
                                 {:type :boring/unknown-profile :profile profile})))
        _ (check-profile-conflicts! profile base opts)
        merged (merge base (dissoc opts :profile))]
    merged))

(defn writer
  ([] (wr/writer 256))
  ([size] (wr/writer size)))

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
  ([w v] (encode-into! w v nil))
  ([w v opts] (wr/to-bytes (write-root! w v (resolve-opts opts)))))

(defn encode-buffered!
  "Encode into `w` and return the byte count, copying nothing out. Reach the
  bytes with `buffer`; they are overwritten by the next encode."
  ([w v] (encode-buffered! w v nil))
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

(defn decode-seq
  "Lazily decode consecutive top-level CBOR items (RFC 8742 sequence)."
  ([bs] (decode-seq bs nil))
  ([bs opts]
   (let [r (configure-reader! (rd/reader bs) opts)]
     ((fn step []
        (lazy-seq
         (when-not (rd/at-end? r)
           (cons (rd/read-next! r) (step)))))))))

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
  ([w values sink] (write-seq! w values sink nil))
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
          (boring/register-tag 40001 js/URL #(.-href %) #(js/URL. %))))"
  [reg tag type write-fn read-fn]
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
