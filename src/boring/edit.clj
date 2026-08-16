(ns boring.edit
  "Edit an encoded boring value in place, without decoding the whole thing.

  `update-in` on a stored blob normally means decode the entire value, apply a
  function, and re-encode all of it. When only a leaf changes, that is almost
  all wasted work. `boring.edit` instead NAVIGATES to the target with
  `boring.nav`, re-encodes only the new value, and SPLICES it into the byte
  buffer -- ancestor container headers do not move, because a CBOR map/array
  head carries an element COUNT, not a byte length, so growing a nested value
  never invalidates the bytes that enclose it.

  Two shapes, by whether the edit changes the encoded byte length:

  - SAME LENGTH -> a `poke`: overwrite the value's bytes in place. Nothing after
    it shifts, and an offset index stays valid. Reliable only for fixed-width
    values (an i64 to another i64, a same-length string); see `poke-in-bytes`.
  - DIFFERENT LENGTH -> a `splice`: everything after the edit shifts by the
    delta. The compute is trivial (a memmove is memory-bandwidth bound); the
    only real cost is re-establishing the index and writing the result out.

  THE PROFILE IS NOT OPTIONAL. Editing requires a DETERMINISTIC, stringref-off
  profile -- `:archival` or `:canonical`. Determinism is what lets this measure
  the old value's byte length by re-encoding it (`:archival` reproduces the
  inline bytes exactly), and stringref-off is what makes a value's bytes
  independent of everything encoded before it. `boring.nav` already refuses to
  navigate a stringref document that was not indexed; editing one is worse.

  V1 SCOPE. `update-in-bytes`/`assoc-in-bytes` REPLACE the value at a path that
  already exists. Adding a key, removing a key, and array grow/shrink are
  structural edits (they change a container's count header) and land next; until
  then a missing path throws `:boring/path-absent` so a caller can fall back to
  decode/encode. The index is REBUILT by default; `:index :drop` leaves the
  value navigable only by scanning (safe -- a stale frame fails its own checks
  and `nav` falls back). Incremental index maintenance is the next optimisation."
  (:require [boring.core :as boring]
            [boring.nav :as nav]
            [boring.frame :as frame]))

(set! *warn-on-reflection* true)

(defn- data-len
  "Length of the value/data region of `blob` -- everything before a sealed index
  frame, or the whole buffer when there is none."
  ^long [^bytes blob]
  (let [f (frame/footer-start blob)]
    (if (neg? f) (alength blob) f)))

(defn- child-offset
  "Offset of `k`'s value in the container at `off`, dispatching on the CONTAINER
  type the way `clojure.core/get-in` does -- a map key (of ANY type, integer keys
  included) via `field-offset`, an array index via `nth-offset` -- rather than on
  the key's type. Returns -1 when absent. `field-offset` returns -2 for a
  non-map, which is the signal to try an array index."
  ^long [src ^long off k]
  (let [fo (long (nav/field-offset src off k))]
    (cond
      (not= fo -2) fo
      (integer? k) (long (nav/nth-offset src off (long k)))
      :else -1)))

(defn- start-of
  "Byte offset of the value at `path` under `root`, or -1 if any step is absent.
  Dispatches on container type (see `child-offset`), so a map keyed by integers
  and a vector indexed by integers both resolve correctly."
  ^long [src ^long root path]
  (loop [off root ks (seq path)]
    (if (nil? ks)
      off
      (let [nxt (child-offset src off (first ks))]
        (if (neg? nxt) -1 (recur nxt (next ks)))))))

(defn- splice-bytes
  "`src[0,s) ++ ins ++ src[e,dlen)` -- the data region only, dropping anything
  at or after `dlen` (a stale index frame)."
  ^bytes [^bytes src s e ^bytes ins dlen]
  (let [head (long s)
        tail (- (long dlen) (long e))
        out (byte-array (+ head (alength ins) tail))]
    (System/arraycopy src 0 out 0 head)
    (System/arraycopy ins 0 out head (alength ins))
    (System/arraycopy src (long e) out (+ head (alength ins)) tail)
    out))

