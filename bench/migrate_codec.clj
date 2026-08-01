(ns migrate-codec
  "Evidence for datahike's import/export codec question.

  alekcz/datahike#5 reworks `datahike.migrate` into a streaming, verifiable
  dump/restore, and parks ONE question for maintainers (its §13): keep CBOR, or
  move to EDN-lines? The stated case against CBOR is specific and testable:

    1. `clj-cbor` routes 0.0/NaN/+-Inf doubles through float16 and decodes them
       as `java.lang.Float`. datahike's `double?` predicate has no coercion, so
       the flipped class fails schema validation -- datahike issue #633.
    2. CBOR is not type-exact for float vs double, `byte[]`, symbols,
       `float[]`/`double[]`, or `BigInt` vs `Long`, so the PR hand-rolls
       `#datahike/float`, `/bytes`, `/farray`, `/darray`, `/symbol` tags.
    3. Dumps must be deterministic to be signable.
    4. Dumps must stream in bounded memory (their case: 1.2 GB store, 285 MB
       dump, 144 MB heap).

  Points 1 and 2 are true of clj-cbor and are the reason the PR leans
  EDN-lines. They are not true of CBOR -- they are true of that codec. This
  namespace measures all four for boring, clj-cbor and EDN-lines side by side
  so the fork is settled on numbers rather than on a general impression of the
  format.

      clojure -M:bench -m migrate-codec           # all four sections
      clojure -M:bench -m migrate-codec types     # just the type table"
  (:require [boring.core :as b]
            [clj-cbor.core :as cbor]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io FileInputStream FileOutputStream BufferedOutputStream)))

;; ---------------------------------------------------------------------------
;; 1. Type exactness -- the PR's tag table, one row per entry
;; ---------------------------------------------------------------------------

