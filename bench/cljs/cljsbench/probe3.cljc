(ns cljsbench.probe3
  "SCRATCH -- cross-platform audit round 9. Delete when done."
  (:require [boring.core :as b]
            [cljsbench.probe-fix :as fx]))

(defn- blen [b] #?(:clj (alength ^bytes b) :cljs (.-length b)))
(defn hexs [bs]
  (apply str (for [i (range (blen bs))]
               #?(:clj (format "%02x" (bit-and (aget ^bytes bs i) 0xff))
                  :cljs (.padStart (.toString (aget bs i) 16) 2 "0")))))
(defn- etype [e]
  (or (:type (ex-data e)) #?(:clj (.getName (class e)) :cljs (str (.-name e)))))
(defn- try* [f]
  (try (str (f)) (catch #?(:clj Throwable :cljs :default) e (str "ERR " (etype e)))))

(defn- s [n c] (apply str (repeat n c)))

;; ---------------------------------------------------------------- stringref
;; The spec threshold: a string earns an index when it is at least as long as
;; the reference would be -- 3 octets for index 0-23, 4 for 24-255, 5 for
;; 256-65535, 7 beyond. Sweep across each boundary, and across the index-width
;; boundaries themselves, with both text and byte strings.

(def sr-cases
  (concat
   ;; text strings of every length 0..8, repeated -- which get an index?
   (for [n (range 0 9)]
     [(str "sr-txt-" n) (vec (repeat 4 (s n "a"))) nil])
   ;; byte strings of every length 0..8
   (for [n (range 0 9)]
     [(str "sr-bin-" n)
      (vec (repeat 4 #?(:clj (byte-array n) :cljs (js/Uint8Array. n))))
      nil])
   [;; cross the index-24 boundary: 24 distinct 3-char strings, then a repeat
    ["sr-idx24" (conj (mapv #(str "a" (quot % 10) (mod % 10)) (range 30))
                      "a00" "a23" "a24" "a29") nil]
    ;; a string long enough that its reference is 4 octets, at index >= 24
    ["sr-idx24-4oct" (conj (mapv #(str "b" (quot % 10) (mod % 10)) (range 30))
                           "b00") nil]
    ;; UUIDs are byte strings -- do they take an index on both?
    ["sr-uuid3" (vec (repeat 3 #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6")) nil]
    ;; a bignum magnitude is a byte string
    ["sr-bignum" [#?(:clj 123456789012345678901234567890N
                     :cljs (js/BigInt "123456789012345678901234567890"))
                  #?(:clj 123456789012345678901234567890N
                     :cljs (js/BigInt "123456789012345678901234567890"))] nil]
    ;; nested namespace shadowing: a record opens no namespace, but a nested
    ;; encode does not happen -- test repeated keys across nesting instead
    ["sr-deep" [["abcdefgh" ["abcdefgh" ["abcdefgh"]]] "abcdefgh"] nil]
    ;; keywords are tag 39 around a text string
    ["sr-kw" [:foo/bar :foo/bar :foo/bar] nil]
    ["sr-sym" ['foo/bar 'foo/bar] nil]
    ;; a key repeated as both a map key and a value
    ["sr-keyval" [(array-map "abcdefgh" "abcdefgh") (array-map "abcdefgh" "abcdefgh")] nil]
    ;; empty string / empty bytes never take an index
    ["sr-empty" ["" "" #?(:clj (byte-array 0) :cljs (js/Uint8Array. 0))] nil]]))

;; ---------------------------------------------------------------- canonical
(def can-cases
  [["can-k-majors" (array-map 1 :a -1 :b "" :c #?(:clj (byte-array 0) :cljs (js/Uint8Array. 0)) :d
                              [] :e true :f nil :g)
    {:profile :canonical}]
   ["can-k-majors-7049" (array-map 1 :a -1 :b "" :c
                                   #?(:clj (byte-array 0) :cljs (js/Uint8Array. 0)) :d
                                   [] :e true :f nil :g)
    {:profile :canonical-rfc7049}]
   ["can-k-lens" (array-map "b" 1 "aa" 2 "ccc" 3 "a" 4) {:profile :canonical}]
   ["can-k-lens-7049" (array-map "b" 1 "aa" 2 "ccc" 3 "a" 4) {:profile :canonical-rfc7049}]
   ["can-k-ints" (array-map 24 :a 0 :b 1000 :c 100000 :d -1 :e -1000 :f) {:profile :canonical}]
   ["can-set-mixed" #{1 "a" :b [1] true nil} {:profile :canonical}]
   ["can-set-nested" #{[1 2] [1] [2]} {:profile :canonical}]
   ["can-dup-after-narrow" (array-map "a" 1.0 "b" 2.0) {:profile :canonical}]
   ["arch-k-lens" (array-map "b" 1 "aa" 2 "ccc" 3 "a" 4) {:profile :archival}]
   ["can-nested-set" (array-map "z" #{"b" "a"} "y" 1) {:profile :canonical}]])

;; ---------------------------------------------------------------- shapes
(def shape-cases
  [["sh-meta-first"  [(with-meta (array-map "a" 1) {:m 1}) (array-map "a" 2)] {:shapes true :stringref false}]
   ["sh-meta-second" [(array-map "a" 1) (with-meta (array-map "a" 2) {:m 1})] {:shapes true :stringref false}]
   ["sh-sorted-first" [(into (sorted-map) {"a" 1}) (array-map "a" 2)] {:shapes true :stringref false}]
   ["sh-sorted-second" [(array-map "a" 1) (into (sorted-map) {"a" 2})] {:shapes true :stringref false}]
   ["sh-meta-vec"    (with-meta [(array-map "a" 1) (array-map "a" 2)] {:m 1}) {:shapes true :stringref false}]
   ["sh-plain"       [(array-map "a" 1) (array-map "a" 2)] {:shapes true :stringref false}]
   ["sh-nested"      [(array-map "a" [(array-map "b" 1) (array-map "b" 2)])
                      (array-map "a" [(array-map "b" 3) (array-map "b" 4)])]
    {:shapes true :stringref false}]
   ["sh-nil-vals"    [(array-map "a" nil) (array-map "a" nil)] {:shapes true :stringref false}]])

;; ------------------------------------------------------ build-index on foreign bytes
(def bix-cases
  [["bix-indef-arr"  "9f0102030405060708090a0b0c0d0e0f101112131415161718181819ff"]
   ["bix-indef-map"  "bf6161016162026163036164046165056166066167076168086169096a611009ff"]
   ["bix-indef-str"  "7f6161616261636164ff"]
   ["bix-tagchain"   "c0c0c0c0c0c0c0c0c0c09828000102030405060708090a0b0c0d0e0f101112131415161718181819181a181b181c181d181e181f1820182118221823182418251826182718281829"]
   ["bix-shaped"     "d99ae18281616198288100810181028103810481058106810781088109810a810b810c810d810e810f811081118112811381148115811681178118181181181981181a81181b81181c81181d81181e81181f8118208118218118228118238118248118258118268118278118288118298304"]
   ["bix-tag40"      "d828828219020282010203040506070809"]
   ["bix-nested-40"  nil]
   ["bix-empty"      "80"]
   ["bix-bignum"     "c2490102030405060708090a"]])

(defn- nest [n]
  ;; n-deep 1-element arrays around a 20-element array
  (str (s n "81") "9414000102030405060708090a0b0c0d0e0f1011121300"))

(defn run []
  (doseq [[label v opts] sr-cases]
    (println (str "SR\t" label "\t" (try* #(hexs (b/encode v opts))))))
  (doseq [[label v opts] can-cases]
    (println (str "CAN\t" label "\t" (try* #(hexs (b/encode v opts))))))
  (doseq [[label v opts] shape-cases]
    (println (str "SH\t" label "\t" (try* #(hexs (b/encode v opts)))))
    (println (str "SHRT\t" label "\t"
                  (try* #(let [d (b/decode (b/encode v opts))]
                           (str (pr-str d) " metas="
                                (pr-str (mapv meta (if (sequential? d) d [d])))))))))
  (doseq [[label h] bix-cases :when h]
    (let [bs (fx/unhex h)]
      (println (str "BIX\t" label "\t"
                    (try* #(let [i (b/build-index bs {:index 4 :index-min 2})]
                             (if i (pr-str (mapv (fn [k] [k (get i k)])
                                                 [:containers :counts :sorted]))
                                 "nil")))))))
  ;; the index-walk depth bound: 200 on both?
  (doseq [n [10 199 200 201 300]]
    (let [bs (fx/unhex (nest n))]
      (println (str "BIXD\t" n "\t"
                    (try* #(let [i (b/build-index bs {:index 4 :index-min 2})]
                             (if i "SOME" "nil")))))))
  ;; build-index over a stringref document -- skip-from has no stringref branch on CLJS
  (let [body (b/encode (vec (repeat 30 "hello world hello")) {:stringref true})]
    (println (str "BIXSR\tencode-sr\t" (hexs body)))
    (println (str "BIXSR\tbuild\t"
                  (try* #(let [i (b/build-index body {:index 4 :index-min 2})]
                           (if i (pr-str [(:containers i) (:counts i) (:sorted i)]) "nil")))))))
