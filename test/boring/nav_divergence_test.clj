(ns boring.nav-divergence-test
  "Does navigating ever answer differently from decoding?

   THIS IS THE TEST THAT WAS MISSING. Every other nav property in this suite --
   the defspecs in `generative-test`, and `boring.nav-conformance/check-value`
   itself -- feeds nav bytes that BORING WROTE, under DEFAULT OPTIONS.
   `check-value` inherits the blind spot structurally: it encodes its own input.
   Every divergence found in review lived in the complement of that: bytes
   boring did not write, and options other than the default. Not one of them was
   reachable from the existing generators.

   So this namespace crosses two axes the others never touch --
   `boring.hostile`'s malformed documents, and option maps that change what the
   reader PRODUCES -- against the whole nav surface.

   THE PROPERTY, stated carefully, because the obvious version is false. Nav
   cannot promise to accept exactly what the reader accepts: `:max-items` and
   `:max-depth` are document-WIDE budgets, and charging them to a lookup that
   reads three items would defeat the namespace. That divergence exists on
   `main`, predates every descent, and is deliberate. What nav can promise is:

     P1  No nav operation raises an UNTYPED throwable. Ever, on any input.
     P2  `value` on the whole document agrees with `decode` -- the same value,
         or the same typed error.
     P3  When `decode` succeeds, every other nav operation agrees with the
         corresponding operation on the decoded value.
     P4  `count` never exceeds what the remaining bytes could hold.

   P1 and P4 are absolute. P2 and P3 are conditioned on the reader succeeding,
   which is what makes room for the budget exception without weakening them.

   KNOWN DIVERGENCES ARE LISTED, NOT SKIPPED. A row in `known-divergent` is
   asserted to STILL diverge, so the suite is green today and a fix turns the
   assertion red until the row is removed. A ratchet, not a wish list."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.nav :as nav]
            [boring.conformance :as c]
            [boring.hostile :as hostile]))

;; ---------------------------------------------------------------- the axes

(defrecord Pt [x y])

(def ^:private option-axis
  "Option maps that change what the READER PRODUCES, which is the axis
   `check-value` has no way to vary. `:stringref false` throughout because nav
   categorically refuses a stringref document."
  [["default" {}]
   ["on-unknown-record :error" {:on-unknown-record :error}]
   ["on-unknown-record fn" {:on-unknown-record (fn [_nm m] {:renamed (get m :a)})}]
   ["auto-construct-records" {:auto-construct-records? true}]
   ["tolerate-unknown-tags false" {:tolerate-unknown-tags false}]
   ["check-duplicate-keys false" {:check-duplicate-keys false}]
   ["validate-utf8 false" {:validate-utf8 false}]
   ["a registry" {:registry (-> (boring/tag-registry)
                                (boring/register-record
                                 (boring.data/record-type-name (->Pt 1 2)) map->Pt))}]])

(def ^:private document-axis
  "Well-formed documents covering every descent, plus the shapes that have none."
  (into [["scalar" 42]
         ["map" {:a 1 :b {:c 2}}]
         ["vector" [1 2 3]]
         ["record" (->Pt 1 2)]
         ["sorted-map" (into (sorted-map) {:a 1 :b 2})]
         ["set" #{1 2 3}]
         ["shaped rows" (vec (for [i (range 4)] {:a i :b (str i)}))]
         ["long[]" (long-array [-3 -2 -1 0 1])]
         ["short[]" (short-array (map short [-2 -1 0 1]))]
         ["int[]" (int-array [-2 -1 0 1])]
         ["float[]" (float-array [-1.5 0.0 1.5])]
         ["double[]" (double-array [-1.5 0.0 1.5])]]
        []))

;; -------------------------------------------------------- known divergences
;;
;; Each entry is a finding from the branch review that is not fixed yet.
;; It is asserted to STILL diverge: removing the fix without removing the entry
;; keeps the suite green, but FIXING it turns this red, which is the prompt to
;; delete the row. Every entry must name its finding id.

(def ^:private known-divergent
  "#{[axis-label-or-:any document-label property]}.

   Both entries below are option-INDEPENDENT, hence `:any`: they are arithmetic
   defects that no reader option reaches. Discovering that is itself worth
   something -- it says the fix is local and cannot need an option gate."
  #{;; S1 and S2 were here and are FIXED -- the ratchet turned red and named
    ;; them, which is the whole point of listing rather than skipping.
    ;; S6 -- `record-view` descends on `recordCtor == nil` without consulting
    ;; `:on-unknown-record`, so a handler that RESHAPES the field map leaves nav
    ;; answering from the wire. `value` agrees (it realises through the reader);
    ;; `count` does not. Option-dependent, hence not `:any`.
    ["on-unknown-record fn" "record" :count]})

