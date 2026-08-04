(ns boring.writer
  "CBOR writer for ClojureScript.

  Design follows measurements taken on node before any of this
  was written:

  - `DataView`, not hand-rolled shift/mask. Manual byte assembly measured 2.2x
    SLOWER than DataView for a big-endian i32 (4.34 vs 1.97 ns), which is the
    opposite of the usual instinct. DataView's default byte order is
    big-endian, which is also CBOR's.
  - `TextEncoder` costs ~56 ns per call regardless of string length, so a manual
    ASCII loop wins below ~55 characters (8.7 ns for 9 chars). Keywords and map
    keys are almost always shorter than that, so using TextEncoder everywhere
    would cost ~6x on the hot case.
  - `setBigInt64` is 5.7x a normal write, so it is reserved for values outside
    the safe-integer range."
  ;; boring.reader/reset! and boring.writer/reset! are namespaced entry
  ;; points, not shadows of the core fn -- but without this exclusion every
  ;; downstream ClojureScript build reports a :redef warning for them, and
  ;; warning noise is how real warnings get missed.
  (:refer-clojure :exclude [reset!])
  (:require [boring.data :as data]))

;; ---------------------------------------------------------------- constants

(def ^:const UINT 0)
(def ^:const NINT 0x20)
(def ^:const BYTES 0x40)
(def ^:const TEXT 0x60)
(def ^:const ARRAY 0x80)
(def ^:const MAP 0xA0)
(def ^:const TAG 0xC0)
(def ^:const SIMPLE 0xE0)

(def ^:const TAG-IDENTIFIER 39)
(def ^:const TAG-STRINGREF 25)
(def ^:const TAG-SR-NS 256)
(def ^:const TAG-SET 258)
(def ^:const TAG-POS-BIGNUM 2)
(def ^:const TAG-NEG-BIGNUM 3)
(def ^:const TAG-DATETIME 0)
(def ^:const TAG-UUID 37)
;; Registered tags. js/RegExp and java.util.regex.Pattern both land on tag 35,
;; so a regex is one of the few JVM-only-looking types that is genuinely
;; symmetric across our two platforms. There is no JS URI type, so tag 32
;; decodes to a plain string here -- lossless, since a URI IS its string form,
;; unlike a BigDecimal which is why that one needed a stand-in type.
(def ^:const TAG-URI 32)
(def ^:const TAG-REGEX 35)
(def ^:const TAG-GENERIC-OBJ 27)
;; Reserved tag-27 names for Clojure collections CBOR cannot distinguish. See
;; the JVM Writer: a sorted map is a CBOR map and a queue is a CBOR array, so
;; without a marker they come back as a plain map and a vector. The slash makes
;; them uncollidable with a record's wire name, which is always dotted.
(def ^:const NAME-SORTED-MAP "clojure/sorted-map")
(def ^:const NAME-SORTED-SET "clojure/sorted-set")
(def ^:const NAME-QUEUE      "clojure/queue")
(def ^:const NAME-WITH-META  "clojure/with-meta")
;; See the JVM Writer for the rationale and the provisional-tag caveat.
(def ^:const TAG-SHAPED-ARRAY 39649)
(def ^:const TAG-DECIMAL 4)
(def ^:const TAG-RATIONAL 30)
;; RFC 8746 little-endian typed arrays, keyed by JS constructor name.
(def typed-array-tags
  {"Int16Array" 77 "Int32Array" 78 "BigInt64Array" 79
   "Float32Array" 85 "Float64Array" 86})

;; Above this length TextEncoder's fixed per-call cost is cheaper than looping.
(def ^:const ASCII-LOOP-MAX 55)

(def text-encoder (js/TextEncoder.))

(defn- ident-string
  "The wire form of a keyword or symbol, memoised PER MESSAGE.

  Two earlier shapes were wrong. A module-global cache leaked process-wide —
  2000 decode/re-encode round trips of the same 3-keyword map grew it to 6003
  entries — because the docstring's claim that CLJS interns keywords is false:
  `(identical? (keyword \"ab\") (keyword \"ab\"))` is false, so a fresh reader
  hands the writer fresh Keyword objects every message. Removing the cache
  entirely then cost 27% of encode, because within ONE message the same keyword
  recurs (200 datom maps share `:a`) and stringref still needs its string to do
  the table lookup.

  So: per-writer, cleared by `reset!` alongside the stringref table. Bounded by
  one message's distinct identifier count, and it dies with the writer.

  The JVM does not need this — `clojure.lang.Keyword.toString()` memoises on the
  keyword itself."
  [^Writer w k]
  (let [m (.-identStrings w)
        hit (.get m k)]
    (if (some? hit)
      hit
      ;; `keyword?` narrows k for the compiler; the symbol branch has to be
      ;; hinted by hand or it emits an :infer-warning downstream.
      (let [s (if (keyword? k) (str ":" (.-fqn k)) (.-str ^cljs.core/Symbol k))]
        (.set m k s)
        s))))

(def ^:const MAX-SAFE 9007199254740991)

;; ---------------------------------------------------------------- writer

