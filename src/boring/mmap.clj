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
  (:require [boring.nav :as nav])
  (:import (java.io File)
           (java.lang.foreign Arena MemorySegment)
           (java.nio.channels FileChannel FileChannel$MapMode)
           (java.nio.file StandardOpenOption)
           (org.replikativ.boring ByteSource)
           (org.replikativ.boring.ffm SegmentSource)))

(set! *warn-on-reflection* true)

(defn segment-source
  "Wrap a `MemorySegment` as a `ByteSource` the reader accepts."
  ^ByteSource [^MemorySegment seg]
  (SegmentSource/of seg))

(defn mmap-segment
  "Map `file` read-only into `arena`. The segment is valid until the arena
  closes."
  ^MemorySegment [file ^Arena arena]
  (let [^File f (if (instance? File file) file (File. (str file)))]
    (with-open [ch (FileChannel/open (.toPath f)
                                     (into-array StandardOpenOption
                                                 [StandardOpenOption/READ]))]
      (.map ch FileChannel$MapMode/READ_ONLY 0 (.size ch) arena))))

(defn mmap-source
  "Map `file` and return `[cursor arena]` -- a `boring.nav` cursor at the root,
  and the arena that owns the mapping.

  The caller closes the arena. Every cursor derived from this one dies with it,
  loudly. A shared arena is used rather than a confined one: it is 8% faster
  per access and does not pin the mapping to one thread.

  The file must have been written with `{:stringref false}` -- see
  `boring.nav`, which refuses a stringref document rather than resolving
  references wrongly."
  ([file] (mmap-source file nil))
  ([file opts]
   (let [arena (Arena/ofShared)
         seg (mmap-segment file arena)]
     [(nav/source (segment-source seg) opts) arena])))

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