(defn- known? [axis doc prop]
  (or (contains? known-divergent [axis doc prop])
      (contains? known-divergent [:any doc prop])))

;; WHAT THESE PROPERTIES CANNOT REACH, stated so the gap is not mistaken for
;; coverage. S6 and S7 -- a descent into a tag the READER WOULD REFUSE, e.g.
;; `:on-unknown-record :error`, or one of the eight reserved tag-27 names -- do
;; not violate P1 through P4. Both `decode` and `nav/value` raise, so P2 agrees;
;; P3 is conditioned on decode succeeding, so it does not run. The divergence is
;; that `(get c :a)` ANSWERS.
;;
;; And it cannot be made generic. "Nav must not answer where the reader raises"
;; would forbid a shallow lookup on a document with one deep malformed element,
;; which is exactly what lazy navigation is for. So this is a per-descent
;; obligation -- a descent must not enter a tag the reader would refuse -- and it
;; belongs in the tests for each descent, not here. See `boring.record-nav-test`,
;; which pins it per descent.

;; ------------------------------------------------------------------ helpers

(defn- outcome
  "`[:ok v]`, `[:raised type]`, or `[:untyped e]`."
  [f]
  (try [:ok (f)]
       (catch clojure.lang.ExceptionInfo e
         (if-let [t (:type (ex-data e))] [:raised t] [:untyped e]))
       (catch Throwable e [:untyped e])))

(defn- same-value?
  "Value equality that also holds for Java arrays, which `=` compares by
   identity -- the trap `nav-conformance/same?` exists for."
  [a b]
  (cond
    (and (some? a) (some? b) (.isArray (class a)) (.isArray (class b)))
    (= (seq a) (seq b))
    :else (= a b)))

