(ns boring.option-matrix-test
  "Freeze the option matrix — see `boring.option-matrix` for the shape.

  Two assertions, and the first is the one that matters:

  1. **Every entry point on a side gives the SAME verdict.** A fix applied to
     five of six entry points fails here. That is the defect this branch has
     shipped repeatedly: `{:profile :nope}` refused by `decode-seq-from` and
     `decode-with`, accepted by `decode` and `decode-seq`.
  2. **The verdict is the frozen one.** Agreement alone would be satisfied by
     every entry point accepting garbage, and a widened default is silent.

  Both run on both platforms from one table, so a JVM-only fix fails on
  ClojureScript rather than passing quietly."
  (:require [clojure.set]
             [clojure.test :refer [deftest testing is]]
            [boring.core :as boring]
            [boring.option-matrix :as m]))

(def frozen
  "`[side label] -> verdict`, one per case, because every entry point on a side
  must agree. Generated on the JVM and asserted from both platforms — never the
  reverse, so a ClojureScript-only defect cannot freeze itself in."
  {[:decode "default"]               :ok
   [:decode "max-depth legal"]       :ok
   [:decode "max-items legal"]       :ok
   [:decode "chunk-size legal"]      :ok
   [:decode "profile interop"]       :ok
   ;; All three legal forms must be ACCEPTED by every decode entry point. The
   ;; `:fallback` row is not redundant with `default`: a reader that tested
   ;; callability before the keywords would accept it here and then invoke it,
   ;; and only these rows plus `on-unknown-record-policies` distinguish those.
   [:decode "on-unknown-record fallback"] :ok
   [:decode "on-unknown-record error"]    :ok
   [:decode "on-unknown-record fn"]       :ok
   [:decode "max-depth nil"]         :boring/bad-option
   [:decode "max-depth string"]      :boring/bad-option
   [:decode "max-depth fractional"]  :boring/bad-option
   [:decode "max-depth zero"]        :boring/bad-option
   [:decode "max-depth negative"]    :boring/bad-option
   [:decode "max-depth oversized"]   :boring/bad-option
   [:decode "max-items string"]      :boring/bad-option
   [:decode "max-items fractional"]  :boring/bad-option
   [:decode "max-items negative"]    :boring/bad-option
   [:decode "chunk-size string"]     :boring/bad-option
   [:decode "chunk-size zero"]       :boring/bad-option
   [:decode "chunk-size negative"]   :boring/bad-option
   [:decode "on-unknown-record unknown"] :boring/bad-option
   [:decode "on-unknown-record number"]  :boring/bad-option
   [:decode "profile unknown"]       :boring/unknown-profile
   [:decode "registry number"]       :boring/bad-option

   [:encode "default"]               :ok
   [:encode "profile interop"]       :ok
   [:encode "index legal"]           :ok
   [:encode "float-policy legal"]    :ok
   ;; `:index 0` is the documented off switch, and `encode` never indexes, so
   ;; accepting it there is not the same silence as accepting `:index -1`.
   ;; `encode-indexed` refuses it by name, because building an index with no
   ;; index is not something it can do. Recorded as an override rather than
   ;; smoothed away: it is the one asymmetry in the table, and an unrecorded
   ;; exception is indistinguishable from drift.
   [:encode "index zero"]            :ok
   [:encode "index string"]          :boring/bad-option
   [:encode "index fractional"]      :boring/bad-option
   [:encode "index negative"]        :boring/bad-option
   [:encode "index 2^31"]            :boring/bad-option
   [:encode "index-min string"]      :boring/bad-option
   [:encode "index-min negative"]    :boring/bad-option
   [:encode "float-policy unknown"]  :boring/bad-option
   [:encode "encode-fallback bad"]   :boring/bad-option
   [:encode "profile unknown"]       :boring/unknown-profile
   [:encode "registry number"]       :boring/bad-option

   [:index "default"]                :ok
   [:index "index legal"]            :ok
   [:index "index zero"]             :boring/bad-option
   [:index "index string"]           :boring/bad-option
   [:index "index fractional"]       :boring/bad-option
   [:index "index negative"]         :boring/bad-option
   [:index "index 2^31"]             :boring/bad-option
   [:index "index-min string"]       :boring/bad-option
   [:index "index-min negative"]     :boring/bad-option})

