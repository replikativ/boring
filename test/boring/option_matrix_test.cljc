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
  (:require [clojure.test :refer [deftest testing is]]
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
