(ns ^:no-doc boring.options
  "Profiles, option validation and option resolution — once, for both platforms.

  Not part of the public API. `boring.core` re-exports what callers touch.

  ## Why this is shared and not mirrored

  This was two copies. `boring.core` and its ClojureScript twin each carried
  the profile tables, the conflict check and five or six per-option validators,
  and the copies had drifted: `check-profile-conflicts!` was rewritten on the
  JVM to allocate nothing on the overwhelmingly common no-options call and the
  ClojureScript version still built a sorted set every encode; `:index` capped
  at 2^31-1 on one platform and MAX_SAFE_INTEGER on the other, so ClojureScript
  would write a stride the JVM's 32-bit slot arithmetic cannot read.

  ## Why validation happens HERE and not at the point of use

  It used to happen at the point of use -- `max-depth-opt` inside
  `configure-reader!`, `index-opt` inside `build-index`, `float-policy!` inside
  the writer's `configure!`. So whether an option was checked depended on
  whether the code path that reads it ran. Measured consequence:
  `{:profile :nope}` was refused by `decode-seq-from` and `decode-with` and
  accepted by `decode` and `decode-seq`; `{:chunk-size 0}` was refused by one
  entry point of five.

  Validating in `resolve-opts` fixes that by construction, because resolution
  is the one thing every entry point does. It also costs less than the old
  arrangement rather than more: the validators ran per `configure!`, which is
  per encode call, and this runs per RESOLVE -- once per writer for the
  documented reusable-writer idiom, and not at all on the nil-options fast
  path, where the defaults are legal by construction."
  (:require [boring.data :as data])
  #?(:clj (:import (org.replikativ.boring TagRegistry))))

;; ---------------------------------------------------------------- profiles

(def profile-defaults
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
(def profile-locked
  {:clojure           #{:canonical :canonical-order}
   :interop           #{:canonical :canonical-order :stringref :shapes}
   ;; Everything is locked, as under :canonical: both bits are what the profile
   ;; MEANS. `:float-policy :shortest` here would just be :canonical spelled
   ;; oddly, and `:canonical false` would just be :interop -- two more ways to
   ;; say things that already have names.
   :archival          #{:canonical :canonical-order :stringref :shapes :float-policy}
   :canonical         #{:canonical :canonical-order :stringref :shapes :float-policy}
   :canonical-rfc7049 #{:canonical :canonical-order :stringref :shapes :float-policy}})

;; RESOLUTION IS IDEMPOTENT, and it is marked rather than inferred.
;;
;; It was not, and that cost two separate breakages in one afternoon -- 415
;; test errors from `nav/source` and 6 more from `seal-index!`, both from the
;; same cause. A resolved map carries the profile's own `:canonical`, so
;; re-resolving reads that as the caller trying to override what the profile
;; locks, and the second resolve throws `:boring/incompatible-options` on a map
;; the first resolve produced.
;;
;; That is a trap for every caller that passes options down: whether you may
;; resolve depends on whether somebody above you already did, which is not
;; knowable locally. The marker makes it knowable. Metadata because it is
;; inert -- nothing reads the options map except by keyword, and it is never
;; encoded.
(def ^:private resolved-meta {::resolved true})

(defn resolved?
  "Whether `opts` has already been through `resolve-opts`."
  [opts]
  (boolean (::resolved (meta opts))))

;; The resolved options for `(encode v)` / `(decode bs)`, computed once.
;; nil opts is the dominant call shape and always resolves to the same map.
(def default-opts (with-meta (:clojure profile-defaults) resolved-meta))

;; ------------------------------------------------------------- the placeholder

(defn unencodable
  "The default `:encode-fallback` placeholder: a tag-27 frame naming the type
  that could not be encoded, with its `pr-str`.

  Readable by any CBOR implementation, and obviously a placeholder rather than
  a value that might be mistaken for the original."
  [x]
  (data/unknown-record "boring/unencodable"
                       {:type #?(:clj (.getName (class x))
                                 ;; `(str (type x))` is the constructor's
                                 ;; COMPILED SOURCE on ClojureScript -- for
                                 ;; `(atom 1)` it is
                                 ;; `function He(){this.state=1;...}`. Unbounded,
                                 ;; different in every build, and it leaks
                                 ;; implementation into a placeholder that gets
                                 ;; WRITTEN TO STORAGE. It also defeats
                                 ;; `:archival`, whose whole point is that two
                                 ;; dumps of the same value compare equal.
                                 ;;
                                 ;; The constructor's `.name` is minified under
                                 ;; `:advanced` and so is not stable across
                                 ;; builds either -- but it is SHORT and it does
                                 ;; not embed source. There is no stable type
                                 ;; name on this platform; the placeholder says
                                 ;; what it can rather than pasting a function
                                 ;; body into the wire.
                                 :cljs (let [t (type x)
                                             n (some-> t .-name)]
                                         (if (and n (seq n)) n "unknown")))
                        :repr (pr-str x)}))

(defn fallback-fn
  "`:encode-fallback` as the writer wants it: nil (throw, the default), or a
  function of the offending value returning a replacement.

  The only coercion in this namespace, and it is here rather than in the
  writers because both of them needed it and `:placeholder` has to mean the
  same function on both platforms."
  [fb]
  (if (= :placeholder fb) unencodable fb))

;; ------------------------------------------------------------- the predicates
;;
;; Few and named, deliberately. These are the only places the two platforms can
;; legitimately differ, so a divergence has to be introduced HERE, in a
;; four-line function with a name, rather than inlined into a table row.

;; The decoder recurses per level, so a limit the host stack cannot honour is
;; not a limit: `:max-depth 4000` accepted a 2000-deep document by raising a
;; raw StackOverflowError. 2048 is the most that has been measured to hold on a
;; default stack; the default of 1024 is well inside it.
(def ^:const max-safe-depth 2048)

;; The wire format's slot arithmetic is 32-bit on both platforms, so this is
;; the ceiling on both -- it is a property of the FORMAT, not of the host's
;; integer range, which is why ClojureScript capping at MAX_SAFE_INTEGER
;; produced files the JVM could not read.
(def ^:const max-index-stride 2147483647)

(defn- whole?
  "An integer in the host's exactly-representable range."
  [v]
  #?(:clj (integer? v)
     :cljs (and (number? v) (js/Number.isInteger v))))

(defn- int-in? [v lo hi]
  (and (whole? v) (>= v lo) (<= v hi)))

(defn- registry? [v]
  #?(:clj (instance? TagRegistry v)
     :cljs (map? v)))

(defn- callable?
  "Invocable, but not one of the DATA types that happen to be invocable.

  `ifn?` is true of keywords, symbols, maps, sets and vectors, so
  `:placehodler` -- one letter wrong -- was accepted, invoked as
  `(:placehodler v)`, and silently replaced every unencodable value with nil,
  while a vector threw untyped. `fn?` fixed that and went too far the other
  way: it rejects vars, multimethods and any record or `reify` implementing
  IFn, all of which are legitimate fallbacks."
  [v]
  (and (ifn? v)
       (not (or (keyword? v) (symbol? v) (map? v) (set? v) (vector? v)))))

(defn- boolish? [v] (or (true? v) (false? v) (nil? v)))

;; ------------------------------------------------------------------ the spec
;;
;; One row per option, and the row is the whole rule: what a legal value is,
;; and what to say when it is not. `:pred` is called only when the key is
;; PRESENT, so a default never has to satisfy its own predicate at runtime.
;;
;; WHICH LEAVES THE DEFAULTS UNGUARDED, and this comment used to end "-- the
;; test freezes the defaults instead", naming a test that does not exist.
;; Nothing under `test/` requires `boring.options` at all: `option_matrix.cljc`
;; freezes VERDICTS (what each entry point does with a given map) and
;; `generative_test.cljc` freezes the profile NAMES, both through
;; `boring.core`. A default widened here -- `:max-depth` to 100 000, say -- is
;; caught only if some behavioural assertion happens to notice.
;;
;; The same gap covers this namespace's whole exported surface. `spec`,
;; `profile-defaults`, `profile-locked`, `max-safe-depth`, `max-index-stride`,
;; `validate!` and `check-profile-conflicts!` have no reference anywhere in
;; `src/`, `test/` or `bench/` outside this file, so every one of them is
;; reachable only through the paths `resolve-opts` and `check-opts` happen to
;; take. That is fine for the code and not fine for the guarantee.

(def spec
  {:profile        {:pred #(contains? profile-defaults %)
                    :type :boring/unknown-profile
                    :want "one of :clojure, :interop, :archival, :canonical, :canonical-rfc7049"}
   :max-depth      {:pred #(int-in? % 1 max-safe-depth)
                    :want (str "a positive integer no greater than " max-safe-depth
                               " (the decoder recurses per level, and a limit the host "
                               "stack cannot honour is not a limit)")}
   :max-items      {:pred #(int-in? % 0 #?(:clj Long/MAX_VALUE :cljs js/Number.MAX_SAFE_INTEGER))
                    :want "a non-negative integer (0 means unlimited)"}
   :chunk-size     {:pred #(int-in? % 1 max-index-stride)
                    :want "a positive integer"}
   :index          {:pred #(int-in? % 0 max-index-stride)
                    :want (str "a non-negative integer no greater than " max-index-stride
                               " (0 turns indexing off)")}
   :index-min      {:pred #(int-in? % 0 max-index-stride)
                    :want (str "a non-negative integer no greater than " max-index-stride)}
   :float-policy   {:pred #{:preserve-width :shortest}
                    :want ":preserve-width or :shortest"}
   ;; `:canonical` and `:canonical-order` are LOCKED BY EVERY PROFILE, so
   ;; passing either can only ever produce `:boring/incompatible-options`. They
   ;; stay in the spec deliberately rather than being dropped: a dropped key is
   ;; an unknown key, and `{:cannonical true}` would then be indistinguishable
   ;; from a stray one. The conflict error names the profile and the value it
   ;; defines, which is more use than silence. Say what you want by NAMING a
   ;; profile -- that is what a profile is.
   :canonical      {:pred boolish? :want "true or false"}
   :canonical-order {:pred #{:rfc8949 :rfc7049}
                     :want ":rfc8949 or :rfc7049"}
   ;; A function is legal on both platforms: ClojureScript has one time type,
   ;; so a caller wanting `cljc.java-time`/`tick` values supplies the
   ;; constructor rather than boring depending on js-joda. `(fn [epoch-millis])`.
   :instant-type   {:pred #(or (#{:date :instant} %) (fn? %))
                    :want ":date, :instant, or a function of epoch millis"}
   :date-type      {:pred #{:local-date :sql-date}
                    :want ":local-date or :sql-date"}
   :encode-fallback {:pred #(or (nil? %) (= :placeholder %) (callable? %))
                     :want "nil, :placeholder, or a function"}
   ;; How much of a sealed index to believe, for `boring.nav`.
   ;;
   ;;   :trusted  use it (the default, and today's behaviour)
   ;;   :ignore   never read it -- always scan
   ;;
   ;; `:ignore` exists because a chosen index can misdirect a lookup WITHIN the
   ;; blob it came with. That gains an attacker nothing directly -- they wrote
   ;; every byte, so they could have sent the value they misdirect you to. It
   ;; matters when an application verifies one part of a document and then acts
   ;; on another: two `get`s can be made to resolve to overlapping regions, so
   ;; you checked one thing and used a different one. Scanning removes the
   ;; question, at the cost of the acceleration.
   ;;
   ;; A third value, `:validate` -- walk every anchor chain at load and refuse
   ;; the index if any link fails -- is measured (about one unindexed scan:
   ;; 12.2 ms on a 4.6 MB, 200 000-item file) and not yet implemented. It is
   ;; deliberately NOT accepted here until it is, because an option that names
   ;; a behaviour it does not perform is worse than one that does not exist.
   :trust-index    {:pred #{:trusted :ignore}
                    :want ":trusted or :ignore"}
   :registry       {:pred #(or (nil? %) (registry? %))
                    :want #?(:clj "a TagRegistry from boring/tag-registry"
                             :cljs "a registry map from boring/tag-registry")}
   ;; The booleans. `(boolean v)` accepts anything, which is the same shape of
   ;; silence `:float-policy` had: `{:validate-utf8 "false"}` read as TRUE and
   ;; turned nothing off, on the option doc/SECURITY.md names for lenient
   ;; decoding.
   :stringref                    {:pred boolish? :want "true or false"}
   :shapes                       {:pred boolish? :want "true or false"}
   :incl-metadata?               {:pred boolish? :want "true or false"}
   :permit-reserved-simple-values {:pred boolish? :want "true or false"}
   :tolerate-unknown-tags        {:pred boolish? :want "true or false"}
   :validate-utf8                {:pred boolish? :want "true or false"}
   :check-duplicate-keys         {:pred boolish? :want "true or false"}
   :auto-construct-records?      {:pred boolish? :want "true or false"}})

(defn- bad-option! [k v want type]
  (throw (ex-info (str "boring: " k " must be " want ", got " (pr-str v))
                  {:type (or type :boring/bad-option) :option k :value v})))

;; The predicates alone, so the checking loop does ONE lookup and one call per
;; key. Reading `:pred`, `:want` and `:type` off the spec row for every key
;; measured at 70-90 ns per option -- +18% on `encode` with a three-key map and
;; +23% on `decode`, which previously did no option work at all. The message
;; and the error type are only needed when a check FAILS, so they stay in
;; `spec` and are fetched on that path.
(def ^:private preds
  (persistent! (reduce-kv (fn [m k v] (assoc! m k (:pred v))) (transient {}) spec)))

(defn- near-miss
  "A spec key within one edit of `k`, or nil.

  Unknown keys pass -- callers thread their own map through, and konserve does
  -- but a key ONE CHARACTER from a real option is far more likely a typo than
  a foreign key, and this library has shipped two defects that were exactly
  that. `:max-item`, `:stringrefs` and `:cannonical` are caught; anything that
  looks nothing like an option is left alone.

  Only ever runs on keys the spec does not know, so the cost falls on the
  unusual case rather than on every option of every call."
  [k]
  (when (keyword? k)
    (let [a (name k)
          n (count a)
          within-one?
          (fn [b]
            (let [m (count b)]
              (when (<= (Math/abs (- n m)) 1)
                (loop [i 0 j 0 slack 1]
                  (cond (and (= i n) (= j m)) true
                        (neg? slack) false
                        (= i n) (recur i (inc j) (dec slack))
                        (= j m) (recur (inc i) j (dec slack))
                        (= (nth a i) (nth b j)) (recur (inc i) (inc j) slack)
                        (= n m) (recur (inc i) (inc j) (dec slack))
                        (< n m) (recur i (inc j) (dec slack))
                        :else (recur (inc i) j (dec slack)))))))]
      (first (filter #(and (not= % k) (= (namespace k) (namespace %))
                           (within-one? (name %)))
                     (keys spec))))))

