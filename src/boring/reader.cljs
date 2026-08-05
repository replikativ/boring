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

;; `:validate-utf8 false` HAS TO DECODE, not merely skip the manual check.
;;
;; Turning validation off left every byte going through the fatal decoder above,
;; so `61 ff` came back as a raw JS TypeError -- "The encoded data was not valid
;; for encoding utf-8" -- from an option whose whole purpose is to accept that
;; input. The JVM's decoder uses REPLACE for both malformed and unmappable input
;; when validation is off, so the two platforms disagreed about whether the
;; option existed: lenient there, fatal-and-untyped here.
;;
;; Two decoders rather than one constructed per call: TextDecoder construction
;; is not free and the mode is fixed for the life of a read.
(def replacing-decoder
  (js/TextDecoder. "utf-8" #js {:fatal false :ignoreBOM true}))

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
                 ^:mutable instantFn
                 ^:mutable validateUtf8
                 ^:mutable checkDuplicateKeys
                 ;; The cumulative item budget, absent here entirely until now:
                 ;; `:max-items` was accepted by the CLJS API and silently
                 ;; ignored, so the only heap-amplification control
                 ;; doc/SECURITY.md names did not exist in a browser or under
                 ;; Node. 0 means unlimited, which is the default.
                 ^:mutable maxItems
                 ^:mutable items
                 ;; `:fallback` (the default), `:error`, or a function of
                 ;; [name payload]. Only ever consulted for a name NOTHING
                 ;; recognises -- not for boring's own reserved markers, whose
                 ;; names are known even where the type is not.
                 ^:mutable onUnknownRecord
                 ^:mutable registry])

(defn reader
  [^js/Uint8Array bs]
  (Reader. bs (js/DataView. (.-buffer bs) (.-byteOffset bs) (.-byteLength bs))
           0 #js [] #js [] false (js/Map.) 0 1024 true nil true true 0 0
           :fallback nil))

(defn reset! [^Reader r ^js/Uint8Array bs]
  (set! (.-buf r) bs)
  (set! (.-dv r) (js/DataView. (.-buffer bs) (.-byteOffset bs) (.-byteLength bs)))
  (set! (.-pos r) 0)
  (set! (.-depth r) 0)
  (set! (.-items r) 0)
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

(defn- count-item! [^Reader r]
  ;; Counts ITEMS, not bytes: a one-byte container head that becomes an object
  ;; is the worst amplification case, so what matters is how many objects the
  ;; document asks for. Mirrors Reader.countItem on the JVM.
  (when (pos? (.-maxItems r))
    (set! (.-items r) (inc (.-items r)))
    (when (> (.-items r) (.-maxItems r))
      (err :boring/max-items-exceeded
           (str "boring: decoded more than " (.-maxItems r) " items")
           {:max-items (.-maxItems r)}))))

(defn- count-items!
  "Charge `n` items at once, for host objects the decoder BUILDS rather than
  reads.

  `count-item!` runs from `read!`, so it only ever sees values that arrived as
  their own data item. A tag-40 payload arrives as ONE byte string and is then
  expanded into a host object per element -- exactly the amplification the
  budget exists to bound, and exactly what it could not see. Mirrors
  `Reader.countItems` on the JVM."
  [^Reader r n]
  (when (pos? (.-maxItems r))
    (set! (.-items r) (+ (.-items r) n))
    (when (> (.-items r) (.-maxItems r))
      (err :boring/max-items-exceeded
           (str "boring: decoded more than " (.-maxItems r) " items")
           {:max-items (.-maxItems r)}))))

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

;; ## Positional primitives, for indexing already-encoded bytes
;;
;; `boring.core/build-index` walks a blob it did not write, so it needs to ask
;; where an item's head ends and where the item ends WITHOUT decoding it. These
;; mirror `Reader.majorAt`/`headArgAt`/`headEndAt`/`skipFrom` on the JVM, and
;; they must agree with the decoder byte for byte: a skip that lands one byte
;; off produces an index pointing into the middle of an item, which reads back
;; as a plausible wrong value rather than an error. The property test that
;; guards them compares `skip-from` against where `read!` actually stops.

(defn- b-at
  "Unsigned byte at an absolute offset, BOUNDS-CHECKED.

  Mirrors `Reader.b(long)`, whose comment records the same defect on the JVM:
  the positional accessors had no limit check, so a damaged document gave
  `decode` a typed error and the navigator an untyped one. Here it was worse
  than untyped -- `aget` past the end of a Uint8Array yields `undefined`, and
  `(bit-shift-right undefined 5)` is `0`, so offset 99 of a two-byte buffer read
  back as an unsigned-integer head of length 0 rather than raising at all."
  [^Reader r p]
  (when (or (neg? p) (>= p (.-length (.-buf r))))
    (err :boring/truncated-input
         (str "boring: read past the end of the input at offset " p
              " (size " (.-length (.-buf r)) ")")))
  (aget (.-buf r) p))

(defn major-at [^Reader r p] (bit-shift-right (b-at r p) 5))
(defn- info-at [^Reader r p] (bit-and (b-at r p) 0x1F))

(defn head-arg-at
  "The head's argument at `p` -- element count, pair count, or byte length.
  -1 for an indefinite item, whose count is not on the wire."
  [^Reader r p]
  (let [info (info-at r p)]
    (if (== info 31)
      -1
      (let [save (.-pos r)]
        (set! (.-pos r) (inc p))
        (let [v (arg! r info)] (set! (.-pos r) save) v)))))

(defn head-end-at
  "Offset just past the head at `p` -- where its content begins."
  [^Reader r p]
  (let [info (info-at r p)
        save (.-pos r)]
    (set! (.-pos r) (inc p))
    (when (and (>= info 24) (< info 28)) (arg! r info))
    (let [v (.-pos r)] (set! (.-pos r) save) v)))

(defn- skip-limit
  "The bound on skip NESTING -- a STACK bound, deliberately not `maxDepth`.

  Mirrors `Reader.skipLimit()` exactly, including the floor: skip cannot
  reproduce `read`'s depth accounting (a tag reader that consumes its payload's
  containers inline charges nothing for them), so it is deliberately LAXER than
  read -- never below 1024, above that whatever the caller allowed read.
  Making skip STRICTER than read is the failure that matters, because
  navigation would then refuse a document that decodes."
  [^Reader r] (max (.-maxDepth r) 1024))

(defn- skip-indefinite-chunks!
  "Definite-length chunks of `mj` up to the break, all skipped.

  Mirrors `Reader.skipIndefiniteChunks`. The major check is the point: without
  it `5f 20 ff` (an indefinite BYTE string holding a negative integer) and
  `7f 41 61 ff` (an indefinite TEXT string holding a byte string) were skipped
  clean here and refused with `:boring/bad-indefinite-chunk` on the JVM -- so
  `build-index` in a browser indexed documents neither platform can decode.
  A chunk head of info 31 falls through to `arg!`, which refuses 28-31, so a
  nested indefinite chunk is `:boring/reserved-info` on both sides."
  [^Reader r mj]
  (loop []
    (let [h (b-at r (.-pos r))]
      (set! (.-pos r) (inc (.-pos r)))
      (when (not== h 0xff)
        (when (not== mj (bit-shift-right h 5))
          (err :boring/bad-indefinite-chunk
               (str "boring: indefinite-length item contains a chunk of major "
                    (bit-shift-right h 5))
               {:major (bit-shift-right h 5)}))
        (let [n (check-count r (arg! r (bit-and h 0x1F)) 1)]
          (set! (.-pos r) (+ (.-pos r) n)))
        (recur)))))