(deftype Writer [^:mutable buf
                 ^:mutable dv
                 ^:mutable pos
                 ^:mutable srKeys      ; string -> index, a JS Map
                 ^:mutable srNext
                 ^:mutable stringref
                 ^:mutable preserveWidth
                 ^:mutable canonical
                 ^:mutable legacyCanonicalOrder
                 ^:mutable shapes
                 ^:mutable registry
                 ^:mutable identStrings
                 ^:mutable inclMetadata
                 ^:mutable metaWrapped
                 ;; Encode-side nesting cap, matching the JVM writer's. Without
                 ;; one, deeply nested input escaped as a raw JS RangeError
                 ;; ("Maximum call stack size exceeded") where the JVM raises a
                 ;; typed :boring/max-depth-exceeded -- and a stack overflow in
                 ;; a browser tab is not a recoverable error the way a typed
                 ;; one is.
                 ^:mutable depth
                 ^:mutable maxDepth
                 ;; RFC 8949 3.3 forbids ENCODING simple values 24-31. The JVM
                 ;; offers an opt-out for byte-identical passthrough of a
                 ;; document that contains one; CLJS did not, so the same
                 ;; portable option was accepted on one platform and silently
                 ;; ignored on the other.
                 ^:mutable permitReservedSimpleValues
                 ;; Called with a value that has no encoding; its result is
                 ;; written instead. nil (default) throws as before.
                 ^:mutable encodeFallback
                 ^:mutable inFallback
                 ;; Depth already consumed by a PARENT writer, for a canonical
                 ;; scratch. Separate from `depth` because `reset!` runs before
                 ;; every staged key and zeroes `depth`: copying the parent's
                 ;; depth into the scratch therefore did nothing at all, and a
                 ;; map key got a fresh budget -- so nesting through key
                 ;; positions could repeatedly renew a cap set as a SECURITY
                 ;; bound. The JVM carries the same field for the same reason.
                 ^:mutable depthOffset
                 ;; Pre-resolved core options for reusable writers. Kept opaque
                 ;; here to avoid coupling the byte emitter back to core.
                 ^:mutable opts])

(defn writer
  ([] (writer 256))
  ([size]
   (let [b (js/Uint8Array. (max 64 size))]
     (Writer. b (js/DataView. (.-buffer b)) 0 (js/Map.) 0 true true false false false nil
              (js/Map.) true false 0 1024 false nil false 0 nil))))

(defn reset! [^Writer w]
  (set! (.-pos w) 0)
  (when (pos? (.-size (.-srKeys w))) (.clear (.-srKeys w)))
  (when (pos? (.-size (.-identStrings w))) (.clear (.-identStrings w)))
  (set! (.-srNext w) 0)
  ;; `depth` and `metaWrapped` too. `enter!` increments BEFORE the try/finally
  ;; that decrements, so a value rejected at the cap left the counter raised on
  ;; a reusable writer -- and the next shallow `encode-into!` was rejected too,
  ;; each failure raising it further. A typed error that poisons the writer is
  ;; worse than an untyped one, because it looks recoverable. The JVM reset
  ;; already cleared its counter.
  (set! (.-depth w) 0)
  ;; `depthOffset` deliberately survives: it records what a PARENT already
  ;; spent, and a scratch writer is reset once per staged key.
  (set! (.-metaWrapped w) false)
  w)

(defn position [^Writer w] (.-pos w))
(defn buffer [^Writer w] (.-buf w))

(defn to-bytes [^Writer w]
  (.slice (.-buf w) 0 (.-pos w)))

(defn- grow! [^Writer w need]
  (let [cap (loop [c (.-length (.-buf w))] (if (< c need) (recur (* 2 c)) c))
        nb (js/Uint8Array. cap)]
    (.set nb (.subarray (.-buf w) 0 (.-pos w)) 0)
    (set! (.-buf w) nb)
    (set! (.-dv w) (js/DataView. (.-buffer nb)))))

(defn- ensure! [^Writer w n]
  (when (> (+ (.-pos w) n) (.-length (.-buf w))) (grow! w (+ (.-pos w) n))))

(defn- u8! [^Writer w b]
  (ensure! w 1)
  (aset (.-buf w) (.-pos w) b)
  (set! (.-pos w) (inc (.-pos w))))

(declare min-len-for-index)

(defn head!
  "Header byte for `major` carrying unsigned `val`, in the shortest form."
  [^Writer w major val]
  ;; EVERY byte string consumes a stringref index, wherever it appears -- a
  ;; bignum magnitude, a UUID's 16 bytes, a typed-array payload. Accounted here
  ;; rather than at each call site because forgetting one is SILENT: our index
  ;; space drifts from the decoder's and a later reference resolves to the
  ;; wrong entry. Major type 2 is always a byte string, so this cannot misfire.
  (when (and (== major BYTES)
             (.-stringref w)
             (>= val (min-len-for-index (.-srNext w))))
    (set! (.-srNext w) (inc (.-srNext w))))
  (cond
    ;; A tag number is an unsigned 64-bit integer, so the reader yields a
    ;; BigInt past 2^53. Every branch below does Number arithmetic, and mixing
    ;; a BigInt into it throws a TypeError -- so a TaggedValue the reader had
    ;; just produced could not be written back.
    (= "bigint" (goog/typeOf val))
    (if (<= val (js/BigInt js/Number.MAX_SAFE_INTEGER))
      (head! w major (js/Number val))            ; shortest form, as usual
      (do (when (or (neg? val) (> val (js/BigInt "18446744073709551615")))
            (throw (ex-info (str "boring: tag must be an unsigned 64-bit integer, got " val)
                            {:type :boring/bad-tag})))
          (ensure! w 9)
          (aset (.-buf w) (.-pos w) (bit-or major 27))
          (.setBigUint64 (.-dv w) (inc (.-pos w)) val)
          (set! (.-pos w) (+ (.-pos w) 9))))

    (< val 24)
    (do (ensure! w 1)
        (aset (.-buf w) (.-pos w) (bit-or major val))
        (set! (.-pos w) (inc (.-pos w))))

    (< val 0x100)
    (do (ensure! w 2)
        (aset (.-buf w) (.-pos w) (bit-or major 24))
        (aset (.-buf w) (inc (.-pos w)) val)
        (set! (.-pos w) (+ (.-pos w) 2)))

    (< val 0x10000)
    (do (ensure! w 3)
        (aset (.-buf w) (.-pos w) (bit-or major 25))
        (.setUint16 (.-dv w) (inc (.-pos w)) val)   ; big-endian by default
        (set! (.-pos w) (+ (.-pos w) 3)))

    (< val 0x100000000)
    (do (ensure! w 5)
        (aset (.-buf w) (.-pos w) (bit-or major 26))
        (.setUint32 (.-dv w) (inc (.-pos w)) val)
        (set! (.-pos w) (+ (.-pos w) 5)))

    :else
    ;; Beyond u32 but within safe-integer range: split into two u32 writes
    ;; rather than setBigInt64, which measured 5.7x a normal write.
    (do (ensure! w 9)
        (aset (.-buf w) (.-pos w) (bit-or major 27))
        (.setUint32 (.-dv w) (inc (.-pos w)) (js/Math.floor (/ val 0x100000000)))
        (.setUint32 (.-dv w) (+ (.-pos w) 5) (mod val 0x100000000))
        (set! (.-pos w) (+ (.-pos w) 9)))))

