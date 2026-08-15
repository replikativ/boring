(ns boring.mmap
  "Memory-mapped CBOR files, for navigating what will not fit in the heap.

  JDK 22+ ONLY. This namespace touches `java.lang.foreign`; everything else in
  boring, including `boring.nav`, runs on JDK 9. Requiring this on an older JVM
  throws rather than degrading quietly.

  What a mapping buys, measured (`clojure -M:bench -m mmap`): random selective
  decode over a 16 MB / 200 000-item file costs 3-17% over a no-copy floor, and
  beats one `pread` per item by 2.3x. Pages you never probe are never faulted
  in, so the cost of a lookup scales with the item, not the file.

  What it does NOT buy: encoding. mmap loses to a `BufferedOutputStream` for
  sequential append, because writing touches every page while `write(2)` hands
  the kernel one prepared buffer. See doc/PERFORMANCE.md.

  Two things to know:

  - Off-heap decode costs ~1.35x heap decode -- per-access bounds and liveness
    checks that the JIT hoists out of a tight loop but not out of a recursive
    decoder. Navigation still wins by a wide margin because it touches so few
    bytes, but realising a WHOLE large subtree is cheaper staged through the
    heap: see `boring.nav/raw-bytes`.

  - Prefer a shared arena over a confined one: 1.35x versus 1.46x, because a
    confined arena adds a thread check to every access.

  The mapping lives until the arena closes. Closing it invalidates every cursor
  derived from it -- access after close throws a typed FFM error rather than
  reading freed memory, which is the property `MappedByteBuffer` never had."
  (:require [boring.nav :as nav]
            [boring.edit :as edit])
  (:import (java.io File)
           (java.lang.foreign Arena MemorySegment)
           (java.nio.channels FileChannel FileChannel$MapMode)
           (java.nio.file StandardOpenOption)
           (org.replikativ.boring ByteSource)
           (org.replikativ.boring.ffm SegmentSink SegmentSource)))

(set! *warn-on-reflection* true)

(defn segment-source
  "Wrap a `MemorySegment` as a `ByteSource` the reader accepts."
  ^ByteSource [^MemorySegment seg]
  (SegmentSource/of seg))

(defn segment-sink
  "Wrap a `MemorySegment` as an `OutputStream` the writer streams into.

  The write-side mirror of `segment-source`, and the answer to \"can boring
  encode straight into off-heap memory\": yes, through the ordinary streaming
  path, because `boring.core/write-to!` takes an `OutputStream` and this is one.
  Each chunk becomes a single bulk copy.

      (with-open [arena (java.lang.foreign.Arena/ofShared)]
        (let [seg (.allocate arena (* 64 1024 1024))
              snk (segment-sink seg)
              w (boring/writer 65536 {:stringref false})]
          (boring/write-to! w value snk {:stringref false})
          (.written snk)))                    ; a slice of exactly the bytes

  It is an `OutputStream` and not a sink type of its own because `Writer`
  compiles at `--release 9` and cannot name a `MemorySegment`. That is the
  constraint the two source sets exist to preserve, and keeping the writer's
  sink an OutputStream is what lets the streaming encoder reach off-heap memory
  without breaking it.

  WRITING TO A MAPPED SEGMENT IS USUALLY THE WRONG CHOICE -- see doc/STORAGE.md:
  appending 200 000 items costs 130 ms through a `BufferedOutputStream` and
  171 ms through a mapping, because a mapping faults per page while `write(2)`
  hands the kernel one prepared buffer. Reach for this when a native peer will
  read the bytes, or when a mapping is open anyway; not as a faster file writer."
  ^java.io.OutputStream [^MemorySegment seg]
  (SegmentSink/of seg))

(defn mmap-segment
  "Map `file` read-only into `arena`. The segment is valid until the arena
  closes."
  ^MemorySegment [file ^Arena arena]
  (let [^File f (if (instance? File file) file (File. (str file)))]
    (with-open [ch (FileChannel/open (.toPath f)
                                     (into-array StandardOpenOption
                                                 [StandardOpenOption/READ]))]
      (.map ch FileChannel$MapMode/READ_ONLY 0 (.size ch) arena))))

