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

(defn- start-of
  "Byte offset of the value at `path` under `root`, or -1 if any step is absent.
  Integer path elements index arrays (`nth-offset`); everything else is a map
  key (`field-offset`)."
  ^long [src ^long root path]
  (loop [off root ks (seq path)]
    (if (nil? ks)
      off
      (let [k (first ks)
            nxt (long (if (integer? k)
                        (nav/nth-offset src off (long k))
                        (nav/field-offset src off k)))]
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

(defn- finish
  "Apply the `:index` policy to freshly spliced `data`."
  ^bytes [^bytes data index-mode eopts]
  (case index-mode
    :drop data
    :rebuild (reindex data eopts)))

(defn- parent-cursor
  "The cursor at `parent-path` (empty path -> the root cursor), or nil when the
  path does not resolve to a container that can be re-encoded."
  [^bytes blob parent-path]
  (reduce (fn [c k]
            (if (nav/container? c)
              (if (integer? k) (nth c k nil) (get c k))
              (reduced nil)))
          (nav/root blob) parent-path))

(defn- structural
  "Re-encode ONLY the parent container at `parent-path` after `(coll-fn parent)`,
  and splice it back. O(parent), not O(document). This is the general path for
  edits that change a container's shape -- adding or removing a key, changing an
  array's length -- where a value-level splice cannot express the new header."
  ^bytes [^bytes blob parent-path coll-fn opts]
  (let [index-mode (get opts :index :rebuild)
        eopts (dissoc opts :index)
        pc (parent-cursor blob parent-path)]
    (when-not (nav/container? pc)
      (throw (ex-info (str "boring.edit: no container at path " (pr-str parent-path))
                      {:type :boring/path-absent :path parent-path})))
    (let [[ps pe] (nav/byte-span pc)
          np (coll-fn (nav/value pc))
          ins ^bytes (boring/encode np eopts)
          data (splice-bytes blob ps pe ins (data-len blob))]
      (finish data index-mode eopts))))

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
             ins ^bytes (boring/encode (f old-val) eopts)]
         (finish (splice-bytes blob s (+ s old-len) ins dlen) index-mode eopts))))))

(defn assoc-in-bytes
  "Set the value at `path` to `v`. Replaces an existing leaf by a value-level
  splice, or adds/inserts by re-encoding the parent container (which keeps map
  keys in the profile's canonical order). See `update-in-bytes`."
  (^bytes [^bytes blob path v] (assoc-in-bytes blob path v {:profile :archival}))
  (^bytes [^bytes blob path v opts]
   (let [src (nav/source blob nil)
         s (start-of src (nav/root-offset src) path)]
     (if (neg? s)
       (structural blob (butlast path) #(assoc % (last path) v) opts)
       (update-in-bytes blob path (constantly v) opts)))))

(defn dissoc-in-bytes
  "Remove the key at the end of `path` from its parent map, re-encoding only that
  parent. `(dissoc-in-bytes blob [\"a\" \"b\"])` removes key \"b\" from the map at
  \"a\"."
  (^bytes [^bytes blob path] (dissoc-in-bytes blob path {:profile :archival}))
  (^bytes [^bytes blob path opts]
   (structural blob (butlast path) #(dissoc % (last path)) opts)))

(defn assoc-in-bytes
  "Replace the value at an existing `path` with `v`. See `update-in-bytes`."
  (^bytes [^bytes blob path v] (assoc-in-bytes blob path v {:profile :archival}))
  (^bytes [^bytes blob path v opts] (update-in-bytes blob path (constantly v) opts)))

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
