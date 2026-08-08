(ns ^:no-doc boring.frame
  "Recognising boring's own trailing index frame — once, for both platforms.

  Not part of the public API.

  ## Why this is shared

  \"Is this boring's index footer\" had FOUR implementations. `footer-start`
  checked the 17 constant prefix bytes, the back-pointer's range, and that the
  frame reaches EOF. `frame-prefix-at?` checked the prefix alone.
  `index-frame?` decoded the item and checked its shape. `boring.nav`'s
  `read-index*` had its own. Only the JVM had the first two at all —
  ClojureScript had `index-frame?` and nothing else.

  Each weaker copy has cost something real:

  - The version that checked only the prefix and the pointer range, but not
    that the frame ends at EOF, made `decode-seq` return **40 of 82 items**
    from two concatenated sealed batches of equal length. No error. The second
    batch's pointer named an offset inside the FIRST batch that also carried
    the prefix.
  - The version before that accepted any file whose byte at n-9 was `0x48`, so
    nine appended bytes aimed the gate at offset 0 and whatever item lived
    there was re-read with the budgets lifted — 473 MB from a 3 MB item.
  - Having only the value-level check on ClojureScript meant the frame had to
    be DECODED to be recognised, and its own fixed nesting was charged to the
    caller's `:max-depth`. A valid 500-item file written by this library on the
    JVM, which the JVM reads at `{:max-depth 3}`, raised
    `:boring/max-depth-exceeded` on ClojureScript at both 3 and 4.

  ## The two levels, and when each applies

  `footer-start` is the strong one and the one to prefer: it decodes nothing,
  so a forged frame cannot allocate anything, and it can check the EOF property
  that the others only approximate. It needs random access and a file length.

  `index-frame?` is the weak one, and exists for `decode-seq-from`, which has
  neither — a stream has no length, and its buffer positions move under
  refills. It is a shape check on an already-decoded value, so a caller
  relying on it must gate it on end-of-input separately."
  (:require [boring.data :as data]
            #?(:cljs [boring.reader :as rd]))
  #?(:clj (:import (org.replikativ.boring Reader))))

(def ^:const index-name
  "Tag-27 type name for a sequence/container index."
  "boring/index")

;; ------------------------------------------------------------ byte access
;;
;; The two host byte containers differ in exactly two ways: how you ask for the
;; length, and whether an element comes back signed. Both are settled here, so
;; nothing below is platform-specific.

(defn- blen ^long [bs]
  #?(:clj (alength ^bytes bs) :cljs (.-length bs)))

(defn- ubyte ^long [bs ^long i]
  #?(:clj (bit-and (aget ^bytes bs i) 0xff) :cljs (aget bs i)))

(defn- ends-at
  "Where the item at `p` ends, walking heads only — never building a value."
  ^long [bs ^long p]
  #?(:clj (.skipFrom (Reader. ^bytes bs) p)
     :cljs (rd/skip-from (rd/reader bs) p)))

;; ------------------------------------------------------------ the prefix

(def prefix-bytes
  "The exact bytes every sealed frame starts with: tag 27, a two-element array,
  the text string `boring/index`, and the six-element payload array.

  A CONSTANT, compared before anything is decoded. That is the whole point:
  the gates that preceded it judged the frame only AFTER materialising it, and
  materialising a forged frame is what a forged frame is for."
  [0xd8 0x1b 0x82 0x6c 0x62 0x6f 0x72 0x69 0x6e 0x67
   0x2f 0x69 0x6e 0x64 0x65 0x78 0x86])

(def ^:const prefix-length 17)

(def ^:const prefix-head-length
  "The prefix bytes that are FIXED: everything but the payload's array head."
  16)

(def payload-count-bytes
  "Array heads a frame's payload may carry: 6 through 15 elements.

  SIX IS WHAT THIS LIBRARY WRITES, and every reader here uses only the first
  six. Accepting more is forward compatibility, and the reason it matters is
  that the failure mode of NOT accepting it is the worst one available: a
  reader that does not RECOGNISE a frame never learns `data-end`, so the frame
  is republished as a trailing DATA item and a file of N records reads back as
  N+1 -- silently, and in both directions. Refusing to USE an index is safe;
  refusing to SEE one is not.

  So a reader from this version on treats a widened frame as a frame: bounded,
  its index used or refused on its own merits, never mistaken for data. The
  elements past the sixth are skipped, which costs nothing -- `payload-offsets`
  chains `skipFrom` and never visits them, and the tile-to-EOF check walks the
  whole array regardless.

  Fifteen is where the CBOR array head stops being one byte. Past that the
  prefix changes length and this stops being a constant comparison, which is
  the property the gate exists for."
  #{0x86 0x87 0x88 0x89 0x8a 0x8b 0x8c 0x8d 0x8e 0x8f})

#?(:clj
   (def ^:no-doc prefix-head-array
     "The fixed 16 bytes as a `byte[]`, for `Reader.bytesEqualAt`. The
     seventeenth is compared against `payload-count-bytes` separately."
     (byte-array (map unchecked-byte (take prefix-head-length prefix-bytes)))))

#?(:clj
   (def ^:no-doc prefix-array
     "The same constant as a `byte[]`, for callers that reach bytes through a
     `Reader` rather than an array -- `boring.nav`, which must also work over a
     memory-mapped `ByteSource`.

     One constant, two access paths, so the rule cannot differ between them.
     It differed before: `nav` checked tag 27, then that the payload was an
     array, then the name -- but never the array's ELEMENT COUNT. Widen a
     genuine frame's payload from six elements to seven and `nav` used it as an
     index while `decode-seq` published it as a phantom trailing data item. One
     file, two logical contents. The 0x86 in these bytes is that count."
     (byte-array (map unchecked-byte prefix-bytes))))

(defn- be64
  "The 8 big-endian bytes at `off` as a number.

  PLATFORM-SPLIT, and the split is the point. `bit-shift-left` is 32-BIT on
  ClojureScript, so the shift form -- correct on the JVM -- truncated the
  pointer there BEFORE the range test the code below says \"rejects a nonsense
  pointer\" ever saw it. Measured over an exhaustive single-byte sweep of a
  sealed file: 20 cases where both platforms decoded successfully and disagreed
  about how many items the file holds. One file, two logical contents.

  Multiplication is exact on ClojureScript to 2^53, which is far past any file
  length; on the JVM it is CHECKED arithmetic and a pointer with the high bit
  set would raise a raw ArithmeticException out of the function whose whole job
  is deciding whether to trust these bytes -- which is why that side keeps the
  shifts. Anything above 2^53 fails the range test as a nonsense pointer, which
  is what it is."
  ^long [bs ^long off]
  (loop [i 0 acc 0]
    (if (= i 8)
      acc
      (recur (inc i)
             #?(:clj (bit-or (bit-shift-left acc 8) (ubyte bs (+ off i)))
                :cljs (+ (* acc 256) (ubyte bs (+ off i))))))))

(defn prefix-at?
  "Whether `bs` carries the frame prefix at `off`.

  Sixteen bytes exactly, then an array head of six THROUGH FIFTEEN elements --
  see `payload-count-bytes` for why the count is a range and not a constant."
  [bs ^long off]
  (and (some? bs) (>= off 0) (<= (+ off prefix-length) (blen bs))
       (loop [i 0]
         (cond (= i prefix-head-length)
               (contains? payload-count-bytes (ubyte bs (+ off prefix-head-length)))
               (= (ubyte bs (+ off i)) (long (nth prefix-bytes i))) (recur (inc i))
               :else false))))

;; ------------------------------------------------------------ the strong gate

(defn footer-start
  "Where a genuine index footer begins in `bs`, or -1.

  `seal-index!` ends every sealed file with a byte string of exactly 8 bytes --
  `0x48` and the offset the frame itself starts at -- so the footer announces
  its own position, and that position is checkable without decoding anything.
  Reading it here, once, is what lets a decoder know which item is the footer
  BEFORE it tries to read it."
  ^long [bs]
  (let [n (blen bs)]
    (if (or (< n 9) (not= 0x48 (ubyte bs (- n 9))))
      -1
      (let [p (be64 bs (- n 8))]
        ;; Three conditions, and the third is the one that keeps being dropped.
        ;; The pointer doubles as the length of the data section, so it must
        ;; land inside the file and leave room for the frame it names -- and
        ;; THE FRAME MUST END AT THE FILE'S END. Prefix plus pointer is not
        ;; enough: concatenate two sealed batches of equal length and the
        ;; second file's pointer names an offset inside the FIRST batch that
        ;; also carries the prefix, so `decode-seq` stopped there and returned
        ;; 40 of 82 items with no error, while `decode-seq-from`, `nav/items`,
        ;; a bare Reader loop and ClojureScript all returned 82.
        (if (and (>= p 0) (< p (- n 9)) (prefix-at? bs p) (= n (ends-at bs p)))
          p
          -1)))))

;; ------------------------------------------------------------ the weak gate

(defn index-frame?
  "True for the tag-27 frame `seal-index!` appends, at offset `start`.

  For `decode-seq-from` only -- see the namespace docstring. Everything with
  random access should use `footer-start`, which decides the same question
  without materialising anything.

  Both fallback shapes count: the payload is an array, so it may decode to a
  tagged literal rather than an unknown record, and `frame-name` reads either.

  AUTHENTICITY, not just the name. This once tested the name alone, so ANY
  final tag-27 item called `boring/index` was silently erased from a sequence
  -- including a malformed one, and including one somebody put there as data. A
  name collision must not delete a logical item.

  `start` of -1 means the caller cannot supply an offset (the streaming
  decoder, whose positions are buffer-relative across refills); the shape check
  still applies."
  [v ^long start]
  (and (data/tagged-frame? v)
       (= index-name (data/frame-name v))
       (let [p (data/frame-payload v)]
         (and (sequential? p)
              ;; AT LEAST SIX, and no more than fifteen. `index-payload`
              ;; destructured six names off this without checking the count at
              ;; all, so a seven-element payload was used as an index there and
              ;; published as a phantom trailing data item here -- one file,
              ;; two logical contents. The bound is a RANGE rather than an
              ;; equality so that a future widening is recognised by readers
              ;; from this version on; see `payload-count-bytes`.
              (<= 6 (count p) 15)
              ;; THE POINTER IS LAST, not element 5, and that is forced rather
              ;; than chosen: the trailer this whole scheme is found by is the
              ;; file's final 9 bytes, so the element encoded last must be the
              ;; 8-byte back-pointer. A widened payload therefore inserts its
              ;; new elements BEFORE it. Reading `(nth p 5)` here would refuse
              ;; exactly the frames `prefix-at?` now accepts, and the two gates
              ;; disagreeing about what a frame is, is the defect this test
              ;; family exists for.
              (let [ptr (nth (vec p) (dec (count p)))]
                (and #?(:clj (bytes? ptr) :cljs (instance? js/Uint8Array ptr))
                     (= 8 (blen ptr))
                     (or (neg? start)
                         (= start (be64 ptr 0)))))))))
