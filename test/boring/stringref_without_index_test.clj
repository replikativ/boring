(ns boring.stringref-without-index-test
  "Stringref is a CBOR feature. The index is boring's own extension. One does
  not require the other, and this file exists because a session claimed it did.

  THE CONFUSION, recorded so it does not recur. `nav/source` refuses a stringref
  document that carries no index, with `:boring/stringref-not-navigable`. That
  is a NAVIGATION restriction and it is correct: a reference `25(n)` means \"the
  n-th string defined so far in this namespace\", so resolving one requires
  having decoded everything before it, and a cursor holding only an offset has
  decoded nothing. boring therefore writes a pointer table into the index frame
  and nav reads references through that.

  From which it was concluded that stringref needs an index at all -- i.e. that
  boring's default output is not plain CBOR. It very much is. Encoding and
  decoding stringref involve the index nowhere; only random access does.

  The distinction matters because it is the difference between \"we extend CBOR
  with an optional index\" and \"we broke CBOR\". Pinned in both directions
  below."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.nav :as nav]))

(def ^:private repeated (vec (repeat 6 "a-repeated-string")))

(deftest stringref-needs-no-index-to-be-written-or-read
  (let [sr (boring/encode repeated {:stringref true})
        plain (boring/encode repeated {:stringref false})]
    (testing "it is the DEFAULT, and it compresses"
      (is (= (vec (boring/encode repeated)) (vec sr))
          "stringref on by default -- if this flips, the claim below is about
           a non-default path and the interop story changes with it")
      (is (< (count sr) (count plain))))
    (testing "no index frame is present"
      ;; `read-index` is how nav finds one: the 9-byte trailer, the pointer,
      ;; and a tag-27 frame carrying the name. Nil means there is none.
      (is (nil? (#'boring.nav/read-index (nav/source plain nil)))))
    (testing "and it round-trips"
      (is (= repeated (boring/decode sr))))
    (testing "the bytes are the standard construction: tag 256 opening a
              namespace, then tag 25 back-references"
      (is (= [0xd9 0x01 0x00] (mapv #(bit-and % 0xff) (take 3 sr)))
          "d9 0100 is tag 256")
      (is (some? (some #{[0xd8 0x19]} (partition 2 1 (mapv #(bit-and % 0xff) sr))))
          "d8 19 is tag 25"))))

(deftest only-NAVIGATION-needs-the-index
  (testing "the refusal is on nav/source, and it is about resolving a reference
            from an offset -- not about the document being malformed"
    (let [sr (boring/encode repeated {:stringref true})]
      (is (= :boring/stringref-not-navigable
             (try (do (nav/source sr nil) nil)
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          "a stringref document with no pointer table is refused for NAVIGATION")
      (is (= repeated (boring/decode sr))
          "while the very same bytes decode, which is the whole point")))
  (testing "add an index and navigation works on identical semantics"
    (let [ix (boring/encode-indexed repeated {:stringref true})]
      (is (= repeated (boring/decode ix)))
      (is (= "a-repeated-string" (nav/value (nav/walk (nav/root ix) [4])))))))

(deftest the-index-is-additive-not-a-different-encoding
  (testing "an indexed document still decodes under a reader that knows nothing
            about the index: the frame is a separate top-level item and
            `decode` yields the DATA, not a wrapper"
    (doseq [[label opts] [["stringref" {:stringref true}]
                          ["plain" {:stringref false}]
                          ["shaped" {:shapes true :stringref true}]]]
      (let [v (vec (for [i (range 80)] {:e i :a :user/name :v (str "p" i)}))]
        (is (= v (boring/decode (boring/encode-indexed v opts))) label)
        (is (= v (boring/decode (boring/encode v opts))) label)))))

(deftest the-interop-profile-turns-both-extensions-off
  (testing "doc/INTEROP.md promises `:profile :interop` emits only registered
            CBOR. Stringref is registered (tags 25/256) but niche, and shapes
            are not registered at all, so both are off there -- the escape
            hatch for a consumer who will not implement hooks."
    (let [v (vec (for [i (range 40)] {:e i :a :user/name :v (str "p" i)}))
          io (boring/encode v {:profile :interop})]
      (is (not= [0xd9 0x01 0x00] (mapv #(bit-and % 0xff) (take 3 io)))
          "no stringref namespace")
      (is (not= [0xd9 0x9a 0xe1] (mapv #(bit-and % 0xff) (take 3 io)))
          "and no tag 39649")
      (is (= v (boring/decode io))))))
