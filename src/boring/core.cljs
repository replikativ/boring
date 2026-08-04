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
            [boring.frame :as frame]
            [boring.options :as opt]
            [boring.reader :as rd]
            [boring.writer :as wr]))

;; :canonical selects :shortest — see the JVM boring.core for why. Deterministic
;; encoding requires the shortest float form that round-trips, so 65504.0 must
;; go out as `f97bff`. Both platforms must agree here or a signed document
;; verifies on one and not the other.
;; Profiles, validation and resolution live in `boring.options`, shared with
;; the JVM -- see that namespace for why. These two copies had already drifted:
;; the conflict check was made allocation-free on the JVM and this one still
;; built a sorted set on every encode, and `:index` capped at 2^31-1 there and
;; at MAX_SAFE_INTEGER here, so this platform could write a stride the JVM's
;; 32-bit slot arithmetic cannot read.
(def ^:private default-opts opt/default-opts)
(def ^:private resolve-opts opt/resolve-opts)

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
  ;; `check-opts`, not `resolve-opts`, matching the JVM twin and the three
  ;; other decode entry points here. Left on `resolve-opts` when the others
  ;; were converted, it produced a fresh divergence out of the change that was
  ;; meant to end them: `{:canonical true}` decoded fine through `decode` and
  ;; raised `:boring/incompatible-options` through `reader`.
  ([bs opts] (configure-reader! (rd/reader bs) (opt/check-opts opts))))