(defn validate!
  "Check every KNOWN key in `opts`. Returns `opts`.

  Iterates the caller's map, not the spec: a caller's options map is small and
  usually holds two or three keys, while the spec has twenty-two. Unknown keys
  pass -- callers legitimately thread their own map through (konserve does),
  and rejecting those would make boring's option map closed in a way nothing
  else about this library is."
  [opts]
  (reduce-kv (fn [_ k v]
               (let [p (get preds k)]
                 (if p
                   (when-not (p v)
                     (let [{:keys [want type]} (get spec k)]
                       (bad-option! k v want type)))
                   (when-let [m (near-miss k)]
                     (throw (ex-info (str "boring: unknown option " k
                                          " -- did you mean " m "?")
                                     {:type :boring/bad-option :option k
                                      :did-you-mean m})))))
               nil)
             nil opts)
  opts)

;; ------------------------------------------------------------------- resolve

;; Allocation-free unless there IS a conflict.
;;
;; This runs on EVERY encode and decode that passes options. The first version
;; built a sorted set through `into` whatever the input, so the common call
;; paid for a reduction and a set allocation to discover it could not conflict
;; with anything. The ClojureScript copy kept that version for months after the
;; JVM one was fixed, which is the whole argument for this file.
(defn check-profile-conflicts! [profile base opts]
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

(defn- profile-of [opts]
  (let [profile (get opts :profile :clojure)]
    (or (profile-defaults profile)
        (bad-option! :profile profile (:want (:profile spec))
                     :boring/unknown-profile))))

(defn check-opts
  "The gate for the DECODE side: validate, and return `opts` untouched.

  Not `resolve-opts`, because no profile key reaches a Reader -- a profile
  names `:stringref`, `:float-policy`, `:canonical`, `:canonical-order` and
  `:shapes`, and `configure-reader!` reads none of them. Merging would allocate
  a fresh map of ~230-300 bytes on every `decode` call that passes options, to
  add five keys nothing downstream looks at.

  The profile is still LOOKED UP, so `{:profile :nope}` is refused here as it
  is on the encode side. That asymmetry -- `decode` and `decode-seq` accepting
  an unknown profile while `decode-with` and `decode-seq-from` refused it --
  was one of the six measured disagreements this namespace exists to end."
  [opts]
  (when (some? opts)
    ;; The profile is only looked up when one was NAMED. `resolve-opts` needs
    ;; the profile's map and pays for the lookup either way; this side only
    ;; needs to know the name is real, and most decode calls do not pass one.
    (when (contains? opts :profile) (profile-of opts))
    (validate! opts))
  opts)

(defn resolve-opts
  "The one gate. Validate, apply the profile, merge.

  Every public entry point on both platforms calls exactly this and passes the
  result down; nothing downstream re-validates. `nil` short-circuits to the
  frozen default map without touching any of it, which is the dominant call
  shape and the reason the validation above is affordable."
  [opts]
  (cond
    (nil? opts) default-opts
    ;; Already through the gate -- see `resolved?`. Returning it unchanged is
    ;; what lets a function resolve its own argument without having to know
    ;; whether its caller did.
    (resolved? opts) opts
    :else (let [base (profile-of opts)]
            (validate! opts)
            (check-profile-conflicts! (get opts :profile :clojure) base opts)
            (with-meta (merge base (dissoc opts :profile)) resolved-meta))))