(def per-entry-overrides
  "The recorded exceptions to \"every entry point agrees\". Exactly one."
  {[:encode "index zero" "encode-indexed"] :boring/bad-option})

(deftest every-entry-point-on-a-side-agrees
  (let [got (m/run)]
    (doseq [[[side label _ :as k] v] got
            :let [want (or (per-entry-overrides k) (frozen [side label]))]]
      (is (= want v) (str side " " label " " (nth k 2))))))

(deftest the-matrix-covers-what-it-claims-to
  (testing "a case dropped from the table, or an entry point that stopped being
            exercised, must fail rather than shrink the matrix silently"
    (let [got (m/run)]
      (is (= (count m/cases) (count (distinct (map (fn [[_ label _]] label) (keys got))))))
      (is (= (set (keys frozen)) (set (map (fn [[side label _]] [side label]) (keys got))))))))

(deftest every-declared-entry-point-is-exercised-or-named-as-absent
  (testing "the two assertions above count CASE LABELS, so an entry point could
            be dropped from a side's table without either of them moving -- and
            one was: three encode rows sit behind `#?@(:clj ...)` directly under
            a comment promising that a row is marked rather than omitted. The
            matrix then could not see that `write-seq!` handles `:stringref`
            differently on the two platforms.

            So the claim is checked against `declared-entry-points`, which is
            written once for both platforms, and a shortfall has to be NAMED in
            `unavailable-entry-points` rather than simply not being there."
    (let [got (m/run)
          exercised (reduce (fn [acc [side _ ep]] (update acc side (fnil conj #{}) ep))
                            {} (keys got))]
      (doseq [[side declared] m/declared-entry-points]
        (let [absent (get m/unavailable-entry-points side #{})
              ran (get exercised side #{})]
          (is (empty? (clojure.set/intersection ran absent))
              (str side ": " (pr-str (clojure.set/intersection ran absent))
                   " is named unavailable but ran anyway"))
          (is (= declared (clojure.set/union ran absent))
              (str side ": declared " (pr-str declared)
                   " but exercised " (pr-str ran)
                   " and named absent " (pr-str absent))))))))

(deftest ok-really-means-it-ran
  (testing "`:ok` is recorded when a call RETURNS, so a row could pass by doing
            nothing. These assert the work actually happened -- the guard the
            second vacuous test on this branch did not have."
    (let [bs (boring/encode m/specimen {:stringref false})]
      (is (= m/specimen (boring/decode bs)))
      (is (= m/specimen (first (boring/decode-seq bs))))
      (is (pos? (m/len (boring/encode m/specimen {:max-depth 64}))))
      (is (some? (boring/build-index bs {:index 2 :index-min 2})))
      (testing "and the index one is real: a stride the format cannot carry is
                refused rather than truncated to something plausible"
        (is (= :boring/bad-option
               (m/verdict #(boring/build-index bs {:index 2147483648}))))))))

(deftest an-option-typo-is-caught-rather-than-ignored
  (testing "Unknown keys pass -- callers thread their own map through, and
            konserve does -- but a key ONE CHARACTER from a real option is far
            more likely a typo than a foreign key, and this library has shipped
            two defects that were exactly that. The suggestion is the point:
            an error that names what you probably meant costs nothing to act on."
    (doseq [[typo meant] [[:max-item :max-items]
                          [:stringrefs :stringref]
                          [:cannonical :canonical]
                          [:shape :shapes]]]
      (let [d (try (do (boring/encode {:a 1} {typo true}) nil)
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
        (is (= :boring/bad-option (:type d)) (str typo))
        (is (= meant (:did-you-mean d)) (str typo " should suggest " meant))))
    (testing "and a key that looks nothing like an option is left alone, which
              is what keeps the option map open"
      (doseq [k [:konserve/version :totally-unrelated :x]]
        (is (some? (boring/encode {:a 1} {k true})) (str k))))))
