(ns boring.data
  "The value vocabulary shared by both platforms.

  CBOR has three things Clojure has no native counterpart for — simple values,
  the `undefined` value, and tagged values whose tag we do not interpret. They
  live here rather than in either implementation so the conformance suite can
  name them once."
  ;; `decimal?` and `rational?` here are CBOR's tag-4/tag-30 predicates, not
  ;; clojure.core's. Without this exclusion every downstream build — konserve,
  ;; kabel, datahike — carries two :redef warnings, which is how real warnings
  ;; get lost.
  (:refer-clojure :exclude [decimal? rational?]))

;; ## Simple values (major type 7)
;;
;; false/true/null are simple values 20/21/22 and decode to Clojure's own
;; booleans and nil. Everything else in the simple-value space is carried
;; opaquely.

(defrecord SimpleValue [n])

(defn simple-value
  "A CBOR simple value with code `n` (0-255)."
  [n]
  (->SimpleValue n))

(defn simple-value?
  [x]
  (instance? SimpleValue x))

(def undefined
  "CBOR's `undefined` — simple value 23. Distinct from nil, which is `null`
  (simple value 22)."
  (->SimpleValue 23))

;; ## Tagged values (major type 6)
;;
;; A tag whose semantics the codec does not implement (or that the caller asked
;; to be left alone) surfaces as a TaggedValue rather than throwing, so that
;; `:tolerate-unknown-tags` style reading and passthrough re-encoding are
;; possible.

(defrecord TaggedValue [tag value])

(defn tagged-value
  [tag value]
  (->TaggedValue tag value))

(defn tagged-value?
  [x]
  (instance? TaggedValue x))

;; ## Records
;;
;; A record encodes as tag 27 (generic object) carrying its type name and its
;; field map. The name is written unconditionally, so the WRITE side needs no
;; registration — the type is never silently flattened to a plain map, which is
;; the failure mode `incognito` exists to work around.
;;
;; Registration is needed only to READ a record back as its concrete type.
;; Without it you get an `UnknownRecord`, which keeps the name and fields and
;; re-encodes to the identical bytes, so passthrough is lossless.