(defn skip-from
  "Where the item at `p` ENDS, without building its value.

  Decoding and discarding would be simpler and is what a first attempt should
  reach for -- but it allocates the whole structure to learn one integer, which
  is the cost `build-index` exists to avoid. This walks heads only.

  IT MUST ACCEPT EXACTLY WHAT `Reader.skipStructural` ACCEPTS. It is the inner
  loop of `build-index` and of frame recognition, and an index built here is
  read by `boring.nav` on the JVM -- so anything this accepts and the JVM
  refuses is a file one platform will index and the other cannot open.

  EXPLICITLY ITERATIVE, with `stack` standing in for the JVM's recursion. Two
  separate reasons, both of which cost something before they were understood:
  a tag chain (`c0 c0 c0 ... 00`, legal CBOR) recursed once per tag, and 20 000
  open `9f` recursed once per container for an UNTYPED `RangeError` with empty
  ex-data -- a promise doc/SECURITY.md makes and this broke on the one platform
  browsers run. Depth is now a NUMBER that is checked, not a stack that runs out.

  One frame per open container, innermost last:

    n >= 0  a definite container still owing `n` items (a map owes 2 per pair)
    -1      an indefinite array: a break may close it here
    -2      an indefinite map at a KEY boundary: a break may close it here
    -3      an indefinite map owing a VALUE: a break here is not a close

  The bottom frame is the single item the caller asked about, so container
  nesting is `(dec (.-length stack))`. `-3` is what makes `bf 01 ff` -- an
  indefinite map that breaks between a key and its value -- come out
  `:boring/unexpected-break` here as it does on the JVM, instead of `:ok`."
  [^Reader r p]
  (let [save (.-pos r)
        limit (skip-limit r)
        stack #js [1]
        push! (fn [v]
                (.push stack v)
                (when (> (dec (.-length stack)) limit)
                  (err :boring/max-depth-exceeded
                       (str "boring: nesting deeper than the skip bound (" limit ")")
                       {:max-depth limit})))]
    (set! (.-pos r) p)
    (while (pos? (.-length stack))
      (let [d (dec (.-length stack))
            top (aget stack d)]
        (cond
          ;; A definite container that owes nothing is closed.
          (zero? top) (.pop stack)

          ;; An indefinite container at a boundary where a break is legal.
          (and (or (== top -1) (== top -2)) (== 0xff (b-at r (.-pos r))))
          (do (set! (.-pos r) (inc (.-pos r))) (.pop stack))

          :else
          (let [q (.-pos r)
                h (b-at r q)
                mj (bit-shift-right h 5)
                info (bit-and h 0x1F)]
            (set! (.-pos r) (inc q))
            ;; Settle the parent BEFORE dispatching -- except for a tag, which
            ;; owes its payload and is settled by whatever the payload turns
            ;; out to be.
            (when (not== mj 6)
              (aset stack d (cond (== top -1) -1        ; indefinite array
                                  (== top -2) -3        ; key read, value owed
                                  (== top -3) -2        ; pair complete
                                  :else (dec top))))
            (case mj
              ;; DECLARED COUNTS ARE VALIDATED AGAINST THE BYTES THAT REMAIN,
              ;; as `Reader.skipStructural` has always done with `checkCount`.
              ;; Unchecked, `9b ffffffffffffffff` owed 2^64 items and this loop
              ;; simply did not finish; and a declared byte-string length past
              ;; the end walked `pos` outside the buffer. Bounding the count
              ;; also bounds the total work: the outstanding item count can
              ;; never exceed the bytes left, so the walk is linear in the
              ;; input rather than in what the input claims.
              (0 1) (arg! r info)                ; info 28-31 -> :reserved-info
              (2 3) (if (== info 31)
                      (skip-indefinite-chunks! r mj)
                      (let [n (check-count r (arg! r info) 1)]
                        (set! (.-pos r) (+ (.-pos r) n))))
              ;; An EMPTY DEFINITE ARRAY costs no nesting and an empty definite
              ;; map costs one, because that is what `Reader.skipStructural`
              ;; does -- case 4 returns before `enterSkip()` when n is 0, case 5
              ;; does not. Mirrored rather than tidied: the two walkers agreeing
              ;; is worth more here than either one being tidy, and the
              ;; difference is only observable at the bound itself.
              4 (if (== info 31)
                  (push! -1)
                  (let [n (check-count r (arg! r info) 1)]
                    (when-not (zero? n) (push! n))))
              5 (if (== info 31)
                  (push! -2)
                  (push! (* 2 (check-count r (arg! r info) 2))))
              ;; A TAG CHAIN IS CONSUMED INLINE AND BOUNDED BY ITS LENGTH, as
              ;; the JVM does. Charging each tag to the nesting budget instead
              ;; was tried there and was wrong: several tag readers parse their
              ;; payload's head without entering, so a mirrored skip refused
              ;; `#{}` at a depth decode accepted.
              6 (do (arg! r info)
                    (loop [chain 1]
                      (let [h2 (b-at r (.-pos r))]
                        (when (== 6 (bit-shift-right h2 5))
                          (when (> (inc chain) limit)
                            (err :boring/max-depth-exceeded
                                 (str "boring: tag chain longer than the skip bound ("
                                      limit ")")
                                 {:max-depth limit}))
                          (set! (.-pos r) (inc (.-pos r)))
                          (arg! r (bit-and h2 0x1F))
                          (recur (inc chain))))))
              ;; Major 7. A break that reaches here is one no indefinite
              ;; container is waiting for -- `83 ff 01 ff 02 03`, a break inside
              ;; a DEFINITE array, which this platform used to skip clean and
              ;; hand to `build-index`.
              (if (== info 31)
                (err :boring/unexpected-break
                     "boring: break code outside an indefinite-length item")
                (arg! r info)))))))
    (let [end (.-pos r)]
      (set! (.-pos r) save)
      end)))

(defn compare-items-at
  "Lexicographic comparison of the ENCODED items at `a` and `b`.

  RFC 8949 §4.2.1 orders canonical map keys by their encoded bytes, so a
  navigator can binary-search a sorted map without decoding a single key.
  Bytewise, with the shorter encoding first when one is a prefix of the other.
  Mirrors `Reader.compareItemsAt`, and must agree with it byte for byte: the
  `sorted` flag it decides is written into the index frame, and a frame written
  here is read by `boring.nav` on the JVM."
  [^Reader r a b]
  (let [buf (.-buf r)
        an (- (skip-from r a) a)
        bn (- (skip-from r b) b)
        n (min an bn)]
    (loop [i 0]
      (if (== i n)
        (cond (< an bn) -1 (> an bn) 1 :else 0)
        (let [x (aget buf (+ a i)) y (aget buf (+ b i))]
          (if (== x y) (recur (inc i)) (if (< x y) -1 1)))))))

