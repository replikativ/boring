(ns cljsbench.probe2
  "SCRATCH -- cross-platform audit round 9. Delete when done."
  (:require [boring.core :as b]
            [cljsbench.probe-fix :as fx]))

(defn- blen [b] #?(:clj (alength ^bytes b) :cljs (.-length b)))

(defn- etype [e]
  (or (:type (ex-data e))
      #?(:clj (.getName (class e)) :cljs (str (.-name e)))))

(defn- try* [f]
  (try (str (f)) (catch #?(:clj Throwable :cljs :default) e (str "ERR " (etype e)))))

;; --- decode-seq-from adapters ------------------------------------------------

(defn- from-bytes [bs opts]
  #?(:clj (b/decode-seq-from (java.io.ByteArrayInputStream. bs) opts)
     :cljs (let [pos (atom 0)
                 n (get opts :chunk-size 65536)
                 pull (fn []
                        (let [p @pos]
                          (when (< p (.-length bs))
                            (let [e (min (.-length bs) (+ p n))]
                              (reset! pos e)
                              (.slice bs p e)))))]
             (b/decode-seq-from pull opts))))

;; --- cases -------------------------------------------------------------------

(def framing-cases
  ;; [label fixture opts]
  [["sealed500"   "sealed500" nil]
   ["sealed500-md3"  "sealed500" {:max-depth 3}]
   ["sealed500-md1"  "sealed500" {:max-depth 1}]
   ["sealed500-md2"  "sealed500" {:max-depth 2}]
   ["sealed500-mi100" "sealed500" {:max-items 100}]
   ["sealed500-mi5"  "sealed500" {:max-items 5}]
   ["sealed500-cs64" "sealed500" {:chunk-size 64}]
   ["sealed1"     "sealed1"   nil]
   ["sealed1-md3" "sealed1"   {:max-depth 3}]
   ["sealed0"     "sealed0"   nil]
   ["sealed0-md3" "sealed0"   {:max-depth 3}]
   ["unindexed"   "unindexed" nil]
   ["unindexed-md3" "unindexed" {:max-depth 3}]
   ["two"         "two"       nil]
   ["ei-m40"      "ei-m40"    nil]
   ["ei-m40-md3"  "ei-m40"    {:max-depth 3}]
   ["ei-m40-md4"  "ei-m40"    {:max-depth 4}]])

(defn run-framing []
  (doseq [[label fk opts] framing-cases]
    (let [bs (fx/unhex (fx/fixtures fk))]
      (println (str "SEQ\t" label "\t" (try* #(count (b/decode-seq bs opts)))))
      (println (str "SQF\t" label "\t" (try* #(count (from-bytes bs opts))))))
    ;; truncated by one byte: is the trailing frame still recognised?
    (let [full (fx/unhex (fx/fixtures fk))
          bs (fx/cut full (dec (blen full)))]
      (println (str "SEQCUT\t" label "\t" (try* #(count (b/decode-seq bs opts))))))))

;; --- error typing ------------------------------------------------------------

(defn- chain [n head]
  (let [b #?(:clj (byte-array (+ n 1)) :cljs (js/Uint8Array. (+ n 1)))]
    (dotimes [i n] #?(:clj (aset-byte b i (unchecked-byte head)) :cljs (aset b i head)))
    #?(:clj (aset-byte b n (unchecked-byte 0)) :cljs (aset b n 0))
    b))

(def err-cases
  [;; label, bytes-thunk, opts
   ["deep-tag-3000"    #(chain 3000 0xc0) nil]
   ["deep-arr-3000"    #(chain 3000 0x81) nil]
   ["deep-tag-100"     #(chain 100 0xc0) nil]
   ["truncated-arr"    #(fx/unhex "830102") nil]
   ["bad-count"        #(fx/unhex "9affffffff") nil]
   ["reserved-simple"  #(fx/unhex "f81c") nil]
   ["bad-utf8"         #(fx/unhex "62c328") nil]
   ["dup-keys"         #(fx/unhex "a2616101616102") nil]
   ["indef-str-bad"    #(fx/unhex "7f01ff") nil]
   ["unknown-tag-strict" #(fx/unhex "d9ea60 01") {:tolerate-unknown-tags false}]
   ["tag2-nonbytes"    #(fx/unhex "c201") nil]
   ["tag4-notarray"    #(fx/unhex "c401") nil]
   ["tag30-bad"        #(fx/unhex "d81e01") nil]
   ["tag40-bad"        #(fx/unhex "d82801") nil]
   ["tag258-notarray"  #(fx/unhex "d90102a0") nil]
   ["tag64-bad"        #(fx/unhex "d840646e6f7065") nil]
   ["tag39649-bad"     #(fx/unhex "d99ae101") nil]
   ["stringref-outside" #(fx/unhex "d81900") nil]
   ["tag27-empty"      #(fx/unhex "d81b80") nil]
   ["tag0-bad"         #(fx/unhex "c001") nil]
   ["tag1-huge"        #(fx/unhex "c11b0000009184e72a00") nil]
   ["tag35-notstr"     #(fx/unhex "d82301") nil]
   ["tag37-badlen"     #(fx/unhex "d82541ff") nil]
   ["maxitems"         #(b/encode (vec (range 100))) {:max-items 5}]
   ["maxdepth-w"       #(fx/unhex "818181818101") {:max-depth 2}]
   ["breakless"        #(fx/unhex "9f0102") nil]
   ["extra-break"      #(fx/unhex "ff") nil]
   ["nan-payload"      #(fx/unhex "f97e01") nil]
   ["half-subnormal"   #(fx/unhex "f90001") nil]])

(defn run-errors []
  (doseq [[label mk opts] err-cases]
    (let [bs (try (mk) (catch #?(:clj Throwable :cljs :default) _ nil))]
      (println (str "E-dec\t" label "\t"
                    (if (nil? bs) "SETUP-FAIL"
                        (try* #(let [v (b/decode bs opts)] (str "OK " (pr-str v)))))))
      (println (str "E-seq\t" label "\t"
                    (if (nil? bs) "SETUP-FAIL"
                        (try* #(str "OK " (count (b/decode-seq bs opts)))))))
      (println (str "E-sqf\t" label "\t"
                    (if (nil? bs) "SETUP-FAIL"
                        (try* #(str "OK " (count (from-bytes bs opts))))))))))

;; --- option verdicts ---------------------------------------------------------

(def opt-cases
  [["index-2^31"      {:index 2147483648}]
   ["index-2^31-1"    {:index 2147483647}]
   ["index-0"         {:index 0}]
   ["index-neg"       {:index -1}]
   ["index-float"     {:index 1.5}]
   ["chunk-0"         {:chunk-size 0}]
   ["profile-nope"    {:profile :nope}]
   ["registry-5"      {:registry 5}]
   ["maxdepth-4000"   {:max-depth 4000}]
   ["maxitems--1"     {:max-items -1}]
   ["validate-utf8-s" {:validate-utf8 "false"}]
   ["fallback-kw"     {:encode-fallback :placehodler}]
   ["shapes-str"      {:shapes "yes"}]
   ["canonical-over"  {:profile :canonical :stringref true}]
   ["stringref+index" {:stringref true :index 4}]])

(defn run-opts []
  (doseq [[label o] opt-cases]
    (println (str "O-enc\t" label "\t" (try* #(str "OK " (blen (b/encode [1 2 3] o))))))
    (println (str "O-dec\t" label "\t" (try* #(str "OK " (b/decode (fx/unhex "83010203") o)))))
    (println (str "O-ei\t" label "\t"
                  (try* #(str "OK " (blen (b/encode-indexed (vec (range 40)) o))))))
    (println (str "O-bix\t" label "\t"
                  (try* #(str "OK " (some? (b/build-index (b/encode (vec (range 40)) nil) o))))))))

;; --- register-tag ------------------------------------------------------------

(def tag-cases [["tag-2^53" 9007199254740992] ["tag-2^53-1" 9007199254740991]
                ["tag-2^63" 9223372036854775807] ["tag-neg" -1]
                ["tag-1.5" 1.5] ["tag-25" 25] ["tag-256" 256] ["tag-ok" 40001]])

(defn run-tags []
  (doseq [[label t] tag-cases]
    (println (str "TAG\t" label "\t"
                  (try* #(do (b/register-tag (b/tag-registry) t nil nil identity) "OK"))))))

(defn run-all []
  (run-framing) (run-errors) (run-opts) (run-tags))