(defn- reindex
  "A fresh sealed blob for already-encoded data, or the data unchanged when
  nothing is worth indexing."
  ^bytes [^bytes data opts]
  (if-let [ix (boring/build-index data opts)]
    (let [w (boring/writer 8192 opts)
          out (java.io.ByteArrayOutputStream. (+ (alength data) 64))]
      (.write out data)
      (boring/seal-index! w out ix (alength data))
      (.toByteArray out))
    data))

(defn- seal-onto
  "Seal a GIVEN index map onto `data`, or return `data` when the map is nil."
  ^bytes [^bytes data ix eopts]
  (if ix
    (let [w (boring/writer 8192 eopts)
          out (java.io.ByteArrayOutputStream. (+ (alength data) 64))]
      (.write out data)
      (boring/seal-index! w out ix (alength data))
      (.toByteArray out))
    data))

(defn shift-index-map
  "Shift a pre-seal index map for a LEAF-REPLACE splice at value-start `E` with
  byte delta `D`: every absolute offset strictly greater than `E` moves by `+D`,
  counts and sorted flags unchanged. The edited value starts at `E` and does not
  move; everything after it does, at any nesting depth (ancestors start before
  `E`). The result equals what `build-index` would produce on the spliced bytes
  -- byte for byte -- so `:maintain` and `:rebuild` seal identical frames.

  ONLY VALID FOR A LEAF REPLACE. A structural edit (add/remove key) re-encodes a
  container, changing its index nodes arbitrarily rather than shifting them, so
  this must not be used there."
  [ix ^long E ^long D]
  (let [sh (fn [^long o] (if (> o E) (+ o D) o))]
    (-> ix
        (assoc :containers (long-array (map sh (:containers ix))))
        (assoc :slots (mapv (fn [row] (long-array (map sh row))) (:slots ix))))))

(defn spans-index-node?
  "Whether the replaced value's OLD byte span `[E, E+old-len)` contains any
  indexed container offset. A uniform shift is only valid when it does NOT: if
  the replaced value is itself (or contains) an indexed container, its internal
  index nodes describe bytes that the new value no longer has, and shifting them
  wholesale by the value-level delta corrupts the index -- silently for arrays,
  which trust their anchors. In that case the caller must rebuild, not shift."
  [ix ^long E ^long old-len]
  (let [end (+ E old-len)]
    (boolean (some (fn [o] (let [o (long o)] (and (>= o E) (< o end))))
                   (:containers ix)))))

(defn- maintain-index
  "Re-seal `data` (the spliced bytes) with `orig-blob`'s frame shifted for a leaf
  replace at `E`/`D` -- O(index size), no data walk. Falls back to a full rebuild
  when the replaced value spanned an index node (see `spans-index-node?`), since
  a shift would corrupt those nodes. If `orig-blob` carried no frame, `data` stays
  unindexed."
  ^bytes [^bytes orig-blob ^bytes data E D old-len eopts]
  (if-let [ix (nav/frame->index-map orig-blob eopts)]
    (if (spans-index-node? ix (long E) (long old-len))
      (reindex data eopts)
      (seal-onto data (shift-index-map ix (long E) (long D)) eopts))
    data))

(defn- finish
  "Apply the `:index` policy to freshly spliced `data`. `:maintain` degrades to
  `:rebuild` here: a structural edit changed a container's shape, so there is no
  shift that preserves it -- only the leaf-replace path can maintain."
  ^bytes [^bytes data index-mode eopts]
  (case index-mode
    :drop data
    (:rebuild :maintain) (reindex data eopts)))