(defn mmap-segment-rw
  "Map `file` READ_WRITE into `arena`. The segment is valid until the arena
  closes, and writes to it go to the file's pages -- call `.force` on the
  segment (or a slice) to msync them. The file must already be at least
  `.size ch` bytes; this maps exactly the current length and does not grow it."
  ^MemorySegment [file ^Arena arena]
  (let [^File f (if (instance? File file) file (File. (str file)))]
    (with-open [ch (FileChannel/open (.toPath f)
                                     (into-array StandardOpenOption
                                                 [StandardOpenOption/READ
                                                  StandardOpenOption/WRITE]))]
      (.map ch FileChannel$MapMode/READ_WRITE 0 (.size ch) arena))))

(defn- sub-segment
  "`seg`, narrowed to `:offset`/`:length` if either is given.

  WHY THIS EXISTS. A CBOR document is not always the whole file. konserve
  writes a blob as a 20-byte header, then metadata, then the value -- so the
  value a caller wants to navigate begins partway in, and mapping from zero
  addresses the header as though it were CBOR.

  `MemorySegment.asSlice` is the whole mechanism and it already worked; what
  was missing was a way to ASK for it without reaching past this namespace
  into `asSlice` and `segment-source` by hand. `:length` defaults to the rest
  of the segment, which is the common case -- a trailing value.

  Bounds are checked here rather than left to `asSlice`, whose
  IndexOutOfBoundsException says nothing about why a caller's offset was
  wrong."
  ^MemorySegment [^MemorySegment seg {:keys [offset length]}]
  (if (and (nil? offset) (nil? length))
    seg
    (let [total (.byteSize seg)
          off   (long (or offset 0))
          len   (long (or length (- total off)))]
      (when (or (neg? off) (> off total))
        (throw (ex-info (str "boring.mmap: :offset " off " is outside the file, "
                             "which is " total " bytes")
                        {:type :boring/bad-argument :offset off :size total})))
      (when (or (neg? len) (> (+ off len) total))
        (throw (ex-info (str "boring.mmap: :offset " off " plus :length " len
                             " runs past the end of the file, which is " total
                             " bytes")
                        {:type :boring/bad-argument
                         :offset off :length len :size total})))
      (.asSlice seg off len))))

(defn mmap-source
  "Map `file` and return `[cursor arena]` -- a `boring.nav` cursor at the root,
  and the arena that owns the mapping.

  The caller closes the arena. Every cursor derived from this one dies with it,
  loudly. A shared arena is used rather than a confined one: it is 8% faster
  per access and does not pin the mapping to one thread.

  A stringref file is fine PROVIDED IT WAS INDEXED: the frame carries the
  offsets a reference resolves by jumping to. What `boring.nav` refuses is a
  document that opens a namespace and carries no such table -- one that was
  `encode`d rather than `encode-indexed`. This used to say `{:stringref false}`
  was required, which it no longer is.

  `:offset` and `:length` narrow the mapping to PART of the file, for a
  document that is not the whole of it. konserve stores a blob as a 20-byte
  header, then metadata, then the value, so navigating the value means

      (mmap-source path {:offset (+ 20 meta-size)})

  `:length` defaults to the rest of the file."
  ([file] (mmap-source file nil))
  ([file opts]
   ;; The arena is CLOSED if anything after it throws. It owns the mapping, and
   ;; the caller only learns about it through the return value -- so a failure
   ;; in `mmap-segment` (a missing file, a permissions error) or in the cursor
   ;; construction (`boring.nav` refuses a stringref document) leaked the
   ;; mapping with no handle left to close it.
   (let [arena (Arena/ofShared)]
     (try
       (let [seg (sub-segment (mmap-segment file arena) opts)]
         [(nav/root (segment-source seg) opts) arena])
       (catch Throwable t
         (.close ^java.lang.AutoCloseable arena)
         (throw t))))))

(defn mmap-items
  "Map `file` as a CBOR SEQUENCE and return `[items arena]`, where `items` is
  what `boring.nav/items` returns -- seqable, reducible, `nth`-able.

  `mmap-source` is the single-value shape: it hands back a cursor at the root,
  which is wrong for a file of many top-level items. This is the other one, and
  it is the shape a log actually has. If the sequence was sealed with an index
  (`write-seq!` with `:index N`), `nth` uses it here exactly as it does on the
  heap -- which is the combination this whole feature is for: seek into a large
  file without faulting in the pages you skipped.

  Same rules as `mmap-source`: the caller closes the arena and nothing derived
  from it may escape.

  A SEQUENCE still needs `{:stringref false}`, which is not the same claim
  `mmap-source` makes and is why this no longer says \"same rules\" about it.
  `write-seq!` resets the namespace per top-level item, so one index frame
  cannot carry a pointer table for all of them and it forces the option off.
  A single mapped DOCUMENT keeps stringref and resolves references through the
  frame -- see `mmap-source`, which used to carry this restriction and no
  longer does."
  ([file] (mmap-items file nil))
  ([file opts]
   ;; The arena is CLOSED if anything after it throws. It owns the mapping, and
   ;; the caller only learns about it through the return value -- so a failure
   ;; in `mmap-segment` (a missing file, a permissions error) or in the cursor
   ;; construction (`boring.nav` refuses a stringref document) leaked the
   ;; mapping with no handle left to close it.
   (let [arena (Arena/ofShared)]
     (try
       (let [seg (sub-segment (mmap-segment file arena) opts)]
         [(nav/items (segment-source seg) opts) arena])
       (catch Throwable t
         (.close ^java.lang.AutoCloseable arena)
         (throw t))))))

