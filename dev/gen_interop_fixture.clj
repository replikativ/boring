(ns gen-interop-fixture
  "Write interop/fixture.cbor — the file the reference readers are tested against.

  Regenerate with:

    bin/regen-interop

  The fixture is COMMITTED so the reference readers can be run standalone, by
  someone who has never touched Clojure. If a change to boring alters this
  file, that is a wire change and the reference readers must be checked against
  it — which is the whole point of testing them in CI rather than pasting
  snippets into a document."
  (:require [boring.core :as boring]))

(defrecord Point [x y])

(def fixture
  "One value per tag boring can emit, keyed by a name the reader asserts on."
  {"keyword"      :user/name
   "keyword-bare" :simple
   "symbol"       'my.ns/sym
   "string"       "hello"
   "string-utf8"  "héllo wörld 💩"
   "int"          42
   "int-negative" -7
   "float"        1.5
   "bool-true"    true
   "bool-false"   false
   "null"         nil
   "vector"       [1 2 3]
   "map"          {:a 1 :b "two"}
   "set"          #{1 2 3}
   "record"       (->Point 3 4)
   "uuid"         #uuid "9682952b-fafa-4b41-8e4a-31ae948d6f08"
   "bignum"       (bigint "18446744073709551616")
   "decimal"      1.50M
   "ratio"        (/ 22 7)
   "instant"      (java.util.Date. 1234567890123)
   "bytes"        (byte-array [1 2 253])
   "long-array"   (long-array [1 -2 3])
   "double-array" (double-array [1.5 -2.5])
   ;; Stringref threshold, exactly at the boundary. A 3-byte string encodes as
   ;; 4 bytes and a reference to an index below 24 costs 3, so it IS entered in
   ;; the table -- as boring does, and as cbor2 does. A reader using `>` rather
   ;; than `>=` skips it, and because the tables must agree exactly, every
   ;; index after this point then resolves to the WRONG string.
   ;;
   ;; Here because the Rust reference reader had that off-by-one and passed
   ;; anyway: nothing else in this fixture put a 3-byte string in a referencing
   ;; position. A fixture only catches what it contains.
   "sr-threshold" ["abc" "abc" "wxyz" "wxyz"]

   ;; The one extension: an array of maps. Written with the keys ONCE under
   ;; tag 39649 when :shapes is on.
   "shaped"       [{:e 1 :a :x} {:e 2 :a :y}]

   ;; RAGGED ROWS, and it is here for the same reason "sr-threshold" is: a
   ;; fixture only catches what it contains. The shape's keys are the UNION of
   ;; every row's, so a row can lack one -- spelled `undefined` (0xf7) for a
   ;; gap in the middle and a SHORT ROW for a missing tail. Both reference
   ;; readers reconstructed this as a zip, which is right for the short row by
   ;; accident and wrong for the gap: it produced a phantom key holding
   ;; `undefined`. CI was green throughout, because nothing in this fixture had
   ;; a ragged row.
   ;;
   ;; `:a nil` in the first row is the other half: `null` (0xf6) is a key that
   ;; is PRESENT with a nil value, and a reader that treats it as absence loses
   ;; the difference between `{:a nil}` and `{}`.
   "ragged"       [{:a nil :b 1 :c 2}     ; :a present, value nil
                   {:a 3 :c 4}            ; :b absent -- a gap, 0xf7
                   {:a 5}]})              ; :b and :c absent -- a short row

(defn -main [& _]
  (let [out "interop/fixture.cbor"
        bs (boring/encode fixture {:shapes true})]
    (with-open [o (java.io.FileOutputStream. out)]
      (.write o ^bytes bs))
    (println "wrote" out (alength ^bytes bs) "bytes," (count fixture) "entries")))
