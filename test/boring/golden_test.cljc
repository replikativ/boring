(ns boring.golden-test
  "Enforce the frozen wire format.

  Two directions, and both matter:

  - **Forward**: every frozen byte string must still DECODE to the value it was
    written from. This is the guarantee that protects data already on disk. It
    is the one that must never break.
  - **Backward**: encoding the value must still PRODUCE the frozen bytes. This
    is what makes a format change impossible to ship by accident — it fails
    here first, in a diff a reviewer cannot miss.

  A failure in the forward direction is a data-loss bug. A failure in the
  backward direction is a format change, which may be intentional; if it is,
  regenerate with `clj -M:dev -e \"(require 'gen-golden)(gen-golden/-main)\"`
  and justify the diff in the commit message."
  (:require [clojure.test :refer [deftest testing is]]
            [boring.core :as boring]
            [boring.conformance :as c]
            [boring.golden :as g]
            [boring.golden-v1 :as v1]))

(defn- check-corpus [corpus frozen platform-label]
  (doseq [[label value opts] corpus]
    (testing (str platform-label " " label)
      (if-let [expected-hex (get frozen label)]
        (do
          (testing "encoding still produces the frozen bytes"
            (is (= expected-hex (c/bytes->hex (boring/encode value opts))) label))
          (testing "the frozen bytes still decode to the original value"
            (is (c/equiv? value (boring/decode (c/hex->bytes expected-hex) opts))
                label)))
        (is false (str "no frozen bytes for " label
                       " — regenerate the golden corpus"))))))

(deftest portable-corpus-is-frozen
  (check-corpus g/portable v1/portable "portable"))

#?(:clj
   (deftest jvm-corpus-is-frozen
     (check-corpus g/jvm-only v1/jvm-only "jvm")))

(deftest corpus-and-frozen-file-agree-on-size
  (testing "a value added to the corpus without regenerating must fail loudly
            rather than being silently skipped"
    (is (= (count g/portable) (count v1/portable)))
    #?(:clj (is (= (count g/jvm-only) (count v1/jvm-only))))))

(deftest frozen-labels-are-unique
  (testing "a duplicated label would silently shadow one vector in the map"
    (let [labels (map first g/portable)]
      (is (= (count labels) (count (distinct labels)))))
    #?(:clj (let [labels (map first g/jvm-only)]
              (is (= (count labels) (count (distinct labels))))))))

;; The portable corpus doubles as the cross-platform byte-identity check: one
;; frozen file, asserted from both runtimes, is a stronger statement than
;; comparing two runs to each other, because it also pins them to a value that
;; a human reviewed once.
(deftest portable-corpus-proves-cross-platform-identity
  (testing "the same frozen bytes are produced by whichever platform runs this"
    (is (pos? (count v1/portable)))
    (doseq [[label value opts] g/portable]
      (is (= (get v1/portable label) (c/bytes->hex (boring/encode value opts)))
          (str label " on " #?(:clj "JVM" :cljs "ClojureScript"))))))