;; The `^wr/Writer` / `^rd/Reader` hints are not decoration: without them the
;; compiler cannot infer the target of these `set!`s, and every downstream
;; ClojureScript build with :infer-externs on (which is shadow-cljs's default)
;; reports ten :infer-warnings pointing into this file.
(def unencodable
  "The default `:encode-fallback` placeholder -- see the JVM core."
  opt/unencodable)

;; `max-depth-opt`, `float-policy!` and `encode-fallback-fn` used to live here,
;; each validating its option at the point the option was READ -- so whether an
;; option was checked at all depended on which code path ran.
;; `boring.options` checks every one of them, once, where every entry point
;; passes through. Everything below simply `get`s a value it knows is legal.

(defn- configure-writer! [^wr/Writer w opts]
  (set! (.-stringref w) (boolean (:stringref opts)))
  (set! (.-inclMetadata w) (boolean (get opts :incl-metadata? true)))
  (set! (.-preserveWidth w) (= :preserve-width (get opts :float-policy :preserve-width)))
  (set! (.-canonical w) (boolean (:canonical opts)))
  (set! (.-legacyCanonicalOrder w) (= :rfc7049 (:canonical-order opts)))
  (set! (.-shapes w) (boolean (:shapes opts)))
  (set! (.-registry w) (:registry opts))
  (set! (.-maxDepth w) (get opts :max-depth 1024))
  (set! (.-permitReservedSimpleValues w)
        (boolean (:permit-reserved-simple-values opts)))
  (set! (.-encodeFallback w) (opt/fallback-fn (:encode-fallback opts)))
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

(defn- bytes!
  "The input, or a typed error -- see the JVM `bytes!`. A nil reached the reader
  and surfaced as a raw host error, which doc/SECURITY.md's third guarantee
  says cannot happen."
  [bs entry]
  (when (nil? bs)
    (throw (ex-info (str "boring: " entry " needs a Uint8Array, got nil")
                    {:type :boring/bad-argument :entry entry})))
  bs)

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
  ;; platform at all. Validated in `boring.options` with everything else now,
  ;; rather than inline in the one function that happens to read it.
  (set! (.-maxItems r) (get opts :max-items 0))
  ;; ALWAYS set, never `when-let` -- a reusable reader kept the previous
  ;; call's registry. See the JVM core for the reproduction.
  (set! (.-registry r) (:registry opts))
  r)

(defn decode
  ([bs] (decode bs nil))
  ([bs opts] (rd/read! (configure-reader! (rd/reader (bytes! bs "decode"))
                                          (opt/check-opts opts)))))

(defn decode-with
  "Decode using a reusable Reader. With `opts`, every option is re-applied on
  this call; without them the reader keeps its previous configuration."
  ([r bs] (rd/reset! r (bytes! bs "decode-with")) (rd/read! r))
  ([r bs opts]
   (rd/reset! r bs)
   (configure-reader! r (opt/check-opts opts))
   (rd/read! r)))

(def ^:const index-name
  "Tag-27 type name for a sequence/container index. Must match the JVM's --
  which it does by construction now: both read it from `boring.frame`, along
  with the byte prefix derived from it."
  frame/index-name)

(defn decode-seq
  "Lazily decode consecutive top-level CBOR items (RFC 8742 sequence).

  Memory is bounded by the item being realised, not by `bs` — but `bs` itself
  is already in memory. Use `decode-seq-from` to read a source larger than the
  heap."
  ([bs] (decode-seq bs nil))
  ([bs opts]
   (let [r (configure-reader! (rd/reader (bytes! bs "decode-seq")) (opt/check-opts opts))
         ;; THE FOOTER IS NOT DECODED AT ALL, exactly as on the JVM. This used
         ;; to read the item and judge it afterwards, which had two costs. A
         ;; forged frame got materialised before anything looked at it. And the
         ;; frame's OWN fixed nesting was charged to the caller's `:max-depth`,
         ;; so a valid 500-item file this library writes on the JVM -- which
         ;; the JVM reads at `{:max-depth 3}` -- raised
         ;; `:boring/max-depth-exceeded` here at 3 and at 4. Write on the
         ;; server, fail in the browser, on boring's own default output.
         frame-at (frame/footer-start bs)]
     ((fn step []
        (lazy-seq
         (when-not (rd/at-end? r)
           (let [start (rd/position r)]
             (if (== start frame-at)
               nil
               ;; THE SHAPE CHECK AS WELL AS THE POSITION ONE, matching the JVM.
               ;; `footer-start` needs the frame's payload header to be the
               ;; literal `0x86`, so a frame whose payload is written
               ;; indefinite-length (`9f ... ff`) is not found by position --
               ;; and the JVM then catches it with `index-frame?` while this
               ;; had no second gate at all. Same bytes, one item on the JVM
               ;; and two here.
               (let [v (rd/read-next! r)]
                 (if (and (rd/at-end? r) (frame/index-frame? v start))
                   nil
                   (cons v (step)))))))))))))

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
   ;; `:chunk-size 0` sized the buffer at zero and then copied the first block
   ;; into it for a raw `RangeError: offset is out of bounds`; a negative one
   ;; threw out of the `Uint8Array` constructor. Checked in `boring.options`
   ;; now, with every other option and on both platforms.
   (let [o (opt/check-opts opts)
         block (get opts :chunk-size 65536)
         r (configure-reader! (rd/reader (js/Uint8Array. 0)) o)
         state (atom {:buf (js/Uint8Array. block) :limit 0 :last-good 0 :base 0
                      :eof? false})]
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
               (let [{:keys [buf limit last-good eof? base]} @state
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
                 ;; `base` is what earlier compactions discarded, so
                 ;; `base + last-good` is a position in the FILE. Without it
                 ;; this passed -1 to `index-frame?`, which by construction
                 ;; skips the back-pointer test -- the only check that tells
                 ;; a frame describing THIS file from one carried in from
                 ;; another. The JVM sibling was fixed; this was not.
                 (swap! state assoc :buf buf :limit new-limit :last-good 0
                        :base (+ base last-good)
                        :eof? (or eof? (nil? chunk)))
                 (rd/reset! r (.subarray buf 0 new-limit))
                 (pos? n)))
             (step []
               (lazy-seq
                (if-not (rd/at-end? r)
                  (let [;; Captured BEFORE the read: `last-good` is advanced past
                        ;; the item in the `:ok` branch below, and the frame test
                        ;; runs after that, so reading it there gives the frame's
                        ;; END. Same trap the JVM sibling fell into first.
                        item-start (+ (:base @state) (:last-good @state))
                        v (try
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
                              (condp contains? (:type (ex-data e))
                                #{:boring/truncated-input :boring/bad-count}
                                {:need-more e}
                                ;; BORING'S OWN FOOTER, as the JVM arity does.
                                ;; Without this branch the frame's own fixed
                                ;; nesting was charged to the caller's budget,
                                ;; so a valid 500-item file this library writes
                                ;; on the JVM -- which the JVM streams back at
                                ;; `{:max-depth 3}` -- raised
                                ;; `:boring/max-depth-exceeded` here.
                                ;;
                                ;; A stream has no length to check a
                                ;; back-pointer against, so the gate is END OF
                                ;; INPUT instead: the seventeen prefix bytes at
                                ;; this item's start, and nothing after it. A
                                ;; budget error is definitive -- more bytes
                                ;; cannot make a value shallower -- so deciding
                                ;; immediately is sound where retrying a
                                ;; truncation is not. Nothing is materialised.
                                #{:boring/max-depth-exceeded :boring/max-items-exceeded}
                                (if (and (frame/prefix-at? (:buf @state) (:last-good @state))
                                         (not (refill!)))
                                  {:frame true}
                                  (throw e))
                                (throw e))))]
                    (cond
                      (contains? v :frame) nil
                      (contains? v :ok)
                      ;; THE INDEX FRAME IS NOT AN ITEM HERE EITHER. `decode-seq`
                      ;; has recognised it since the cross-platform parity fix;
                      ;; this arity never did, so every JVM-written default
                      ;; sequence read through the streaming path -- the
                      ;; documented way to read a dump larger than the heap --
                      ;; came back with a phantom trailing frame: 40 items
                      ;; became 41, and an empty indexed sequence became 1.
                      ;;
                      ;; `at-end?` is checked against the buffer AND the source:
                      ;; a frame is only a footer when nothing follows it, and
                      ;; on a pull source "nothing follows" means EOF as well.
                      ;; A refill is ATTEMPTED, not a flag consulted: `:eof?`
                      ;; is only set once a pull has come back empty, which has
                      ;; not happened yet at the moment the last item is read.
                      ;; The JVM arity resolves it the same way.
                      (if (and (rd/at-end? r)
                               ;; -1, NOT the reader position. `position` is
                               ;; relative to the CURRENT BUFFER, which the
                               ;; refill above compacts, so it is only the file
                               ;; offset while the whole file fits one pull
                               ;; block. Past that the back-pointer comparison
                               ;; failed and every frame came back as an item:
                               ;; a 139 KB file read as 4001 items at the
                               ;; default 64 KiB chunk. `index-frame?`'s own
                               ;; docstring says the streaming decoder passes
                               ;; -1; this did not.
                               (frame/index-frame? (:ok v) item-start)
                               (not (refill!)))
                        nil
                        (do (swap! state assoc :last-good (rd/position r))
                            (cons (:ok v) (step))))
                      (refill!) (step)
                      :else (throw (:need-more v))))
                  (when (refill!) (step)))))]
       (refill!)
       (step)))))

