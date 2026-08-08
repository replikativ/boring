(ns boring.reader-config-test
  "`configure-reader!` and `reader-config` + `apply-reader-config!` must put the
   same option in the same field.

   There are two implementations of that mapping on purpose. `configure-reader!`
   reads the options map directly, which is right for a one-shot `decode`;
   `reader-config` resolves the same eleven options ONCE so a scan can apply them
   per document without re-reading the map. Delegating one to the other would put
   an array allocation on the `decode` path, so instead they are pinned here.

   Two implementations that must agree is exactly the shape of defect this
   codebase keeps finding -- an option honoured on one path and silently ignored
   on another. This test is what makes the duplication safe."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring])
  (:import (org.replikativ.boring Reader TagRegistry)))

(defn- fields
  "Every Reader field the two paths write, read back."
  [^Reader r]
  {:tolerate-unknown-tags (.-tolerateUnknownTags r)
   :on-unknown-record (.-onUnknownRecord r)
   :instant-fn (.-instantFn r)
   :instant-as-date (.-instantAsDate r)
   :full-date-as-sql-date (.-fullDateAsSqlDate r)
   :max-depth (.-maxDepth r)
   :max-items (.-maxItems r)
   :validate-utf8 (.-validateUtf8 r)
   :check-duplicate-keys (.-checkDuplicateKeys r)
   :auto-construct-records (.-autoConstructRecords r)
   :registry (.-registry r)})

(def ^:private cases
  [["defaults" {}]
   ["every boolean flipped"
    {:tolerate-unknown-tags false :validate-utf8 false
     :check-duplicate-keys false :auto-construct-records? true}]
   ["budgets" {:max-depth 7 :max-items 99}]
   ["instant :instant" {:instant-type :instant}]
   ["instant a function" {:instant-type (fn [x] x)}]
   ["sql-date" {:date-type :sql-date}]
   ["on-unknown-record :error" {:on-unknown-record :error}]
   ["on-unknown-record a function" {:on-unknown-record (fn [_ p] p)}]
   ["a registry" {:registry (boring/tag-registry)}]
   ["combined" {:max-depth 3 :max-items 5 :validate-utf8 false
                :date-type :sql-date :instant-type :instant
                :tolerate-unknown-tags false}]])

(deftest the-two-configuration-paths-agree
  (doseq [[label opts] cases]
    (testing label
      (let [a (boring/configure-reader! (Reader. (byte-array 1)) opts)
            b (boring/apply-reader-config! (Reader. (byte-array 1))
                                           (boring/reader-config opts))]
        (is (= (fields a) (fields b))
            (str label ": configure-reader! and reader-config disagree"))))))

(deftest a-resolved-config-can-be-applied-many-times
  (testing "the whole point: resolve once, apply per document, and every Reader
            must come out configured as `configure-reader!` would have left it"
    (let [opts {:max-depth 11 :max-items 22 :validate-utf8 false}
          cfg (boring/reader-config opts)
          want (fields (boring/configure-reader! (Reader. (byte-array 1)) opts))]
      (dotimes [_ 5]
        (is (= want (fields (boring/apply-reader-config! (Reader. (byte-array 1)) cfg))))))))

(deftest a-context-configures-its-readers-like-decode-does
  (testing "`nav/context` applies the resolved config, so a navigated value must
            realise exactly as `decode` would -- the promise nav's docstrings
            make about using the same options."
    (let [reg (boring/tag-registry)
          opts {:stringref false :registry reg :max-depth 64}
          bs (boring/encode {:a {:b [1 2 3]}} opts)
          ctx ((requiring-resolve 'boring.nav/context) opts)
          src ((requiring-resolve 'boring.nav/root) bs ctx)]
      (is (= (boring/decode bs opts)
             ((requiring-resolve 'boring.nav/value) src)))
      (is (= [1 2 3]
             ((requiring-resolve 'boring.nav/value) (get (get src :a) :b)))))))

(deftest an-empty-registry-is-still-set
  (testing "`(or (:registry opts) TagRegistry/EMPTY)` -- never left null, on
            either path"
    (is (identical? TagRegistry/EMPTY
                    (.-registry (boring/apply-reader-config!
                                 (Reader. (byte-array 1))
                                 (boring/reader-config {})))))))