(defn- read-text! [^Reader r info]
  (let [n (check-count r (arg! r info) 1)
        start (.-pos r)]
    (need! r n)
    (when (.-validateUtf8 r) (validate-utf8! r start n))
    (let [s (.decode (if (.-validateUtf8 r) text-decoder replacing-decoder)
                     (.subarray (.-buf r) start (+ start n)))]
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
  (.decode (if (.-validateUtf8 r) text-decoder replacing-decoder)
           (.subarray (.-buf r) start (+ start n))))

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

;; ## Duplicate keys the host compares by identity
;;
;; A CBOR byte string decodes to a Uint8Array, and two Uint8Arrays are `=` only
;; when they are the SAME OBJECT. So `{h'01': 1, h'01': 2}` -- one key repeated,
;; which RFC 8949 5.6 makes invalid -- decoded to a TWO-ENTRY map: more entries
;; than the wire described, carrying the very duplicate the check exists to
;; reject. Every typed array from tags 77-86 has the same shape.
;;
;; The count comparisons below catch every key the host itself compares by
;; value, and they are free. They cannot catch these. The JVM had exactly this
;; gap and closed it with a content-aware probe; this is the same rule.

(defn- array-key? [x] (.isView js/ArrayBuffer x))

(defn- array-content-key
  "A string equal for exactly the array-like values CBOR calls the same data
  item.

  Built from the UNDERLYING BYTES, not from `.join` of the elements: joining a
  Float64Array renders -0.0 as \"0\" and would report two DIFFERENT data items
  as a duplicate, rejecting a valid map. Bytes are what the wire carried and
  what `java.util.Arrays.equals` compares on the JVM, so the platforms agree.

  The constructor name is part of the key: a tag-77 Int16Array and a byte
  string with the same bytes are different data items."
  [x]
  (str (.. x -constructor -name) ":"
       (.join (js/Uint8Array. (.-buffer x) (.-byteOffset x) (.-byteLength x)) ",")))

(defn- same-key?
  "Host equality first, content equality only for array-likes.

  `identical?` leads because boring's ident cache returns the SAME keyword
  object for a repeated key, so the overwhelmingly common case is a pointer
  comparison and never reaches the rest.

  IT IS ONLY A FAST PATH, NEVER A DECISION. A version of this skipped `=`
  entirely once `identical?` failed for a keyword or symbol, on the theory that
  the ident cache makes equal identifiers identical within one read. The cache
  is BOUNDED and clears WHOLESALE at IDENT-CACHE-MAX, so that theory is false
  the moment a map's values contain more than 4096 distinct identifiers between
  two occurrences of the same key: `{:a 1, :zzz <5000 keywords>, :a 2}` then
  decoded to a THREE-entry PersistentArrayMap holding `:a` twice, count 3, with
  `(get m :a)` returning the first binding. A corrupt map, not a missed error,
  and the same failure the `read-map-n!` comment describes for transit.

  ClojureScript keywords are not globally interned the way the JVM's are, so
  there is no equivalent invariant to lean on here. Measured worth of the
  shortcut: about 1% on the datom workload, i.e. inside the noise."
  [a b]
  (or (identical? a b)
      (= a b)
      ;; A BIGNUM AND A NUMBER THAT AGREE ARE ONE KEY, as they are on the JVM
      ;; where `(= 1 1N)` is true. Here `(= 1 (js/BigInt 1))` is false, so
      ;; `{1: true, 2(h'01'): false}` decoded as a TWO-entry map in a browser
      ;; and raised `:boring/duplicate-map-key` on the server -- a parser
      ;; differential in the direction `doc/COMPATIBILITY.md` does not consider,
      ;; a document a browser accepts and the JVM refuses.
      ;;
      ;; This one is a CHOICE, unlike `1` versus `1.0` and `0` versus `-0.0`:
      ;; those two JavaScript genuinely cannot tell apart, so calling them
      ;; duplicates is forced. Both platforms decode `c2 41 01` to a bignum, and
      ;; boring's own canonical rule reduces a bignum that fits to a basic
      ;; integer -- so treating them as one key is what the rest of the codec
      ;; already believes.
      (let [ba (= "bigint" (goog/typeOf a))
            bb (= "bigint" (goog/typeOf b))]
        (and (or ba bb)
             (or (not ba) (not (js/isNaN (js/Number a))))
             (or (not bb) (not (js/isNaN (js/Number b))))
             (== (js/Number a) (js/Number b))))
      (and (array-key? a) (array-key? b)
           (= (array-content-key a) (array-content-key b)))))

;; Whether an interleaved [k v k v ...] array holds a repeated key. O(n^2), but
;; n is bounded by the array-map threshold, so it is at most 28 comparisons.
(defn- dup-key? [arr n]
  (loop [i 0]
    (if (>= i n)
      false
      (let [a (aget arr (* 2 i))
            dup (loop [j (inc i)]
                  (if (>= j n)
                    false
                    (let [b (aget arr (* 2 j))]
                      (if (same-key? a b) true (recur (inc j))))))]
        (if dup true (recur (inc i)))))))

(defn- check-array-dups!
  "Reject array-like members of `xs` that are content-equal, in one pass.

  The Set is allocated on the FIRST array-like member, so a map or set without
  one -- which is nearly all of them -- pays one `ArrayBuffer.isView` per member
  and allocates nothing.

  Residual gap, stated rather than implied: a byte string NESTED inside a vector
  or map key is still compared by the host, so `{[h'01']: 1, [h'01']: 2}` keeps
  two entries. The JVM has the identical gap for the identical reason, so the
  platforms agree on what they accept; doc/SECURITY.md records it."
  [xs err-type what]
  (loop [s (seq xs) seen nil]
    (when s
      (let [x (first s)]
        (if (array-key? x)
          (let [ck (array-content-key x)
                seen (or seen (js/Set.))]
            (when (.has seen ck)
              (err err-type
                   (str "boring: duplicate " what " (byte-identical " ck ")")
                   {:key ck}))
            (.add seen ck)
            (recur (next s) seen))
          (recur (next s) seen))))))

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
    (when (.-checkDuplicateKeys r)
      (when (not= (count m) pairs)
        (err :boring/duplicate-map-key
             (str "boring: duplicate map key in an indefinite-length map of "
                  pairs " pairs")
             {:declared pairs :actual (count m)}))
      (check-array-dups! (keys m) :boring/duplicate-map-key "map key"))
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
  ;; AN EMPTY MAP COSTS A LEVEL OF `:max-depth`, because it does on the JVM --
  ;; `Reader.read` case 5 has no `n == 0` short-circuit and calls `enter()`
  ;; around an empty loop, where case 4 returns the shared empty vector first.
  ;; Skipping `enter!` here made `[{}]` decode at `:max-depth 1` in a browser
  ;; and raise `:boring/max-depth-exceeded` on the JVM for the same bytes, so a
  ;; pipeline that writes in the browser and reads on the server rejected its
  ;; own valid documents at exactly the setting doc/SECURITY.md tells an
  ;; operator to tighten. `skip-from` mirrors the same asymmetry.
  ;;
  ;; The shared `{}` singleton stays: it is the memory-amplification guard, and
  ;; it is independent of the depth charge.
  (if (zero? n) (do (enter! r) (exit! r) {}) (read-map-n! r n)))

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
    (when (and (nil? fast) (.-checkDuplicateKeys r))
      (when (not= (count m) n)
        (err :boring/duplicate-map-key
             (str "boring: duplicate map key in a map of declared size " n)
             {:declared n :actual (count m)}))
      ;; The small-map path is covered by `dup-key?`, which compares content
      ;; directly; only the transient path needs this second pass.
      (check-array-dups! (keys m) :boring/duplicate-map-key "map key"))
    m))

(defn- definite-bytes!
  "Refuse an indefinite-length byte string as a typed-array payload.

  RFC 8746 2 defines the content as a byte string whose length is a whole
  number of elements, and the JVM reads the head by hand and so has always
  required the definite form. ClojureScript routed the payload through `read!`,
  which MERGES indefinite chunks into one Uint8Array -- so
  `67(_ h'01020304' h'05060708')` decoded here and was refused there."
  [^Reader r tag]
  (need! r 1)
  (when (== 0x5f (aget (.-buf r) (.-pos r)))
    (err :boring/bad-tag-content
         (str "boring: typed-array tag " tag
              " must wrap a definite-length byte string")
         {:tag tag})))