(def type-cases
  "Every row of the PR's §5.3 encoding table, plus the values that trigger
  #633. A dump codec has to return the same CLASS, not merely an `=` value:
  datahike validates with `double?`/`bytes?`/`symbol?`, which are class checks."
  [["double 1.5"     1.5]
   ["double 0.0"     0.0]
   ["double NaN"     Double/NaN]
   ["double +Inf"    Double/POSITIVE_INFINITY]
   ["double -Inf"    Double/NEGATIVE_INFINITY]
   ["float 1.5"      (float 1.5)]
   ["float 0.0"      (float 0.0)]
   ["float NaN"      (float Float/NaN)]
   ["float-array"    (float-array [1.0 2.5 3.0])]
   ["double-array"   (double-array [1.0 2.5 3.0])]
   ["ref (long)"     536870913]
   ["instant (Date)" (java.util.Date. 1700000000000)]
   ["uuid"           #uuid "b0e11a6f-0000-4000-8000-000000000001"]
   ["bigint"         123N]
   ["BigInteger"     (biginteger 123)]
   ["bigdec 1.50M"   1.50M]
   ["bytes"          (byte-array [1 2 -3])]
   ["symbol"         'foo/bar]
   ["keyword"        :db/txInstant]
   ["tuple (vector)" [1 :a "s" 2.5]]
   ["string"         "person-42"]
   ["boolean"        true]
   ["char"           \x]
   ["ratio"          2/3]])

(defn- cls [x] (if (nil? x) "nil" (.getName (class x))))

(defn- verdict
  "`:ok` or a short reason. Distinguishes CLASS drift from VALUE drift because
  they fail differently downstream: a wrong class trips schema validation at
  import, a wrong value corrupts silently."
  [enc dec x]
  (try
    (let [y (dec (enc x))]
      (cond
        (.isArray (class x)) (if (and (= (class x) (class y)) (= (seq x) (seq y)))
                               :ok
                               (str "ARRAY->" (cls y)))
        (not= (cls x) (cls y)) (str "CLASS->" (cls y))
        (and (instance? Double x) (Double/isNaN x)) (if (Double/isNaN y) :ok "VALUE")
        (and (instance? Float x) (Float/isNaN x)) (if (Float/isNaN y) :ok "VALUE")
        (not= x y) (str "VALUE " (pr-str y))
        :else :ok))
    (catch Throwable e (str "THROWS " (.getSimpleName (class e))))))

(defn types []
  (println "\n## 1. Type exactness\n")
  (println "| case | boring | clj-cbor |")
  (println "|---|---|---|")
  (let [rows (for [[nm x] type-cases]
               [nm (verdict #(b/encode %) #(b/decode %) x)
                (verdict #(cbor/encode %) #(cbor/decode %) x)])]
    (doseq [[nm bo cc] rows]
      (println (format "| %s | %s | %s |" nm bo cc)))
    (println (format "\nexact: boring %d/%d, clj-cbor %d/%d"
                     (count (filter #(= :ok (second %)) rows)) (count rows)
                     (count (filter #(= :ok (nth % 2)) rows)) (count rows))))
  (flush))

;; ---------------------------------------------------------------------------
;; 2. Determinism -- the signing requirement
;; ---------------------------------------------------------------------------

(defn- hex [^bytes a] (apply str (map #(format "%02x" %) a)))

(defn determinism []
  (println "\n## 2. Determinism (a dump you can sign)\n")
  (let [ds (vec (for [i (range 2000)]
                  {:e i :a :user/name :v (str "p-" i) :tx (+ 536870912 i) :added true}))
        o  {:profile :canonical}
        a  (b/encode ds o)]
    (println "- same value encoded twice is byte-identical:"
             (= (hex a) (hex (b/encode ds o))))
    ;; The property that matters for re-export: decode-then-encode must land on
    ;; the same bytes, or a verified dump cannot be regenerated and re-checked.
    (println "- decode then re-encode is byte-identical:"
             (= (hex a) (hex (b/encode (b/decode a) o))))
    ;; Insertion order must not leak into the bytes, for both map
    ;; implementations Clojure will hand you.
    (println "- array-map insertion order does not change the bytes:"
             (= (hex (b/encode (array-map :a 1 :b 2 :c 3) o))
                (hex (b/encode (array-map :c 3 :b 2 :a 1) o))))
    (println "- hash-map insertion order does not change the bytes:"
             (= (hex (b/encode (hash-map :a 1 :b 2 :c 3) o))
                (hex (b/encode (hash-map :c 3 :b 2 :a 1) o))))
    ;; But a sorted-map is NOT the same value as a hash map, and must not
    ;; encode identically -- boring carries sortedness, so a dump restores the
    ;; comparator rather than silently downgrading to unordered.
    (println "- a sorted-map is distinguishable from a hash map (by design):"
             (not= (hex (b/encode (sorted-map :a 1 :b 2 :c 3) o))
                   (hex (b/encode (hash-map :a 1 :b 2 :c 3) o)))))
  (flush))

;; ---------------------------------------------------------------------------
;; 3+4. Scale: size, speed, and bounded memory
;; ---------------------------------------------------------------------------

(defn datoms
  "A datom stream shaped like a real dump: mixed value types, repeated
  attribute keywords, monotonic t. `map`, not a vector -- the streaming section
  must never hold the whole thing."
  [n]
  (map (fn [i]
         (let [e (+ 100 i) t (+ 536870912 (quot i 50))]
           (case (int (mod i 6))
             0 [e :user/name (str "person-" i) t true]
             1 [e :user/age (mod i 90) t true]
             2 [e :user/score (double (/ i 7.0)) t true]
             3 [e :user/id (java.util.UUID/nameUUIDFromBytes (.getBytes (str i))) t true]
             4 [e :user/joined (java.util.Date. (+ 1700000000000 (* i 1000))) t true]
             5 [e :user/friend (+ 100 (mod i 1000)) t false])))
       (range n)))

(def ^:private edn-codec
  ;; The PR's format, approximated: one datom per line, read with a reader map.
  ;; `read-string` here is a floor on EDN's cost, not the PR's actual reader --
  ;; `clojure.edn/read-string` with tag handlers is slower, not faster, so this
  ;; comparison is generous to EDN.
  [#(.getBytes ^String (str/join "\n" (map pr-str %)))
   #(mapv read-string (str/split-lines (String. ^bytes %)))])

(defn scale [n]
  (println (format "\n## 3. Size and speed, %,d datoms in one document\n" n))
  (println "| codec | bytes | encode ms | decode ms |")
  (println "|---|---:|---:|---:|")
  (let [ds (vec (datoms n))]
    (doseq [[nm enc dec] [["boring"         #(b/encode %) #(b/decode %)]
                          ["boring :shapes" #(b/encode % {:shapes true}) #(b/decode %)]
                          ["clj-cbor"       #(cbor/encode %) #(cbor/decode %)]
                          ["EDN lines"      (first edn-codec) (second edn-codec)]]]
      (dotimes [_ 2] (dec (enc ds)))                    ; warm
      (let [t0 (System/nanoTime) bs (enc ds)
            t1 (System/nanoTime) _ (dec bs) t2 (System/nanoTime)]
        (println (format "| %s | %,d | %.0f | %.0f |" nm (count bs)
                         (/ (- t1 t0) 1e6) (/ (- t2 t1) 1e6))))))
  (flush))

(defn streaming [n]
  (println (format "\n## 4. Bounded memory, %,d datoms\n" n))
  (let [f (str "/tmp/boring-migrate-" n ".cbor")
        t0 (System/nanoTime)]
    (with-open [os (BufferedOutputStream. (FileOutputStream. f) 262144)]
      (b/write-seq! (b/writer 65536) (datoms n) os))
    (println (format "- wrote %,d bytes in %.1f s (RFC 8742 sequence, one item per datom)"
                     (.length (io/file f)) (/ (- (System/nanoTime) t0) 1e9)))
    (println (format "- max heap: %,d MiB"
                     (quot (.maxMemory (Runtime/getRuntime)) 1048576)))
    ;; `reduce`, not `count` and not `doseq` over a bound seq: anything that
    ;; retains the head turns a bounded-memory test into an OOM test that
    ;; passes only because the heap happened to be big enough.
    (let [t1 (System/nanoTime)
          [cnt sum] (with-open [is (FileInputStream. f)]
                      (reduce (fn [[c s] d] [(inc c) (unchecked-add s (long (first d)))])
                              [0 0] (b/decode-seq-from is)))]
      (println (format "- streamed back %,d datoms in %.1f s, eid checksum %d"
                       cnt (/ (- (System/nanoTime) t1) 1e9) sum))
      (println "- checksum matches the source:"
               (= sum (reduce (fn [s d] (unchecked-add s (long (first d)))) 0 (datoms n))))))
  (flush))

(defn -main [& args]
  (let [only (set args)
        all? (empty? only)]
    (println "machine:" (System/getProperty "java.vm.name")
             (System/getProperty "java.version"))
    (when (or all? (only "types"))      (types))
    (when (or all? (only "determinism")) (determinism))
    (when (or all? (only "scale"))      (scale 200000))
    ;; 5M datoms is ~200 MB on disk. Run with a deliberately small -Xmx to make
    ;; the claim mean something: `clojure -J-Xmx64m -M:bench -m migrate-codec
    ;; streaming`.
    (when (or all? (only "streaming"))  (streaming 5000000))))
