(ns boring.nav-conformance
  "Check that navigating a document answers exactly as realising it does.

   `boring.nav` descends into some tagged values instead of building them --
   shaped arrays, typed arrays, records. Every descent rests on one condition:
   the reader's transformation must COMMUTE with the operation, so that

     (get (reader payload) k)  =  (reader-child (get payload (translate k)))

   When it does, descending is a pure optimisation. When it does not, descending
   returns A WRONG VALUE -- silently, with no exception and no slow path. That
   is the worst failure mode available here, which is why this namespace exists
   and why `boring/declare-navigable-record` points at it.

   Use it on your own registry, in your own test suite:

     (deftest my-records-are-navigable
       (is (nil? (nc/check-record registry \"my.ns.Point\"
                                  [(->Point 1 2) (->Point 0 0)]))))

   Returns nil when everything agrees, or a map describing the FIRST
   disagreement. nil-punning is deliberate: `(is (nil? ...))` reads well and
   prints the whole explanation on failure.

   This checks a claim about YOUR constructor against YOUR data. It cannot prove
   the claim for values you did not supply, so supply the awkward ones -- empty,
   one field, a nil value, a field whose name collides with something."
  (:require [boring.core :as boring]
            [boring.nav :as nav]))

(defn- realised-get
  "`get` on the realised value, with a sorted collection's raw
   ClassCastException folded to not-found -- which is `nav/get`'s contract, and
   was before any descent existed."
  [m k nf]
  (try (get m k nf) (catch ClassCastException _ nf)))

(defn- declined?
  "Whether `x` is nav DECLINING rather than answering -- a typed `:boring/...`
   error out of an operation the realised value can answer.

   Declining is sound and is not a divergence. `count` on a tag with no descent
   raises `:boring/not-a-container`, which is the contract for an opaque tag and
   was the behaviour for EVERY tag before descents existed. What the invariant
   forbids is answering DIFFERENTLY, not answering less.

   An UNTYPED throwable is never declining -- it is the failure this namespace
   exists to catch -- so it is deliberately not folded in here.

   DECLINING TAKES TWO FORMS, which is easy to miss: `count` on an opaque tag
   THROWS `:boring/not-a-container`, while `seq` on one simply returns NIL. Only
   folding the throw in left the nil comparing as an empty collection against a
   populated one."
  [x]
  (and (instance? clojure.lang.ExceptionInfo x)
       (some? (:type (ex-data x)))))

(defn- same?
  "Value equality, INCLUDING Java arrays.

   `=` on two arrays is identity, so a typed array compared against itself
   decoded twice is never equal and this namespace reported a disagreement on
   data that agreed perfectly. Found by running the check against boring's own
   descents, which is the argument for shipping it rather than only writing the
   assertions by hand."
  [a b]
  (cond
    (and (some? a) (some? b) (.isArray (class a)) (.isArray (class b)))
    (= (seq a) (seq b))
    :else (= a b)))

(defn check-value
  "Navigate `v` and realise it, and compare every answer. Returns nil if they
   agree, else a map describing the first disagreement.

   `opts` are encode/decode options; `probe-keys` are extra keys to look up
   beyond the value's own, and should include some that are ABSENT and some of
   the wrong type -- those are where a translation bug shows up first.

   Checks `value`, `count`, `seq`, `reduce` and a lookup per key. Descent is
   exercised only when the encoding produces something navigable, so pass the
   options you actually use; `{:shapes true}` and `:index` change what is
   tested."
  ([v] (check-value v {} nil))
  ([v opts] (check-value v opts nil))
  ([v opts probe-keys]
   (let [o (merge {:stringref false} opts)
         bs (boring/encode v o)
         realised (boring/decode bs o)
         c (nav/root bs o)
         fail (fn [what expected actual]
                {:check what :expected expected :actual actual
                 :value v :opts o :bytes (alength ^bytes bs)})]
     (or
      (when-not (same? realised (nav/value c))
        (fail :value realised (nav/value c)))
      (when (or (map? realised) (sequential? realised)
                (and (some? realised) (.isArray (class realised))))
        (or
         (let [want (count (if (and (some? realised) (.isArray (class realised)))
                             (seq realised) realised))
               got (try (count c) (catch Exception e e))]
           (when-not (or (declined? got) (same? want got))
             (fail :count want got)))
         ;; the RAW seq first: nil is nav declining to enumerate, not an empty
         ;; collection, and the two are indistinguishable once poured into a map
         (let [raw (try (seq c) (catch Exception e e))]
           (when-not (or (nil? raw) (declined? raw))
             (let [want (if (map? realised) (into {} realised) (vec (seq realised)))
                   got (try (if (map? realised)
                              (into {} (map (fn [[k x]] [k (nav/value x)])) raw)
                              (mapv nav/value raw))
                            (catch Exception e e))]
               (when-not (or (declined? got) (same? want got))
                 (fail :seq want got)))))
         (let [raw (try (seq c) (catch Exception e e))]
           (when-not (or (nil? raw) (declined? raw))
             (let [want (if (map? realised) (into {} realised) (vec (seq realised)))
                   got (try (if (map? realised)
                              (into {} (map (fn [[k x]] [k (nav/value x)])) c)
                              (into [] (map nav/value) c))
                            (catch Exception e e))]
               (when-not (or (declined? got) (same? want got))
                 (fail :reduce want got)))))))
      (first
       (keep (fn [k]
               (let [want (realised-get realised k ::absent)
                     got (try (nav/value (get c k ::absent))
                              (catch Exception e e))]
                 (when-not (same? want got)
                   (assoc (fail :lookup want got) :key k))))
             (concat (when (map? realised) (keys realised))
                     (when (or (sequential? realised)
                               (and (some? realised) (.isArray (class realised))))
                       (range (count (if (.isArray (class realised))
                                       (seq realised) realised))))
                     probe-keys
                     [::no-such-key "no-such-key" 0 -1])))))))

(defn check-record
  "Check `boring/declare-navigable-record`'s claim for `wire-name` against
   `examples`, using `registry`.

   Encodes each example through the registry, then compares navigating with
   realising -- so it tests the CONSTRUCTOR YOU REGISTERED, not a model of it.
   Returns nil if every example agrees, else a map describing the first
   disagreement.

   Supply awkward examples. A constructor that merely defaults a missing field
   agrees on values that have it and disagrees on values that do not, so a
   suite of fully-populated records proves less than it appears to.

   The declaration is also checked to be in force: an example that never
   descends would pass this trivially and prove nothing, so a `wire-name` that
   was never declared is itself reported."
  ([registry wire-name examples] (check-record registry wire-name examples {}))
  ([registry wire-name examples opts]
   (let [o (merge {:stringref false :registry registry} opts)]
     (or
      (when-not (.isNavigableRecord
                 ^org.replikativ.boring.TagRegistry registry ^String wire-name)
        {:check :declared
         :note (str "`" wire-name "` is not declared navigable in this registry, "
                    "so nav realises it and this check would pass without "
                    "testing anything. Call boring/declare-navigable-record.")
         :wire-name wire-name})
      (first (keep #(some-> (check-value % o) (assoc :wire-name wire-name))
                   examples))))))
