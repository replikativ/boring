(ns boring.fixtures
  "Type-identity fixtures, from datahike's dump requirements' \"sanity test we
  would find most useful\".

  The point of these is that they assert **type identity, not value equality**.
  That distinction is what catches the double->float narrowing bug found in
  both clj-cbor (codec.clj:582-598, datahike #633) and fress-cljs
  (3.14159 -> 3.141590118408203).")

(def shape-collapse
  "Collection SHAPES that CBOR cannot distinguish, with what they become.

  Each entry: [label value expected]. These are not defects, they are the
  format: CBOR's wire has arrays, maps and a tagged set, and nothing finer. A
  Clojure-specific codec like nippy keeps a list distinct from a vector because
  it never has to be read by anything but Clojure; that is precisely the trade
  boring makes in the other direction.

  Pinned rather than left implicit, because `=` HIDES all of it -- (= (list 1 2)
  [1 2]) is true, so a value-equality test passes while the type quietly
  changes. The same blind spot that let a Character become a String.

  Runs on both platforms, which is the point: these are the cases where
  ClojureScript's collection zoo differs from the JVM's and a codec that
  dispatches on concrete types rather than protocols starts failing on one side
  only. fress cannot write LazySeq, Subvec, Cons, KeySeq or PersistentTreeMap in
  a browser at all; boring writes them because it dispatches on sequential?,
  map? and set?."
  [["list"          (list 1 2 3)                       [1 2 3]]
   ["lazy-seq"      (map inc [1 2 3])                  [2 3 4]]
   ["lazy-seq-empty" (map inc [])                      []]
   ["cons"          (cons 1 [2 3])                     [1 2 3]]
   ["subvec"        (subvec [1 2 3 4] 1 3)             [2 3]]
   ["keys-seq"      (keys (array-map :a 1 :b 2))       [:a :b]]
   ["map-entry"     (first (array-map :a 1))           [:a 1]]
   ["keys-seq2"     (keys (array-map :x 1))            [:x]]])

(def ordered-collections
  "Collections whose TYPE and ORDER both survive, via reserved tag-27 names.

  These were in `shape-collapse` until the markers existed: a sorted map is a
  CBOR map and a queue is a CBOR array, so they came back a plain map and a
  vector -- value-equal under `=`, and the wrong type. Tag 27 carries a name
  saying what to rebuild, which is the same mechanism records use and needs no
  private tag.

  Order is asserted separately from equality on purpose. A wrong
  reconstruction produced a PersistentTreeMap whose keys were MapEntries: the
  TYPE check passed while the value was wrong, and only comparing `seq` caught
  it."
  [["sorted-map"   (sorted-map :b 2 :a 1 :c 3)]
   ["sorted-set"   (sorted-set 3 1 2)]
   #?@(:clj [["queue"       (into clojure.lang.PersistentQueue/EMPTY [1 2 3])]
             ["queue-empty" clojure.lang.PersistentQueue/EMPTY]]
       :cljs [["queue"       (into cljs.core/PersistentQueue.EMPTY [1 2 3])]
              ["queue-empty" cljs.core/PersistentQueue.EMPTY]])])