;; UnknownRecord is a named tag-27 frame whose payload is a FIELD MAP — which
;; is what every record boring writes becomes when the reader has no
;; registration for it. It presents that payload directly: field lookup,
;; count, seq, assoc and friends all work, so code that only reads fields does
;; not care whether a read handler was installed. It follows defrecord's
;; equality semantics — equal to another UnknownRecord of the same type with
;; the same fields, NOT equal to a bare map with those fields.
;;
;; That last clause holds ON THE JVM ONLY. On ClojureScript `=` between an
;; UnknownRecord and a map is ASYMMETRIC: `(= u {:a 1})` is false and
;; `(= {:a 1} u)` is TRUE. This is a deftype satisfying IMap, so cljs `map?` is
;; true of it while `record?` is false, and cljs's `equiv-map` excludes only
;; REAL records — so the map's own `-equiv` accepts it. `hash` disagrees in
;; both directions either way, so set membership follows whichever side you put
;; it on: `(contains? #{u} {:a 1})` is true and `(contains? #{{:a 1}} u)` is
;; false. Measured, same expression on both platforms:
;;
;;   [(= u {:a 1}) (= {:a 1} u) (= (hash u) (hash {:a 1}))
;;    (contains? #{{:a 1}} u) (contains? #{u} {:a 1})]
;;   JVM   [false false false false false]
;;   CLJS  [false true  false false true ]
;;
;; Do not rely on `=` between an UnknownRecord and a map in portable code.
;;
;; It does NOT assert that the sender used `defrecord`, and cannot: tag 27
;; carries no such bit, a registered write handler may emit a map payload for
;; a `deftype`, and a frame from Python carries no origin information at all.
;; The map presentation is a choice about the payload in hand, not a claim
;; about where it came from.
;;
;; A frame whose payload is NOT a map decodes to a `clojure.lang.TaggedLiteral`
;; instead. That split is the whole point: this type's map surface is sound
;; only over a map, and returning it for a positional payload produced a value
;; that claimed IPersistentMap and then threw.
;;
;; `incognito`'s IncognitoTaggedLiteral carries {:tag :value} and requires
;; reaching through :value to see a field; this does not.

(deftype UnknownRecord [rtype rfields _meta]
  #?@(:clj
      [clojure.lang.ILookup
       (valAt [_ k] (get rfields k))
       (valAt [_ k nf] (get rfields k nf))

       clojure.lang.IPersistentMap
       (assoc [_ k v] (UnknownRecord. rtype (assoc rfields k v) _meta))
       (assocEx [_ k v] (UnknownRecord. rtype (.assocEx ^clojure.lang.IPersistentMap rfields k v) _meta))
       (without [_ k] (UnknownRecord. rtype (dissoc rfields k) _meta))

       clojure.lang.Associative
       (containsKey [_ k] (contains? rfields k))
       (entryAt [_ k] (find rfields k))

       clojure.lang.IPersistentCollection
       (count [_] (count rfields))
       (cons [_ o] (UnknownRecord. rtype (conj rfields o) _meta))
       (empty [_] (UnknownRecord. rtype {} _meta))
       (equiv [_ o] (and (instance? UnknownRecord o)
                         (= rtype (.-rtype ^UnknownRecord o))
                         (= rfields (.-rfields ^UnknownRecord o))))

       clojure.lang.Seqable
       (seq [_] (seq rfields))

       clojure.lang.Counted

       clojure.lang.IObj
       (withMeta [_ m] (UnknownRecord. rtype rfields m))

       clojure.lang.IMeta
       (meta [_] _meta)

       Iterable
       (iterator [_] (.iterator ^Iterable rfields))

       Object
       (equals [this o] (.equiv this o))
       (hashCode [_] (hash [rtype rfields]))
       (toString [_] (str "#boring/record [" rtype " " (pr-str rfields) "]"))]

      :cljs
      [ILookup
       (-lookup [_ k] (get rfields k))
       (-lookup [_ k nf] (get rfields k nf))

       IAssociative
       (-contains-key? [_ k] (contains? rfields k))
       (-assoc [_ k v] (UnknownRecord. rtype (assoc rfields k v) _meta))

       IMap
       (-dissoc [_ k] (UnknownRecord. rtype (dissoc rfields k) _meta))

       ICollection
       (-conj [_ o] (UnknownRecord. rtype (conj rfields o) _meta))

       IEmptyableCollection
       (-empty [_] (UnknownRecord. rtype {} _meta))

       ISeqable
       (-seq [_] (seq rfields))

       ICounted
       (-count [_] (count rfields))

       IEquiv
       ;; `instance?` in an `and` does not narrow `o` for the ClojureScript
       ;; compiler, so the field reads need explicit hints or they warn in every
       ;; downstream build.
       (-equiv [_ o] (and (instance? UnknownRecord o)
                          (= rtype (.-rtype ^UnknownRecord o))
                          (= rfields (.-rfields ^UnknownRecord o))))

       IHash
       (-hash [_] (hash [rtype rfields]))

       IMeta
       (-meta [_] _meta)

       IWithMeta
       (-with-meta [_ m] (UnknownRecord. rtype rfields m))

       IPrintWithWriter
       (-pr-writer [_ w _] (-write w (str "#boring/record [" rtype " " (pr-str rfields) "]")))]))

(defn unknown-record
  "A tag-27 frame carrying a wire type name and a FIELD MAP.

  `fields` is a map. That is the contract, not an accident of the constructor
  being permissive: a record is a named product of fields, tag 27 is CBOR's
  \"serialised language-independent object with type name and constructor
  arguments\", and the reader reconstructs one by looking the name up and
  handing it the map.

  A non-map payload encodes and decodes as valid CBOR, but it comes back a
  tagged literal rather than an `UnknownRecord` -- so
  `(= v (decode (encode v)))` is false for it. The bytes are stable and
  nothing is lost from the wire; what does not survive is the claim that this
  was a record, because with no field map it was not one. boring's own index
  frame is the exception that proves the rule: its payload is an array, it is
  read by `boring.frame` rather than by the record path, and it is deliberately
  not reconstructed as a record."
  ([type fields] (UnknownRecord. type fields nil))
  ([type fields m] (UnknownRecord. type fields m)))

(defn unknown-record?
  [x]
  (instance? UnknownRecord x))

(defn record-type
  "The wire type name an UnknownRecord stands for."
  [^UnknownRecord x]
  (.-rtype x))

(defn record-fields
  "The field map of an UnknownRecord, as a plain map."
  [^UnknownRecord x]
  (.-rfields x))

;; Implementing IPersistentMap means Clojure's printer treats it as a map and
;; the type name — the whole point of keeping the value — becomes invisible.
;; Print it explicitly.
#?(:clj
   (defmethod print-method UnknownRecord
     [x ^java.io.Writer w]
     (.write w "#boring/record [")
     (print-method (record-type x) w)
     (.write w " ")
     (print-method (record-fields x) w)
     (.write w "]")))

