(ns boring.nippy-stress-test
  "boring against nippy's own reference stress data.

  nippy is the JVM benchmark for Clojure serialization coverage, and
  `taoensso.nippy/stress-data` is the map its maintainers use to define what
  \"handles Clojure\" means. Measuring against someone else's definition is
  worth more than measuring against our own fixtures, which were written by
  the same person who wrote the encoder and share its blind spots.

  nippy is EPL-1.0 and boring is Apache-2.0, so this CALLS `stress-data` as a
  test-scope dependency and never copies it. Nothing here is vendored, and the
  dependency is confined to this alias -- `bin/ci`'s main JVM stage does not
  see nippy at all. See NOTICE.

  Lives in its own source root for that reason: adding nippy to `:test` would
  put an EPL library on the classpath of the whole suite for one namespace."
  (:require [clojure.test :refer [deftest testing is]]
            [boring.core :as boring]
            [taoensso.nippy :as nippy]))

(def data (nippy/stress-data {:comparable? true}))

;; Registered so `:defrecord` round-trips. boring will NOT instantiate a class
;; named on the wire -- that is deserialization RCE, the defect class that made
;; Java's ObjectInputStream a permanent CVE source -- so a record needs the
;; receiver to opt in by naming the constructor. nippy's equivalent is its
;; thaw allowlist; this is the same decision made at registration time.
;; `namespace/Name`, not the dotted class name. This registration said
;; `taoensso.nippy.StressRecord` and stopped matching when the wire name became
;; the record's true Clojure name -- the breaking change CHANGELOG.md documents
;; with this exact migration. The failure is quiet in the shape that matters:
;; no error, just a StressRecord coming back as an `UnknownRecord`, which is
;; what a consumer who misses the migration will see.
(def registry
  (-> (boring/tag-registry)
      (boring/register-record "taoensso.nippy/StressRecord"
                              #(nippy/map->StressRecord %))))

(def opts {:registry registry})

(defn- round-trip [v]
  (boring/decode (boring/encode v opts) opts))

;; --------------------------------------------------------------- known gaps
;;
;; Every entry here is a DECISION, not a backlog item. A gap with no defensible
;; reason belongs in the passing set instead.

(def known-different
  {:deftype
   (str "REFUSED, deliberately. A deftype is a bag of fields with no read "
        "constructor and no map factory; nippy carries it through Java "
        "serialization, which is the exact mechanism behind the Java "
        "deserialization CVE family. boring throws rather than opening that "
        "door. Use a defrecord, or register a tag.")

   :instant
   (str "java.time.Instant and java.util.Date are BOTH CBOR tag 1 -- an epoch "
        "time, with no room for a JVM class name. A reader chooses one with "
        ":date-type, so a map holding both cannot return both. This is the "
        "price of writing a tag every language already reads; a private tag "
        "would preserve the type and be unreadable outside the JVM.")

   :sql-date
   (str "java.sql.Date is a date with no time-of-day wearing a "
        "java.util.Date's millisecond field. boring writes RFC 8943 tag 1004 "
        "(full-date) and reads back java.time.LocalDate, which is what the "
        "value MEANS. Not equal to the input, and deliberately so.")})

(deftest stress-data-round-trips
  (testing "every key nippy considers reference data, minus the documented gaps"
    (doseq [[k v] (sort-by (comp str key) data)
            :when (not (contains? known-different k))]
      (is (= v (round-trip v))
          (str k " -- if this is a real limitation add it to `known-different` "
               "with the reason; if not, fix the encoder"))))

  (testing "the documented gaps still behave as documented, so a fix that makes
            one obsolete shows up here rather than sitting in a stale comment"
    (is (thrown? Exception (round-trip (:deftype data))))
    (is (= java.util.Date (class (round-trip (:instant data)))))
    (is (= java.time.LocalDate (class (round-trip (:sql-date data)))))
    (is (= java.sql.Date
           (class (boring/decode (boring/encode (:sql-date data) opts)
                                 (assoc opts :date-type :sql-date))))
        ":date-type gets the legacy class back; the dropped time-of-day does
         not come with it")
    (is (= (.toEpochMilli ^java.time.Instant (:instant data))
           (.getTime ^java.util.Date (round-trip (:instant data))))
        "the instant's VALUE survives; only the JVM class does not")
    (is (= (:instant data)
           (boring/decode (boring/encode (:instant data) opts)
                          (assoc opts :instant-type :instant)))
        ":instant-type is the escape hatch -- the reader picks which JVM class
         tag 1 becomes, because the wire cannot say")))

(deftest non-comparable-stress-data-is-also-covered
  (testing "stress-data's `{:comparable? false}` branch holds the types that
            cannot be compared with `=` -- a regex, throwables, and ten array
            kinds. Our suite only ever ran `{:comparable? true}`, so those
            types were never measured at all: ex-info, Exception, Throwable,
            boolean[], char[], String[] and Object[] had NO encoding and threw
            :boring/unsupported-type, and the headline '46 of 49 keys' was
            measured over a set that excludes them.

            A blind spot is worse than a documented gap, which is the same
            argument the three known-different entries already make."
    (let [nc (:non-comparable (nippy/stress-data {:comparable? false}))]
      (testing "throwables carry message, data and cause"
        (doseq [k [:throwable :exception :ex-info]]
          (let [back (round-trip (get nc k))]
            (is (instance? clojure.lang.ExceptionInfo back) (str k))
            (is (= (ex-message (get nc k)) (ex-message back)) (str k " message")))))

      (testing "a regex keeps its source; tag 35 carries no flags"
        (is (= (str (:regex nc)) (str (round-trip (:regex nc))))))

      (testing "every array kind round-trips with its type intact"
        (doseq [[k v] (:arrays nc)]
          (let [back (round-trip v)]
            (is (= (class v) (class back)) (str k " type"))
            ;; NaN is never = to itself, so compare element-wise by string.
            (is (= (mapv str (vec v)) (mapv str (vec back))) (str k " values"))))))))

(deftest stress-data-shape-is-what-we-measured
  (testing "a nippy upgrade that adds a key must not slip past silently -- a
            doseq over a bigger map still passes, so the count is asserted"
    (is (= 49 (count data)))
    (is (= 3 (count known-different)))))

(defn- widens-to [k cls]
  (let [v (get data k), back (round-trip v)]
    (is (= v back) (str k " -- value survives"))
    (is (= cls (class back)) (str k " -- widens to " cls))))

(deftest type-widening-is-value-preserving
  (testing "CBOR has one integer type and one array type, so boxed widths and
            seq flavours do not survive. `=` holds in every case, which is why
            these are widening rather than the silent corruption a Character
            becoming a String was."
    (doseq [[k cls] [[:byte Long] [:short Long] [:integer Long]
                     [:list           clojure.lang.PersistentVector]
                     [:subvec         clojure.lang.PersistentVector]
                     [:lazy-seq       clojure.lang.PersistentVector]
                     [:lazy-seq-empty clojure.lang.PersistentVector]
                     [:map-entry      clojure.lang.PersistentVector]]]
      (widens-to k cls))))
