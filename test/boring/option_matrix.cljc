(ns boring.option-matrix
  "Every option, every public entry point, both platforms — as data.

  The recurring defect on this branch is one rule with several
  implementations, where the copies drift and the system looks correct because
  a second copy happens to cover the first one's gap. Option validation is that
  shape at its worst: twelve validators and two `configure` functions per
  platform, so `{:profile :nope}` was refused by `decode-seq-from` and accepted
  by `decode`, and `{:registry 5}` raised a raw ClassCastException on the JVM
  and was silently ignored on ClojureScript.

  So the assertion here is not \"this option is rejected\". It is **every entry
  point on a side returns the SAME verdict, and that verdict is the frozen
  one**. The first half is what makes a fix applied to five of six entry points
  fail; the second is what stops a default quietly widening.

  Shared rather than duplicated per platform, for the reason this whole
  exercise exists."
  (:require [boring.core :as boring]))

;; ------------------------------------------------------------------ verdicts

(defn verdict
  "`:ok` if `f` returns, the error's `:type` if it throws typed, `:untyped`
  otherwise. `:untyped` is never an acceptable frozen value — it is what a raw
  ClassCastException looks like from the outside."
  [f]
  (try (do (f) :ok)
       (catch #?(:clj Throwable :cljs :default) e
         (or (:type (ex-data e)) :untyped))))

;; ------------------------------------------------------------- the specimens

(def specimen
  "A value with a map, a vector, strings and integers — enough that every
  option under test has something to act on."
  {:rows [{:e 1 :a "x" :v "alpha"} {:e 2 :a "y" :v "beta"}]
   :n 42 :s "hello"})

(defn- encoded ^"[B" []
  (boring/encode specimen {:stringref false}))

;; `decode-seq-from` reads a stream on the JVM and a pull function on
;; ClojureScript. That difference is real and lives HERE, in one expression,
;; rather than being spread through the table.
(defn- source-of [bs]
  #?(:clj (java.io.ByteArrayInputStream. ^bytes bs)
     :cljs (let [done (volatile! false)]
             (fn [] (when-not @done (vreset! done true) bs)))))

(defn len [bs]
  #?(:clj (alength ^bytes bs) :cljs (.-length bs)))

;; -------------------------------------------------------------- entry points
;;
;; Each takes an options map and exercises the option for real. A row that
;; merely CONSTRUCTS something would pass by not running, which is how the
;; second vacuous test on this branch passed: an input that never reached the
;; code under test. So every one of these ends in a decoded value or a byte
;; count, and `ok-check` below asserts it.

(def decode-side
  [["decode"          (fn [o] (boring/decode (encoded) o))]
   ;; `reader` is here because it was the ONE decode entry point the option
   ;; unification missed on ClojureScript, and the matrix could not see it.
   ["reader"          (fn [o] (boring/decode-with (boring/reader (encoded) o) (encoded)))]
   ["decode-with"     (fn [o] (boring/decode-with (boring/reader #?(:clj (byte-array 0)
                                                                    :cljs (js/Uint8Array. 0))
                                                                o)
                                                  (encoded)))]
   ["decode-seq"      (fn [o] (first (boring/decode-seq (encoded) o)))]
   ["decode-seq-from" (fn [o] (first (boring/decode-seq-from (source-of (encoded)) o)))]])

(def encode-side
  [["encode"          (fn [o] (len (boring/encode specimen o)))]
   ["encode-buffered" (fn [o] (boring/encode-buffered! (boring/writer 256) specimen o))]
   ["encode-indexed"  (fn [o] (len (boring/encode-indexed specimen o)))]
   ;; JVM-only, and both were missed: `write-seq!` read and COERCED `:index`
   ;; off the raw map before resolution ran, and `seal-index!`'s opts arity was
   ;; behind no gate at all. Marked rather than omitted -- a row that silently
   ;; is not there on one platform is how a gap hides.
   #?@(:clj [["write-seq!"      (fn [o] (let [out (java.io.ByteArrayOutputStream.)]
                                          (boring/write-seq! (boring/writer 4096)
                                                             [specimen] out o)))]
             ["write-indexed!"  (fn [o] (let [out (java.io.ByteArrayOutputStream.)]
                                          (boring/write-indexed! (boring/writer 4096)
                                                                 specimen out o)))]
             ["seal-index!"     (fn [o] (let [out (java.io.ByteArrayOutputStream.)
                                              bs (boring/encode specimen {:stringref false})
                                              ix (boring/build-index bs {:index 2 :index-min 2})]
                                          (boring/seal-index! (boring/writer 4096) out ix
                                                              (len bs) o)))]])])