(defn- structural
  "Re-encode ONLY the parent container at `parent-path` after `(coll-fn parent)`,
  and splice it back. O(parent), not O(document). This is the general path for
  edits that change a container's shape -- adding or removing a key, changing an
  array's length -- where a value-level splice cannot express the new header.

  Works off the offset layer, so it dispatches on container type like the rest of
  `boring.edit` -- a parent map keyed by integers is handled the same as one
  keyed by keywords."
  ^bytes [^bytes blob parent-path coll-fn opts]
  (let [index-mode (get opts :index :rebuild)
        eopts (dissoc opts :index)
        dlen (data-len blob)
        src (nav/source blob nil)
        ps (if (seq parent-path)
             (start-of src (nav/root-offset src) parent-path)
             (nav/root-offset src))]
    (when (neg? ps)
      (throw (ex-info (str "boring.edit: no container at path " (pr-str parent-path))
                      {:type :boring/path-absent :path parent-path})))
    (let [old-parent (nav/value-at src ps)]
      (when-not (coll? old-parent)
        (throw (ex-info (str "boring.edit: value at path " (pr-str parent-path)
                             " is not a container")
                        {:type :boring/path-absent :path parent-path})))
      (let [old-len (alength ^bytes (boring/encode old-parent eopts))
            np (coll-fn old-parent)
            ins ^bytes (boring/encode np eopts)
            data (splice-bytes blob ps (+ ps old-len) ins dlen)]
        (finish data index-mode eopts)))))