(defn record-type-name
  "The canonical wire name for a record type: the name as WRITTEN, on both
  platforms.

  Nothing is munged. ClojureScript's `pr-str` reports the name as written
  (`#my-test-ns.My-Rec{...}`), and on the JVM `boring.TagRegistry` inverts
  Clojure's `namespace-munge` by looking the namespace up rather than guessing
  — see its `recordName`. This used to munge ClojureScript DOWN to the JVM's
  lossy form, discarding information the browser still had in order to agree
  with a platform that had lost it. Every lossy step breaks a symmetry, and
  this one was not forced.

  On ClojureScript the name has to come from `pr-str` rather than the
  constructor's `.name`, which is MINIFIED under `:advanced` — a record printed
  as `#my.ns.P{...}` reports a type name of `Vg`. `pr-str` carries it as a
  string constant the compiler embeds, which survives minification.

  SLASH separates namespace from name. A dot is ambiguous -- `a.b.c.D` could
  split either way -- and `/` is legal in neither part. It is also what boring's
  own reserved tag-27 names already use: `clojure/sorted-map`, `java/period`."
  [x]
  #?(:clj (.recordName org.replikativ.boring.TagRegistry/EMPTY (class x))
     :cljs (let [s (pr-str x)
                 i (.indexOf s "{")]
             (if (and (pos? i) (= "#" (subs s 0 1)))
               (let [n (subs s 1 i) dot (.lastIndexOf n ".")]
                 (if (pos? dot) (str (subs n 0 dot) "/" (subs n (inc dot))) n))
               (str (type x))))))

;; ## Decimal and rational stand-ins for ClojureScript
;;
;; The JVM writes BigDecimal as tag 4 and Ratio as tag 30. ClojureScript has
;; neither type, so before these existed a JVM-written decimal or rational
;; decoded on CLJS as an opaque TaggedValue — silent data loss on exactly the
;; JVM-to-browser path a portable dump format promises to support.
;;
;; datahike's dump requirements ask for precisely this shape: "returning
;; [exponent mantissa] or a small record is fine for us, as long as scale is not
;; lost." Scale lives in the exponent, so 1.50M and 1.5M stay distinct.
;;
;; They re-encode to tags 4 and 30, so a value written on either platform reads
;; back correctly on the other.

(defrecord Decimal [exponent mantissa])

(defn decimal
  "A decimal fraction: mantissa * 10^exponent."
  [exponent mantissa]
  (->Decimal exponent mantissa))

(defn decimal? [x] (instance? Decimal x))

;; ### Interoperating with BigDecimal-shaped decimals
;;
;; `Decimal` mirrors CBOR tag 4: `mantissa * 10^exponent`. `java.math.BigDecimal`
;; and fress's `fress.impl.bigdec/Bigdec` mirror the other convention:
;; `unscaled * 10^-scale`. The two differ in TWO ways, and both are quiet.
;;
;; 1. The sign of the power is inverted. `scale = -exponent`. Getting this
;;    backwards on an ordinary money amount is a 10^4 error with no symptom.
;;
;; 2. `mantissa` is not one type. On ClojureScript the reader produces an
;;    ordinary number below 2^53 and a `js/BigInt` above it, because making
;;    every small decimal a BigInt would allocate for nothing. Consumers doing
;;    exact BigInt arithmetic -- which is the whole point of having a decimal --
;;    then throw "Cannot mix BigInt and other types" on the SMALL case and work
;;    on the large one. That polarity is the dangerous one: a test suite with
;;    interesting numbers in it passes, and 1.50 fails in production.
;;
;; These two accessors are the conversion surface. `decimal-unscaled` always
;; returns a big integer, so arithmetic over it never has to check.

