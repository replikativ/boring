(ns boring.conformance
  "Shared machinery for the conformance suite: realises the fixture markers in
  `boring.vectors` into platform values, and compares results in a way that is
  honest about what each platform can actually represent.

  This namespace is deliberately implementation-free so the same suite runs
  against the JVM codec and the CLJS one."
  (:require [boring.data :as data]
            [clojure.string :as str]))

;; ## Hex

(defn hex->bytes
  [^String s]
  (let [n (quot (count s) 2)]
    #?(:clj (let [a (byte-array n)]
              (dotimes [i n]
                (aset a i (unchecked-byte
                           (Integer/parseInt (subs s (* 2 i) (+ 2 (* 2 i))) 16))))
              a)
       :cljs (let [a (js/Uint8Array. n)]
               (dotimes [i n]
                 (aset a i (js/parseInt (subs s (* 2 i) (+ 2 (* 2 i))) 16)))
               a))))

(defn bytes->hex
  [bs]
  (let [n #?(:clj (alength ^bytes bs) :cljs (.-length bs))]
    (str/join
     (for [i (range n)]
       (let [b (bit-and (aget bs i) 0xFF)]
         (str (when (< b 16) "0")
              #?(:clj (Integer/toString b 16) :cljs (.toString b 16))))))))

;; ## Fixture marker realisation
;;
;; The vectors file is pure data so both platforms can read it without reader
;; conditionals. The platform-specific part lives here.

(declare ->expected)

(defn- marker?
  [x kw]
  (and (vector? x) (= kw (first x))))

(defn ->expected
  "Realise a fixture marker into the value this platform should decode to."
  [x]
  (cond
    (= :undefined x) data/undefined

    (marker? x :bytes)
    (let [bs (rest x)]
      #?(:clj (byte-array (map unchecked-byte bs))
         :cljs (js/Uint8Array. (clj->js (vec bs)))))

    (marker? x :tagged)
    (data/tagged-value (nth x 1) (->expected (nth x 2)))

    (marker? x :simple)
    (data/simple-value (nth x 1))

    ;; Tag 32 is a registered URI. The JVM has a URI type and returns one;
    ;; ClojureScript has none, so it returns the string -- lossless, since a URI
    ;; IS its string form, but a genuine type difference the corpus has to name
    ;; rather than hide.
    (marker? x :uri)
    #?(:clj (java.net.URI. (nth x 1))
       ;; No JS URI type; tag 32 stays a TaggedValue there so it re-encodes to
       ;; the same bytes rather than collapsing to a plain string.
       :cljs (data/tagged-value 32 (nth x 1)))

    (marker? x :instant)
    #?(:clj (java.util.Date/from (java.time.Instant/parse (nth x 1)))
       :cljs (js/Date. (nth x 1)))

    (marker? x :bigint)
    #?(:clj (bigint (nth x 1))
       :cljs (js/BigInt (nth x 1)))

    ;; f16 and f32 both decode to a single-precision value on the JVM; CLJS has
    ;; only one number type, so the width distinction cannot survive there.
    ;; `unchecked-float`, not `float` — the latter range-checks and so rejects
    ;; Infinity, which is exactly one of the values under test.
    (or (marker? x :f16) (marker? x :f32))
    #?(:clj (unchecked-float (nth x 1)) :cljs (nth x 1))

    (marker? x :f64)
    #?(:clj (unchecked-double (nth x 1)) :cljs (nth x 1))

    (vector? x) (mapv ->expected x)
    (map? x)    (into {} (map (fn [[k v]] [(->expected k) (->expected v)])) x)
    (set? x)    (into #{} (map ->expected) x)
    :else x))

;; ## Comparison

(defn nan?
  [x]
  #?(:clj  (and (number? x) (Double/isNaN (double x)))
     :cljs (and (number? x) (js/isNaN x))))

(defn platform-array?
  "Any primitive array — not just byte arrays. RFC 8746 typed arrays decode to
  long[]/double[]/int[]/float[]/short[], none of which have value equality."
  [x]
  #?(:clj  (and (some? x) (.isArray (class x)))
     :cljs (and (some? x) (or (cljs.core/array? x) (js/ArrayBuffer.isView x)))))

(defn array=
  [a b]
  (let [n #?(:clj (alength a) :cljs (.-length a))
        m #?(:clj (alength b) :cljs (.-length b))]
    (and (= n m)
         (loop [i 0]
           (cond (= i n) true
                 (not= (aget a i) (aget b i)) false
                 :else (recur (inc i)))))))

(defn same-bytes?
  "Compare two encoded results. NOT `(= (seq a) (seq b))`: on CLJS `seq` over a
  Uint8Array yields an IndexedSeq whose equality does not compare element-wise
  across different backing arrays, so identical bytes compare unequal."
  [a b]
  (data/bytes= a b))

(defn equiv?
  "Value equality that copes with primitive arrays, NaN (which is never = to
  itself), and nested structures containing either."
  [a b]
  (cond
    (and (nan? a) (nan? b)) true
    (or (data/bytes-like? a) (data/bytes-like? b)) (data/bytes= a b)

    (and (platform-array? a) (platform-array? b)) (array= a b)
    (or (platform-array? a) (platform-array? b)) false

    ;; A Throwable has no value equality either -- two ex-infos with the same
    ;; message and data are not `=`, because Throwable inherits Object
    ;; identity. Compared by what actually crosses the wire: message, data and
    ;; the cause chain. The class is NOT compared: a non-ex-info Throwable is
    ;; deliberately rebuilt as an ex-info carrying its original class name in
    ;; the data, so the data comparison covers it.
    (and (instance? #?(:clj Throwable :cljs js/Error) a)
         (instance? #?(:clj Throwable :cljs js/Error) b))
    (and (= (ex-message a) (ex-message b))
         (= (ex-data a) (ex-data b))
         (let [ca (ex-cause a) cb (ex-cause b)]
           (if (or ca cb) (equiv? ca cb) true)))

    ;; A JVM Pattern has no value equality -- `(= #"a" #"a")` is false, because
    ;; Pattern inherits Object identity. Compared by source, which is exactly
    ;; what boring puts on the wire (tag 35).
    #?@(:clj [(and (instance? java.util.regex.Pattern a)
                   (instance? java.util.regex.Pattern b))
              (= (.pattern ^java.util.regex.Pattern a)
                 (.pattern ^java.util.regex.Pattern b))]
        :cljs [(and (regexp? a) (regexp? b)) (= (.-source a) (.-source b))])

    ;; A tagged literal is neither sequential nor a map, so it fell straight
    ;; through to `=` -- and `=` on a form holding primitive arrays is IDENTITY.
    ;; So two decodes of the SAME bytes compared unequal, which is how a
    ;; property test that compares two readers reported a disagreement where
    ;; there was none. The docstring already promised nested structures; this
    ;; was one it did not reach.
    ;; `TaggedValue` needs no branch: it is a defrecord, so the map branch
    ;; below reaches it and recurses into `:value`. A tagged LITERAL is not a
    ;; map and has no other branch to fall into.
    (and (tagged-literal? a) (tagged-literal? b))
    (and (= (:tag a) (:tag b)) (equiv? (:form a) (:form b)))

    (and (map? a) (map? b))
    (and (= (count a) (count b))
         (every? (fn [[k v]] (and (contains? b k) (equiv? v (get b k)))) a))

    (and (sequential? a) (sequential? b))
    (and (= (count a) (count b)) (every? true? (map equiv? a b)))

    :else (= a b)))

(defn type-identical?
  "Stronger than equiv?: the decoded value must have the same *type*, not just
  the same value. This is the property datahike's dump requirements ask for and
  the one that catches double->float narrowing (clj-cbor #633, fress-cljs).

  On CLJS there is a single number type, so numeric width cannot be asserted;
  the check degrades to value equality there, which the suite reports honestly
  rather than silently passing."
  [a b]
  #?(:clj  (and (equiv? a b)
                (or (and (data/bytes-like? a) (data/bytes-like? b))
                    (= (class a) (class b))))
     :cljs (equiv? a b)))

#?(:clj
   (defn width-checkable?
     "Whether this platform can distinguish float widths at all."
     [] true)
   :cljs
   (defn width-checkable? [] false))
