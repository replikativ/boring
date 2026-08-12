(ns navfuzz
  "The fuzzer nav never had, and nav is the component with the trust boundary:
  it takes offsets from a FRAME somebody else may have written and hands them
  to a Reader whose array access is unchecked past its own gates.

  Same method as `fuzz`: mutate VALID encodings, which reaches far past what
  random bytes reach (random bytes die on the first header). The seed corpus
  is indexed documents specifically -- stringref on and off, strides 1 and 16
  -- because the frame, its trailer, its pointer table and its packed slots
  are exactly the bytes nav trusts.

  TWO INVARIANTS, because nav's promise has two strengths:

  1. WHOLE-BLOB mutants: every operation returns a value or throws ex-info
     with a `:boring/*` type. A wrong VALUE is allowed -- doc/INDEX.md is
     explicit that in-bounds anchor damage is a trust boundary -- but
     StackOverflow, NPE, AIOOBE, OOM, ClassCast, Arithmetic are defects,
     every one of which this codebase has shipped at least once.

  2. FRAME-ONLY mutants (bytes strictly after the data section): `decode`
     must return the ORIGINAL value, exactly. The value item precedes the
     frame, so no frame damage may change what the document MEANS to a
     decoder -- and nav's open must still be value-or-typed-error.

  Run: clojure -M:fuzz -m navfuzz [n]"
  (:require [boring.core :as boring]
            [boring.nav :as nav]
            [clojure.test.check.generators :as gen]
            [boring.generative-test :as g]))

(defn- random-path
  "A path that exists in `v`, depth up to 3 -- so walks reach INTO documents
  rather than bouncing off the root."
  [v ^java.util.Random rnd]
  (loop [v v path [] depth 0]
    (cond
      (and (map? v) (pos? (count v)) (< depth 3))
      (let [k (nth (keys v) (.nextInt rnd (count v)))]
        (recur (get v k) (conj path k) (inc depth)))
      (and (vector? v) (pos? (count v)) (< depth 3))
      (let [i (.nextInt rnd (count v))]
        (recur (nth v i) (conj path i) (inc depth)))
      :else path)))

(defn- typed? [e]
  (and (instance? clojure.lang.ExceptionInfo e)
       (= "boring" (some-> (ex-data e) :type namespace))))

(defn- run-ops
  "Every nav operation the store layer actually uses, against one blob.
  Returns nil or an untyped-failure description."
  [^bytes bs opts path]
  (try
    (let [src (nav/source bs opts)]
      ;; the open succeeded; now walk, read, realise
      (let [o (nav/walk-from src (nav/root-offset src) path)]
        (when-not (or (neg? o) (= -2 o)) (nav/value-at src o)))
      (nav/value (nav/root bs opts))
      nil)
    (catch clojure.lang.ExceptionInfo e
      (when-not (typed? e)
        (str "untyped ExceptionInfo: " (pr-str (ex-data e)))))
    (catch Throwable e
      (str (.getName (class e)) ": " (.getMessage e)))))

(defn -main [& [n-str]]
  (let [n (Long/parseLong (or n-str "40000"))
        seeds (map #(gen/generate g/gen-value 15 %) (range 150))
        ;; every seed sealed four ways; keep the value beside the bytes for
        ;; invariant 2 and for path derivation
        corpus (vec (for [v (cons {:hdr {:a 1} :big (vec (repeat 40 {:x 1 :y "s"}))
                                   :tail {:city "b"}} seeds)
                          o [{:index 1 :index-min 4 :stringref false}
                             {:index 16 :index-min 4 :stringref false}
                             {:index 1 :index-min 4}
                             {:index 16 :index-min 4}]
                          :let [bs (try (boring/encode-indexed v o) (catch Throwable _ nil))]
                          :when bs]
                      [v bs (if (:stringref o false) {} {:stringref false})
                       (alength ^bytes (boring/encode v (if (contains? o :stringref)
                                                          {:stringref false} {})))]))
        rnd (java.util.Random. 4242)
        tally (atom {}) bad (atom [])]
    (println "seed corpus:" (count corpus) "sealed documents")
    (dotimes [_ n]
      (let [[v ^bytes src opts data-len] (nth corpus (.nextInt rnd (count corpus)))
            bs (java.util.Arrays/copyOf src (alength src))
            frame-only? (and (zero? (.nextInt rnd 2)) (< (long data-len) (alength bs)))
            lo (if frame-only? (long data-len) 0)
            span (- (alength bs) lo)
            path (random-path v rnd)]
        (dotimes [_ (inc (.nextInt rnd 4))]
          (when (pos? span)
            (aset bs (+ lo (.nextInt rnd span)) (byte (- (.nextInt rnd 256) 128)))))
        (if-let [fail (run-ops bs opts path)]
          (do (swap! tally update :UNTYPED (fnil inc 0))
              (swap! bad conj [fail (vec bs)]))
          (swap! tally update :ok-or-typed (fnil inc 0)))
        ;; invariant 2: frame damage may not change what decode returns.
        ;;
        ;; EQUALITY IS RE-ENCODED BYTES, not `=`. The generated values carry
        ;; primitive arrays, and a decoded double[] never equals the original
        ;; by identity-based array equality -- the first version of this
        ;; oracle failed every array-bearing seed with or without a mutation.
        ;; Encoding is deterministic (a pinned generative property), so two
        ;; values are equal iff their encodings are byte-equal.
        (when frame-only?
          (let [d (try {:v (boring/decode bs opts)} (catch Throwable e {:e e}))]
            (cond
              (and (contains? d :v)
                   (java.util.Arrays/equals
                    ^bytes (boring/encode (:v d) {:stringref false})
                    ^bytes (boring/encode v {:stringref false}))) nil
              (and (:e d) (typed? (:e d))) nil    ; truncation-style refusal is typed
              :else (do (swap! tally update :FRAME-CHANGED-DECODE (fnil inc 0))
                        (swap! bad conj [(str "frame-only mutation changed decode: "
                                              (pr-str (dissoc d :e)))
                                         (vec bs)])))))))
    (println "\noutcomes over" n "mutants:")
    (doseq [[k c] (sort-by (comp - val) @tally)]
      (println (format "  %-24s %7d" k c)))
    (when (seq @bad)
      (println "\nDEFECTS:")
      (doseq [[msg bs] (take 5 @bad)]
        (println " " msg "\n   bytes:" (vec (take 80 bs)) (when (> (count bs) 80) "..."))))
    (println (if (seq @bad) "\nFAIL" "\nno defects"))
    (when (seq @bad) (System/exit 1))))
