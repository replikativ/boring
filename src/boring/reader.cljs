(ns boring.reader
  "CBOR reader for ClojureScript. Mirrors the JVM decoder's structure and, more
  importantly, its hardening: validated counts, a nesting cap, duplicate-key
  rejection, UTF-8 validation and typed errors. Those were all found the hard
  way on the JVM side (fuzzing, reading other implementations) and there is no
  reason for the port to relearn them.

  Measured on node: `TextDecoder` beats a `fromCharCode` loop 6.7x on
  decode, the opposite polarity from encode."
  ;; boring.reader/reset! and boring.writer/reset! are namespaced entry
  ;; points, not shadows of the core fn -- but without this exclusion every
  ;; downstream ClojureScript build reports a :redef warning for them, and
  ;; warning noise is how real warnings get missed.
  (:refer-clojure :exclude [reset!])
  (:require [boring.data :as data]))

;; :ignoreBOM true is NOT optional, despite the name.
;;
;; TextDecoder defaults to ignoreBOM:false, which does not mean "reject a BOM"
;; -- it means "silently CONSUME a leading U+FEFF". So a CBOR text string whose
;; first character is U+FEFF decoded one character short: "﻿" came back as
;; "", and "﻿A" as "A". Encoding was correct (63 efbbbf) and the JVM
;; decoded it correctly, so this was a silent ClojureScript-only data loss and
;; a cross-platform differential -- found by a generative case, not by hand.
;;
;; U+FEFF is a legitimate character in a CBOR text string; the BOM convention
;; belongs to file formats, not to a length-prefixed string. :fatal true keeps
;; invalid UTF-8 an error rather than U+FFFD.
(def text-decoder
  (js/TextDecoder. "utf-8" #js {:fatal true :ignoreBOM true}))

(defn- not== [a b] (not (== a b)))

(defn- err
  ([type msg] (throw (ex-info msg {:type type})))
  ([type msg data] (throw (ex-info msg (assoc data :type type)))))

(deftype Reader [^:mutable buf
                 ^:mutable dv
                 ^:mutable pos
                 ^:mutable srStrings
                 ^:mutable srIdents
                 ;; Whether a tag-256 namespace is open. See the JVM Reader for
                 ;; the defect this fixes: without it, sibling namespaces shared
                 ;; one table and the second one's index 0 resolved to the
                 ;; first one's entry -- a wrong VALUE, not an error.
                 ^:mutable srActive
                 ^:mutable identCache
                 ^:mutable depth
                 ^:mutable maxDepth
                 ^:mutable tolerateUnknownTags
                 ^:mutable validateUtf8
                 ^:mutable checkDuplicateKeys
                 ^:mutable registry])

(defn reader
  [^js/Uint8Array bs]
  (Reader. bs (js/DataView. (.-buffer bs) (.-byteOffset bs) (.-byteLength bs))
           0 #js [] #js [] false (js/Map.) 0 1024 true true true nil))

(defn reset! [^Reader r ^js/Uint8Array bs]
  (set! (.-buf r) bs)
  (set! (.-dv r) (js/DataView. (.-buffer bs) (.-byteOffset bs) (.-byteLength bs)))
  (set! (.-pos r) 0)
  (set! (.-depth r) 0)
  (set! (.-srStrings r) #js [])
  (set! (.-srIdents r) #js [])
  (set! (.-srActive r) false)
  ;; identCache deliberately survives: interning is a pure function of the
  ;; bytes, so the cache stays valid across messages.
  r)

(defn at-end? [^Reader r] (>= (.-pos r) (.-length (.-buf r))))
(defn position [^Reader r] (.-pos r))

(defn- remaining [^Reader r] (- (.-length (.-buf r)) (.-pos r)))

(defn- enter! [^Reader r]
  (set! (.-depth r) (inc (.-depth r)))
  (when (> (.-depth r) (.-maxDepth r))
    (err :boring/max-depth-exceeded
         (str "boring: nesting deeper than maxDepth (" (.-maxDepth r) ")")
         {:max-depth (.-maxDepth r)})))

(defn- exit! [^Reader r] (set! (.-depth r) (dec (.-depth r))))

(defn- need! [^Reader r n]
  (when (> n (remaining r))
    (err :boring/truncated-input
         "boring: input ended mid-value (truncated or malformed)")))

(defn- check-count [^Reader r n0 min-each]
  ;; `arg!` yields a BigInt past 2^53, and mixing BigInt with Number throws a
  ;; TypeError — which fuzzing surfaced as an untyped failure. Anything that
  ;; large is already impossible as a count, so reject it before arithmetic.
  ;;
  ;; Tested with `number?` (typeof === "number") rather than
  ;; (= "bigint" (goog/typeOf n0)), which ran a cljs string comparison on every
  ;; container header.
  (when-not (number? n0)
    (err :boring/bad-count (str "boring: count out of range: " n0) {:count (str n0)}))
  (let [n n0]
    (when (or (neg? n) (> (* n min-each) (remaining r)))
      (err :boring/bad-count
           (str "boring: declared count " n " needs at least " (* n min-each)
                " bytes but only " (remaining r) " remain")
           {:count n :remaining (remaining r)}))
    n))

(defn- u8! [^Reader r]
  (need! r 1)
  (let [v (aget (.-buf r) (.-pos r))]
    (set! (.-pos r) (inc (.-pos r)))
    v))

(defn- arg! [^Reader r info]
  ;; `==` throughout, not `=`: on numbers cljs `=` dispatches through
  ;; cljs.core/-equiv, which the CPU profile showed at 14.7% combined with
  ;; `=`. `==` compiles to JS `===`.
  (cond
    (< info 24) info
    (== info 24) (u8! r)
    (== info 25) (do (need! r 2)
                     (let [v (.getUint16 (.-dv r) (.-pos r))]
                       (set! (.-pos r) (+ (.-pos r) 2)) v))
    (== info 26) (do (need! r 4)
                     (let [v (.getUint32 (.-dv r) (.-pos r))]
                       (set! (.-pos r) (+ (.-pos r) 4)) v))
    (== info 27) (do (need! r 8)
                     (let [v (.getBigUint64 (.-dv r) (.-pos r))]
                       (set! (.-pos r) (+ (.-pos r) 8))
                      ;; Stay a Number while that is lossless; only values past
                      ;; 2^53 keep BigInt, so ordinary integers never become
                      ;; BigInt (datahike's dump requirements ask for no silent
                      ;; precision loss, not for BigInt everywhere).
                       (if (<= v (js/BigInt js/Number.MAX_SAFE_INTEGER))
                         (js/Number v)
                         v)))
    :else (err :boring/reserved-info
               (str "boring: reserved additional-info " info) {:info info})))

;; ---------------------------------------------------------------- utf-8

(defn- validate-utf8! [^Reader r start n]
  (let [buf (.-buf r) end (+ start n)]
    (loop [i start]
      (when (< i end)
        (let [b (aget buf i)]
          (if (< b 0x80)
            (recur (inc i))
            (let [[len cp-init lo]
                  (cond (= 0xC0 (bit-and b 0xE0)) [2 (bit-and b 0x1F) 0x80]
                        (= 0xE0 (bit-and b 0xF0)) [3 (bit-and b 0x0F) 0x800]
                        (= 0xF0 (bit-and b 0xF8)) [4 (bit-and b 0x07) 0x10000]
                        :else (err :boring/invalid-utf8
                                   (str "boring: invalid UTF-8 lead byte 0x" (.toString b 16))
                                   {:offset (- i start)}))]
              (when (> (+ i len) end)
                (err :boring/invalid-utf8 "boring: truncated UTF-8 sequence"
                     {:offset (- i start)}))
              (let [cp (loop [j 1 cp cp-init]
                         (if (< j len)
                           (let [c (aget buf (+ i j))]
                             (when (not= 0x80 (bit-and c 0xC0))
                               (err :boring/invalid-utf8
                                    "boring: invalid UTF-8 continuation byte"
                                    {:offset (- (+ i j) start)}))
                             (recur (inc j) (bit-or (bit-shift-left cp 6) (bit-and c 0x3F))))
                           cp))]
                (when (< cp lo)
                  (err :boring/invalid-utf8 "boring: overlong UTF-8 encoding"
                       {:offset (- i start)}))
                (when (and (>= cp 0xD800) (<= cp 0xDFFF))
                  (err :boring/invalid-utf8 "boring: UTF-8 encodes a surrogate"
                       {:offset (- i start)}))
                (when (> cp 0x10FFFF)
                  (err :boring/invalid-utf8 "boring: UTF-8 codepoint out of range"
                       {:offset (- i start)}))
                (recur (+ i len))))))))))

(defn- stringref-index
  "Validate a stringref index against what has been registered. Unchecked, this
  returned raw JS `undefined` as a decoded value — including as a map key — and
  the tag-39 path dereferenced it for a TypeError that escaped the typed-error
  contract. The wire argument can also be a BigInt or exceed the table."
  [^Reader r idx]
  (when-not (.-srActive r)
    (err :boring/bad-stringref
         "boring: stringref outside any tag-256 namespace" {:index (str idx)}))
  (when (or (not (number? idx)) (neg? idx) (>= idx (.-length (.-srStrings r))))
    (err :boring/bad-stringref
         (str "boring: stringref " idx " but only " (.-length (.-srStrings r))
              " strings have been seen")
         {:index (str idx) :count (.-length (.-srStrings r))}))
  idx)

(defn- min-len-for-index [idx]
  (cond (< idx 24) 3 (< idx 256) 4 (< idx 65536) 5 :else 7))

(defn- read-text! [^Reader r info]
  (let [n (check-count r (arg! r info) 1)
        start (.-pos r)]
    (need! r n)
    (when (.-validateUtf8 r) (validate-utf8! r start n))
    (let [s (.decode text-decoder (.subarray (.-buf r) start (+ start n)))]
      (set! (.-pos r) (+ start n))
      ;; Inside a namespace only: a string outside one is not a table entry,
      ;; and registering it shifted every later index by one.
      (when (and (.-srActive r) (>= n (min-len-for-index (.-length (.-srStrings r)))))
        (.push (.-srStrings r) s)
        (.push (.-srIdents r) nil))
      s)))

(defn- decode-range
  "Always TextDecoder.

  A manual ASCII loop looked attractive — raw JS `out += String.fromCharCode(b)`
  measured 17.9 ns for 2 bytes against TextDecoder's ~82 ns fixed cost. But in
  ClojureScript the loop becomes `(str out c)`, i.e. `cljs.core.str` per byte
  rather than V8's rope-optimised `+=`, and decode got ~60% SLOWER
  (datom-maps-200 543 -> 894 us). The micro-benchmark measured JavaScript that
  the compiler does not actually emit."
  [^Reader r start n]
  (.decode text-decoder (.subarray (.-buf r) start (+ start n))))

(defn- intern-ident [s]
  ;; charCodeAt + == rather than (= ":" (.charAt s 0)): the latter goes through
  ;; cljs.core/-equiv, which the profile showed at 6.8% combined with =.
  (if (and (pos? (.-length s)) (== 58 (.charCodeAt s 0)))   ; 58 = \:
    (keyword (.substring s 1))
    (symbol s)))

(def ^:const IDENT-CACHE-MAX 4096)

(defn- ident-from-bytes
  "Intern the identifier spelled by buf[start, start+n), via a cache keyed on
  the DECODED STRING.

  Worth recording: on the JVM the equivalent cache is keyed on the raw bytes,
  which was worth 36-42% because it skips String construction entirely. Porting
  that idea here made CLJS decode ~30% SLOWER (datom-maps-200 543 -> 700 us).
  JS engines hash and compare string Map keys in native code, so a hand-rolled
  byte hash plus byte comparison in JS loses to `Map.get(str)` — the same
  optimisation has opposite sign on the two platforms. Measure per platform;
  do not port performance folklore."
  [^Reader r start n]
  (let [s (decode-range r start n)
        hit (.get (.-identCache r) s)]
    (if (some? hit)
      hit
      (let [v (intern-ident s)]
        ;; Bounded, unlike the previous unbounded js/Map: a long-lived reader
        ;; fed distinct identifiers grew without limit (20k decoded keywords
        ;; measured as 20k live entries). Clearing wholesale is crude but keeps
        ;; the common repeat-heavy case fast without a real LRU.
        (when (>= (.-size (.-identCache r)) IDENT-CACHE-MAX)
          (.clear (.-identCache r)))
        (.set (.-identCache r) s v)
        v))))

;; ---------------------------------------------------------------- read

(declare read! read-array-n! read-map-n!)

(def ^:private BREAK (js/Object.))

(defn- at-break? [^Reader r]
  (and (< (.-pos r) (.-length (.-buf r)))
       (= 0xFF (aget (.-buf r) (.-pos r)))))

(defn- read-or-break! [^Reader r]
  (if (at-break? r)
    (do (set! (.-pos r) (inc (.-pos r))) BREAK)
    (read! r)))

(defn- read-chunk! [^Reader r major]
  ;; BOUNDS FIRST. `aget` past the end of a Uint8Array returns `undefined`, and
  ;; `(bit-shift-right undefined 5)` is 0 in JavaScript -- so an indefinite
  ;; string that simply RAN OUT before its break code was reported as
  ;; ":boring/bad-indefinite-chunk ... contains a chunk of major 0", naming a
  ;; chunk that does not exist. `5f 41 00` is truncated, and the JVM says so.
  ;; Misreporting a truncation as a syntax error is the same defect class as
  ;; the reverse, which boring.streaming-test already pins in the other
  ;; direction.
  (need! r 1)
  (if (at-break? r)
    (do (set! (.-pos r) (inc (.-pos r))) BREAK)
    (let [h (aget (.-buf r) (.-pos r))]
      (when (not= major (bit-shift-right h 5))
        (err :boring/bad-indefinite-chunk
             (str "boring: indefinite-length item of major " major
                  " contains a chunk of major " (bit-shift-right h 5))))
      (when (= 31 (bit-and h 0x1F))
        (err :boring/bad-indefinite-chunk
             "boring: indefinite-length chunks must have a definite length"))
      (read! r))))

(defn- read-indefinite-bytes! [^Reader r]
  (loop [acc []]
    (let [c (read-chunk! r 2)]
      (if (identical? c BREAK)
        (let [total (reduce + 0 (map #(.-length %) acc))
              out (js/Uint8Array. total)]
          (reduce (fn [off ^js/Uint8Array c] (.set out c off) (+ off (.-length c))) 0 acc)
          out)
        (recur (conj acc c))))))

(defn- read-indefinite-text! [^Reader r]
  ;; Collect then join once. `(str acc c)` per chunk is quadratic: a message
  ;; made of many tiny chunks -- which an attacker chooses freely, since the
  ;; chunk count is not bounded by anything but the message length -- blocked
  ;; the event loop for O(n^2) time in an environment with one thread.
  (let [parts (array)]
    (loop []
      (let [c (read-chunk! r 3)]
        (if (identical? c BREAK)
          (.join parts "")
          (do (.push parts c) (recur)))))))

(defn- read-indefinite-array! [^Reader r]
  (enter! r)
  (let [v (loop [acc (transient [])]
            (let [x (read-or-break! r)]
              (if (identical? x BREAK) (persistent! acc) (recur (conj! acc x)))))]
    (exit! r) v))

(defn- read-indefinite-map! [^Reader r]
  (enter! r)
  ;; The definite form counts entries against the declared size to catch a
  ;; duplicate key for free. An indefinite map has no declared size, so it
  ;; counted the pairs it read instead -- it did NOT, and the two forms had
  ;; different security semantics for the same document. The JVM rejects both.
  (let [[m pairs] (loop [acc (transient {}) n 0]
                    (let [k (read-or-break! r)]
                      (if (identical? k BREAK)
                        [(persistent! acc) n]
                        (recur (assoc! acc k (read! r)) (inc n)))))]
    (exit! r)
    (when (and (.-checkDuplicateKeys r) (not= (count m) pairs))
      (err :boring/duplicate-map-key
           (str "boring: duplicate map key in an indefinite-length map of "
                pairs " pairs")
           {:declared pairs :actual (count m)}))
    m))

(defn- read-array! [^Reader r n]
  ;; Shared singletons for empty collections. A fresh empty vector per
  ;; occurrence was the worst case for memory amplification on the JVM
  ;; (2 MB of `80` bytes -> 121 MB, 64x); CLJS had the same shape.
  (if (zero? n) [] (read-array-n! r n)))

;; transit-js builds vectors of <= 32 elements from a plain JS array and only
;; falls back to transients above that
;; (transit-js/src/com/cognitect/transit/impl/decoder.js:317). Measured here:
;; 20 elements cost 148.6 ns through transient+conj! against 80.9 ns through
;; PersistentVector.fromArray. The cutoff is real and not superstition -- at
;; 200 elements fromArray is SLOWER (1631 ns against 1237), because above one
;; tail node it rebuilds the tree that conj! was filling incrementally.
(def ^:private ^:const VECTOR-FROM-ARRAY-MAX 32)

;; Split rather than branched inside one function. Folding both strategies into
;; `read-array-n!` made long-vec-1k -- which takes the transient path either way
;; and should not have changed at all -- 14% SLOWER, while the small-vector case
;; got faster. A bigger body with two allocation shapes is a worse inlining
;; candidate, and the hot loop pays for a branch it never takes.
(defn- read-array-small! [^Reader r n]
  (let [arr (js/Array. n)]
    (dotimes [i n] (aset arr i (read! r)))
    (cljs.core/PersistentVector.fromArray arr true)))

(defn- read-array-big! [^Reader r n]
  (loop [i 0 acc (transient [])]
    (if (< i n) (recur (inc i) (conj! acc (read! r))) (persistent! acc))))

(defn- read-array-n! [^Reader r n]
  ;; No try/finally: it inhibits V8 optimisation on a per-container basis, and
  ;; an aborted decode resets depth wholesale via reset!/read-next! anyway.
  (enter! r)
  (let [v (if (<= n VECTOR-FROM-ARRAY-MAX)
            (read-array-small! r n)
            (read-array-big! r n))]
    (exit! r)
    v))

(defn- read-map! [^Reader r n]
  (if (zero? n) {} (read-map-n! r n)))

;; Whether an interleaved [k v k v ...] array holds a repeated key.
;;
;; `identical?` first, `=` only as a fallback: boring's ident cache returns the
;; SAME keyword object for a repeated key, so the overwhelmingly common case is
;; a pointer comparison. O(n^2), but n is bounded by the array-map threshold,
;; so it is at most 28 comparisons.
(defn- dup-key? [arr n]
  (loop [i 0]
    (if (>= i n)
      false
      (let [a (aget arr (* 2 i))
            dup (loop [j (inc i)]
                  (if (>= j n)
                    false
                    (let [b (aget arr (* 2 j))]
                      (if (or (identical? a b) (= a b)) true (recur (inc j))))))]
        (if dup true (recur (inc i)))))))

;; Separate function for the same inlining reason as read-array-small!.
(defn- read-map-small! [^Reader r n]
  (let [arr (js/Array. (* 2 n))]
    (dotimes [i n]
      (aset arr (* 2 i) (read! r))
      (aset arr (inc (* 2 i)) (read! r)))
    (if (dup-key? arr n)
      ;; Rebuild through the slow path so the duplicate is either reported or
      ;; collapsed, exactly as before. `nil` would be ambiguous with "no fast
      ;; path taken", so a duplicate raises here when the check is on and
      ;; otherwise returns the collapsed map.
      (let [m (loop [i 0 acc (transient {})]
                (if (< i n)
                  (recur (inc i) (assoc! acc (aget arr (* 2 i))
                                         (aget arr (inc (* 2 i)))))
                  (persistent! acc)))]
        (when (.-checkDuplicateKeys r)
          (err :boring/duplicate-map-key
               (str "boring: duplicate map key in a map of declared size " n)
               {:declared n :actual (count m)}))
        m)
      (PersistentArrayMap. nil n arr nil))))

(defn- read-map-n! [^Reader r n]
  (enter! r)
  ;; Small maps skip the transient entirely, the way transit-js does
  ;; (decoder.js:255, threshold 8) and the way the shaped-array path above
  ;; already did. TransientArrayMap's -assoc! calls array-map-index-of on every
  ;; insert, so a 5-key map pays 10 key comparisons plus the transient's own
  ;; allocation: 99.6 ns against 46.8 ns for a direct build.
  ;;
  ;; Unlike transit we do NOT skip duplicate detection. transit passes
  ;; no-check=true to fromArray, which turns a duplicate key into a corrupt map
  ;; -- wrong count, lookups that find the first binding. Scanning first costs
  ;; 22 ns and still leaves the fast path 1.4x ahead, and on the rare hit we
  ;; fall back so that both `checkDuplicateKeys` modes keep their existing
  ;; semantics (error when on, last-one-wins when off).
  (let [arraymap-max (.-HASHMAP-THRESHOLD PersistentArrayMap)
        fast (when (<= n arraymap-max) (read-map-small! r n))
        m (or fast
              (loop [i 0 acc (transient {})]
                (if (< i n)
                  (let [k (read! r) v (read! r)] (recur (inc i) (assoc! acc k v)))
                  (persistent! acc))))]
    (exit! r)
    ;; Duplicate detection for free on the transient path: a map that swallowed
    ;; a repeated key is shorter than the declared count. The previous version
    ;; allocated a js/Set per map and hashed every key — O(n) work and an
    ;; allocation, for a check a single count comparison already makes.
    (when (and (nil? fast) (.-checkDuplicateKeys r) (not= (count m) n))
      (err :boring/duplicate-map-key
           (str "boring: duplicate map key in a map of declared size " n)
           {:declared n :actual (count m)}))
    m))

(defn- read-typed-array! [^Reader r tag]
  (let [bs (read! r)]
    (when-not (instance? js/Uint8Array bs)
      (err :boring/bad-tag-content
           (str "boring: typed-array tag " tag " must wrap a byte string") {:tag tag}))
    (let [elem (case tag 77 2 78 4 79 8 85 4 86 8)
          len (.-byteLength bs)]
      (when-not (zero? (mod len elem))
        (err :boring/bad-tag-content
             (str "boring: typed-array tag " tag " payload is not a multiple of " elem)
             {:tag tag}))
      ;; Copy rather than view: the source may be a subarray of a larger
      ;; buffer at an offset a typed array cannot honour.
      (let [buf (.-buffer (.slice bs 0))
            n (/ len elem)]
        (case tag
          77 (js/Int16Array. buf 0 n)
          78 (js/Int32Array. buf 0 n)
          79 (js/BigInt64Array. buf 0 n)
          85 (js/Float32Array. buf 0 n)
          86 (js/Float64Array. buf 0 n))))))

(defn- seq-content
  "A tag-27 argument that must be a sequence. `into` over a number raises a raw
  \"is not ISeqable\" JS error, straight through the typed-error contract."
  [argument nm]
  (when-not (or (nil? argument) (sequential? argument) (set? argument))
    (err :boring/bad-tag-content
         (str "boring: " nm " must wrap an array") {:tag 27}))
  argument)

(defn- leap-second?
  "`:60` in the seconds field -- valid RFC 3339 (5.6), representable by neither
  platform.

  `Instant.parse` COLLAPSES it to :59 and `new Date` returns Invalid Date, so
  there is no lossless native value to decode to on either side. Preserving the
  string under an inert tag 0 is the only option that neither corrupts the
  instant nor fails the document over a legal timestamp.

  Index arithmetic rather than a regex, to mirror `Reader.isLeapSecond`
  character for character: the two platforms must agree on exactly which
  strings these are, or this fix trades one differential for another."
  [s]
  (let [i (.indexOf s ":")]
    (if (neg? i)
      false
      (let [j (.indexOf s ":" (inc i))]
        (and (>= j 0)
             (< (+ j 2) (.-length s))
             (= "6" (.charAt s (inc j)))
             (= "0" (.charAt s (+ j 2))))))))

(defn- nest-dims
  "Nested vectors for a tag-40 payload of any dimensionality, row-major.

  `cnt` is the number of elements in the block at `offset`, so each level
  divides rather than re-multiplying the remaining shape. The product was
  checked against the payload length before the first call, which is what makes
  the indexing here total."
  [flat typed? shape dim offset cnt]
  (let [len (nth shape dim)]
    (if (= dim (dec (count shape)))
      (mapv (fn [i] (if typed? (aget flat (+ offset i)) (nth flat (+ offset i))))
            (range len))
      (let [sub (quot cnt len)]
        (mapv (fn [i] (nest-dims flat typed? shape (inc dim) (+ offset (* i sub)) sub))
              (range len))))))

(defn- cbor-integer?
  "An integer as CBOR means it: a JS number with no fractional part, or a
  BigInt. `number?` alone is true of 1.5."
  [x]
  (or (= "bigint" (goog/typeOf x))
      (and (number? x) (js/Number.isInteger x))))

(defn- stringref-arg!
  "The argument of tag 25, which MUST be an unsigned integer.

  This used to be `(arg! r (bit-and (u8! r) 0x1F))`: the mask keeps the
  additional-info bits and DISCARDS the major type, so a negative-integer
  header aliased onto an index -- `d8 19 20` is the integer -1 and was read as
  index 0. Malformed CBOR silently became a valid reference."
  [^Reader r]
  (let [h (u8! r)]
    (when (not== 0 (bit-shift-right h 5))
      (err :boring/bad-tag-content
           (str "boring: tag 25 content must be an unsigned integer, got major "
                (bit-shift-right h 5))
           {:tag 25}))
    (arg! r (bit-and h 0x1F))))

(declare read-tagged!**)

(defn- read-tagged!* [^Reader r tag]
  ;; A REGISTERED reader wins over the built-in one, as on the JVM, where this
  ;; same defect was fixed by hoisting the lookup above the switch. Consulted
  ;; only in the default branch here, registering a reader for a tag boring
  ;; knows natively was silently ignored -- so identical portable registry code
  ;; behaved differently on the two platforms. Consumers with their own numeric
  ;; types need the built-in mapping to be a DEFAULT, not a ceiling.
  (if-let [override (get-in (.-registry r) [:readers tag])]
    (override (read! r))
    (read-tagged!** r tag)))

(defn- read-tagged!** [^Reader r tag]
  (case tag
    256 (let [saved-strings (.-srStrings r)
              saved-idents  (.-srIdents r)
              saved-active  (.-srActive r)]
          ;; A fresh table, with the enclosing one restored on the way out.
          ;; Nested namespaces SHADOW rather than extend, so an index can never
          ;; leak across a boundary in either direction.
          (set! (.-srStrings r) #js [])
          (set! (.-srIdents r) #js [])
          (set! (.-srActive r) true)
          (try
            (read! r)
            (finally
              (set! (.-srStrings r) saved-strings)
              (set! (.-srIdents r) saved-idents)
              (set! (.-srActive r) saved-active))))
    25 (aget (.-srStrings r) (stringref-index r (stringref-arg! r)))
    39 (let [save (.-pos r)
             th (u8! r)]
         (if (== 6 (bit-shift-right th 5))
             ;; Nested tag => stringref. Resolve the index and reuse the
             ;; ALREADY INTERNED ident for it.
             ;;
             ;; This previously called `read!` to get the string back and then
             ;; `intern-ident` on every occurrence, so a repeated keyword
             ;; re-interned every time. CPU profiling put `cljs.core/keyword`
             ;; at 21.3% of decode because of it. The JVM reader has had this
             ;; srIdents fast path since §10g; the port simply dropped it.
           (let [inner (arg! r (bit-and th 0x1F))]
             (if (== 25 inner)
               (let [idx (stringref-index r (stringref-arg! r))
                     cached (aget (.-srIdents r) idx)]
                 (if (some? cached)
                   cached
                   ;; The slot must hold TEXT. A byte string legally occupies a
                   ;; stringref slot, so a document can point tag 39 at one --
                   ;; `intern-ident` then called string methods on a
                   ;; Uint8Array. Same defect as the JVM's unguarded cast.
                   (let [sv (aget (.-srStrings r) idx)]
                     (when-not (string? sv)
                       (err :boring/bad-tag-content
                            (str "boring: tag 39 references stringref " idx
                                 ", which holds a byte string, not text")
                            {:tag 39}))
                     (let [v (intern-ident sv)]
                       (aset (.-srIdents r) idx v)
                       v))))
               (do (set! (.-pos r) save)
                   (let [s (read! r)]
                     (if (string? s) (intern-ident s) (data/tagged-value 39 s))))))
           (if (or (not== 3 (bit-shift-right th 5))
                   (== 31 (bit-and th 0x1F)))
             ;; NOT AN ERROR. IANA registers tag 39's data item as "multiple",
             ;; and the defining spec says it "can be applied to multiple types
             ;; to indicate that the tagged object has identifier semantics".
             ;; Throwing failed the whole document over a foreign identifier
             ;; boring has no mapping for; an inert TaggedValue is the same
             ;; degradation every other uninterpreted tag gets. An
             ;; indefinite-length text string lands here too -- still major 3,
             ;; so the ai check is what catches it -- and must still intern.
             (do (set! (.-pos r) save)
                 (let [v (read! r)]
                   (if (string? v) (intern-ident v) (data/tagged-value 39 v))))
             (let [n (check-count r (arg! r (bit-and th 0x1F)) 1)
                   start (.-pos r)]
               (need! r n)
               (when (.-validateUtf8 r) (validate-utf8! r start n))
               (set! (.-pos r) (+ start n))
               (let [ident (ident-from-bytes r start n)]
                   ;; keep the stringref index space in lockstep with the
                   ;; encoder -- inside a namespace only, see read-text!
                 (when (and (.-srActive r)
                            (>= n (min-len-for-index (.-length (.-srStrings r)))))
                   (.push (.-srStrings r) (decode-range r start n))
                   (.push (.-srIdents r) ident))
                 ident)))))
    39649 ;; shaped array: [keys, rows]. Read the frame by hand — building each
            ;; row as a vector only to turn it into a map would undo the point.
    (let [outer (u8! r)]
      (when (or (not== 4 (bit-shift-right outer 5))
                (not== 2 (check-count r (arg! r (bit-and outer 0x1F)) 1)))
        (err :boring/bad-tag-content "boring: shaped array must wrap [keys rows]" {:tag 39649}))
      (let [kh (u8! r)]
        (when (not== 4 (bit-shift-right kh 5))
          (err :boring/bad-tag-content "boring: shaped array keys must be an array" {:tag 39649}))
        (let [n (check-count r (arg! r (bit-and kh 0x1F)) 1)]
          (when (zero? n)
            (err :boring/bad-tag-content "boring: shaped array needs at least one key"
                 {:tag 39649}))
          (let [ks (js/Array. n)
                _ (dotimes [i n] (aset ks i (read! r)))
                  ;; Distinctness is checked ONCE here, for the shape, rather
                  ;; than per row. That is what makes the fast row build below
                  ;; safe: PersistentArrayMap's raw constructor does no
                  ;; duplicate checking, so a shape with repeated keys would
                  ;; otherwise produce a corrupt map for every row.
                  ;; O(n) via a Set, not the O(n^2) pairwise scan this used to
                  ;; do: `n` is bounded only by remaining bytes, so ~3 bytes per
                  ;; key gave n^2/2 comparisons — 48 KB of input measured at
                  ;; 2.1 s of blocked event loop.
                _ (let [seen (js/Set.)]
                    (dotimes [i n]
                      (let [k (aget ks i)
                            h (hash k)]
                        (when (.has seen h)
                          (dotimes [j i]
                            (when (= (aget ks j) k)
                              (err :boring/bad-tag-content
                                   (str "boring: shaped array has a duplicate key: "
                                        (pr-str k))
                                   {:tag 39649 :key k}))))
                        (.add seen h))))
                arraymap-max (.-HASHMAP-THRESHOLD PersistentArrayMap)
                rh (u8! r)]
            (when (not== 4 (bit-shift-right rh 5))
              (err :boring/bad-tag-content "boring: shaped array rows must be an array"
                   {:tag 39649}))
            (let [rows (check-count r (arg! r (bit-and rh 0x1F)) 1)]
              (loop [i 0 acc (transient [])]
                (if (= i rows)
                  (persistent! acc)
                  (let [vh (u8! r)]
                    (when (not== 4 (bit-shift-right vh 5))
                      (err :boring/bad-tag-content "boring: shaped array row must be an array"
                           {:tag 39649}))
                    (let [vn (check-count r (arg! r (bit-and vh 0x1F)) 1)]
                      (when (not== vn n)
                        (err :boring/bad-tag-content
                             (str "boring: shaped array row has " vn " values but the shape has "
                                  n " keys") {:tag 39649}))
                        ;; Keys are already decoded, interned and known
                        ;; distinct, so hand PersistentArrayMap a pre-filled
                        ;; interleaved array instead of assoc!-ing key by key.
                        ;; TransientArrayMap's -assoc! calls array-map-index-of
                        ;; on every key — 10 comparisons plus 10 pushes for a
                        ;; 5-key map — and all of that is redundant when the
                        ;; shape is already validated.
                      (recur (inc i)
                             (conj! acc
                                    (if (<= n arraymap-max)
                                      (let [arr (js/Array. (* 2 n))]
                                        (dotimes [j n]
                                          (aset arr (* 2 j) (aget ks j))
                                          (aset arr (inc (* 2 j)) (read! r)))
                                        (PersistentArrayMap. nil n arr nil))
                                      (loop [j 0 m (transient {})]
                                        (if (< j n)
                                          (recur (inc j) (assoc! m (aget ks j) (read! r)))
                                          (persistent! m)))))))))))))))

    258 (let [save (.-pos r)
              h (u8! r)]
          (when (not== 4 (bit-shift-right h 5))
            (err :boring/bad-tag-content
                 (str "boring: tag 258 must wrap an array, got major "
                      (bit-shift-right h 5)) {:tag 258}))
          ;; INDEFINITE-LENGTH ARRAY. Tag 258 is registered against "array",
          ;; and 3.2.2 makes the indefinite form an array; neither the
          ;; registration nor cbor-sets-spec restricts it. Hand-rolling the
          ;; head rejected it as :boring/reserved-info -- conforming input
          ;; refused, under an error that was wrong too, since ai 31 is not
          ;; reserved for major type 4.
          (let [items (if (== 31 (bit-and h 0x1F))
                        (do (set! (.-pos r) save)
                            (let [c (read! r)]
                              (when-not (vector? c)
                                (err :boring/bad-tag-content
                                     "boring: tag 258 must wrap an array" {:tag 258}))
                              c))
                        (vec (repeatedly (check-count r (arg! r (bit-and h 0x1F)) 1)
                                         #(read! r))))
                n (count items)
                st (into #{} items)]
              ;; Maps reject duplicate keys; sets silently collapsed them.
            (when (and (.-checkDuplicateKeys r) (not== (count st) n))
              (err :boring/duplicate-set-element
                   (str "boring: tag 258 declared " n " elements but "
                        (count st) " are distinct") {:declared n}))
            st))
    (2 3) (let [bs (read! r)]
            (when-not (instance? js/Uint8Array bs)
              (err :boring/bad-tag-content
                   (str "boring: bignum tag " tag " must wrap a byte string") {:tag tag}))
            (let [hex (reduce (fn [acc b]
                                (str acc (.padStart (.toString b 16) 2 "0")))
                              "" (array-seq bs))
                  m (js/BigInt (str "0x" (if (= "" hex) "0" hex)))]
              (if (= tag 2) m (- (- m) (js/BigInt 1)))))
    0 (let [s (read! r)]
        (when-not (string? s)
          (err :boring/bad-tag-content "boring: tag 0 must wrap a text string" {:tag 0}))
        ;; RFC 3339, not "whatever js/Date parses". `new Date("2020")` and
        ;; `new Date("March 5 2020")` both succeed in JS and both are rejected
        ;; on the JVM, so the platforms disagreed about which documents are
        ;; valid -- a parser differential, and the lenient side is the one
        ;; running in a browser.
        (when-not (re-matches #"\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:\d{2})" s)
          (err :boring/bad-tag-content
               (str "boring: tag 0 content is not a valid RFC 3339 instant: " s)
               {:tag 0 :value s}))
        ;; A LEAP SECOND IS PRESERVED, NOT REJECTED. `new Date` returns Invalid
        ;; Date for :60, which sent a legal RFC 3339 timestamp down the error
        ;; path while the JVM handed back an inert tag 0 carrying the string --
        ;; a semantic differential (value here, error there) on input a real
        ;; producer emits. doc/SECURITY.md names those as their own defect
        ;; class. Both platforms now preserve.
        (if (leap-second? s)
          (data/tagged-value 0 s)
          (let [d (js/Date. s)]
            (when (js/isNaN (.getTime d))
              (err :boring/bad-tag-content
                   (str "boring: tag 0 content is not a valid RFC 3339 instant: " s)
                   {:tag 0 :value s}))
            d)))
    1 (let [v (read! r)]
        (when-not (number? v)
          (err :boring/bad-tag-content "boring: tag 1 must wrap a number" {:tag 1}))
        (let [d (js/Date. (* v 1000))]
            ;; An out-of-range epoch gives an Invalid Date rather than throwing,
            ;; so it has to be checked. The JVM raises DateTimeException here,
            ;; which fuzzing caught escaping untyped.
          (when (js/isNaN (.getTime d))
            (err :boring/bad-tag-content
                 (str "boring: tag 1 epoch out of range: " v) {:tag 1 :value v}))
          d))
    4 (let [l (read! r)]                            ; decimal fraction
        (when-not (and (vector? l) (== 2 (count l)))
          (err :boring/bad-tag-content
               "boring: tag 4 must wrap [exponent mantissa]" {:tag 4}))
        (let [e (nth l 0) m (nth l 1)]
          ;; INTEGER, not merely number. `number?` is true of 1.5 in JS, so a
          ;; float exponent was accepted here and rejected on the JVM, where
          ;; the tag definition requires an integer.
          (when-not (and (cbor-integer? e) (cbor-integer? m))
            (err :boring/bad-tag-content
                 "boring: tag 4 exponent and mantissa must be integers" {:tag 4}))
          (data/decimal e m)))

    30 (let [l (read! r)]                           ; rational
         (when-not (and (vector? l) (== 2 (count l)))
           (err :boring/bad-tag-content
                "boring: tag 30 must wrap [numerator denominator]" {:tag 30}))
         (let [n (nth l 0) d (nth l 1)]
           (when-not (and (cbor-integer? n) (cbor-integer? d))
             (err :boring/bad-tag-content
                  "boring: tag 30 numerator and denominator must be integers" {:tag 30}))
           (when (zero? d)
             (err :boring/bad-tag-content "boring: tag 30 denominator is zero" {:tag 30}))
           (data/rational n d)))

      ;; RFC 8746 typed arrays, little-endian. JS has native counterparts, so
      ;; these map directly rather than through a stand-in.
    (77 78 79 85 86) (read-typed-array! r tag)

    ;; Tags 32 (URI), 1002 (duration) and 1004 (full-date) have no
    ;; ClojureScript counterpart, so they stay `TaggedValue`s: decoding a URI
    ;; to a bare string looks lossless -- a URI is its string form -- but is
    ;; not round-trip safe, because re-encoding that string emits a plain text
    ;; string and a JVM peer receives a String where it sent a URI. A
    ;; TaggedValue re-encodes to identical bytes.
    ;;
    ;; They are still VALIDATED to the JVM's standard. Falling through to the
    ;; unknown-tag path meant CLJS accepted content the JVM rejects -- a tag
    ;; 1004 wrapping the integer 1, a tag 1002 whose seconds are a string --
    ;; which is a parser differential whichever value the two sides go on to
    ;; produce.
    32 (let [v (read! r)]
         (when-not (string? v)
           (err :boring/bad-tag-content "boring: tag 32 must wrap a text string" {:tag 32}))
         (data/tagged-value 32 v))

    1002 (let [v (read! r)]
           (when-not (map? v)
             (err :boring/bad-tag-content "boring: tag 1002 must wrap a map" {:tag 1002}))
           (let [sec (get v 1) nano (get v -9)]
             (when (nil? sec)
               (err :boring/bad-tag-content
                    "boring: tag 1002 has no base value (key 1)" {:tag 1002}))
             (when-not (number? sec)
               (err :boring/bad-tag-content
                    "boring: tag 1002 base value must be a number" {:tag 1002}))
             (when-not (or (nil? nano) (number? nano))
               (err :boring/bad-tag-content
                    "boring: tag 1002 fraction must be a number" {:tag 1002})))
           (data/tagged-value 1002 v))

    1004 (let [v (read! r)]
           (when-not (and (string? v)
                          (re-matches #"\d{4}-\d{2}-\d{2}" v)
                          (let [d (js/Date. (str v "T00:00:00Z"))]
                            (and (not (js/isNaN (.getTime d)))
                                 (= v (.slice (.toISOString d) 0 10)))))
             (err :boring/bad-tag-content
                  (str "boring: tag 1004 must wrap an RFC 3339 full-date, got "
                       (pr-str v))
                  {:tag 1004}))
           (data/tagged-value 1004 v))

    35 (let [v (read! r)]
         (when-not (string? v)
           (err :boring/bad-tag-content "boring: tag 35 must wrap a text string" {:tag 35}))
         (try
           (js/RegExp. v)
           (catch :default e
             ;; Tag 35 does not say which regex dialect, so a pattern written
             ;; by another language may not compile here.
             (err :boring/bad-tag-content
                  (str "boring: tag 35 content is not a valid JS regex: " (.-message e))
                  {:tag 35}))))

    ;; RFC 8746 multi-dimensional array, row-major. Read-only here: the JVM
    ;; has distinct double[][] / long[][] types to dispatch on, JavaScript has
    ;; only "Array of TypedArray", which is indistinguishable from a vector
    ;; that happens to hold typed arrays. So a browser peer can READ a matrix a
    ;; JVM peer wrote -- the direction that matters for this stack -- without
    ;; guessing on the way out.
    40 (let [l (read! r)]
         (when-not (and (vector? l) (= 2 (count l)))
           (err :boring/bad-tag-content "boring: tag 40 must wrap [dims flat-array]" {:tag 40}))
         (let [dims (nth l 0)
               flat0 (nth l 1)
               ;; RFC 8746 3.1.1 allows a Homogeneous Array (tag 41) as payload.
               flat (if (and (data/tagged-value? flat0) (= 41 (:tag flat0)))
                      (:value flat0) flat0)]
           ;; ANY DIMENSIONALITY -- 3.1.1 never bounds the count, and demanding
           ;; exactly two rejected the RFC's own 3-D examples.
           (when-not (and (vector? dims) (pos? (count dims)))
             (err :boring/bad-tag-content
                  "boring: tag 40 dimensions array must not be empty" {:tag 40}))
           (let [shape (mapv (fn [d]
                               (when-not (cbor-integer? d)
                                 (err :boring/bad-tag-content
                                      "boring: tag 40 dimensions must be integers" {:tag 40}))
                               ;; "unsigned integers distinct from zero" (3.1.1)
                               (when-not (pos? d)
                                 (err :boring/bad-tag-content
                                      (str "boring: tag 40 dimension " d " is not an unsigned "
                                           "integer distinct from zero")
                                      {:tag 40}))
                               d)
                             dims)
                 total (reduce * 1 shape)
                 ;; TYPE-CHECKED BEFORE IT IS USED. Reading `.-length` off
                 ;; whatever the wire supplied and calling `.subarray` on it
                 ;; escaped as a raw TypeError for a text-string payload,
                 ;; straight through doc/SECURITY.md's typed-failure guarantee.
                 typed? (.isView js/ArrayBuffer flat)
                 declared (cond typed? (.-length flat)
                                (vector? flat) (count flat)
                                :else
                                (err :boring/bad-tag-content
                                     (str "boring: tag 40 payload must be a CBOR array, a typed "
                                          "array or a homogeneous array, got " (pr-str flat))
                                     {:tag 40}))]
             ;; Dimensions and payload length both come from the wire; if they
             ;; disagree the item is malformed, and trusting the dimensions
             ;; would be an unchecked allocation.
             (when (not= total declared)
               (err :boring/bad-tag-content
                    (str "boring: tag 40 dimensions " (pr-str dims)
                         " do not match the " declared "-element payload")
                    {:tag 40}))
             (if (and (= 2 (count shape)) typed?)
               ;; A 2-D typed array keeps the zero-copy array-of-subarrays it
               ;; has always produced; everything else becomes nested vectors.
               (let [rows (nth shape 0) cols (nth shape 1) out (make-array rows)]
                 (dotimes [i rows]
                   (aset out i (.subarray flat (* i cols) (* (inc i) cols))))
                 out)
               (nest-dims flat typed? shape 0 0 total)))))

    37 (let [bs (read! r)]
         (when-not (and (instance? js/Uint8Array bs) (= 16 (.-length bs)))
           (err :boring/bad-tag-content "boring: tag 37 must wrap a 16-byte string" {:tag 37}))
         (let [h (reduce (fn [acc b] (str acc (.padStart (.toString b 16) 2 "0")))
                         "" (array-seq bs))]
           (uuid (str (subs h 0 8) "-" (subs h 8 12) "-" (subs h 12 16) "-"
                      (subs h 16 20) "-" (subs h 20 32)))))
    27 (let [arr (read! r)]
         (when-not (and (vector? arr) (= 2 (count arr)))
           (err :boring/bad-tag-content "boring: tag 27 must wrap [type-name argument]"
                {:tag 27}))
         (let [nm (nth arr 0) argument (nth arr 1)]
           (when-not (string? nm)
             (err :boring/bad-tag-content "boring: tag 27 name must be a string" {:tag 27}))
           (if-let [ctor (get-in (.-registry r) [:records nm])]
             (ctor argument)
             ;; Built-in collection markers, AFTER the registry so a caller can
             ;; still take these names for themselves.
             (case nm
               "clojure/with-meta"
               (do (when-not (and (vector? argument) (= 2 (count argument)))
                     (err :boring/bad-tag-content
                          "boring: clojure/with-meta must wrap [meta value]" {:tag 27}))
                   (when-not (map? (nth argument 0))
                     (err :boring/bad-tag-content
                          "boring: clojure/with-meta first element must be a map"
                          {:tag 27}))
                   (when-not (satisfies? IWithMeta (nth argument 1))
                     (err :boring/bad-tag-content
                          "boring: clojure/with-meta value cannot carry metadata"
                          {:tag 27}))
                   (with-meta (nth argument 1) (nth argument 0)))
               "clojure/sorted-map"
               (do (when-not (map? argument)
                     (err :boring/bad-tag-content
                          "boring: clojure/sorted-map must wrap a map" {:tag 27}))
                   (into (sorted-map) argument))
               "clojure/sorted-set" (into (sorted-set) (seq-content argument "clojure/sorted-set"))
               "clojure/queue"      (into cljs.core/PersistentQueue.EMPTY
                                          (seq-content argument "clojure/queue"))
               ;; ClojureScript has no character type -- `\a` READS as the
               ;; one-character string -- and no java.time. The marker is
               ;; therefore write-side-only on this platform: JVM data still
               ;; decodes, to the closest thing that exists here, rather than
               ;; surfacing as an UnknownRecord the caller has to unwrap.
               ;;
               ;; Validated to the same standard as the JVM even so. A reader
               ;; that accepts what the other platform rejects is a parser
               ;; differential, and it does not stop being one because the
               ;; value it produces here is simpler.
               "clojure/char"
               (do (when-not (and (string? argument) (== 1 (count argument)))
                     (err :boring/bad-tag-content
                          "boring: clojure/char must wrap exactly one character"
                          {:tag 27}))
                   argument)
               ;; ClojureScript has no typed arrays for these and no Throwable
               ;; hierarchy, so they decode to the closest thing that exists:
               ;; a vector, a string, or an ex-info. JVM data still DECODES
               ;; rather than surfacing as an UnknownRecord to unwrap.
               "java/boolean-array" (vec argument)
               "java/char-array"    argument
               "java/string-array"  (vec argument)
               "java/object-array"  (vec argument)
               "clojure/ex-info"
               (do (when-not (and (vector? argument) (== 3 (count argument)))
                     (err :boring/bad-tag-content
                          "boring: clojure/ex-info must wrap [message data cause]" {:tag 27}))
                   (ex-info (nth argument 0) (nth argument 1) (nth argument 2)))
               "java/throwable"
               (do (when-not (and (vector? argument) (== 3 (count argument)))
                     (err :boring/bad-tag-content
                          "boring: java/throwable must wrap [class message cause]" {:tag 27}))
                   (ex-info (or (nth argument 1) "")
                            {:boring/throwable-class (nth argument 0)}
                            (nth argument 2)))
               "java/period"
               (do (when-not (and (string? argument)
                                  (re-matches #"[+-]?P(?!$)([+-]?\d+Y)?([+-]?\d+M)?([+-]?\d+W)?([+-]?\d+D)?"
                                              argument))
                     (err :boring/bad-tag-content
                          (str "boring: java/period is not an ISO-8601 period: "
                               (pr-str argument))
                          {:tag 27}))
                   argument)
               ;; Chosen by PAYLOAD SHAPE, not by any claim about the sender --
               ;; see the JVM Reader. A map gets the map-presenting wrapper; a
               ;; positional payload gets a TaggedLiteral, which offers :tag
               ;; and :form and never promises map-ness.
               (if (map? argument)
                 (data/unknown-record nm argument)
                 (tagged-literal (symbol nm) argument))))))
    ;; The registry was consulted above, before the built-ins.
    (if (.-tolerateUnknownTags r)
      (data/tagged-value tag (read! r))
      (err :boring/unregistered-tag (str "boring: unregistered tag " tag) {:tag tag}))))

(defn- read-tagged! [^Reader r tag]
  (enter! r)
  (let [v (read-tagged!* r tag)]
    (exit! r)
    v))

(defn read! [^Reader r]
  (let [header (u8! r)
        major (bit-shift-right header 5)
        info (bit-and header 0x1F)]
    (case major
      0 (arg! r info)
      1 (let [v (arg! r info)]
          (if (= "bigint" (goog/typeOf v)) (- (- v) (js/BigInt 1)) (- (- v) 1)))
      2 (if (= info 31)
          (read-indefinite-bytes! r)
          (let [n (check-count r (arg! r info) 1)
                _ (need! r n)
                out (.slice (.-buf r) (.-pos r) (+ (.-pos r) n))]
            (set! (.-pos r) (+ (.-pos r) n))
            ;; BYTE strings take stringref indices too -- see the JVM Reader.
            ;; Omitting them made our table shorter than every other
            ;; implementation's, so a reference resolved to the wrong entry.
            (when (and (.-srActive r)
                       (>= n (min-len-for-index (.-length (.-srStrings r)))))
              (.push (.-srStrings r) out)
              (.push (.-srIdents r) nil))
            out))
      3 (if (= info 31) (read-indefinite-text! r) (read-text! r info))
      4 (if (= info 31)
          (read-indefinite-array! r)
          (read-array! r (check-count r (arg! r info) 1)))
      5 (if (= info 31)
          (read-indefinite-map! r)
          (read-map! r (check-count r (arg! r info) 2)))
      6 (read-tagged! r (arg! r info))
      7 (case info
          20 false
          21 true
          22 nil
          23 data/undefined
          ;; `f8 00` .. `f8 1f` are not well-formed -- RFC 8949 3.3, "Such
          ;; sequences are not well-formed", which binds the decoder and not
          ;; just the encoder (Appendix C fails; Appendix F.1 enumerates them).
          ;; See the long note in Reader.java for why this once read the other
          ;; way: Appendix A's simple(24) row is RFC 7049's, removed by Erratum
          ;; 5917.
          24 (let [sv (u8! r)]
               (if (< sv 32)
                 (err :boring/malformed-simple-value
                      (str "boring: two-byte simple value 0x" (.toString sv 16)
                           " is not well-formed (RFC 8949 3.3 reserves f8 00..f8 1f)")
                      {:value sv})
                 (data/simple-value sv)))
          ;; DataView.getFloat16 is ES2025 and missing from Node 23, so decode
          ;; binary16 by hand. We never emit f16, but interop requires reading it.
          25 (do (need! r 2)
                 (let [h (.getUint16 (.-dv r) (.-pos r))
                       _ (set! (.-pos r) (+ (.-pos r) 2))
                       sign (if (zero? (bit-and h 0x8000)) 1 -1)
                       exp (bit-and (bit-shift-right h 10) 0x1F)
                       mant (bit-and h 0x3FF)]
                   (* sign
                      (cond (zero? exp) (* mant (js/Math.pow 2 -24))
                            (not= exp 31) (* (+ mant 1024) (js/Math.pow 2 (- exp 25)))
                            (zero? mant) js/Infinity
                            :else js/NaN))))
          26 (do (need! r 4)
                 (let [v (.getFloat32 (.-dv r) (.-pos r))]
                   (set! (.-pos r) (+ (.-pos r) 4)) v))
          27 (do (need! r 8)
                 (let [v (.getFloat64 (.-dv r) (.-pos r))]
                   (set! (.-pos r) (+ (.-pos r) 8)) v))
          (if (< info 20)
            (data/simple-value info)
            (if (= info 31)
              (err :boring/unexpected-break
                   "boring: break code outside an indefinite-length item")
              (err :boring/reserved-simple-value
                   (str "boring: reserved simple value " info) {:value info}))))
      (err :boring/bad-major (str "boring: unhandled major type " major) {:major major}))))

(defn read-next!
  "Next top-level item of a CBOR sequence, clearing the per-message stringref
  namespace but keeping the ident cache."
  [^Reader r]
  (set! (.-depth r) 0)
  (set! (.-srStrings r) #js [])
  (set! (.-srIdents r) #js [])
  (set! (.-srActive r) false)
  (read! r))