(defn- head-bigint!
  "Emit an argument in [0, 2^64) using the SHORTEST form.

  This unconditionally wrote the 9-byte form, so a reduced bignum that fit in a
  byte still went out as nine: BigInt 0 encoded as `1b0000000000000000` and 255
  as `1b00000000000000ff`, where canonical requires `00` and `18ff`. The
  :canonical profile therefore emitted non-canonical bytes. Invisible until
  :canonical stopped preserving width and this path became reachable.

  Below 2^53 the value is exactly representable as a JS number, so it can go
  through the ordinary shortest-form header. At or above that, the 9-byte form
  is its shortest encoding anyway."
  [^Writer w major ^js/BigInt v]
  (if (< v (js/BigInt "9007199254740992"))
    (head! w major (js/Number v))
    (do (ensure! w 9)
        (aset (.-buf w) (.-pos w) (bit-or major 27))
        (.setBigUint64 (.-dv w) (inc (.-pos w)) v)
        (set! (.-pos w) (+ (.-pos w) 9)))))

(defn write-int! [^Writer w n]
  (if (>= n 0) (head! w UINT n) (head! w NINT (- (- n) 1))))

(defn write-f64! [^Writer w d]
  (ensure! w 9)
  (aset (.-buf w) (.-pos w) (bit-or SIMPLE 27))
  (.setFloat64 (.-dv w) (inc (.-pos w)) d)
  (set! (.-pos w) (+ (.-pos w) 9)))

(def ^:private f32-probe (js/Float32Array. 1))

(defn- to-half
  "float -> IEEE binary16 bits, or nil if not exactly representable."
  [f]
  (aset f32-probe 0 f)
  (let [bits (aget (js/Uint32Array. (.-buffer f32-probe)) 0)
        sign (bit-and (bit-shift-right bits 16) 0x8000)
        exp (bit-and (bit-shift-right bits 23) 0xFF)
        mant (bit-and bits 0x7FFFFF)]
    (cond
      (= exp 0xFF) (bit-or sign 0x7C00 (if (zero? mant) 0 0x200))
      (zero? exp) sign
      :else
      (let [new-exp (+ (- exp 127) 15)]
        (cond
          (>= new-exp 0x1F) nil
          (<= new-exp 0)
          (when (>= new-exp -10)
            (let [m (bit-or mant 0x800000)
                  shift (- 14 new-exp)]
              (when (and (<= shift 31) (zero? (bit-and m (dec (bit-shift-left 1 shift)))))
                (bit-or sign (bit-shift-right m shift)))))
          (not (zero? (bit-and mant 0x1FFF))) nil
          :else (bit-or sign (bit-shift-left new-exp 10) (bit-shift-right mant 13)))))))

(defn- from-half [h]
  (let [sign (if (zero? (bit-and h 0x8000)) 1 -1)
        exp (bit-and (bit-shift-right h 10) 0x1F)
        mant (bit-and h 0x3FF)]
    (* sign (cond (zero? exp) (* mant (js/Math.pow 2 -24))
                  (not= exp 31) (* (+ mant 1024) (js/Math.pow 2 (- exp 25)))
                  (zero? mant) js/Infinity
                  :else js/NaN))))

(defn write-f16! [^Writer w bits]
  (ensure! w 3)
  (aset (.-buf w) (.-pos w) (bit-or SIMPLE 25))
  (.setUint16 (.-dv w) (inc (.-pos w)) bits)
  (set! (.-pos w) (+ (.-pos w) 3)))

(defn write-f32! [^Writer w f]
  (ensure! w 5)
  (aset (.-buf w) (.-pos w) (bit-or SIMPLE 26))
  (.setFloat32 (.-dv w) (inc (.-pos w)) f)
  (set! (.-pos w) (+ (.-pos w) 5)))

(defn write-shortest-float!
  "RFC 8949 §4.2.2 preferred serialisation: narrowest form that round-trips."
  [^Writer w d]
  (if (js/isNaN d)
    ;; NaN never equals itself, so the round-trip check below would always
    ;; reject the narrow form and fall through to f64. Canonical NaN is f16
    ;; 0x7e00.
    (write-f16! w 0x7e00)
    (do
      (aset f32-probe 0 d)
      (let [f (aget f32-probe 0)]
        (if (not= f d)
          (write-f64! w d)
          (let [h (to-half f)]
            (if (and (some? h) (= (from-half h) f))
              (write-f16! w h)
              (write-f32! w f))))))))