(defn- read-typed-array! [^Reader r tag]
  (definite-bytes! r tag)
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

(defn- list-marker-content
  "A tag-27 argument that must be an ARRAY, and not merely something seqable.

  Stricter than `seq-content` on purpose, and the difference is the JVM's:
  `seqableContent` admits nil -- an empty sorted set is a sensible reading of a
  null payload -- while `listMarkerContent` does not, because a null is not an
  array. CLJS reached both through `(vec argument)`, which turns nil into `[]`
  and a string into a vector of its characters, so `27([\"java/object-array\",
  null])` decoded here and was refused there."
  [argument nm]
  (when-not (vector? argument)
    (err :boring/bad-tag-content
         (str "boring: " nm " must wrap an array, got " (pr-str argument))
         {:tag 27}))
  argument)

(defn- frame-fallback
  "What a tag-27 frame this platform cannot upgrade decodes to.

  ONE rule, used by the unregistered-name default AND by every reserved marker
  naming a JVM type ClojureScript does not have. The distinction is the payload
  shape, never the name: a map payload gets the map-presenting `UnknownRecord`,
  anything else a `TaggedLiteral`, which offers `:tag` and `:form` and promises
  no map-ness. `boring.data/frame-name` and `frame-payload` read either.

  THE MARKERS USED TO RETURN THE BARE PAYLOAD instead, on the reasoning that a
  browser is better served by `\"P1D\"` than by a wrapper it has to unpack. The
  cost was silent: re-encoding a bare string emits a plain text string, so the
  tag-27 frame was gone and a JVM peer on the far side received a `String`
  where it had sent a `java.time.Period`. Measured over the frames the JVM
  writer actually emits, seven of them lost their frame on a ClojureScript
  round trip -- `clojure/char`, `java/period`, `java/char-array`,
  `java/boolean-array`, `java/string-array`, `java/object-array` -- while the
  four whose type ClojureScript HAS were byte-identical. That is the exact
  failure doc/COMPATIBILITY.md refuses for URI, and it broke the promise
  stated two paragraphs above it: \"a round trip through ClojureScript never
  corrupts a document; it just hands back less than the JVM would.\"

  This also makes the two platforms symmetric in what they can SEND, which
  they already were and could not demonstrate: both writers encode a
  `TaggedLiteral` to its frame, so `(encode (tagged-literal 'clojure/char
  \"a\"))` produces the same bytes here as the JVM writes for a real `\\a`, and
  a JVM peer decodes it to `\\a`. Only the receive half was missing.

  NOT the place `:on-unknown-record` hooks. These names ARE known -- they are
  boring's own reserved markers -- and only the type behind them is missing on
  this platform, so a caller who asked to be told about unregistered records
  must not hear about `clojure/char`. That policy applies at the default
  branch, which is the one reached by a name nothing recognises."
  [nm argument]
  (data/frame-for nm argument))

(defn- unknown-record!
  "`frame-fallback` under the `:on-unknown-record` policy.

  Reached only for a tag-27 name nothing recognises. The default is
  `:fallback`, which is lossless passthrough and the whole reason
  `UnknownRecord` exists -- a relay must be able to carry a type it has no
  constructor for.

  `:error` exists because that passthrough hides a registration that can never
  match. When a record's wire name became `namespace/Name`, a registry still
  keyed on the old munged class name simply stopped matching, and the records
  came back as `UnknownRecord` with no error and no warning -- boring's own
  nippy suite is where that was eventually caught, by asserting equality with
  the input. A consumer who wants records or nothing can now say so."
  [^Reader r nm argument]
  (let [policy (.-onUnknownRecord r)]
    (cond
      (= :error policy)
      (err :boring/unregistered-record
           (str "boring: no record constructor registered for " (pr-str nm)
                " and :on-unknown-record is :error")
           {:tag 27 :record-name nm})

      ;; THE KEYWORDS FIRST, and not by falling through to an `ifn?` test: a
      ;; keyword IS `ifn?`, so `:fallback` would be INVOKED as a function
      ;; here. That exact confusion already shipped once on this branch --
      ;; `:date` and `:instant` were called as instant constructors and
      ;; returned nil -- and it is silent, because invoking a keyword is
      ;; legal.
      (= :fallback policy) (data/frame-for nm argument)

      (some? policy) (policy nm argument)

      :else (data/frame-for nm argument))))

(defn- sorted-content
  "`(into coll xs)` with incomparable members reported as a typed error.

  CLJS `compare` on two values of different types throws a bare JS Error, which
  walked straight out through the typed-error contract; the JVM catches the
  corresponding ClassCastException and reports `:boring/bad-tag-content`. One
  byte of wire reaches this."
  [coll xs nm]
  (try
    (into coll xs)
    (catch :default e
      (err :boring/bad-tag-content
           (str "boring: " nm " members are not mutually comparable ("
                (.-message e) ")")
           {:tag 27}))))

(defn- real-date?
  "Whether the leading `YYYY-MM-DD` of `s` is a date that exists.

  Built from the fields and read back, which is the only way to ask JavaScript:
  `js/Date` has no invalid-day error, it rolls February 30th forward to March
  1st. `setUTCFullYear` rather than `Date.UTC`, because the latter maps years
  0-99 into the 1900s and would call `0050-01-01` a different date than the one
  it was handed."
  [s]
  (let [y (js/parseInt (subs s 0 4) 10)
        m (js/parseInt (subs s 5 7) 10)
        d (js/parseInt (subs s 8 10) 10)
        dt (js/Date. 0)]
    (.setUTCFullYear dt y (dec m) d)
    (and (== y (.getUTCFullYear dt))
         (== m (inc (.getUTCMonth dt)))
         (== d (.getUTCDate dt)))))

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
  [flat typed? shape total]
  ;; ITERATIVE, deliberately -- see the note on Reader.nestDims. This recursed
  ;; once per DIMENSION, and dimensions are a flat array that :max-depth never
  ;; charged for, so a shallow 20 KB item declaring 20 000 dimensions raised a
  ;; raw RangeError under Node with no ex-data.
  (let [k (count shape)
        inner (nth shape (dec k))
        base (mapv (fn [g]
                     (mapv (fn [i] (let [x (+ (* g inner) i)]
                                     (if typed? (aget flat x) (nth flat x))))
                           (range inner)))
                   (range (quot total inner)))]
    (loop [d (- k 2) level base]
      (if (neg? d)
        (nth level 0)
        (let [len (nth shape d)]
          (recur (dec d)
                 (mapv (fn [o] (subvec level (* o len) (* (inc o) len)))
                       (range (quot (count level) len)))))))))

(defn- cbor-integer?
  "An integer as CBOR means it: a JS number with no fractional part, or a
  BigInt. `number?` alone is true of 1.5."
  [x]
  (or (= "bigint" (goog/typeOf x))
      (and (number? x) (js/Number.isInteger x))))

(def ^:private BIG-ZERO (js/BigInt 0))
(def ^:private BIG-ONE (js/BigInt 1))

(defn- ->big [x] (if (= "bigint" (goog/typeOf x)) x (js/BigInt x)))

(defn- narrow-big
  "A BigInt back to an ordinary number when it fits, which is the convention
  every other path in this reader uses -- and which the writer depends on,
  since a `js/BigInt` goes out as a bignum (tag 2/3) even when it is small."
  [b]
  (if (and (<= (js/BigInt (- js/Number.MAX_SAFE_INTEGER)) b)
           (<= b (js/BigInt js/Number.MAX_SAFE_INTEGER)))
    (js/Number b)
    b))

(defn- normalized-rational
  "Tag 30's `[numerator denominator]` reduced to lowest terms, sign on the
  numerator, and an INTEGER when the denominator reduces to 1.

  The JVM reads tag 30 through `clojure.lang.Numbers.divide`, which does all
  three, so `30([4,2])` is `2N` there and re-encodes as `02`. Here it was a
  `Rational{4,2}` that re-encoded as `d81e820402` -- so a document written by
  anyone else did not survive a decode/encode round trip on this platform, and
  `30([1,-2])` and `30([-1,2])` were two different values instead of one.

  BigInt throughout: the numerator and denominator may be bignums, and mixing
  BigInt with Number in JS is a TypeError rather than a coercion.

  THE INTEGER CASE STAYS A BigInt, deliberately, where the ratio's two halves
  are narrowed. That is not tidiness, it is byte parity: `Numbers.divide`
  yields a `clojure.lang.BigInt`, which the JVM writer emits as a bignum, so
  `30([4,2])` re-encodes as `c24102` there -- while a Ratio's numerator and
  denominator go out as plain integers, `30([1,-2])` as `d81e822002`. Narrowing
  both would have made the integer case `02` here and `c24102` there, trading
  one round-trip divergence for another."
  [n d]
  (let [bn (->big n)
        bd (->big d)
        neg? (< bd BIG-ZERO)
        bn (if neg? (- BIG-ZERO bn) bn)
        bd (if neg? (- BIG-ZERO bd) bd)
        g (loop [a (if (< bn BIG-ZERO) (- BIG-ZERO bn) bn) b bd]
            (if (<= b BIG-ZERO) a (recur b (js-mod a b))))
        bn (/ bn g)
        bd (/ bd g)]
    (if (<= bd BIG-ONE)
      bn
      (data/rational (narrow-big bn) (narrow-big bd)))))