(defn- reject-index-opts!
  "ClojureScript cannot WRITE an index; refuse to be asked rather than ignore it.

  `:index` and `:index-min` were read by neither this arity nor anything it
  calls, so portable `.cljc` code asking for an index got a sealed, navigable
  file on the JVM and a silently unindexed one here -- the same byte count for
  `{:index 16}`, `{:index 0}` and no options at all. A file without an index
  cannot gain one without a rewrite, so the loss is discovered much later, by
  whoever tries to `nav` into it.

  Only an EXPLICIT request throws. The JVM's `write-seq!` indexes by default, so
  a portable call with no options must keep working on both platforms; it just
  produces a plain RFC 8742 sequence here, which every reader including
  `boring.nav` still consumes."
  [opts]
  (doseq [k [:index :index-min]]
    ;; `:index 0` is the documented OFF switch, and off is what this platform
    ;; does anyway -- the JVM accepts it and writes no index. Refusing it here
    ;; on `contains?` made the one spelling that means "do not index" the one
    ;; spelling that failed, and only on one platform.
    (when (and (contains? opts k) (not (zero? (get opts k))))
      (throw (ex-info (str "boring: " k " is not supported on ClojureScript -- writing "
                           "an index is JVM-only. Omit it for a plain CBOR sequence, "
                           "or write the indexed file on the JVM.")
                      {:type :boring/unsupported-option :option k :value (get opts k)})))))