;; ### Uniform access across both fallback shapes
;;
;; An unregistered tag-27 frame decodes to an `UnknownRecord` (map payload) or
;; a `clojure.lang.TaggedLiteral` (anything else). These read either without
;; branching, so a caller inspecting unregistered data never has to know which
;; shape arrived.

(defn tagged-frame?
  "True for either fallback an unregistered tag-27 frame can decode to."
  [x]
  (or (unknown-record? x) (tagged-literal? x)))

(defn frame-name
  "The wire type name of an unregistered tag-27 frame, as a string."
  [x]
  (cond
    (unknown-record? x) (record-type x)
    (tagged-literal? x) (str (:tag x))
    :else (throw (ex-info "boring: not a tag-27 frame"
                          {:type :boring/not-a-frame :value x}))))

(defn frame-payload
  "The payload of an unregistered tag-27 frame: a field map for an
  `UnknownRecord`, whatever was written for a `TaggedLiteral`."
  [x]
  (cond
    (unknown-record? x) (record-fields x)
    (tagged-literal? x) (:form x)
    :else (throw (ex-info "boring: not a tag-27 frame"
                          {:type :boring/not-a-frame :value x}))))

(defn decimal-scale
  "The BigDecimal-style scale: value = unscaled * 10^-scale.

  Accepts whatever the platform's decoder produced for CBOR tag 4 -- a real
  `java.math.BigDecimal` on the JVM, this namespace's `Decimal` stand-in on
  ClojureScript -- so portable code does not branch."
  [d]
  #?(:clj  (if (instance? java.math.BigDecimal d)
             (.scale ^java.math.BigDecimal d)
             (- (:exponent d)))
     :cljs (- (:exponent d))))

(defn decimal-unscaled
  "The unscaled value, always as a big integer -- `BigInteger` on the JVM,
  `js/BigInt` on ClojureScript -- whatever its magnitude."
  [d]
  #?(:clj  (if (instance? java.math.BigDecimal d)
             (.unscaledValue ^java.math.BigDecimal d)
             (biginteger (:mantissa d)))
     :cljs (let [m (:mantissa d)]
             (if (= "bigint" (goog/typeOf m)) m (js/BigInt m)))))

(defn decimal-from-unscaled
  "The inverse of `decimal-unscaled` / `decimal-scale`: a platform decimal from
  a BigDecimal-style unscaled value and scale.

  On ClojureScript the unscaled value is narrowed back to an ordinary number
  when it fits, because that is the convention the reader itself uses and the
  writer encodes the two differently: a `js/BigInt` goes out as a bignum (tag
  2/3) even when it is small, so skipping this made the same logical decimal
  encode as `c4 8221 c241 96` here and `c4 8221 1896` on the JVM -- a
  cross-platform byte divergence introduced by the round trip through this
  function, not present in the data."
  [unscaled scale]
  #?(:clj  (java.math.BigDecimal. (biginteger unscaled) (int scale))
     :cljs (decimal (- scale)
                    (if (and (= "bigint" (goog/typeOf unscaled))
                             (<= (js/BigInt (- js/Number.MAX_SAFE_INTEGER)) unscaled)
                             (<= unscaled (js/BigInt js/Number.MAX_SAFE_INTEGER)))
                      (js/Number unscaled)
                      unscaled))))

(defrecord Rational [numerator denominator])

(defn rational [numerator denominator] (->Rational numerator denominator))

(defn rational? [x] (instance? Rational x))

;; ## Byte-array helpers
;;
;; Byte strings decode to a platform byte array, which does not have value
;; equality on either platform. Tests and any content-addressed use need this.

(defn bytes-like?
  [x]
  #?(:clj  (and (some? x) (.isArray (class x)) (identical? Byte/TYPE (.getComponentType (class x))))
     :cljs (instance? js/Uint8Array x)))

(defn bytes=
  "Value equality for byte arrays."
  [a b]
  (cond
    (and (bytes-like? a) (bytes-like? b))
    #?(:clj  (java.util.Arrays/equals ^bytes a ^bytes b)
       :cljs (and (= (.-length a) (.-length b))
                  (loop [i 0]
                    (cond (= i (.-length a)) true
                          (not= (aget a i) (aget b i)) false
                          :else (recur (inc i))))))

    :else (= a b)))