(defn update-in-bytes
  "Return new bytes for `blob` with the value at `path` replaced by `(f old)`.

  `opts` MUST be the deterministic, stringref-off profile the blob was written
  with (`{:profile :archival}` or `{:profile :canonical}`). `:index` selects
  what happens to the offset frame: `:rebuild` (default) reseals a fresh one,
  `:drop` returns a bare value that navigates by scanning.

  When the FULL path resolves this is a value-level splice -- only the leaf's
  bytes are re-encoded. When the leaf is absent but its PARENT resolves, it falls
  back to re-encoding the parent (so `(update-in-bytes blob [\"m\" \"new\"] (fnil inc 0))`
  works like `clojure.core/update-in`). Throws `:boring/path-absent` only when
  the parent itself is missing."
  (^bytes [^bytes blob path f] (update-in-bytes blob path f {:profile :archival}))
  (^bytes [^bytes blob path f opts]
   (let [index-mode (get opts :index :rebuild)
         eopts (dissoc opts :index)
         dlen (data-len blob)
         src (nav/source blob nil)
         s (start-of src (nav/root-offset src) path)]
     (if (neg? s)
       ;; leaf absent -> structural update on the parent, matching update-in
       (structural blob (butlast path) #(update % (last path) f) opts)
       (let [old-val (nav/value-at src s)
             old-len (alength ^bytes (boring/encode old-val eopts))
             ins ^bytes (boring/encode (f old-val) eopts)
             d (- (alength ins) old-len)]
         (if (zero? d)
           ;; SAME LENGTH -- nothing shifts, so overwrite in place and leave any
           ;; frame untouched. Skips the splice's frame work automatically; a
           ;; caller need not know to reach for a poke.
           (let [out (aclone blob)]
             (System/arraycopy ins 0 out (int s) (alength ins))
             out)
           ;; A leaf replace only shifts bytes -- container structure is unchanged
           ;; -- so `:maintain` can move the frame's offsets instead of rebuilding.
           (let [data (splice-bytes blob s (+ s old-len) ins dlen)]
             (if (= index-mode :maintain)
               (maintain-index blob data s d old-len eopts)
               (finish data index-mode eopts)))))))))

(defn assoc-in-bytes
  "Set the value at `path` to `v`. Replaces an existing leaf by a value-level
  splice (delegating to `update-in-bytes`), or adds/inserts a missing key by
  re-encoding the parent container -- which keeps map keys in the profile's
  canonical order. See `update-in-bytes`."
  (^bytes [^bytes blob path v] (assoc-in-bytes blob path v {:profile :archival}))
  (^bytes [^bytes blob path v opts] (update-in-bytes blob path (constantly v) opts)))

(defn dissoc-in-bytes
  "Remove the key at the end of `path` from its parent map, re-encoding only that
  parent. `(dissoc-in-bytes blob [\"a\" \"b\"])` removes key \"b\" from the map at
  \"a\"."
  (^bytes [^bytes blob path] (dissoc-in-bytes blob path {:profile :archival}))
  (^bytes [^bytes blob path opts]
   (structural blob (butlast path) #(dissoc % (last path)) opts)))

(defn same-length?
  "Whether replacing the value at `path` with `v` keeps the encoded byte length
  -- i.e. whether it is a `poke` rather than a `splice`. Nil when the path is
  absent."
  [^bytes blob path v opts]
  (let [src (nav/source blob nil)
        s (start-of src (nav/root-offset src) path)]
    (when-not (neg? s)
      (= (alength ^bytes (boring/encode (nav/value-at src s) opts))
         (alength ^bytes (boring/encode v opts))))))

(def absent
  "Returned by `value-at-path` when the path does not resolve -- distinct from a
  stored `nil`, which is a real value."
  ::absent)

(defn value-at-path
  "The value at `path` in encoded `blob`, or `absent` if the path is missing.
  Reads only what the path touches; `absent` is distinct from a stored `nil`."
  [^bytes blob path opts]
  (let [src (nav/source blob (dissoc opts :index))
        s (start-of src (nav/root-offset src) path)]
    (if (neg? s) absent (nav/value-at src s))))

(defn path-offset
  "Byte offset of the value at `path` under `src` (a `boring.nav/source`), or -1
  if any step is absent. The offset-layer entry point a memory-mapped editor
  needs before reading or overwriting a value."
  ^long [src path]
  (start-of src (nav/root-offset src) path))

(defn encode-same-length
  "Encode `new-val`, requiring it to be the same byte length as `old-val`'s
  encoding; returns the new bytes or throws `:boring/not-pokeable`. The check a
  poke turns on: only an equal-length replacement can go in without a shift."
  ^bytes [old-val new-val opts]
  (let [eopts (dissoc opts :index)
        ol (alength ^bytes (boring/encode old-val eopts))
        nb ^bytes (boring/encode new-val eopts)]
    (when (not= ol (alength nb))
      (throw (ex-info "boring.edit: value would change the byte length"
                      {:type :boring/not-pokeable :old ol :new (alength nb)})))
    nb))

(defn poke-plan
  "Locate the value at `path` under `src` (a `boring.nav/source` over a byte[] or
  a ByteSource) and, if replacing it with `v` keeps the encoded byte length,
  return `{:offset <byte offset in src> :bytes <new encoded bytes>}`.

  This is the source-agnostic core of a poke: it does no IO, so a caller with a
  heap buffer and a caller with a memory-mapped segment share the same locate,
  the same length check, and the same typed refusals. Throws
  `:boring/path-absent` if `path` is missing and `:boring/not-pokeable` if the
  new value's encoding is a different length -- in which case the caller must
  splice (`update-in-bytes`) rather than overwrite."
  [src path v opts]
  (let [eopts (dissoc opts :index)
        s (start-of src (nav/root-offset src) path)]
    (when (neg? s)
      (throw (ex-info (str "boring.edit: no value at path " (pr-str path))
                      {:type :boring/path-absent :path path})))
    (let [old-len (alength ^bytes (boring/encode (nav/value-at src s) eopts))
          ins ^bytes (boring/encode v eopts)]
      (when (not= old-len (alength ins))
        (throw (ex-info "boring.edit: poke would change the byte length"
                        {:type :boring/not-pokeable :old old-len :new (alength ins)})))
      {:offset s :bytes ins})))

(defn poke-in-bytes
  "Overwrite the value at `path` with `v` IN PLACE, requiring the encoded length
  to be unchanged; returns the same `blob` array, mutated. Throws
  `:boring/not-pokeable` if the length differs (use `update-in-bytes`) and
  `:boring/path-absent` if the path is missing. Leaves any index frame valid --
  nothing shifted. See `poke-plan` for the memory-mapped counterpart."
  ^bytes [^bytes blob path v opts]
  (let [{:keys [offset ^bytes bytes]} (poke-plan (nav/source blob nil) path v opts)]
    (System/arraycopy bytes 0 blob (int offset) (alength bytes))
    blob))