(defn write-bool! [^Writer w b] (u8! w (bit-or SIMPLE (if b 21 20))))
(defn write-nil! [^Writer w] (u8! w (bit-or SIMPLE 22)))

(defn write-bytes! [^Writer w ^js/Uint8Array bs]
  (head! w BYTES (.-length bs))
  (ensure! w (.-length bs))
  (.set (.-buf w) bs (.-pos w))
  (set! (.-pos w) (+ (.-pos w) (.-length bs))))

;; ---------------------------------------------------------------- strings

(defn- min-len-for-index [idx]
  (cond (< idx 24) 3 (< idx 256) 4 (< idx 65536) 5 :else 7))

(defn- check-well-formed-utf16!
  "Reject UTF-16 that has no UTF-8 encoding.

  `TextEncoder` REPLACES an unpaired surrogate with U+FFFD rather than
  failing, so a string holding a lone U+D800 encoded as U+FFFD and decoded as
  a different string, silently. The JVM's `String.getBytes(UTF_8)` substitutes
  '?' in the same situation, so the two platforms silently disagreed about the
  same input as well.

  A lone surrogate is half a character. There is no correct UTF-8 for it, so
  the only options are to corrupt or to refuse.

  Only reached from the non-ASCII path -- a surrogate is never below 0x80."
  [^string s]
  (let [n (.-length s)]
    (loop [i 0]
      (when (< i n)
        (let [c (.charCodeAt s i)]
          (if (or (< c 0xD800) (> c 0xDFFF))
            (recur (inc i))
            (if (and (<= c 0xDBFF)
                     (< (inc i) n)
                     (let [d (.charCodeAt s (inc i))] (and (>= d 0xDC00) (<= d 0xDFFF))))
              (recur (+ i 2))                      ; a well-formed pair
              (throw (ex-info
                      (str "boring: string contains an unpaired UTF-16 surrogate (0x"
                           (.toString c 16) ") at index " i
                           " and has no UTF-8 encoding")
                      {:type :boring/invalid-utf16 :index i})))))))))

(defn- write-string-literal! [^Writer w ^string s]
  (let [n (.-length s)]
    (if (<= n ASCII-LOOP-MAX)
      ;; Speculate ASCII and write straight into the buffer. Bails to
      ;; TextEncoder on the first non-ASCII char.
      (let [start (.-pos w)]
        (ensure! w (+ n 5))
        (loop [i 0 p (+ start (cond (< n 24) 1 (< n 0x100) 2 :else 3))]
          (if (< i n)
            (let [c (.charCodeAt s i)]
              (if (>= c 0x80)
                (let [_ (check-well-formed-utf16! s)
                      enc (.encode text-encoder s)]
                  (set! (.-pos w) start)
                  (head! w TEXT (.-length enc))
                  (ensure! w (.-length enc))
                  (.set (.-buf w) enc (.-pos w))
                  (set! (.-pos w) (+ (.-pos w) (.-length enc)))
                  (.-length enc))
                (do (aset (.-buf w) p c) (recur (inc i) (inc p)))))
            (do (set! (.-pos w) start)
                (head! w TEXT n)
                (set! (.-pos w) p)
                n))))
      (let [_ (check-well-formed-utf16! s)
            enc (.encode text-encoder s)]
        (head! w TEXT (.-length enc))
        (ensure! w (.-length enc))
        (.set (.-buf w) enc (.-pos w))
        (set! (.-pos w) (+ (.-pos w) (.-length enc)))
        (.-length enc)))))

(defn write-string! [^Writer w ^string s]
  (if-not (.-stringref w)
    (write-string-literal! w s)
    (let [hit (.get (.-srKeys w) s)]
      (if (some? hit)
        (do (head! w TAG TAG-STRINGREF) (head! w UINT hit))
        (let [blen (write-string-literal! w s)]
          (when (>= blen (min-len-for-index (.-srNext w)))
            (.set (.-srKeys w) s (.-srNext w))
            (set! (.-srNext w) (inc (.-srNext w)))))))))

(defn- check-tag!
  "A caller-supplied tag number, validated before it reaches `head!`.

  `head!`'s BigInt branch range-checks, but its Number branch assumed an
  unsigned integer and did arithmetic on whatever arrived. So `tag 1.5` emitted
  `c1 00` -- silently becoming tag 1 -- and `tag -1` emitted `ff 00`, the BREAK
  code followed by an item, which is not one well-formed CBOR value at all. No
  exception either way: just output no reader can parse, or the wrong tag. The
  JVM has rejected both since `writeTag` gained its check; this is the port.

  THE BIGINT BRANCH IS CHECKED HERE, not delegated. `head!` range-checks its
  BigInt argument, but it narrows every BigInt at or below
  `Number.MAX_SAFE_INTEGER` to a Number FIRST -- and a negative BigInt is below
  that bound, so it landed back on the unchecked Number path this function was
  written to replace. `(tagged-value (js/BigInt -1) 0)` emitted `d90100 ff 00`:
  a stringref namespace, then a bare BREAK code, which boring's own decoder
  then rejects as `:boring/unexpected-break`. `(js/BigInt -100)` silently
  became tag 28. Delegating a range check to a function that changes the type
  before performing it is not a range check."
  [t]
  (cond
    (= "bigint" (goog/typeOf t))
    (if (or (< t (js/BigInt 0)) (> t (js/BigInt "18446744073709551615")))
      (throw (ex-info (str "boring: tag must be an unsigned 64-bit integer, got " t)
                      {:type :boring/bad-tag :tag (str t)}))
      t)
    (not (number? t))
    (throw (ex-info (str "boring: tag must be an integer, got " (pr-str t))
                    {:type :boring/bad-tag :tag t}))
    (or (not (js/Number.isInteger t)) (neg? t) (> t js/Number.MAX_SAFE_INTEGER))
    (throw (ex-info (str "boring: tag must be an unsigned integer, got " t)
                    {:type :boring/bad-tag :tag t}))
    :else t))