(def metadata-preserved
  "Metadata DOES survive, carried as 27([\"clojure/with-meta\", [meta value]]).

  On by default, matching nippy's `*incl-metadata?*`, and for the same reason it
  matters: `=` IGNORES metadata in Clojure, so dropping it passes every
  value-equality test. A limitation no test can see is the dangerous kind --
  the same shape as Character silently becoming a String.

  Nested entries are here because the wrapper has to be consumed on ENTRY, not
  after the inner write: leaving the suppression flag set would silently strip
  metadata from every CHILD of an annotated value while the outer one looked
  fine."
  [["vector+meta"  (with-meta [1 2] {:m true})]
   ["map+meta"     (with-meta {:a 1} {:src "x"})]
   ["set+meta"     (with-meta #{1 2} {:m 1})]
   ["nested-meta"  (with-meta [(with-meta {:a 1} {:inner 1})] {:outer 2})]
   ["meta-on-meta" (with-meta [1] (with-meta {:a 1} {:b 2}))]])

(def type-identity
  "Each entry: [label value]. A round trip must return a value that is both
  equal AND of the same type."
  [["double-1.0"      #?(:clj (double 1.0)  :cljs 1.0)]
   ["double-0.0"      #?(:clj (double 0.0)  :cljs 0.0)]
   ["double--0.0"     #?(:clj (double -0.0) :cljs -0.0)]
   ["double-3.14159"  #?(:clj (double 3.14159) :cljs 3.14159)]
   ["double-NaN"      #?(:clj (double ##NaN) :cljs ##NaN)]
   ["double-Inf"      #?(:clj (double ##Inf) :cljs ##Inf)]
   #?@(:clj [["float-1.0"     (float 1.0)]
             ["float-3.14159" (float 3.14159)]])
   ["long-1"          1]
   ["long-max"        #?(:clj Long/MAX_VALUE :cljs 9007199254740991)]
   ["long-min"        #?(:clj Long/MIN_VALUE :cljs -9007199254740991)]
   ["bigint-1"        #?(:clj (bigint 1) :cljs (js/BigInt 1))]
   ["bigint-huge"     #?(:clj (bigint "18446744073709551616")
                         :cljs (js/BigInt "18446744073709551616"))]
   #?@(:clj [["bigdec-1.50M" 1.50M]      ; scale must survive: 1.50M != 1.5M
             ["bigdec-1.5M"  1.5M]])
   ["string"          "text"]
   ["string-unicode"  "héllo wörld 𐅑"]
   ["keyword"         :kw]
   ["keyword-ns"      :some.ns/kw]
   ["symbol"          'sym]
   ["symbol-ns"       'some.ns/sym]
   ["true"            true]
   ["false"           false]
   ["nil"             nil]
   ["vector"          [1 2 3]]
   ["map"             {:a 1 :b "x"}]
   ["set"             #{:a :b :c}]
   ["nested"          {:xs [1 2 {:y #{:z}}]}]
   ["uuid"            #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"]
   ["instant"         #inst "2026-01-01T12:34:56.789Z"]
   #?@(:clj
       [["ratio"        (/ 22 7)]
        ;; RFC 8746 typed arrays. Compared with bytes=/equiv?, which handles
        ;; arrays; the type assertion is what matters here.
        ["long-array"   (long-array [1 -2 3000000000])]
        ["double-array" (double-array [1.5 -2.25 3.14159])]
        ["int-array"    (int-array [1 2 -3])]
        ["float-array"  (float-array [1.5 2.5])]
        ["short-array"  (short-array [1 -2])]
        ["byte-array"   (byte-array [0 1 127 -1])]])])

(def known-type-collapses
  "Values whose TYPE deliberately does not survive, each with the class it
  becomes. Asserted by `known-collapses-still-collapse`, so a change that makes
  one of these obsolete shows up as a failing test rather than as a stale
  sentence in a document.

  It held exactly one entry -- Character -> String -- and NOTHING referenced
  it: the only mention was a comment in the generative suite, while the docs
  described the behaviour as \"pinned\" here. So the collapse it claimed to pin
  went on being wrong (a Character round-trips now) with no test to say so.
  A fixture nobody asserts is a comment with parentheses."
  [#?@(:clj
       [["instant->date" (java.time.Instant/ofEpochMilli 1234567890123)
         java.util.Date
         "java.util.Date and java.time.Instant are both CBOR tag 1, an epoch
          time with no room for a JVM class name. `:instant-type :instant`
          picks the other reading; a map holding both cannot get both."]
        ["sql-date->local-date" (java.sql.Date. 1577884455500)
         java.time.LocalDate
         "written as RFC 8943 tag 1004 (full-date), which is what a date with
          no time-of-day MEANS. The time-of-day is dropped on the way out, so
          `:date-type :sql-date` returns the class but not the millis."]
        ["byte->long"    (byte 16)  java.lang.Long
         "CBOR has one integer type. `=` still holds -- this is widening, not
          the silent corruption a Character becoming a String was."]
        ["short->long"   (short 42) java.lang.Long "as above"]
        ["integer->long" (int 3)    java.lang.Long "as above"]])])

(def byte-stability
  "Encoding the same value twice must yield identical bytes — the property that
  makes a dump signable and a re-export diffable (datahike's dump requirements)."
  [["small-map"  {:name "Alice" :score 42}]
   ["datom"      {:e 100 :a :user/name :v "person-0" :tx 536870912 :added true}]
   ["mixed-coll" [1 "two" :three 'four true nil [5] {:six 6}]]
   ["set"        #{:a :b :c :d :e :f :g :h}]
   ["big-map"    (into {} (for [i (range 40)] [(keyword (str "k" i)) i]))]])