(defmacro with-mmap
  "Map `file`, bind `binding` to a root cursor, and close the arena after.

      (with-mmap [c \"data.cbor\"]
        (nav/value (get-in c [\"customer-137\" \"name\"])))

  Do not let a cursor escape the body: the mapping is gone, and touching one
  afterwards throws."
  [[binding file & [opts]] & body]
  `(let [[c# arena#] (mmap-source ~file ~opts)]
     (with-open [a# arena#]
       (let [~binding c#]
         ~@body))))

(defn poke!
  "Overwrite the value at `path` inside a memory-mapped CBOR document IN PLACE,
  requiring the new value `v` to encode to the SAME byte length. This is the one
  edit that needs no rewrite: nothing shifts, so any offset index stays valid and
  only the touched pages are dirtied -- a field update in a gigabyte file costs a
  page write, not a re-encode.

  Opens `file` READ_WRITE, writes the new bytes at the located offset, and
  msyncs (`.force`). `:offset`/`:length` narrow to a value that is not the whole
  file -- a konserve blob's value sits past its 20-byte header and metadata, so
  `(poke! path v (assoc opts :offset (+ 20 meta-size)))`. `opts` must be the
  deterministic, stringref-off profile the value was written with.

  Throws `:boring/not-pokeable` if the new encoding is a different length (splice
  with `boring.edit/update-in-bytes` instead) and `:boring/path-absent` if the
  path is missing; in both cases the file is left untouched. Returns the number
  of bytes written.

  DURABILITY. `.force` msyncs the dirtied pages, but an in-place overwrite is not
  torn-write safe on its own -- a crash mid-write can leave a value half-updated.
  Use this where a single writer holds the file and either the value fits a
  sector-atomic write or a higher layer can detect and recover (see the
  durability modes in `konserve.mmap`)."
  ^long [file path v opts]
  (let [eopts (dissoc opts :offset :length)
        arena (Arena/ofShared)]
    (try
      (let [seg (sub-segment (mmap-segment-rw file arena) opts)
            {:keys [offset ^bytes bytes]}
            (edit/poke-plan (nav/source (segment-source seg) eopts) path v eopts)]
        (MemorySegment/copy (MemorySegment/ofArray bytes) 0 seg (long offset) (alength bytes))
        (.force seg)
        (alength bytes))
      (finally (.close ^java.lang.AutoCloseable arena)))))

(defn poke-update!
  "Apply `f` to the value at `path` in a memory-mapped document and, if `(f old)`
  encodes to the SAME byte length, overwrite it in place and msync -- returning
  `[old new]`. One mapping does the read and the write, so a same-length update
  (a counter, a status flag) never reads the rest of the value.

  Throws `:boring/not-pokeable` when the result is a different length (the caller
  splices instead) and `:boring/path-absent` when `path` is missing; the file is
  untouched in both cases. `:offset`/`:length` and `opts` are as for `poke!`."
  [file path f opts]
  (let [eopts (dissoc opts :offset :length)
        arena (Arena/ofShared)]
    (try
      (let [seg (sub-segment (mmap-segment-rw file arena) opts)
            src (nav/source (segment-source seg) eopts)
            off (edit/path-offset src path)]
        (when (neg? off)
          (throw (ex-info (str "boring.mmap: no value at path " (pr-str path))
                          {:type :boring/path-absent :path path})))
        (let [old (nav/value-at src off)
              nv (f old)
              nb ^bytes (edit/encode-same-length old nv eopts)]
          (MemorySegment/copy (MemorySegment/ofArray nb) 0 seg (long off) (alength nb))
          (.force seg)
          [old nv]))
      (finally (.close ^java.lang.AutoCloseable arena)))))