(defn write-stringref-namespace! [^Writer w] (head! w TAG TAG-SR-NS))

(defn- write-ident! [^Writer w s]
  (head! w TAG TAG-IDENTIFIER)
  (write-string! w s))

;; ---------------------------------------------------------------- dispatch

(declare write-value! write-value!*)

(defn- compare-bytes
  "RFC 8949 §4.2.1: bytewise lexicographic, shorter-is-a-prefix first."
  [^js/Uint8Array a ^js/Uint8Array b]
  (let [n (min (.-length a) (.-length b))]
    (loop [i 0]
      (if (< i n)
        (let [d (- (aget a i) (aget b i))]
          (if (zero? d) (recur (inc i)) d))
        (- (.-length a) (.-length b))))))

(defn- compare-bytes-length-first
  "RFC 7049 / clj-cbor: shorter keys sort first, then bytewise."
  [^js/Uint8Array a ^js/Uint8Array b]
  (if (not= (.-length a) (.-length b))
    (- (.-length a) (.-length b))
    (loop [i 0]
      (if (< i (.-length a))
        (let [d (- (aget a i) (aget b i))]
          (if (zero? d) (recur (inc i)) d))
        0))))

(defn- canonical-sub-writer
  "A scratch writer configured to encode a canonical sub-item.

  EVERY behaviour-affecting option is inherited. Three were not, and each
  omission turned an option into a lie under `:profile :canonical`:
  `:incl-metadata? false` still emitted the metadata wrapper on a map key,
  `:permit-reserved-simple-values` was ignored, and `:max-depth` was not
  enforced inside a key at all -- so a nesting cap set as a SECURITY bound was
  bypassed by putting the deep value in key position.

  The parent's current depth carries over, so a deep value does not get a
  fresh budget merely for being a map key."
  [^Writer w]
  (let [scratch (writer 128)]
    (set! (.-stringref scratch) false)
    (set! (.-canonical scratch) true)
    (set! (.-preserveWidth scratch) (.-preserveWidth w))
    (set! (.-legacyCanonicalOrder scratch) (.-legacyCanonicalOrder w))
    (set! (.-registry scratch) (.-registry w))
    (set! (.-inclMetadata scratch) (.-inclMetadata w))
    (set! (.-permitReservedSimpleValues scratch) (.-permitReservedSimpleValues w))
    (set! (.-maxDepth scratch) (.-maxDepth w))
    ;; ACCUMULATED, and into `depthOffset` rather than `depth`: `reset!` runs
    ;; before every staged key and zeroes `depth`, so assigning it there was a
    ;; no-op and the budget renewed itself at each hop through key position.
    (set! (.-depthOffset scratch) (+ (.-depth w) (.-depthOffset w)))
    ;; The fallback AND its re-entry guard. Copying the callback alone lets a
    ;; fallback whose result still contains the unsupported value recurse
    ;; through a fresh scratch forever.
    (set! (.-encodeFallback scratch) (.-encodeFallback w))
    (set! (.-inFallback scratch) (.-inFallback w))
    scratch))

(defn- write-set-canonical!
  "Canonical sets: elements sorted by their canonical encoded bytes.

  RFC 8949 §4.2 defines deterministic ordering for MAP KEYS and says nothing
  about tag 258, which is not part of core CBOR. That left set elements in
  iteration order, so two sets that are `=` produced DIFFERENT canonical bytes
  and a persistent set could iterate differently across platforms -- against a
  profile whose whole promise is that equal values encode identically, which is
  what a signature over the bytes rests on.

  Same rule as map keys, so there is one canonical ordering rather than two."
  [^Writer w s]
  (let [scratch (canonical-sub-writer w)
        encoded (vec (for [v s] (do (reset! scratch)
                                    (write-value! scratch v)
                                    [(to-bytes scratch) v])))
        cmp (if (.-legacyCanonicalOrder w) compare-bytes-length-first compare-bytes)
        sorted (sort-by first cmp encoded)]
    ;; Two DISTINCT host values can encode identically under canonical
    ;; reduction, which would emit a CBOR set with a repeated element: a strict
    ;; decoder rejects it, a lenient one collapses it, and the two sides
    ;; disagree about the set's size.
    (doseq [[[a _] [b _]] (partition 2 1 sorted)]
      (when (zero? (compare-bytes a b))
        (throw (ex-info "boring: two set elements encode identically under :canonical"
                        {:type :boring/canonical-duplicate}))))
    (head! w TAG TAG-SET)
    (head! w ARRAY (count s))
    ;; THE STAGED BYTES, not a second encoding. This called `write-value!` on
    ;; the original element again, so a registered handler ran TWICE per element
    ;; -- and a handler that reads a clock, a counter or anything else mutable
    ;; could emit bytes different from the ones the sort used, making the
    ;; "canonical" output neither canonical nor sorted. The JVM was fixed first;
    ;; the canonical MAP path a few lines below has always done it this way.
    (doseq [[eb _] sorted]
      (ensure! w (.-length eb))
      (.set (.-buf w) eb (.-pos w))
      (set! (.-pos w) (+ (.-pos w) (.-length eb))))))