(def ^:private DURATION-SCALES #{-3 -6 -9 -12 -15 -18})

(def ^:private MIN-I64 (js/BigInt "-9223372036854775808"))
(def ^:private MAX-I64 (js/BigInt "9223372036854775807"))
(def ^:private ZERO-N (js/BigInt 0))
(def ^:private NANOS-N (js/BigInt 999999999))

(defn- zero-n? [b] (identical? b ZERO-N))

(defn- pow10n [^number n]
  (loop [i n acc (js/BigInt 1)] (if (zero? i) acc (recur (dec i) (* acc (js/BigInt 10))))))

(defn- decimal-scale
  "Digits after the decimal point in the shortest decimal that round-trips `x`.

  The JVM's rule is `BigDecimal.valueOf(d)`, which is defined as
  `Double.toString(d)` -- the shortest round-tripping decimal. JavaScript's
  `toString` is the same shortest form, so reading the scale off it agrees with
  the JVM digit for digit. Doing this arithmetically instead does not work:
  `0.1 * 1e9` is 100000000.00000001, which would reject a base the JVM accepts."
  [x]
  (let [s (.toString (js/Math.abs x))
        parts (.split s "e")
        mant (aget parts 0)
        e (if (> (.-length parts) 1) (js/parseInt (aget parts 1) 10) 0)
        dot (.indexOf mant ".")
        frac (if (== -1 dot) 0 (- (.-length mant) dot 1))]
    (- frac e)))

(defn- as-bigint
  "`x` as a BigInt when it is a CBOR integer, else nil."
  [x]
  (cond (= "bigint" (goog/typeOf x)) x
        (and (number? x) (js/Number.isInteger x)) (js/BigInt x)
        :else nil))

(defn- duration-key
  "A tag-1002 map key as a plain JS integer, or one of `:elective` (ignore it)
  and `:critical` (reject it) when it is not one boring can implement.

  EXACT, never narrowed. A key of 2^64+1 truncated to a JS number would ALIAS
  the base key 1 and slip past the very rule that is supposed to reject it --
  the JVM had exactly that defect, found by `exactInteger`. A key too wide for
  a safe integer is by definition one boring does not implement: unsigned means
  critical (error), negative means elective (ignore).

  `nil` for a key that is not an integer at all. RFC 9581 3 groups text-string
  keys with negative ones: both are elective and both are skipped."
  [k]
  (cond
    (and (number? k) (js/Number.isInteger k)) k
    ;; A NON-INTEGRAL NUMERIC KEY IS CRITICAL, not elective. This returned nil,
    ;; which `validate-duration!` reads as "text key: elective" and skips -- so
    ;; `1002({1: 5, 1.5: 0})` was accepted here and rejected on the JVM, whose
    ;; `exactInteger` returns null for a Double and then raises "unknown
    ;; critical key". RFC 9581 3 makes only NEGATIVE INTEGER keys and TEXT
    ;; STRING keys elective; 1.5 is neither, so the unsigned rule applies.
    (number? k) :critical
    (= "bigint" (goog/typeOf k))
    (if (and (<= k (js/BigInt js/Number.MAX_SAFE_INTEGER))
             (>= k (js/BigInt (- js/Number.MAX_SAFE_INTEGER))))
      (js/Number k)
      (if (< k (js/BigInt 0)) :elective :critical))
    :else nil))

(defn- validate-duration!
  "RFC 9581 4's map rules for tag 1002, ENFORCED rather than assumed.

  ClojureScript keeps the value a `TaggedValue` -- there is no
  `java.time.Duration` here -- but \"we do not convert it\" is not a reason to
  accept a shape the JVM rejects. RFC 9581 3 makes unsigned keys CRITICAL: a
  reader that does not understand one must fail rather than ignore it, and a
  reader that admits one the other platform refuses is a parser differential
  whichever value the two sides go on to produce.

  What boring does not represent -- decimal-fraction and bigfloat bases (keys 4
  and 5) -- is refused by name rather than reported as \"no base value\".
  Refusing a conforming form we cannot carry losslessly is honest; ignoring it
  is not."
  [v]
  (let [base (volatile! nil)
        seen-base? (volatile! false)
        frac (volatile! nil)                          ; [scale value-as-BigInt]
        frac-seen? (volatile! false)]
    (doseq [k (keys v)]
      (let [kk (duration-key k)]
        (cond
          (nil? kk) nil                               ; text key: elective
          (= :elective kk) nil
          (= :critical kk)
          (err :boring/bad-tag-content
               (str "boring: tag 1002 has unknown critical key " k) {:tag 1002})
          (or (== kk 4) (== kk 5))
          (err :boring/bad-tag-content
               (str "boring: tag 1002 base key " kk " (decimal fraction /"
                    " bigfloat) is not a form boring represents") {:tag 1002})
          (== kk 1) (do (vreset! seen-base? true) (vreset! base (get v k)))
          ;; Unsigned and not the base: critical and unimplemented.
          (>= kk 0)
          (err :boring/bad-tag-content
               (str "boring: tag 1002 has unknown critical key " kk) {:tag 1002})
          (DURATION-SCALES kk)
          (do
            ;; RFC 9581 3.3: "Each extended time data item MUST NOT contain
            ;; more than one of these keys."
            (when @frac-seen?
              (err :boring/bad-tag-content
                   "boring: tag 1002 has more than one decimally scaled fraction key"
                   {:tag 1002}))
            (vreset! frac-seen? true)
            (let [fv (as-bigint (get v k))]
              (when (nil? fv)
                (err :boring/bad-tag-content
                     "boring: tag 1002 fraction must be an integer" {:tag 1002}))
              (vreset! frac [(- kk) fv])))
          ;; EVERY OTHER NEGATIVE KEY IS IGNORED, which RFC 9581 3 requires:
          ;; the extended time is still usable without what they carry. `{1: 5,
          ;; -1: 0}` -- timescale UTC, the DEFAULT -- is a conforming duration.
          :else nil)))
    (when-not @seen-base?
      (err :boring/bad-tag-content
           "boring: tag 1002 has no base value (key 1)" {:tag 1002}))
    (when-not (or (number? @base) (= "bigint" (goog/typeOf @base)))
      (err :boring/bad-tag-content
           "boring: tag 1002 base value must be a number" {:tag 1002}))
    ;; RANGE-CHECKED, because doc/COMPATIBILITY.md promises this tag is
    ;; validated to the JVM's standard and the JVM refuses a base it cannot
    ;; carry in a java.time.Duration. `1002({1: 1.0e20})` and `1002({1: 2^64})`
    ;; were both accepted here and refused there. One bound covers both: a
    ;; Duration's seconds field is a signed 64-bit count, and `1e20` is an
    ;; integral JS number well past it.
    (let [b @base]
      ;; `9223372036854775807` is not a JS number -- the literal rounds UP to
      ;; 2^63 -- so `(> b 9223372036854775807)` compared against 2^63 and let a
      ;; float base of exactly 2^63 through, which the JVM refuses. For doubles
      ;; the bound has to be `>= 2^63`, the first value past a signed 64-bit
      ;; second count that a double can actually hold.
      (when (if (= "bigint" (goog/typeOf b))
              (or (< b MIN-I64) (> b MAX-I64))
              ;; `<=` on the negative side, not `<`. A JS number this large can
              ;; only have arrived as a FLOAT -- a CBOR integer past 2^53
              ;; decodes to BigInt here -- and the JVM refuses a double base of
              ;; -2^63 for the same reason it refuses +2^63: it is the bound,
              ;; not a value inside it. The two sides of the check were not
              ;; symmetric, so one of them let a document through.
              (or (not (js/isFinite b))
                  (<= b -9223372036854775808) (>= b 9223372036854775808)))
        (err :boring/bad-tag-content
             (str "boring: tag 1002 base value " b " does not fit a 64-bit second count")
             {:tag 1002}))
      ;; NANOSECOND EXACTNESS, which the JVM enforces via
      ;; `BigDecimal.valueOf(d).movePointRight(9)` and this side did not
      ;; implement at all: `1002({1: 1e-10})` was accepted here and refused
      ;; there. A base finer than a nanosecond cannot round-trip through a
      ;; java.time.Duration, so preserving it would hand a JVM peer a document
      ;; it must reject.
      (when (and (number? b) (> (decimal-scale b) 9))
        (err :boring/bad-tag-content
             (str "boring: tag 1002 base value " b
                  " is not representable to nanosecond precision")
             {:tag 1002})))
    (when-let [[scale fv] @frac]
      ;; With a scaled fraction present, RFC 9581 requires an INTEGER base and
      ;; an UNSIGNED fraction.
      ;;
      ;; Residual, and it is a platform limit rather than a decision: JS has one
      ;; number type, so `{1: 5.0, -9: 1}` is indistinguishable from
      ;; `{1: 5, -9: 1}` here and the JVM rejects only the former. A base with
      ;; an actual fractional part is caught.
      (when-not (cbor-integer? @base)
        (err :boring/bad-tag-content
             "boring: tag 1002 base value must be an integer when a scaled fraction is present"
             {:tag 1002}))
      (when (< fv ZERO-N)
        (err :boring/bad-tag-content
             (str "boring: tag 1002 fraction " fv " must be unsigned") {:tag 1002}))
      ;; BigInt arithmetic throughout: a legal fraction at scale -18 runs to
      ;; 999999999e9, which is past `Number.MAX_SAFE_INTEGER`, so doing this in
      ;; JS numbers would lose the low digits of the very check being made.
      (if (<= scale 9)
        (when (> fv (/ NANOS-N (pow10n (- 9 scale))))
          (err :boring/bad-tag-content
               (str "boring: tag 1002 fraction " fv "e-" scale " is a second or more")
               {:tag 1002}))
        ;; -12/-15/-18 are picoseconds and finer. Accepted when they land on a
        ;; whole nanosecond, refused otherwise -- the JVM has no room below 1 ns
        ;; and silently dropping the remainder is the truncation this handler
        ;; exists to prevent, so both platforms refuse rather than differ.
        (let [div (pow10n (- scale 9))]
          (when-not (zero-n? (js-mod fv div))
            (err :boring/bad-tag-content
                 (str "boring: tag 1002 fraction " fv "e-" scale
                      " is finer than a nanosecond")
                 {:tag 1002}))
          (when (> (/ fv div) NANOS-N)
            (err :boring/bad-tag-content
                 (str "boring: tag 1002 fraction " fv "e-" scale " is a second or more")
                 {:tag 1002})))))))

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
                  ;; KEYED ON CONTENT FOR ARRAY-LIKES. `hash` of a Uint8Array
                  ;; is identity-derived, so two byte strings with the same
                  ;; bytes -- ONE CBOR data item, RFC 8949 5.6.1 -- never
                  ;; collided, and both survived into PersistentArrayMap's raw
                  ;; constructor: `39649([[h'01', h'01'], [[1, 2]]])` built a
                  ;; two-entry map per row whose keys are the same key. The JVM
                  ;; rejects it, and doc/SECURITY.md promises both platforms
                  ;; compare by CBOR data-item equality.
                _ (let [seen (js/Set.)]
                    (dotimes [i n]
                      (let [k (aget ks i)
                            h (if (array-key? k) (array-content-key k) (hash k))]
                        (when (.has seen h)
                          (dotimes [j i]
                            (when (same-key? (aget ks j) k)
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
            (when (.-checkDuplicateKeys r)
              (when (not== (count st) n)
                (err :boring/duplicate-set-element
                     (str "boring: tag 258 declared " n " elements but "
                          (count st) " are distinct") {:declared n}))
              ;; And the ones the host compares by identity, which survive the
              ;; count check as two elements: two independently allocated but
              ;; byte-equal Uint8Arrays are ONE CBOR data item.
              (check-array-dups! items :boring/duplicate-set-element "set element"))
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
        ;; AT MOST NINE FRACTION DIGITS. RFC 3339 5.6 caps `time-secfrac` at
        ;; nothing, but `Instant.parse` stops at nanoseconds, so a ten-digit
        ;; fraction was refused on the JVM and accepted here -- and then
        ;; TRUNCATED to milliseconds by `js/Date` without a word. The truncation
        ;; from four digits up is a real platform limit (a Date holds
        ;; milliseconds; doc/COMPATIBILITY.md records it), but the two
        ;; platforms must at least agree on which documents are legal.
        ;; RANGES IN THE GRAMMAR, matching Reader.RFC3339 character for
        ;; character. `\d{2}` accepted hour 24, which RFC 3339 5.6 caps at 23
        ;; and which `js/Date` silently ROLLED FORWARD to the next day -- a
        ;; wrong value out of an invalid document, on both platforms.
        (when-not (re-matches #"\d{4}-\d{2}-\d{2}[Tt]([01]\d|2[0-3]):[0-5]\d:([0-5]\d|60)(\.\d{1,9})?([Zz]|[+-]((0\d|1[0-7]):[0-5]\d|18:00))" s)
          (err :boring/bad-tag-content
               (str "boring: tag 0 content is not a valid RFC 3339 instant: " s)
               {:tag 0 :value s}))
        ;; A LEAP SECOND IS PRESERVED, NOT REJECTED. `new Date` returns Invalid
        ;; Date for :60, which sent a legal RFC 3339 timestamp down the error
        ;; path while the JVM handed back an inert tag 0 carrying the string --
        ;; a semantic differential (value here, error there) on input a real
        ;; producer emits. doc/SECURITY.md names those as their own defect
        ;; class. Both platforms now preserve.
        ;; VALIDATED FIRST, not merely recognised. This returned as soon as it
        ;; found `:60` after the second colon, before `js/Date` ever saw the
        ;; string -- so `9999-99-99T99:99:60Z` was accepted and preserved.
        ;; Preserving a legal leap second does not make an impossible month,
        ;; day, hour or minute legal. The check is the ordinary parser on a copy
        ;; with `:60` replaced by `:59`; if that fails, control falls through to
        ;; the normal path, which reports the malformed date.
        ;; THE CALENDAR, CHECKED BEFORE ANYTHING ELSE RETURNS.
        ;;
        ;; This lived below, gated on a trailing `Z`, and after the leap-second
        ;; branch had already returned -- so it caught exactly one of the three
        ;; ways an impossible day arrives. `0("2020-02-30T00:00:00+00:00")` still
        ;; decoded to 2020-03-01 and re-encoded a DIFFERENT DOCUMENT, and
        ;; `0("2020-02-30T23:59:60Z")` was preserved as an inert leap second
        ;; without the day ever being looked at.
        ;;
        ;; Checked on the FIELDS, not by rendering the parsed instant: a non-UTC
        ;; offset legitimately shifts the rendered day, which is what forced the
        ;; `Z` gate in the first place. Year, month and day are what the grammar
        ;; above already isolated, so ask whether that triple is a real date.
        (when-not (real-date? s)
          (err :boring/bad-tag-content
               (str "boring: tag 0 content is not a real calendar date: " s)
               {:tag 0 :value s}))
        (if (and (leap-second? s)
                 (not (js/isNaN (.getTime (js/Date. (.replace s ":60" ":59"))))))
          (data/tagged-value 0 s)
          (let [d (js/Date. s)]
            (when (js/isNaN (.getTime d))
              (err :boring/bad-tag-content
                   (str "boring: tag 0 content is not a valid RFC 3339 instant: " s)
                   {:tag 0 :value s}))
            ;; `:instant-type` applies to BOTH time tags. A `Date` encodes as
            ;; tag 0 by default -- RFC 3339 text -- so patching only tag 1 left
            ;; the option doing nothing on the shape boring actually writes.
            (if-let [f (.-instantFn r)] (f (.getTime d)) d))))
    1 (let [v0 (read! r)
            ;; A BIGNUM IS A NUMBER for tag 1. RFC 8949 3.4.2 says the content
            ;; is "a numerical value ... represented as an integer or a
            ;; floating-point number", and boring's own bignum handler returns a
            ;; BigInt -- so `1(2(h'01'))`, epoch second 1, decoded to an instant
            ;; on the JVM and was refused here. Whether a bignum qualifies is
            ;; arguable; the platforms disagreeing is not. Converted rather than
            ;; carried, so an out-of-range one still fails the Date check below.
            v (if (= "bigint" (goog/typeOf v0)) (js/Number v0) v0)]
        (when-not (number? v)
          (err :boring/bad-tag-content "boring: tag 1 must wrap a number" {:tag 1}))
        (let [d (js/Date. (* v 1000))]
            ;; An out-of-range epoch gives an Invalid Date rather than throwing,
            ;; so it has to be checked. The JVM raises DateTimeException here,
            ;; which fuzzing caught escaping untyped.
          (when (js/isNaN (.getTime d))
            (err :boring/bad-tag-content
                 (str "boring: tag 1 epoch out of range: " v) {:tag 1 :value v}))
          ;; `:instant-type` as a FUNCTION of epoch-millis. JavaScript has one
          ;; time type, so the JVM's `:date`/`:instant` keywords have no
          ;; counterpart -- but a caller using a cross-platform time library
          ;; (`cljc.java-time`, `tick`, both js-joda underneath) has a type they
          ;; would rather have back, and boring should not have to depend on
          ;; one to allow it. The option was accepted and silently ignored,
          ;; which is the behaviour this file's own policy says it does not do.
          (if-let [f (.-instantFn r)] (f (.getTime d)) d)))
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
          ;; THE EXPONENT IS RANGE-CHECKED, as it is on the JVM, where a
          ;; BigDecimal's scale is a 32-bit int and `Reader` refuses anything
          ;; wider. `c4 82 1a80000000 01` -- exponent 2^31 -- decoded here to a
          ;; Decimal no JVM peer can construct, so a document written in a
          ;; browser had no reading on the server. The mantissa is deliberately
          ;; NOT bounded: a bignum mantissa is exactly what tag 4 is for.
          (let [en (js/Number e)]
            (when (or (> en 2147483647) (< en -2147483648))
              (err :boring/bad-tag-content
                   (str "boring: tag 4 exponent out of range: " e) {:tag 4})))
          (data/decimal e m)))

    30 (let [l (read! r)]                           ; rational
         (when-not (and (vector? l) (== 2 (count l)))
           (err :boring/bad-tag-content
                "boring: tag 30 must wrap [numerator denominator]" {:tag 30}))
         (let [n (nth l 0) d (nth l 1)]
           (when-not (and (cbor-integer? n) (cbor-integer? d))
             (err :boring/bad-tag-content
                  "boring: tag 30 numerator and denominator must be integers" {:tag 30}))
           ;; Compared as a BigInt rather than with `zero?`, because `d` may be
           ;; one and cljs equality does not reach a BigInt primitive.
           (let [bd (->big d)]
             (when-not (or (< bd BIG-ZERO) (> bd BIG-ZERO))
               (err :boring/bad-tag-content "boring: tag 30 denominator is zero" {:tag 30})))
           (normalized-rational n d)))

      ;; RFC 8746 typed arrays, little-endian. JS has native counterparts, so
      ;; these map directly rather than through a stand-in.
    (77 78 79 85 86) (read-typed-array! r tag)

    ;; The other RFC 8746 typed arrays: VALIDATED, then preserved.
    ;;
    ;; ClojureScript decodes 5 of the 24 to a JS typed array and the JVM decodes
    ;; 21; that asymmetry is documented and deliberate. What was not documented,
    ;; and not intended, is that the other 16 fell through to the unknown-tag
    ;; path and were therefore not CHECKED either -- `64("nope")` and
    ;; `67(129)` decoded to TaggedValues here and were refused on the JVM. Over
    ;; 1600 of the differential corpus's disagreements were this one shape.
    ;;
    ;; So the shape is enforced with the JVM's rule -- a definite-length byte
    ;; string whose length is a multiple of the element size -- and the value is
    ;; preserved as a TaggedValue, which re-encodes to identical bytes. The two
    ;; platforms now accept and reject the same documents even where they build
    ;; different values from them. 76 is reserved and 83/87 are float128, which
    ;; the JVM does not implement either.
    (64 65 66 67 68 69 70 71 72 73 74 75 80 81 82 84)
    (let [_ (definite-bytes! r tag)
          bs (read! r)
          elem (case tag
                 (64 68 72) 1
                 (65 69 73 80 84) 2
                 (66 70 74 81) 4
                 (67 71 75 82) 8)]
      (when-not (instance? js/Uint8Array bs)
        (err :boring/bad-tag-content
             (str "boring: typed-array tag " tag " must wrap a byte string") {:tag tag}))
      (when-not (zero? (mod (.-byteLength bs) elem))
        (err :boring/bad-tag-content
             (str "boring: typed-array tag " tag " payload is not a multiple of " elem)
             {:tag tag}))
      (data/tagged-value tag bs))

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
         ;; GRAMMAR-CHECKED, not merely typed. `java.net.URI` refuses `"4:56"`
         ;; -- a colon appearing before any `/`, `?` or `#` makes what precedes
         ;; it a SCHEME, and RFC 3986 3.1 requires a scheme to start with a
         ;; letter -- so that document was accepted here and rejected there.
         ;; `js/URL` is not the check to use: it demands an absolute URL and
         ;; would reject the relative references RFC 3986 4.1 allows and the
         ;; JVM accepts.
         ;; CHARACTERS TOO, not only the scheme. Checking the scheme alone left
         ;; `"a b"`, `"a|b"` and `"%zz"` accepted here and rejected on the JVM.
         ;; RFC 3986 Appendix A: a URI reference is built from unreserved,
         ;; reserved and pct-encoded characters, and a `%` must introduce two
         ;; hex digits.
         ;; NON-ASCII IS ALLOWED. RFC 3986's own grammar is ASCII-only, but
         ;; `java.net.URI` accepts other characters -- it is the IRI-ish
         ;; superset every JVM peer already writes -- so restricting to RFC 3986
         ;; exactly turned `32("http://a.b/café")` into a false rejection here
         ;; while the JVM decoded it. Matching the other platform is the point;
         ;; a stricter grammar that only one side enforces is the defect this
         ;; check was added to remove.
         ;; Non-ASCII yes, but NOT Unicode whitespace or controls.
         ;; `java.net.URI` accepts `e-acute` and CJK and refuses U+00A0,
         ;; U+2000, U+3000, U+0085 and the C1 controls, so admitting all of
         ;; U+0080-U+FFFF traded eleven agreements for eleven disagreements
         ;; in the other direction.
         (when-not (re-matches (js/RegExp.
                                (str "^(?:[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=]"
                                     "|[^\\u0000-\\u009F\\u00A0\\u1680\\u2000-\\u200A"
                                     "\\u2028\\u2029\\u202F\\u205F\\u3000]"
                                     "|%[0-9A-Fa-f]{2})*$")) v)
           (err :boring/bad-tag-content
                (str "boring: tag 32 content is not a valid URI: " v)
                {:tag 32 :value v}))
         (let [i (.search v #"[/?#]")
               colon (.indexOf v ":")]
           (when (and (not= -1 colon) (or (= -1 i) (< colon i)))
             (when-not (re-matches #"[A-Za-z][A-Za-z0-9+.\-]*" (subs v 0 colon))
               (err :boring/bad-tag-content
                    (str "boring: tag 32 content is not a valid URI: " v)
                    {:tag 32 :value v}))
             ;; A SCHEME MUST BE FOLLOWED BY A NON-EMPTY SCHEME-SPECIFIC PART.
             ;; RFC 3986's own grammar allows `path-empty`, so `"a:"` parses --
             ;; but `java.net.URI` refuses it ("Expected scheme-specific part at
             ;; index 2"), and matching the other platform is what this check is
             ;; for. It was the last hole left in the approximation.
             ;;
             ;; The part ends at a `#`, not at the end of the string: the JVM
             ;; accepts `"a:?q"` and refuses `"a:#f"` and `"urn:"`. Measured
             ;; across sixteen forms rather than derived from the class's
             ;; javadoc.
             (when (== (inc colon) (let [h (.indexOf v "#" (inc colon))]
                                     (if (== -1 h) (count v) h)))
               (err :boring/bad-tag-content
                    (str "boring: tag 32 content is not a valid URI: " v)
                    {:tag 32 :value v}))))
         (data/tagged-value 32 v))

    1002 (let [v (read! r)]
           (when-not (map? v)
             (err :boring/bad-tag-content "boring: tag 1002 must wrap a map" {:tag 1002}))
           (validate-duration! v)
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
           ;; The decoded value nests once per dimension, so the dimension
           ;; COUNT is nesting and belongs to the same budget.
           (when (> (+ (.-depth r) (count dims)) (.-maxDepth r))
             (err :boring/max-depth-exceeded
                  (str "boring: tag 40 with " (count dims) " dimensions nests deeper than "
                       (.-maxDepth r))
                  {:tag 40}))
           (let [shape (mapv (fn [d]
                               ;; A BigInt is a CBOR integer but not a usable
                               ;; dimension: `(reduce * 1 shape)` then mixes
                               ;; BigInt with Number and escapes as a raw
                               ;; TypeError, straight through the typed-error
                               ;; contract.
                               (when (= "bigint" (goog/typeOf d))
                                 (err :boring/bad-tag-content
                                      "boring: tag 40 dimension exceeds the largest array this platform can build"
                                      {:tag 40}))
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
             ;; CHARGED BEFORE IT IS BUILT -- see `count-items!`. Everything
             ;; below turns one byte string into `total` host objects plus the
             ;; vectors holding them, none of which goes through `read!`.
             (count-items! r total)
             (if (and (= 2 (count shape)) typed?)
               ;; A 2-D typed array keeps the zero-copy array-of-subarrays it
               ;; has always produced; everything else becomes nested vectors.
               ;; A VECTOR of subarrays, not a raw JS Array. `make-array`
               ;; returns a JS Array, and `writer.cljs` has no case for one --
               ;; so this path decoded a document into a value boring itself
               ;; could not re-encode, which is the one shape a codec must never
               ;; produce. The rows are still `.subarray` views, so the
               ;; zero-copy property this branch exists for is unchanged.
               (let [rows (nth shape 0) cols (nth shape 1)]
                 (into [] (map (fn [i] (.subarray flat (* i cols) (* (inc i) cols))))
                       (range rows)))
               (nest-dims flat typed? shape total)))))

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
                   (sorted-content (sorted-map) argument "clojure/sorted-map"))
               "clojure/sorted-set"
               (sorted-content (sorted-set)
                               (seq-content argument "clojure/sorted-set")
                               "clojure/sorted-set")
               "clojure/queue"      (into cljs.core/PersistentQueue.EMPTY
                                          (seq-content argument "clojure/queue"))
               ;; ClojureScript has no character type -- `\a` READS as the
               ;; one-character string -- and no java.time, so these markers
               ;; name a type this platform cannot build. They are VALIDATED
               ;; here exactly as on the JVM and then handed back through
               ;; `frame-fallback`, which preserves the frame; see its
               ;; docstring for what returning the bare payload cost.
               ;;
               ;; Validation is the half that must not move. A reader that
               ;; accepts what the other platform rejects is a parser
               ;; differential, and it does not stop being one because the
               ;; value it produces here is a carrier rather than the type.
               "clojure/char"
               (do (when-not (and (string? argument) (== 1 (count argument)))
                     (err :boring/bad-tag-content
                          "boring: clojure/char must wrap exactly one character"
                          {:tag 27}))
                   (frame-fallback nm argument))
               ;; ClojureScript has no typed arrays for these and no Throwable
               ;; hierarchy. The arrays go through `frame-fallback` for the
               ;; reason above; the two exception markers do NOT, because
               ;; ClojureScript has `ex-info` and the JVM re-emits a decoded
               ;; `java/throwable` as `clojure/ex-info` too -- that pair is
               ;; already symmetric.
               ;; ELEMENT DOMAINS TOO, not merely the container shape. The JVM
               ;; builds a `boolean[]`/`String[]`, so it rejects an element that
               ;; is not one; `(vec argument)` accepted anything, and
               ;; `27(["java/boolean-array", [1]])` decoded to `[1]` here and
               ;; was refused there. A portable format has to accept and reject
               ;; the same wire shapes on both platforms even when the value it
               ;; produces on one of them is a stand-in.
               "java/boolean-array"
               (let [l (list-marker-content argument "java/boolean-array")]
                 (doseq [x l]
                   (when-not (boolean? x)
                     (err :boring/bad-tag-content
                          "boring: java/boolean-array element is not a boolean"
                          {:tag 27})))
                 (frame-fallback nm l))
               "java/char-array"
               (do (when-not (string? argument)
                     (err :boring/bad-tag-content
                          "boring: java/char-array must wrap a text string" {:tag 27}))
                   (frame-fallback nm argument))
               "java/string-array"
               (let [l (list-marker-content argument "java/string-array")]
                 (doseq [x l]
                   (when-not (or (nil? x) (string? x))
                     (err :boring/bad-tag-content
                          "boring: java/string-array element is not a string" {:tag 27})))
                 (frame-fallback nm l))
               "java/object-array"
               (frame-fallback nm (list-marker-content argument "java/object-array"))
               "clojure/ex-info"
               (do (when-not (and (vector? argument) (== 3 (count argument)))
                     (err :boring/bad-tag-content
                          "boring: clojure/ex-info must wrap [message data cause]" {:tag 27}))
                   (when-not (map? (nth argument 1))
                     (err :boring/bad-tag-content
                          "boring: clojure/ex-info data must be a map" {:tag 27}))
                   (when-not (or (nil? (nth argument 0)) (string? (nth argument 0)))
                     (err :boring/bad-tag-content
                          "boring: clojure/ex-info message must be a text string"
                          {:tag 27}))
                   (ex-info (or (nth argument 0) "") (nth argument 1)
                            ;; A non-exception cause is DROPPED, not carried:
                            ;; the JVM's `cause instanceof Throwable ? ... :
                            ;; null` does the same, and a decoded map sitting in
                            ;; `ex-cause` is not a cause on either platform.
                            (when (instance? js/Error (nth argument 2))
                              (nth argument 2))))
               "java/throwable"
               (do (when-not (and (vector? argument) (== 3 (count argument)))
                     (err :boring/bad-tag-content
                          "boring: java/throwable must wrap [class message cause]" {:tag 27}))
                   (when-not (string? (nth argument 0))
                     (err :boring/bad-tag-content
                          "boring: java/throwable class must be a text string" {:tag 27}))
                   (when-not (or (nil? (nth argument 1)) (string? (nth argument 1)))
                     (err :boring/bad-tag-content
                          "boring: java/throwable message must be a text string"
                          {:tag 27}))
                   (ex-info (or (nth argument 1) "")
                            {:boring/throwable-class (nth argument 0)}
                            (when (instance? js/Error (nth argument 2))
                              (nth argument 2))))
               ;; CANONICAL FORM ONLY -- exactly what `Period.toString()`
               ;; emits, which is the only thing boring's own writer produces.
               ;; The JVM enforces this definitionally (parse, then require the
               ;; result to print back to the input); this regex is the same
               ;; rule spelled out, and `period-domains-agree` in the
               ;; conformance suite holds the two to the same verdict.
               ;;
               ;; The old regex tracked `java.time.Period.parse`, which is far
               ;; looser -- lower case, a leading sign, per-component signs,
               ;; weeks, leading zeros -- and tracking it by hand went wrong in
               ;; BOTH directions: `p1d` was accepted on the JVM and refused
               ;; here, and `P2147483648D` was accepted HERE and refused there,
               ;; a browser admitting a document its server rejects. It could
               ;; not be fixed by widening, either: `Period` stores years,
               ;; months and days and no spelling, so the JVM cannot round-trip
               ;; `P1W` (it re-emits `P7D`), `+P1D`, `P00001D` or `P1Y0M`.
               ;; Accepting only what we can faithfully store is what makes the
               ;; two platforms agree AND every accepted document byte-stable.
               "java/period"
               (do (when-not (string? argument)
                     (err :boring/bad-tag-content
                          (str "boring: java/period must wrap a text string, got "
                               (pr-str argument))
                          {:tag 27}))
                   (let [m (re-matches
                            #"P(?:0D|(?=[-\d])(?:(-?[1-9]\d*)Y)?(?:(-?[1-9]\d*)M)?(?:(-?[1-9]\d*)D)?)"
                            argument)]
                     ;; Each component is an `int` on the JVM, so one that does
                     ;; not fit is refused rather than silently widened.
                     (when-not (and m (every? (fn [g]
                                                (or (nil? g)
                                                    (let [n (js/parseInt g 10)]
                                                      (and (>= n -2147483648)
                                                           (<= n 2147483647)))))
                                              (rest m)))
                       (err :boring/bad-tag-content
                            (str "boring: java/period is not a canonical ISO-8601 "
                                 "period: " (pr-str argument))
                            {:tag 27})))
                   (frame-fallback nm argument))
               ;; The one place `:on-unknown-record` applies: a name nothing
               ;; recognises -- not the registry, not a reserved marker.
               (unknown-record! r nm argument)))))
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
  (count-item! r)
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
  ;; A FRESH ITEM BUDGET PER TOP-LEVEL ITEM, matching the JVM. Carrying it
  ;; across items made a sequence spend one cumulative budget for the whole
  ;; file, and on the JVM made acceptance depend on the streaming chunk size.
  (set! (.-items r) 0)
  (set! (.-srStrings r) #js [])
  (set! (.-srIdents r) #js [])
  (set! (.-srActive r) false)
  (read! r))
