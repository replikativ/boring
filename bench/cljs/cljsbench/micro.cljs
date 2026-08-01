(ns cljsbench.micro
  "The ClojureScript micro-benchmarks behind doc/PERFORMANCE.md's CLJS section.

  One namespace rather than a file per question, because each is a few lines
  and the interesting part is the answer. Run:

      clojure -M:cljs-compare -m cljs.main -co '{:language-in :ecmascript-next}' \\
        -O advanced -t node -o target/micro.js -c cljsbench.micro
      node target/micro.js

  Three of the four sections are NEGATIVE results, kept on purpose: they are
  the ideas that look obviously right and are not, and without them written
  down they get retried.")
(enable-console-print!)

(defn now [] (js/performance.now))
(defn bench [nm f]
  (dotimes [_ 30000] (f))
  (loop [it 2000]
    (let [t0 (now) _ (dotimes [_ it] (f)) dt (- (now) t0)]
      (if (< dt 250) (recur (* it 4))
          (println "   " (.padEnd nm 38) (.toFixed (/ (* dt 1e6) it) 2) "ns")))))

;; ---------------------------------------------------------------- collections
;; The one that PAID, and it came from reading transit-js. transit builds maps
;; of <= 8 pairs and vectors of <= 32 elements from a plain JS array, falling
;; back to transients above that (transit-js/src/com/cognitect/transit/impl/
;; decoder.js:255,317). boring used transients at every size in its generic
;; path -- while its own shaped-array path had already found the direct build.
;; Both thresholds matter: at 200 elements fromArray is SLOWER, because above
;; one tail node it rebuilds a tree that conj! was filling incrementally.
(def ^:private ks [:e :a :v :tx :added])
(def ^:private vs [100 :user/name "person-1" 536870912 true])

(defn- fill-map! [arr]
  (loop [i 0] (when (< i 5)
                (aset arr (* 2 i) (nth ks i))
                (aset arr (inc (* 2 i)) (nth vs i))
                (recur (inc i))))
  arr)

;; ---------------------------------------------------------------- dispatch
;; NEGATIVE. `(declare read!)` gives the analyzer no :fn-var, so all 30
;; recursive call sites compile to
;;   f.cljs$core$IFn$_invoke$arity$1 ? f.cljs$core$IFn$_invoke$arity$1(r)
;;                                   : f.call(null, r)
;; where `arg!` -- defined before use -- gets a direct `f(r)`. On the hottest
;; call in the decoder that looks like an obvious win. It is worth ~1%: V8
;; folds the property load and the branch away. Not worth restructuring a
;; mutually recursive reader for.
(def ^:private nested (js/Array. 8))
(dotimes [i 8] (aset nested i (let [a (js/Array. 8)] (dotimes [j 8] (aset a j j)) a)))

(declare walk-decl)
(defn- walk-decl [x]
  (if (array? x)
    (loop [i 0 s 0] (if (< i (alength x)) (recur (inc i) (+ s (walk-decl (aget x i)))) s))
    x))
(defn- walk-direct [x]
  (if (array? x)
    (loop [i 0 s 0] (if (< i (alength x)) (recur (inc i) (+ s (walk-direct (aget x i)))) s))
    x))

;; ---------------------------------------------------------------- byte access
;; NEGATIVE in the sense that boring already does the right thing. V8's 2018
;; DataView rewrite (v8.dev/blog/dataview) made DataView beat a Uint8Array
;; wrapper for multi-byte reads, and that still holds: single bytes tie, but
;; getUint32 is 1.9x a manual shift. boring reads single bytes with `aget` and
;; everything wider through the DataView, which is the measured optimum.
(def ^:private N 4096)
(def ^:private buf (js/Uint8Array. N))
(dotimes [i N] (aset buf i (bit-and i 0xFF)))
(def ^:private dv (js/DataView. (.-buffer buf)))

(defn -main [& _]
  (println "node" js/process.version "\n")

  (println "collection construction — 5-entry keyword-keyed map")
  (bench "transient {} + assoc!"
         #(loop [i 0 acc (transient {})]
            (if (< i 5) (recur (inc i) (assoc! acc (nth ks i) (nth vs i)))
                (persistent! acc))))
  (bench "fromArray, no dup check (transit)"
         #(cljs.core/PersistentArrayMap.fromArray (fill-map! (make-array 10)) true true))

  (println "\ncollection construction — vectors")
  (let [src (vec (range 20))]
    (bench "20 elems: transient [] + conj!"
           #(loop [i 0 acc (transient [])]
              (if (< i 20) (recur (inc i) (conj! acc (nth src i))) (persistent! acc))))
    (bench "20 elems: PersistentVector.fromArray"
           #(let [arr (make-array 20)]
              (loop [i 0] (when (< i 20) (aset arr i (nth src i)) (recur (inc i))))
              (cljs.core/PersistentVector.fromArray arr true))))
  (let [src (vec (range 200))]
    (bench "200 elems: transient [] + conj!"
           #(loop [i 0 acc (transient [])]
              (if (< i 200) (recur (inc i) (conj! acc (nth src i))) (persistent! acc))))
    (bench "200 elems: fromArray (worse!)"
           #(let [arr (make-array 200)]
              (loop [i 0] (when (< i 200) (aset arr i (nth src i)) (recur (inc i))))
              (cljs.core/PersistentVector.fromArray arr true))))

  (println "\ncall dispatch — recursive walk, 8x8")
  (bench "self-recursive (direct call)" #(walk-direct nested))
  (bench "via declare (IFn dispatch)" #(walk-decl nested))

  (println "\nbyte access — one pass over 4096 bytes")
  (bench "u8: aget Uint8Array (boring)"
         #(loop [i 0 s 0] (if (< i N) (recur (inc i) (+ s (aget buf i))) s)))
  (bench "u8: DataView.getUint8"
         #(loop [i 0 s 0] (if (< i N) (recur (inc i) (+ s (.getUint8 dv i))) s)))
  (bench "u32: DataView.getUint32 (boring)"
         #(loop [i 0 s 0] (if (< i N) (recur (+ i 4) (+ s (.getUint32 dv i))) s)))
  (bench "u32: manual shift on Uint8Array"
         #(loop [i 0 s 0] (if (< i N)
                            (recur (+ i 4)
                                   (+ s (+ (* (aget buf i) 16777216)
                                           (bit-shift-left (aget buf (+ i 1)) 16)
                                           (bit-shift-left (aget buf (+ i 2)) 8)
                                           (aget buf (+ i 3))))) s))))
(set! *main-cli-fn* -main)