;; `build-index` takes the same `:index`/`:index-min` knobs but no encode
;; options, so it is its own side rather than a row in `encode-side`.
(def index-side
  [["build-index"     (fn [o] (some? (boring/build-index (encoded) o)))]])

(defn sides [] {:decode decode-side :encode encode-side :index index-side})

;; --------------------------------------------------------------- the matrix
;;
;; `:sides` names which entry points an option is meant to reach. An option
;; that means nothing on a side is not in the table for it -- silence there
;; would be indistinguishable from "accepted and ignored", which is the bug.

(def cases
  "`[label opts sides]`. Grouped by the class of wrongness rather than by
  option, because the classes are what a validator either handles uniformly or
  does not."
  [;; --- legal values, to prove the entry points run at all ------------------
   ["default"                {}                        #{:decode :encode :index}]
   ["max-depth legal"        {:max-depth 64}            #{:decode}]
   ["max-items legal"        {:max-items 1000}          #{:decode}]
   ["chunk-size legal"       {:chunk-size 4096}         #{:decode}]
   ["profile interop"        {:profile :interop}        #{:decode :encode}]
   ["index legal"            {:index 4 :index-min 2}    #{:encode :index}]
   ["float-policy legal"     {:float-policy :shortest}  #{:encode}]

   ;; --- wrong type ---------------------------------------------------------
   ["max-depth nil"          {:max-depth nil}           #{:decode}]
   ["max-depth string"       {:max-depth "5"}           #{:decode}]
   ["max-depth fractional"   {:max-depth 1.5}           #{:decode}]
   ["max-items string"       {:max-items "5"}           #{:decode}]
   ["max-items fractional"   {:max-items 1.5}           #{:decode}]
   ["chunk-size string"      {:chunk-size "5"}          #{:decode}]
   ["index string"           {:index "x"}               #{:encode :index}]
   ["index fractional"       {:index 1.5}               #{:encode :index}]
   ["index-min string"       {:index-min "x"}           #{:encode :index}]

   ;; --- out of range -------------------------------------------------------
   ["max-depth zero"         {:max-depth 0}             #{:decode}]
   ["max-depth negative"     {:max-depth -1}            #{:decode}]
   ["max-depth oversized"    {:max-depth 100000}        #{:decode}]
   ["max-items negative"     {:max-items -1}            #{:decode}]
   ["chunk-size zero"        {:chunk-size 0}            #{:decode}]
   ["chunk-size negative"    {:chunk-size -1}           #{:decode}]
   ["index zero"             {:index 0}                 #{:encode :index}]
   ["index negative"         {:index -1}                #{:encode :index}]
   ;; 2^31: the JVM's index-opt caps here and ClojureScript's caps at
   ;; MAX_SAFE_INTEGER, so the same option has two ceilings and the wider one
   ;; writes a stride the JVM's int slot arithmetic cannot read.
   ["index 2^31"             {:index 2147483648}        #{:encode :index}]
   ["index-min negative"     {:index-min -1}            #{:encode :index}]

   ;; --- unknown or unusable value for a named option -----------------------
   ["profile unknown"        {:profile :nope}           #{:decode :encode}]
   ["float-policy unknown"   {:float-policy :nope}      #{:encode}]
   ["encode-fallback bad"    {:encode-fallback :nope}   #{:encode}]
   ["registry number"        {:registry 5}              #{:decode :encode}]])

(defn run
  "The whole matrix as `{[side label entry] verdict}`."
  []
  (into (sorted-map)
        (for [[label opts want] cases
              [side eps] (sides)
              :when (contains? want side)
              [nm f] eps]
          [[side label nm] (verdict #(f opts))])))
