(ns boring.hexdump-cases
  "The corpus for the cross-platform byte-identity gate.

  Values expressible identically on both platforms -- no Double/Long
  distinction, no Ratio/BigDecimal, since those have no ClojureScript
  counterpart.

  Shared, not duplicated. `bench/hexdump_jvm.clj` and
  `bench/cljs/cljsbench/hexdump.cljs` dump these and the two outputs are
  diffed; they each held their own hand-synced copy of the list, so the gate
  compared whatever the two copies happened to agree on. A drift between them
  would not fail -- it would quietly shrink what the gate covers.

  Lives under test/ because that is the one source root both the :bench and
  the ClojureScript bench aliases have on their classpath.")

(def cases
  [["nil" nil] ["true" true] ["false" false]
   ["int-0" 0] ["int-23" 23] ["int-24" 24] ["int-1000" 1000]
   ["int-neg" -1000] ["int-big" 1000000] ["int-max-safe" 9007199254740991]
   ["float" 3.14159] ["float-half" 1.5]
   ["str-empty" ""] ["str-a" "a"] ["str-hello" "hello world"]
   ["str-unicode" "héllo wörld"] ["str-long" "0123456789012345678901234567890123456789012345678901234567890123456789"]
   ["kw" :kw] ["kw-ns" :ns/kw] ["sym" 'sym] ["sym-ns" 'ns/sym]
   ["vec" [1 2 3]] ["vec-empty" []] ["map-empty" {}]
   ["map" {:a 1 :b "x"}]
   ["datom" {:e 100 :a :user/name :v "person-0" :tx 536870912 :added true}]
   ["datoms-3" [{:a :user/name} {:a :user/name} {:a :user/name}]]
   ["repeated-strings" ["hello world" "hello world" "hello world"]]
   ["nested" {:xs [1 [2 [3 {:y 4}]]]}]
   ["uuid" #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"]
   ["mixed-keys" {"s" 1 :k 2}]
   ["shaped-3" [{:e 1 :a :x} {:e 2 :a :y} {:e 3 :a :z}]]
   ["shaped-mixed" [{:a 1} {:b 2}]]])