(defn- write-map-entries! [^Writer w m]
  (if-not (.-canonical w)
    (do (head! w MAP (count m))
        (doseq [[k v] m] (write-value! w k) (write-value! w v)))
    ;; Canonical: sort by the ENCODED key bytes. Each key is encoded into a
    ;; scratch writer to compare it, so this is not the hot path.
    (let [scratch (canonical-sub-writer w)
          entries (vec (for [[k v] m]
                         (do (reset! scratch)
                             (write-value! scratch k)
                             [(to-bytes scratch) v])))
          cmp (if (.-legacyCanonicalOrder w) compare-bytes-length-first compare-bytes)
          sorted (sort-by first cmp entries)]
      ;; Two DISTINCT host keys can encode identically under canonical
      ;; reduction -- `1` and `(js/BigInt 1)` are both the single byte `01` --
      ;; and a map with two identical CBOR keys is output this library's OWN
      ;; reader rejects as :boring/duplicate-map-key. Canonical SETS have always
      ;; checked this; maps did not, on either platform. The JVM was fixed
      ;; first; a successful encode must never produce bytes the paired decoder
      ;; refuses.
      (doseq [[[a _] [b _]] (partition 2 1 sorted)]
        (when (zero? (compare-bytes a b))
          (throw (ex-info "boring: two map keys encode identically under :canonical"
                          {:type :boring/canonical-duplicate}))))
      (head! w MAP (count m))
      (doseq [[kb v] sorted]
        (ensure! w (.-length kb))
        (.set (.-buf w) kb (.-pos w))
        (set! (.-pos w) (+ (.-pos w) (.-length kb)))
        (write-value! w v)))))

(def ^:private sentinel (js-obj))

(defn- homogeneous-shape
  "The shared key vector of `v`, or nil if its elements are not all plain maps
  with the same keys. Membership is checked with `contains?` rather than by
  comparing key order, since equal key sets need not iterate alike."
  [v]
  (let [rows (count v)]
    (when (>= rows 2)
      (let [m0 (nth v 0)]
        (when (and (map? m0) (not (record? m0)) (pos? (count m0)))
          (let [ks (vec (keys m0))
                n (count ks)]
            (loop [i 1]
              (cond
                (= i rows) ks
                :else (let [m (nth v i)]
                        (if (and (map? m) (not (record? m)) (== n (count m))
                                 ;; get-with-sentinel: one lookup, where
                                 ;; contains? does a lookup and a test
                                 (loop [j 0]
                                   (cond (== j n) true
                                         (identical? sentinel (get m (nth ks j) sentinel)) false
                                         :else (recur (inc j)))))
                          (recur (inc i))
                          nil))))))))))

(defn- write-shaped-array! [^Writer w v ks]
  (head! w TAG TAG-SHAPED-ARRAY)
  (head! w ARRAY 2)
  (head! w ARRAY (count ks))
  (doseq [k ks] (write-value! w k))
  (head! w ARRAY (count v))
  (doseq [m v]
    (head! w ARRAY (count ks))
    (doseq [k ks] (write-value! w (get m k)))))

(defn- write-bigint! [^Writer w ^js/BigInt v]
  (let [neg (neg? v)
        m (if neg (- (- v) (js/BigInt 1)) v)]
    (if (and (not (.-preserveWidth w)) (<= m (js/BigInt "18446744073709551615")))
      (head-bigint! w (if neg NINT UINT) m)
      (do (head! w TAG (if neg TAG-NEG-BIGNUM TAG-POS-BIGNUM))
          ;; magnitude as big-endian bytes
          (let [hex (.toString m 16)
                hex (if (odd? (.-length hex)) (str "0" hex) hex)
                n (/ (.-length hex) 2)
                out (js/Uint8Array. n)]
            (dotimes [i n]
              (aset out i (js/parseInt (.substring hex (* 2 i) (+ 2 (* 2 i))) 16)))
            (write-bytes! w out))))))

(declare write-value!*)

(defn- enter! [^Writer w]
  ;; Checked BEFORE incrementing, and against the parent's spend as well as our
  ;; own -- see `depthOffset`.
  (when (> (+ (inc (.-depth w)) (.-depthOffset w)) (.-maxDepth w))
    (throw (ex-info (str "boring: value nested deeper than maxDepth ("
                         (.-maxDepth w) ")")
                    {:type :boring/max-depth-exceeded
                     :max-depth (.-maxDepth w)})))
  (set! (.-depth w) (inc (.-depth w))))

(defn- exit! [^Writer w] (set! (.-depth w) (dec (.-depth w))))

(defn write-value! [^Writer w x]
  ;; Consume the flag on ENTRY, so the wrapper is not re-applied to the value it
  ;; just wrapped while CHILDREN stay eligible for their own metadata. Leaving
  ;; it set would suppress meta on every nested value.
  (let [wrapped? (.-metaWrapped w)]
    (set! (.-metaWrapped w) false)
    (if-let [m (and (.-inclMetadata w) (not wrapped?)
                    (satisfies? IWithMeta x)
                    (not-empty (meta x)))]
      (do (head! w TAG TAG-GENERIC-OBJ) (head! w ARRAY 2)
          (write-string! w NAME-WITH-META)
          (head! w ARRAY 2)
          (write-value! w m)
          (set! (.-metaWrapped w) true)
          (write-value! w x))
      ;; Depth counted around every value, matching the JVM writer: the
      ;; recursion is per value, so that is where the cap belongs.
      (do (enter! w)
          (try (write-value!* w x)
               (finally (exit! w)))))))