(defn write-seq!
  "Encode each value as a consecutive top-level item, appending to `sink`, a
  function of one Uint8Array. Returns the total byte count.

  **Writing an index is JVM-only.** The JVM arity takes an OutputStream and
  seals an offset index by default; this one takes a sink function and writes a
  plain RFC 8742 sequence. Passing `:index` or `:index-min` here throws
  `:boring/unsupported-option` rather than quietly producing a file that cannot
  be navigated. READING past an index written elsewhere works on both platforms
  -- see `decode-seq`.

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
   ;; CHECKED HERE TOO. The 4-arity rejected `:index`; this one did not look,
   ;; so `(write-seq! (writer 256 {:index 16}) vs sink)` returned a byte count
   ;; and a plain sequence -- the same silent loss the 4-arity exists to
   ;; prevent, reachable by moving the option from the call to the writer.
   (let [o (doto (writer-opts w) reject-index-opts!)]
     (reduce (fn [total v]
               (let [n (wr/position (write-root! w v o))]
                 (sink (.slice (wr/buffer w) 0 n))
                 (+ total n)))
             0 values)))
  ([w values sink opts]
   (reject-index-opts! opts)
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


(def ^:const default-index-stride
  "Stride used when `:index` is not given. Matches the JVM's."
  16)

;; ## Indexing an already-encoded blob
;;
;; `encode-indexed` on ClojureScript. konserve is portable and wants to offer
;; indexed access into stored blobs from either platform, so this had to stop
;; being JVM-only.
;;
;; It walks bytes rather than hooking the writer, which is why it ports at all:
;; the JVM's `build-index` does the same, and the streaming capture path
;; (`write-seq! {:index N}`, offsets recorded while encoding) is a separate and
;; much larger thing that stays JVM-only for now.
;;
;; THE WIRE FORMAT IS THE JVM'S, byte for byte -- a blob indexed here is read by
;; `boring.nav` there, which is the shape konserve needs and the acceptance test
;; that guards this code.

(def ^:private INDEX-WALK-MAX-DEPTH 200)

(defn- delta-slot
  "Entry offsets as deltas, in the narrowest CBOR form that holds them.

  Mirrors the JVM's `delta-slot` exactly, including the tier boundaries: a byte
  string for 0..255, tag 77 (sint16) to 0x7FFF, tag 78 (sint32) beyond. The tag
  IS the width declaration, so this adds no format surface -- and a reader
  cannot tell which platform produced the slot, which is the point.

  `base` is what the first delta is measured from: the container's own offset,
  or 0 for the sequence node."
  [offs base]
  (let [n (count offs)
        d (js/Array. n)]
    (loop [i 0 prev base mn js/Number.MAX_SAFE_INTEGER mx js/Number.MIN_SAFE_INTEGER]
      (if (== i n)
        (cond
          (or (zero? n) (and (>= mn 0) (<= mx 0xFF)))
          (js/Uint8Array.from d)
          (and (>= mn 0) (<= mx 0x7FFF)) (js/Int16Array.from d)
          :else (js/Int32Array.from d))
        (let [v (nth offs i) delta (- v prev)]
          (aset d i delta)
          (recur (inc i) v (min mn delta) (max mx delta)))))))

(defn- index-walk*
  "Walk the value at `p`, returning where it ends, accumulating nodes into `acc`.

  Returning the end offset is the trick the JVM version explains at length:
  calling a skip per entry would re-walk every subtree and make the scan
  quadratic in nesting depth. Here each byte is visited once.

  Tag chains are collapsed iteratively -- a tag's extent IS its payload's -- so
  a chain of them is not a stack hazard. Container nesting carries an explicit
  bound for the same reason the JVM does: this is public and runs on bytes
  somebody else wrote."
  [r p stride min-entries base acc depth]
  (when (> depth INDEX-WALK-MAX-DEPTH)
    (throw (ex-info (str "boring: nesting deeper than the index walk's bound ("
                         INDEX-WALK-MAX-DEPTH "). This document can be decoded "
                         "but not indexed.")
                    {:type :boring/max-depth-exceeded :max-depth INDEX-WALK-MAX-DEPTH})))
  (let [p (loop [q p] (if (== 6 (rd/major-at r q)) (recur (rd/head-end-at r q)) q))
        mj (rd/major-at r p)]
    (if-not (or (== mj 4) (== mj 5))
      (rd/skip-from r p)
      (let [n (rd/head-arg-at r p)
            map? (== mj 5)]
        (if (neg? n)
          (rd/skip-from r p)                    ; indefinite: not indexable
          (let [keep? (>= n min-entries)
                m (if keep?
                    (cond (<= n 0) 0
                          (== stride 1) n
                          :else (inc (quot (dec n) stride)))
                    0)
                kept (when keep? (js/Array. m))
                ;; EVERY adjacent key pair decides `sorted`, not the anchors --
                ;; see the JVM `index-walk` for why sampling the anchors returns
                ;; wrong answers. Arrays are never marked sorted: the flag is
                ;; about map key order, and `boring.nav` only consults it there.
                srt (when (and keep? map?) #js [true])
                end (loop [i 0 q (rd/head-end-at r p) prev -1]
                      (if (== i n)
                        q
                        (do (when (and keep? (zero? (rem i stride)))
                              (aset kept (quot i stride) q))
                            (when (and srt (aget srt 0) (>= prev 0)
                                       (>= (rd/compare-items-at r prev q) 0))
                              (aset srt 0 false))
                            (recur (inc i)
                                   (if map?
                                     ;; A map entry is a key AND a value, and
                                     ;; the anchor points at the key.
                                     (index-walk* r (index-walk* r q stride min-entries
                                                                 base acc (inc depth))
                                                  stride min-entries base acc (inc depth))
                                     (index-walk* r q stride min-entries base acc
                                                  (inc depth)))
                                   q))))]
            (when keep?
              (.push acc [(+ p base) n (vec kept) (boolean (and srt (aget srt 0)))]))
            end))))))

(defn build-index
  "Index nodes for the containers inside already-encoded `bs`, or nil.

  `:index` is the stride (default 16) and `:index-min` the smallest container
  worth a node (default 16). `:index-min` is the dominant size knob -- see the
  JVM docstring for the measurements."
  ([bs] (build-index bs nil))
  ([bs opts]
   ;; RESOLVED, like every other public entry point, even though nothing here
   ;; encodes -- see the JVM `build-index`.
   (let [_ (bytes! bs "build-index")
         opts (resolve-opts opts)
         stride (get opts :index default-index-stride)
         _ (when (zero? stride)
             (throw (ex-info (str "boring: :index 0 turns indexing off, which build-index "
                                  "cannot do; use `encode` instead")
                             {:type :boring/bad-option :option :index :value 0})))
         min-entries (get opts :index-min 16)
         r (rd/reader bs)
         acc (array)
         end (.-length bs)]
     (loop [p 0] (when (< p end) (recur (index-walk* r p stride min-entries 0 acc 0))))
     (when (pos? (.-length acc))
       (let [idx (vec (sort-by first (vec acc)))]
         ;; `:stride` INCLUDED. The JVM's `build-index` returns it and
         ;; `seal-index!` reads it from the index; here it was omitted and
         ;; `seal-index!` took it from its own options map instead, so the
         ;; documented public pair sealed a stride-16 frame over stride-4
         ;; slots whenever the two calls were given different options.
         {:stride stride
          :containers (mapv #(nth % 0) idx)
          :counts (mapv #(nth % 1) idx)
          :slots (mapv #(nth % 2) idx)
          :sorted (mapv #(nth % 3) idx)})))))

(defn- long->8-bytes [v]
  (let [b (js/Uint8Array. 8)]
    (loop [i 0 x v]
      (when (< i 8)
        (aset b (- 7 i) (bit-and x 0xff))
        (recur (inc i) (js/Math.floor (/ x 256)))))
    b))

(defn seal-index!
  "Append the tag-27 index frame describing `index` over `data-len` bytes.

  The frame is tag 27 wrapping [name, [stride, containers, counts, slots,
  sorted, <8-byte data-len>]] -- the same item `boring.nav` reads on the JVM. The
  trailing byte string is always exactly 8 bytes, so a sealed file ends with 9
  predictable bytes, which is how the frame is found without parsing backwards."
  [index data-len opts]
  (let [{:keys [containers counts slots sorted]} index
        stride (get opts :index default-index-stride)
        packed (vec (map-indexed (fn [i s] (delta-slot s (max 0 (nth containers i))))
                                 slots))]
    ;; TYPED ARRAYS, not plain vectors. `boring.nav/read-index*` requires
    ;; `containers` and `counts` to arrive as int arrays -- tag 78 on the wire
    ;; -- and silently IGNORES an index whose shape it does not recognise. So
    ;; the first version of this produced a frame the JVM accepted as a
    ;; trailing item and then discarded, and my acceptance test passed because
    ;; nav fell back to scanning: it proved the bytes were harmless, never that
    ;; the index was used. Same class as the vacuous budget tests.
    (encode (data/unknown-record index-name
                                 [stride (js/Int32Array.from (clj->js containers))
                                  (js/Int32Array.from (clj->js counts))
                                  packed (vec sorted)
                                  (long->8-bytes data-len)])
            (assoc (or opts {}) :stringref false))))

(defn encode-indexed
  "Encode `v` and seal an index onto it, returning a Uint8Array.

  The result is a two-item CBOR sequence -- the value, then the index -- so
  `decode` still returns the value and any CBOR reader consumes both. A JVM peer
  passes it to `boring.nav/source` and lookups inside large containers become
  jumps.

  `:stringref false` IS FORCED, exactly as on the JVM: an index records byte
  offsets, and a string reference resolves against a table built from every
  preceding string, so an offset alone cannot be decoded inside a stringref
  namespace.

  Returns the plain encoding when nothing clears `:index-min`, which is what the
  JVM does and why the result is always decodable either way."
  ([v] (encode-indexed v nil))
  ([v opts]
   (let [o (assoc (or opts {}) :stringref false)
         body (encode v o)
         idx (build-index body o)]
     (if-not idx
       body
       (let [frame (seal-index! idx (.-length body) o)
             out (js/Uint8Array. (+ (.-length body) (.-length frame)))]
         (.set out body 0)
         (.set out frame (.-length body))
         out)))))

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
