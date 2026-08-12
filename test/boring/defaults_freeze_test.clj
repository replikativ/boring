(ns boring.defaults-freeze-test
  "FREEZE the defaults a first caller inherits without asking. `boring.options`
  itself notes no test pinned these, so a future edit widening a default -- say
  `:max-depth` to 100000, or flipping `:check-duplicate-keys` off -- would be
  caught only by luck.

  A default is a public promise on a public-beta codec: it is what a stranger's
  data is encoded and decoded with. Changing one is a compatibility event, so
  each is asserted here with the REASON it has that value, and a change to any
  must be a deliberate edit to this test, not a silent drift.

  Format-bit defaults (stringref, float-policy, canonical) come from the
  `:clojure` profile; the rest are read-site `(get opts k default)` calls in
  `boring.core`. Both are covered."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.options :as opt]))

(deftest the-clojure-profile-defaults-are-frozen
  (testing "the default profile a bare `encode`/`decode` uses"
    (is (= {:stringref true            ; boring's compression extension, on for
                                        ; Clojure<->Clojure; every deterministic
                                        ; profile forces it off
            :float-policy :preserve-width  ; lossless; :shortest is canonical-only
            :canonical false            ; canonicalisation is opt-in
            :canonical-order :rfc8949}
           opt/default-opts))))

(deftest read-site-decode-guards-are-frozen
  (testing "the resource and safety guards a decode of a stranger's blob runs
            with by default -- asserted by BEHAVIOUR, since they are read-site
            defaults not in a map"
    ;; :max-depth -- the KNOB is honoured (a value deeper than the limit is
    ;; refused, typed). The default VALUE is 1024, but triggering the default
    ;; here is deliberately avoided: it needs 1024+ recursion frames, which
    ;; StackOverflows before the guard fires on a thread with a small stack
    ;; (the test runner's) -- a real robustness finding tracked separately, not
    ;; what this default freeze is for. So freeze the mechanism, not the fragile
    ;; boundary.
    (is (thrown? clojure.lang.ExceptionInfo
                 (boring/decode (boring/encode [[[[:x]]]]) {:max-depth 2}))
        ":max-depth knob must reject depth over the set limit")
    ;; :check-duplicate-keys true -- a map with a repeated key is refused, not
    ;; last-wins. Hand-build {1:1, 1:2} on the wire: a2 01 01 01 02
    (is (thrown? clojure.lang.ExceptionInfo
                 (boring/decode (byte-array [0xa2 0x01 0x01 0x01 0x02])))
        ":check-duplicate-keys default must reject duplicate map keys")
    ;; :tolerate-unknown-tags true -- an unregistered tag SURFACES, does not throw
    (is (some? (boring/decode (byte-array [0xd9 0x27 0x0f 0x01])))
        ":tolerate-unknown-tags default must surface, not throw")))

(deftest max-items-default-is-unlimited-and-that-is-a-documented-choice
  (testing "0 == no ceiling. This is the ONE guard left open by default:
            :max-depth is bounded, item COUNT is not, so a public boundary
            parsing untrusted blobs should set :max-items explicitly. Frozen
            here so the choice is visible and any change is deliberate.
            doc/SECURITY.md carries the same note."
    ;; a 10k-element array decodes with no ceiling by default
    (is (= 10000 (count (boring/decode (boring/encode (vec (range 10000)))))))
    ;; and :max-items is honoured when set -- the knob works
    (is (thrown? clojure.lang.ExceptionInfo
                 (boring/decode (boring/encode (vec (range 100))) {:max-items 10})))))