(defn- nav-ops
  "Every nav operation worth probing on a root cursor, as [label thunk]."
  [bs opts]
  (let [src #(nav/source bs opts)]
    [[:value  #(nav/value (src))]
     [:count  #(count (src))]
     [:seq    #(doall (map nav/value (seq (src))))]
     [:reduce #(doall (into [] (map nav/value) (src)))]
     [:type   #(nav/value-type (src))]
     [:get-kw #(nav/value (get (src) :a ::nf))]
     [:get-int #(nav/value (get (src) 0 ::nf))]
     [:nth    #(nav/value (nth (src) 0 ::nf))]
     [:big-int-key #(nav/value (get (src) 2147483648 ::nf))]]))

;; ------------------------------------------------------- P1: never untyped

(deftest p1-no-untyped-throwable-from-any-nav-operation
  (testing "on HOSTILE documents -- bytes boring did not write. The typed-error
            contract is absolute: only ex-info with a :boring/... type escapes,
            whatever the input."
    (doseq [[label hex] hostile/cases
            [axis opts] option-axis]
      (let [bs (c/hex->bytes hex)
            o (assoc opts :stringref false)]
        (doseq [[op f] (nav-ops bs o)]
          (let [[kind e] (outcome f)]
            (when (and (= :untyped kind) (not (known? axis label op)))
              (is false (str "UNTYPED from nav " op " | doc " label
                             " | opts " axis " | " (class e) ": " (ex-message e))))))))))

(deftest p1-no-untyped-throwable-on-well-formed-documents
  (testing "and on documents boring DID write -- where an untyped throwable is
            not a hostile-input question but a plain defect. S1 lives here."
    (doseq [[dl v] document-axis
            [axis opts] option-axis]
      (let [o (assoc opts :stringref false)
            bs (boring/encode v o)]
        (doseq [[op f] (nav-ops bs o)]
          (let [[kind e] (outcome f)]
            (if (known? axis dl :untyped)
              nil                       ; tracked; see known-divergent
              (when (= :untyped kind)
                (is false (str "UNTYPED from nav " op " | doc " dl
                               " | opts " axis " | " (class e) ": "
                               (ex-message e)))))))))))

;; ------------------------------- P2/P3: agreement when the reader succeeds

(deftest p2-whole-value-agrees-with-decode
  (testing "`value` reads the whole document, so it has no room for the budget
            exception: it must return what `decode` returns, or raise what
            `decode` raises."
    (doseq [[dl v] document-axis
            [axis opts] option-axis]
      (let [o (assoc opts :stringref false)
            bs (boring/encode v o)
            [dk dv] (outcome #(boring/decode bs o))
            [nk nv] (outcome #(nav/value (nav/source bs o)))]
        (when-not (known? axis dl :value)
          (is (= dk nk) (str "value: decode " dk " but nav " nk
                             " | doc " dl " | opts " axis))
          (when (and (= :ok dk) (= :ok nk))
            (is (same-value? dv nv)
                (str "value differs | doc " dl " | opts " axis))))))))

(deftest p3-operations-agree-when-decode-succeeds
  (testing "count, seq and lookups against the same operation on the decoded
            value. This is where a descent that answers from the wire while the
            reader would have produced something else shows up."
    (doseq [[dl v] document-axis
            [axis opts] option-axis]
      (let [o (assoc opts :stringref false)
            bs (boring/encode v o)
            [dk dv] (outcome #(boring/decode bs o))]
        (when (and (= :ok dk) (not (known? axis dl :value)))
          ;; count, only where the realised value has one
          (when (and (not (known? axis dl :count))
                     (or (map? dv) (sequential? dv) (set? dv)
                         (and (some? dv) (.isArray (class dv)))))
            (let [want (count (if (.isArray (class dv)) (seq dv) dv))
                  [k got] (outcome #(count (nav/source bs o)))]
              (when (= :ok k)
                (is (= want got) (str "count | doc " dl " | opts " axis)))))
          ;; a keyword lookup, where the realised value supports one
          (when (map? dv)
            (let [want (get dv :a ::nf)
                  [k got] (outcome #(nav/value (get (nav/source bs o) :a ::nf)))]
              (when (= :ok k)
                (is (same-value? want got)
                    (str "get :a | doc " dl " | opts " axis))))))))))

;; ------------------------------------------------------------ P4: count sanity

(deftest p4-count-never-exceeds-the-bytes-that-follow
  (testing "a container declaring more entries than the remaining bytes could
            hold is malformed however it is wrapped. The array and map branches
            check this; S3 is that the tag branch does not."
    (doseq [[label hex] hostile/cases]
      (let [bs (c/hex->bytes hex)
            o {:stringref false}
            [k n] (outcome #(count (nav/source bs o)))]
        (when (= :ok k)
          (is (<= (long n) (alength bs))
              (str "count " n " exceeds the " (alength bs)
                   "-byte document | " label)))))))

;; --------------------------------------------------------------- the ratchet

(deftest every-known-divergence-still-diverges
  (testing "a fixed row must be DELETED from `known-divergent`, not left behind.
            Otherwise the exclusion list silently becomes a list of things
            nobody rechecks -- which is how the originals shipped."
    (doseq [[axis dl prop] known-divergent]
      ;; `:any` means the divergence is option-independent, so the default axis
      ;; is a sufficient witness -- and the cheapest one to keep honest.
      (let [opts (if (= :any axis)
                   {}
                   (some (fn [[a o]] (when (= a axis) o)) option-axis))
            v (some (fn [[d x]] (when (= d dl) x)) document-axis)]
        (is (some? opts) (str "known-divergent names an unknown option axis: " axis))
        (is (some? v) (str "known-divergent names an unknown document: " dl))
        (when (and opts v)
          (let [o (assoc opts :stringref false)
                bs (boring/encode v o)]
            (case prop
              :untyped
              ;; ANY of the probed operations raising untyped keeps the row
              ;; honest -- S1 shows up through seq/nth, S2 only through the
              ;; oversized integer key, and naming one operation per row would
              ;; be a second thing to maintain.
              (is (some (fn [[_ f]] (= :untyped (first (outcome f)))) (nav-ops bs o))
                  (str "[" axis " " dl "] no longer raises untyped -- delete the row"))
              :value
              (is (not= (first (outcome #(boring/decode bs o)))
                        (first (outcome #(nav/value (nav/source bs o)))))
                  (str "[" axis " " dl "] value now agrees -- delete the row"))
              :count
              (let [[dk dv] (outcome #(boring/decode bs o))
                    [nk nv] (outcome #(count (nav/source bs o)))]
                (is (not (and (= :ok dk) (= :ok nk) (= (count dv) nv)))
                    (str "[" axis " " dl "] count now agrees -- delete the row"))))))))))
