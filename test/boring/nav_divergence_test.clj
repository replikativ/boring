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
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [boring.core :as boring]
            [boring.nav :as nav]
            [boring.conformance :as c]
            [boring.hostile :as hostile])
  (:import (org.replikativ.boring Reader)))

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
  (let [src #(nav/root bs opts)]
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
            [nk nv] (outcome #(nav/value (nav/root bs o)))]
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
                  [k got] (outcome #(count (nav/root bs o)))]
              (when (= :ok k)
                (is (= want got) (str "count | doc " dl " | opts " axis)))))
          ;; a keyword lookup, where the realised value supports one
          (when (map? dv)
            (let [want (get dv :a ::nf)
                  [k got] (outcome #(nav/value (get (nav/root bs o) :a ::nf)))]
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
            [k n] (outcome #(count (nav/root bs o)))]
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
                        (first (outcome #(nav/value (nav/root bs o)))))
                  (str "[" axis " " dl "] value now agrees -- delete the row"))
              :count
              (let [[dk dv] (outcome #(boring/decode bs o))
                    [nk nv] (outcome #(count (nav/root bs o)))]
                (is (not (and (= :ok dk) (= :ok nk) (= (count dv) nv)))
                    (str "[" axis " " dl "] count now agrees -- delete the row"))))))))))

;;; walk

(deftest walk-agrees-with-get-in-on-every-path
  (testing "`walk` exists so a caller wanting a compiled access path does not
            write its own walker over `Reader`'s positional primitives. Such a
            walker cannot reach the index -- the index lives on the Nav and
            `skipFrom` knows nothing about it -- and konserve-lmdb wrote one
            that disagreed with this namespace twice: a present integer key
            reported absent, and `[1]` into `{0 \"a\" 1 \"b\"}` returning the
            first VALUE rather than the value under the key `1`.

            So the property is that `walk` and `get-in` are the same function."
    (let [o {:stringref false}
          doc {:m {0 "zero" 1 "one" :k "kw"}
               :v [10 20 30]
               :deep {:a {:b {:c "found"}}}
               :mixed [{:x 1} {:x 2}]}
          bs (boring/encode doc o)
          c (nav/root bs o)]
      (doseq [path [[:m 0] [:m 1] [:m :k]
                    [:v 0] [:v 2] [:v 3]
                    [:deep :a :b :c] [:deep :a :nope]
                    [:mixed 1 :x] [:mixed 0 :x]
                    [] [:nope] [:m] [:v]]]
        (is (= (nav/value (get-in c path)) (nav/value (nav/walk c path)))
            (str "walk and get-in disagree on " (pr-str path)))
        (is (= (get-in doc path) (nav/value (nav/walk c path)))
            (str "walk disagrees with the decoded truth on " (pr-str path)))))))

(deftest walk-uses-the-index-where-a-positional-walker-cannot
  (testing "the reason `walk` is public rather than an internal shortcut. A
            walker built on `Reader.skipFrom` gets no benefit from an index at
            all -- measured, 33.06 us indexed against 32.15 unindexed on the
            same document -- while `walk` reaches 4.91. Asserted here as SKIPS,
            which is deterministic."
    (let [o {:stringref false :trust-index :trusted}
          doc (into {:tree (vec (repeatedly 200 #(hash-map :a 1 :b 2)))}
                    (map (fn [i] [(keyword (str "k" i)) i])) (range 12))
          plain (boring/encode doc {:stringref false})
          idx (boring/encode-indexed doc {:index 1 :index-min 13})
          ;; THE INDEX IS FORCED BEFORE COUNTING. Opening one now walks the
          ;; frame positionally to locate each node's slot -- cheaper than
          ;; decoding them all, but it is `skipFrom` calls, and the counter does
          ;; not distinguish "found the slots" from "walked the data". The claim
          ;; under test is that a LOOKUP consults the index, so open is excluded.
          skips (fn [bs]
                  (let [c (nav/root bs o)
                        nv (.nav ^boring.nav.Cursor c)
                        ^Reader r (.rdr ^boring.nav.Nav nv)
                        _ (#'nav/nav-idx nv)
                        before (.skips r)]
                    [(nav/value (nav/walk c [:k11])) (- (.skips r) before)]))
          [v-plain n-plain] (skips plain)
          [v-idx n-idx] (skips idx)]
      (is (= 11 v-plain v-idx) "same answer either way")
      ;; STRICTLY FEWER, not a ratio. `Reader.skips` counts skipFrom CALLS, and
      ;; skipping a 200-element subtree is one call -- the same wrong unit that
      ;; made an earlier test pass with a live bug in place. It is enough to
      ;; prove the index is CONSULTED; the size of the win is a timing claim and
      ;; lives in the docstring, measured: 4.91 us indexed against 32.15, while
      ;; a positional walker on the same indexed bytes gets 33.06 -- no benefit
      ;; at all, which is the thing this function exists to fix.
      (is (< n-idx n-plain)
          (str "indexed walk took " n-idx " skips against " n-plain
               " unindexed; the index is not being consulted")))))

(deftest a-re-pointed-source-answers-exactly-as-a-fresh-one
  (testing "`re-point!` reuses the Reader, the Nav, the probe cache and the root
            cursor so a scan allocates nothing per row. That is only worth
            having if it answers identically to building a source per row, on
            every document, in any order -- a reused source that lies is
            worthless.

            The length cases are the ones that bite: `Reader.reset` sets the
            limit, and an earlier version of it skipped the rebind when handed
            the SAME array, which left a narrowed limit in place and made a
            256-byte buffer report as 4."
    (let [o {:stringref false}
          ctx (nav/context o)
          docs (mapv (fn [i] {:id i :name (str "u" i) :city (str "c" i)}) (range 50))
          blobs (mapv #(boring/encode % o) docs)
          long-doc (boring/encode (vec (range 500)) o)
          s (nav/root (first blobs) ctx)]
      (is (= (mapv #(:city %) docs)
             (mapv (fn [bs] (nav/value (get (nav/re-point! s bs) :city))) blobs))
          "a reused source answers what each document says")
      (is (= (mapv #(:city %) docs)
             (mapv (fn [bs] (nav/value (get (nav/root bs ctx) :city))) blobs))
          "and a fresh source per row agrees, which is the oracle")
      (testing "a longer document after a short one, and back"
        (is (= 500 (count (nav/value (nav/re-point! s long-doc)))))
        (is (= "c7" (nav/value (get (nav/re-point! s (nth blobs 7)) :city)))))
      (testing "an INDEXED document, so the index is re-parsed rather than stale"
        (let [oi (assoc o :index 4 :index-min 4)
              a (boring/encode-indexed (vec (for [i (range 40)] {:k i})) oi)
              b (boring/encode-indexed (vec (for [i (range 40)] {:k (+ 100 i)})) oi)
              c (nav/root a (nav/context oi))]
          (is (= 39 (nav/value (get (nav/walk (nav/re-point! c a) [39]) :k))))
          (is (= 139 (nav/value (get (nav/walk (nav/re-point! c b) [39]) :k)))
              "the second document's index must be used, not the first's"))))))

(deftest the-cursor-offset-bridge-is-exact
  (testing "`(cursor (source-of c) (offset c))` must be `c`. That is the whole
            claim the two-layer split rests on: a caller can drop from cursors
            to offsets in a hot loop and come back, and the position survives
            the round trip.

            The one exception is stated in `source-of`'s docstring rather than
            left to be discovered -- a SHAPED row carries state on the cursor
            that an offset cannot represent, so it does not round-trip. Pinned
            here so the exception stays exactly one thing."
    (let [o {:stringref false}
          doc {:a {:b [10 20 {:c "deep"}]} :x 1}
          bs (boring/encode doc o)
          c (nav/root bs o)]
      (doseq [[label cur] [["root" c]
                           ["a map" (get c :a)]
                           ["an array" (get-in c [:a :b])]
                           ["an element" (nav/walk c [:a :b 2])]
                           ["a scalar" (nav/walk c [:a :b 0])]]]
        (let [round (nav/cursor (nav/source-of cur) (nav/offset cur))]
          (is (= (nav/offset cur) (nav/offset round)) (str label ": offset survives"))
          (is (= (nav/value cur) (nav/value round)) (str label ": and so does the value"))))
      (testing "`root` gets back to the top from anywhere"
        (is (= doc (nav/value (nav/root (nav/walk c [:a :b 2]))))))
      (testing "`source-of` accepts an items as well as a cursor"
        (let [out (java.io.ByteArrayOutputStream.)]
          (boring/write-seq! (boring/writer 4096 o) [{:i 1} {:i 2}] out o)
          (let [its (nav/items (.toByteArray out) o)]
            (is (some? (nav/source-of its)))
            (is (= 1 (nav/value (get (nav/cursor (nav/source-of its) (nav/offset (nth its 0))) :i)))
                "and the two agree about where item 0 is")))))))

(deftest a-source-is-not-a-cursor-and-says-so
  (testing "`source` returns a SOURCE now, where it used to return a root
            cursor. That break has to be LOUD: a source deliberately implements
            no collection interface, so the calls a caller would have written
            against the old return value throw rather than quietly answering
            nil -- which is the difference between a compile-time-ish failure
            and a silently wrong scan.

            `root` is what `source` was."
    (let [o {:stringref false}
          bs (boring/encode {:a 1 :b [1 2 3]} o)
          s (nav/source bs o)]
      (is (not (nav/cursor? s)) "a source is not a cursor")
      (is (thrown? Exception (get s :a)) "get on a source must not answer nil")
      (is (thrown? Exception (count s)) "nor count")
      (is (thrown? Exception (seq s)) "nor seq")
      (testing "and `root` gives back exactly what `source` used to"
        (is (= {:a 1 :b [1 2 3]} (nav/value (nav/root bs o))))
        (is (= 1 (nav/value (get (nav/root s) :a))))
        (is (= (nav/offset (nav/root bs o)) (nav/offset (nav/root s)))))
      (testing "`root` and `cursor` take raw bytes as well as a source"
        (is (= {:a 1 :b [1 2 3]} (nav/value (nav/root bs))))
        (is (= (nav/value (nav/cursor bs 0 o)) (nav/value (nav/cursor s 0))))))))

(deftest the-offset-layer-answers-what-the-cursor-layer-answers
  (testing "the offset layer exists so a caller does not write their own walker
            -- konserve-lmdb wrote two and both disagreed with this namespace.
            So it has to agree with `get`/`nth`/`walk` on every shape, not
            approximately and not on the shapes it was developed against."
    (let [o {:stringref false}
          doc {:id 7 :name "u7" :tags ["a" "b" "c"]
               :profile {:address {:city "Rome"} :n -12}
               :empty {} :ev [] :neg -1 :big 100000}
          bs (boring/encode doc o)
          s (nav/source bs o)
          c (nav/root s)]
      (testing "field-offset agrees with get, by key and by probe"
        (doseq [k [:id :name :tags :profile :neg :big :missing]]
          (let [off (nav/field-offset s 0 k)
                pb (nav/field-offset s 0 (nav/probe s k))]
            (is (= off pb) (str k ": key and probe find the same offset"))
            (if (contains? doc k)
              (is (= (get doc k) (nav/value-at s off)) (str k ": and the right value"))
              (is (= -1 off) (str k ": absent is -1"))))))
      (testing "nth-offset agrees with nth"
        (let [to (nav/field-offset s 0 :tags)]
          (dotimes [i 3]
            (is (= (nth ["a" "b" "c"] i) (nav/value-at s (nav/nth-offset s to i)))))
          (is (= -1 (nav/nth-offset s to 3)) "past the end is -1")))
      (testing "long-at agrees with value-at on integers, both signs"
        (doseq [k [:id :neg :big]]
          (let [off (nav/field-offset s 0 k)]
            (is (= (get doc k) (nav/long-at s off)) (str k))
            (is (= (nav/value-at s off) (nav/long-at s off))))))
      (testing "and refuses what is not an integer, rather than coercing"
        (is (thrown? clojure.lang.ExceptionInfo
                     (nav/long-at s (nav/field-offset s 0 :name)))))
      (testing "value-at refuses -1 rather than reading the document's head"
        (is (thrown? clojure.lang.ExceptionInfo (nav/value-at s -1))))
      (testing "container-count agrees with count, including empties"
        (is (= 8 (nav/container-count s 0)))
        (is (= 3 (nav/container-count s (nav/field-offset s 0 :tags))))
        (is (= 0 (nav/container-count s (nav/field-offset s 0 :empty))))
        (is (= 0 (nav/container-count s (nav/field-offset s 0 :ev)))))
      (testing "reduce-at sees every element, and reduced stops it"
        (let [to (nav/field-offset s 0 :tags)]
          (is (= ["a" "b" "c"]
                 (nav/reduce-at s to (fn [acc ^long p] (conj acc (nav/value-at s p))) [])))
          (is (= ["a"] (nav/reduce-at s to (fn [acc ^long p]
                                             (reduced (conj acc (nav/value-at s p)))) [])))
          (is (= [] (nav/reduce-at s (nav/field-offset s 0 :ev)
                                   (fn [acc ^long p] (conj acc p)) [])))))
      (testing "reduce-kv-at sees every pair, keys and values"
        (is (= doc (nav/reduce-kv-at s 0
                                     (fn [acc ^long kp ^long vp]
                                       (assoc acc (nav/value-at s kp) (nav/value-at s vp)))
                                     {}))))
      (testing "and each refuses the other's shape rather than misreading it"
        (is (thrown? clojure.lang.ExceptionInfo (nav/reduce-at s 0 (fn [a _] a) nil))
            "reduce-at on a map")
        (is (thrown? clojure.lang.ExceptionInfo
                     (nav/reduce-kv-at s (nav/field-offset s 0 :tags) (fn [a _ _] a) nil))
            "reduce-kv-at on an array")))))

;; ------------------------------------------------- walk-from against walk

(defspec walk-from-answers-exactly-what-walk-answers 400
  ;; TWO WALKERS AGAIN, which is the thing this namespace keeps having to stop.
  ;; `walk-from` exists because a projection over many rows should not allocate
  ;; a Cursor per row to throw away -- but the moment it is a second loop over
  ;; the same containers it can disagree, and konserve-lmdb has twice shipped a
  ;; hand-rolled walker that got an integer step wrong in a different
  ;; direction. Held to `walk` by property rather than by reading.
  (prop/for-all [v (gen/one-of
                    [(gen/map gen/string-alphanumeric
                              (gen/map gen/string-alphanumeric gen/small-integer
                                       {:max-elements 4})
                              {:max-elements 4})
                     (gen/vector (gen/vector gen/small-integer 0 4) 0 4)
                     (gen/map gen/string-alphanumeric
                              (gen/vector gen/small-integer 0 5) {:max-elements 4})])
                 path (gen/vector (gen/one-of [gen/string-alphanumeric
                                               (gen/choose 0 5)])
                                  0 3)]
    (let [o {:profile :clojure :stringref false :index 4 :index-min 2}
          bs (boring/encode-indexed v o)
          src (nav/source bs o)
          off (nav/walk-from src 0 path)
          cur (nav/walk (nav/root bs o) path)]
      (and (not= -2 off)                       ; unreachable without :shapes
           (if (neg? off)
             (nil? cur)
             (and (some? cur) (= (nav/value-at src off) (nav/value cur))))))))

(deftest a-compiled-path-answers-what-a-raw-one-answers
  (testing "A key step is matched by comparing BYTES, so a scan should encode
            each key once rather than once per row. `probe-for` passes
            already-encoded bytes through, which means a compiled path is just
            the path with `probe` substituted -- no second API, and nothing to
            keep in step with the uncompiled one.

            Worth 0.658 us against 0.535 on a four-step path, at the same
            allocation: the cache hit does not allocate, it simply is not free.

            The risk a compiled path introduces is that it stops being checked
            against the raw one, so it is checked here, INCLUDING the mixed
            case -- an integer step must survive compilation unchanged and keep
            meaning position-on-array, key-on-map."
    (let [v {"m" {"a" {"b" 1} "2" "by-key"}
             "v" [10 20 30]
             "mixed" [{"k" "in-vec"}]}
          o {:profile :clojure :stringref false :index 16 :index-min 2}
          bs (boring/encode-indexed v o)
          src (nav/source bs o)
          compile (fn [p] (mapv #(if (integer? %) % (nav/probe src %)) p))]
      (doseq [path [["m" "a" "b"] ["v" 2] ["mixed" 0 "k"] ["m" "2"] ["v" 9] ["nope"]]]
        (let [raw (nav/walk-from src 0 path)
              cmp (nav/walk-from src 0 (compile path))]
          (is (= raw cmp) (str (pr-str path) ": compiled must equal raw"))
          (is (= (some-> (nav/walk (nav/root bs o) path) nav/value)
                 (when-not (neg? cmp) (nav/value-at src cmp)))
              (str (pr-str path) ": and both must equal `walk`")))))))

(deftest walk-from-does-not-assume-a-cursor-behind-a-tag
  (testing "A tag this namespace has no view for REALISES. `(get tag-cursor k)`
            hands back a plain value -- a String, a vector -- and `walk`
            carries on with `get` on exactly that.

            `walk-from` assumed the tail was always a Cursor and cast it, which
            was a raw ClassCastException the first time konserve-lmdb pointed
            it at a stringref blob: a real store holds a mix of encodings
            indefinitely, so the old rows are not a corner case there.

            A realised value has no offset that names it, so the answer is -2
            -- \"there, but ask `walk`\" -- and NOT -1, which would report a
            present key as absent."
    (let [v {"a" #{1 2 3} "b" 7}
          o {:profile :clojure :stringref false :index 16 :index-min 2}
          bs (boring/encode-indexed v o)
          src (nav/source bs o)]
      ;; Stepping TO the set gives a real offset -- its tag's. Stepping INTO
      ;; it is where the realising happens, because nav has no view for a set:
      ;; the cursor realises `#{1 2 3}` and `(get #{1 2 3} 1)` is the plain
      ;; value 1, with no offset behind it.
      (is (pos? (nav/walk-from src 0 ["a"]))
          "the set itself is at an offset")
      (is (= -2 (nav/walk-from src 0 ["a" 1]))
          "but a step THROUGH it lands on a realised value")
      (is (= 1 (nav/value (nav/walk (nav/root bs o) ["a" 1])))
          "and `walk` answers it, which is what -2 tells the caller to do")
      (is (= 7 (nav/value-at src (nav/walk-from src 0 ["b"])))
          "while an ordinary step still comes back as an offset")
      (is (= -1 (nav/walk-from src 0 ["nope"]))
          "and absent is still -1, distinct from -2"))))

(deftest a-non-integer-key-on-an-array-is-absent-not-a-map-lookup
  (testing "`walk` returned a WRONG VALUE for a string step onto an array.

            It fell through to `lookup-map`, which reads the container as a MAP
            -- `n` pairs where there are `n` elements -- so a four-element
            array of alternating strings answered as if it were the map those
            strings spell:

              (get-in {\"arr\" [\"k1\" \"v1\"]} [\"arr\" \"k1\"])   ; nil
              (walk    cursor              [\"arr\" \"k1\"])   ; \"v1\"

            nav's own `get` chain answered nil, and so does `clojure.core`, so
            `walk` disagreed with both and with the rule its own docstring
            states. On a short document the over-read runs past the end and
            raises `:boring/truncated-input` instead -- which of the two you
            get depends on what happens to follow the array.

            PRE-EXISTING, and found only by property-testing `walk-from`
            against `walk`: the two walkers agreed with each other, because
            both had it. Comparing them to `get` is what showed it."
    (let [o {:profile :clojure :stringref false :index 16 :index-min 2}]
      ;; Long enough that the over-read stays IN BOUNDS and returns a value.
      (let [v {"arr" ["k1" "v1" "k2" "v2"] "pad" (vec (range 40))}
            bs (boring/encode-indexed v o)
            root (nav/root bs o)
            src (nav/source bs o)]
        (is (nil? (nav/walk root ["arr" "k1"]))
            "a string step onto an array is absent, as `get-in` says")
        (is (= -1 (nav/walk-from src 0 ["arr" "k1"]))
            "and the offset layer agrees")
        (is (= "v1" (nav/value (nav/walk root ["arr" 1])))
            "while an integer step still indexes it"))
      ;; Short enough that the over-read runs off the end.
      (let [v [["a"] ["b" "c"]]
            bs (boring/encode-indexed v o)
            root (nav/root bs o)
            src (nav/source bs o)]
        (is (nil? (nav/walk root [""]))
            "and where it used to throw, it is simply absent")
        (is (= -1 (nav/walk-from src 0 [""])))))))

(deftest walk-from-refuses-what-no-offset-can-name
  (testing "A ROW OF A SHAPED ARRAY has no offset that means it: its bytes are
            an array while its value is a map, so returning the array's offset
            would answer a vector where the truth is a map. `walk` can express
            it -- a Cursor carries the shape -- and `walk-from` cannot, so it
            says so with -2 rather than returning a plausible wrong offset.

            The offset readers refuse every negative, so the natural
            composition is correct without the caller thinking about it."
    (let [v (vec (for [i (range 6)] {"a" i "b" (str i)}))
          o {:profile :clojure :stringref false :shapes true :index 4 :index-min 2}
          bs (boring/encode-indexed v o)
          src (nav/source bs o)]
      (is (= -2 (nav/walk-from src 0 [2]))
          "a shaped row cannot be named by an offset")
      (is (= {"a" 2 "b" "2"} (nav/value (nav/walk (nav/root bs o) [2])))
          "and `walk` still answers it, which is why the two both exist")
      (is (thrown? clojure.lang.ExceptionInfo (nav/value-at src -2))
          "and the sentinel cannot be read as a value by accident")
      ;; Through the row to a scalar, the answer IS nameable.
      (is (= 2 (nav/value-at src (nav/walk-from src 0 [2 "a"])))
          "a path that continues THROUGH a row lands somewhere an offset means"))))