(defn- write-value!* [^Writer w x]
  (cond
    (nil? x) (write-nil! w)

    (number? x)
    ;; JS has a single number type, so "is this an integer?" is a value
    ;; question, not a type question. 1.0 and 1 are indistinguishable here —
    ;; a platform limit, documented rather than worked around.
    (if (and (js/Number.isInteger x)
             (<= (js/Math.abs x) MAX-SAFE)
             ;; -0.0 is integral by Number.isInteger but is NOT integer 0;
             ;; encoding it as 0 loses the sign, the same defect found in
             ;; clj-cbor (datahike #633). 1/-0 is -Infinity.
             (not (and (zero? x) (neg? (/ 1 x)))))
      (write-int! w x)
      (if (.-preserveWidth w) (write-f64! w x) (write-shortest-float! w x)))

    (string? x) (write-string! w x)
    (keyword? x) (write-ident! w (ident-string w x))
    (boolean? x) (write-bool! w x)
    (symbol? x) (write-ident! w (ident-string w x))

    (instance? js/Uint8Array x) (write-bytes! w x)
    (= "bigint" (goog/typeOf x)) (write-bigint! w x)

    ;; A REGISTERED handler beats every built-in below.
    ;;
    ;; This lookup was moved up once already, out of the :else branch and above
    ;; map?/set?/vector? -- but it was left BELOW the concrete types, so a
    ;; handler for js/Date, a regex, a UUID, a Decimal, a Rational or a typed
    ;; array was still silently ignored. doc/EXTENDING.md promises the opposite
    ;; in as many words, and the JVM writer has had the registry above the
    ;; concrete types since the same defect was fixed there.
    ;;
    ;; Registration is an instruction, not a suggestion: a handler exists
    ;; precisely to override what boring would do on its own.
    (and (some? (.-registry w))
         (get-in (.-registry w) [:writers (type x)]))
    (let [h (get-in (.-registry w) [:writers (type x)])]
      (head! w TAG (check-tag! (:tag h)))
      (write-value! w ((:fn h) x)))

    (instance? js/Date x)
    ;; toISOString always prints milliseconds; ISO_INSTANT on the JVM omits
    ;; them when zero. Normalise so both platforms emit the same bytes.
    (let [s (.replace (.toISOString x) ".000Z" "Z")]
      ;; RFC 3339's date-fullyear is 4DIGIT. JS renders anything outside
      ;; 0000-9999 in the expanded form (`+275760-09-13T00:00:00Z`), which no
      ;; conforming parser accepts -- and boring read it back happily, so the
      ;; round-trip tests saw nothing. The sign prefix is the marker; the
      ;; 4-digit form never carries one. Matches Writer.rfc3339 on the JVM.
      (when (or (= "+" (.charAt s 0)) (= "-" (.charAt s 0)))
        (throw (ex-info
                (str "boring: " s " has a year outside 0000-9999, which RFC 3339 cannot "
                     "express, so there is no valid tag 0 text for it; encode the epoch "
                     "value yourself if you need this range")
                {:type :boring/unrepresentable-date :value s :tag 0})))
      (head! w TAG TAG-DATETIME)
      (write-string! w s))

    ;; Before uuid?: a RegExp is not a uuid, but keeping the registered-tag
    ;; branches together makes the ordering constraint visible.
    (regexp? x)
    (do (head! w TAG TAG-REGEX)
        ;; .-source is the pattern without delimiters or flags, matching what
        ;; tag 35 carries and what the JVM writes from Pattern.toString().
        (write-string! w (.-source x)))

    (uuid? x)
    (do (head! w TAG TAG-UUID)
        (let [hex (.replace (str x) (js/RegExp. "-" "g") "")
              out (js/Uint8Array. 16)]
          (dotimes [i 16]
            (aset out i (js/parseInt (.substring hex (* 2 i) (+ 2 (* 2 i))) 16)))
          (write-bytes! w out)))

    ;; These three MUST precede `record?` and `map?`: they are defrecords, so
    ;; they satisfy both, and would otherwise encode as ordinary maps of their
    ;; own fields. Exactly the trap the JVM writer documents — and the port
    ;; walked straight into it by ordering the cond differently.
    ;; Decimal / Rational re-encode to the same tags the JVM writes, so a value
    ;; created on either platform reads back correctly on the other.
    (data/decimal? x)
    (do (head! w TAG TAG-DECIMAL)
        (head! w ARRAY 2)
        (write-value! w (:exponent x))
        (write-value! w (:mantissa x)))

    (data/rational? x)
    (do (head! w TAG TAG-RATIONAL)
        (head! w ARRAY 2)
        (write-value! w (:numerator x))
        (write-value! w (:denominator x)))

    (and (some? (.-BYTES_PER_ELEMENT x))
         (contains? typed-array-tags (.-name (.-constructor x))))
    (let [tag (get typed-array-tags (.-name (.-constructor x)))
          bytes (js/Uint8Array. (.-buffer x) (.-byteOffset x) (.-byteLength x))]
      (head! w TAG tag)
      (write-bytes! w bytes))

    (data/simple-value? x)
    (let [n (:n x)]
      (cond
        ;; Range-checked FIRST. `(< n 24)` is true of a negative, so
        ;; (simple-value -1) emitted 0xFF -- the break code -- producing a
        ;; document this library then rejects. Above 255 it wrapped silently.
        (or (not (int? n)) (neg? n) (> n 255))
        (throw (ex-info (str "boring: simple value must be 0-255, got " (pr-str n))
                        {:type :boring/bad-simple-value :value n}))
        (< n 24) (u8! w (bit-or SIMPLE n))
        ;; The escape hatch produces bytes boring itself rejects -- RFC 8949 3.3
        ;; makes `f8 00`..`f8 1f` not well-formed, so the reader now refuses
        ;; them. See the longer note in Writer.java for why it is kept.
        (and (< n 32) (not (.-permitReservedSimpleValues w)))
        (throw (ex-info
                (str "boring: RFC 8949 3.3 forbids encoding simple value " n
                     ", and makes such sequences not well-formed, so boring will"
                     " not read the result back; set :permit-reserved-simple-values"
                     " to emit it anyway")
                {:type :boring/reserved-simple-value :value n}))
        :else (do (u8! w (bit-or SIMPLE 24)) (u8! w n))))

    (data/tagged-value? x)
    (do (head! w TAG (check-tag! (:tag x))) (write-value! w (:value x)))

    ;; The payload is USUALLY a field map, but the reader accepts any tag-27
    ;; constructor argument, so a positional type (datahike's Datom carries a
    ;; five-element vector) round-trips through here too. Assuming a map made
    ;; re-encoding such a value emit a malformed frame, which breaks the
    ;; lossless-passthrough guarantee UnknownRecord exists for.
    (data/unknown-record? x)
    (do (head! w TAG TAG-GENERIC-OBJ)
        (head! w ARRAY 2)
        (write-string! w (data/record-type x))
        (let [fields (data/record-fields x)]
          (if (map? fields)
            (write-map-entries! w fields)
            (write-value! w fields))))

    ;; User handlers beat STRUCTURAL inference AND the record branch —
    ;; see the JVM Writer for why this must sit above (record? x).
    ;; What an unregistered tag-27 frame with a NON-map payload decodes to;
    ;; it must re-encode to the same frame or passthrough holds only for
    ;; record-shaped frames.
    ;; ExceptionInfo is the only Throwable ClojureScript has. Carried as data
    ;; -- message, data, cause -- so it crosses to a JVM peer as a real
    ;; ex-info rather than a string.
    (instance? cljs.core/ExceptionInfo x)
    (do (head! w TAG TAG-GENERIC-OBJ) (head! w ARRAY 2)
        (write-string! w "clojure/ex-info")
        (head! w ARRAY 3)
        (write-string! w (or (ex-message x) ""))
        (write-value! w (or (ex-data x) {}))
        (write-value! w (ex-cause x)))

    (tagged-literal? x)
    (do (head! w TAG TAG-GENERIC-OBJ)
        (head! w ARRAY 2)
        (write-string! w (str (:tag x)))
        (write-value! w (:form x)))

    (record? x)
    (do (head! w TAG TAG-GENERIC-OBJ)
        (head! w ARRAY 2)
        (write-string! w (data/record-type-name x))
        (write-map-entries! w (into {} x)))

    ;; Before map?/set?/sequential?, which would flatten these.
    ;; A custom comparator is refused rather than dropped: it is code, and
    ;; rebuilding with `compare` would sort differently from what was stored.
    (instance? PersistentTreeMap x)
    (do (when-not (identical? compare (.-comp x))
          (throw (ex-info "boring: cannot encode a sorted-map with a custom comparator -- the comparator is code, and rebuilding with `compare` would sort differently"
                          {:type :boring/unsupported-type :value x})))
        (head! w TAG TAG-GENERIC-OBJ) (head! w ARRAY 2)
        (write-string! w NAME-SORTED-MAP)
        (write-map-entries! w x))

    (instance? PersistentTreeSet x)
    (do (when-not (identical? compare (.-comp (.-tree-map x)))
          (throw (ex-info "boring: cannot encode a sorted-set with a custom comparator -- the comparator is code, and rebuilding with `compare` would sort differently"
                          {:type :boring/unsupported-type :value x})))
        (head! w TAG TAG-GENERIC-OBJ) (head! w ARRAY 2)
        (write-string! w NAME-SORTED-SET)
        (head! w ARRAY (count x))
        (doseq [e x] (write-value! w e)))

    (instance? PersistentQueue x)
    (do (head! w TAG TAG-GENERIC-OBJ) (head! w ARRAY 2)
        (write-string! w NAME-QUEUE)
        (head! w ARRAY (count x))
        (doseq [e x] (write-value! w e)))

    (map? x) (write-map-entries! w x)

    (set? x)
    (if (.-canonical w)
      (write-set-canonical! w x)
      (do (head! w TAG TAG-SET)
          (head! w ARRAY (count x))
          (doseq [v x] (write-value! w v))))

    (or (vector? x) (seq? x) (list? x))
    (let [v (if (and (counted? x) (vector? x)) x (vec x))
          ks (when (and (.-shapes w) (not (.-canonical w))) (homogeneous-shape v))]
      (if ks
        (write-shaped-array! w v ks)
        (do (head! w ARRAY (count v))
            (doseq [e v] (write-value! w e)))))

    :else
    ;; A fallback turns "one bad field kills the document" into "one bad field
    ;; is replaced". Guarded against recursion: a fallback returning something
    ;; also unencodable would otherwise loop forever.
    (if (and (.-encodeFallback w) (not (.-inFallback w)))
      (do (set! (.-inFallback w) true)
          (try (write-value! w ((.-encodeFallback w) x))
               (finally (set! (.-inFallback w) false))))
      (throw (ex-info (str "boring: no encoding for " (pr-str (type x)))
                      {:type :boring/unsupported-type :value x})))))
