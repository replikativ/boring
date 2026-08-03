(ns boring.writer-opts-test
  "`(writer size opts)` resolves encode options ONCE instead of per call.

  This is an allocation fix with a correctness surface: options that are
  resolved somewhere new must still take effect, still be overridable, and
  still be validated. Measured, a log event went from 301 heap bytes to 15
  through `encode-buffered!`, and from 248 to ZERO through `write-to!` --
  `resolve-opts` merges the caller's map over the profile defaults, and that
  merge was running on every single encode.

  It matters most exactly where it is least wanted: a file meant to be read
  with `boring.nav` must be written `:stringref false`, so it cannot use the
  nil-opts fast path that made this invisible in the existing benchmarks.

  JVM only -- ClojureScript's `writer` has no opts arity."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]))

(def value {"lvl" "info" "n" 12345 "msg" "hello"
            "ctx" {"thread" "main" "ns" "app.core"}})

(deftest writer-opts-take-effect
  (testing "a writer built with opts encodes as if they were passed per call"
    (is (= (seq (boring/encode-into! (boring/writer 4096 {:stringref false}) value))
           (seq (boring/encode-into! (boring/writer 4096) value {:stringref false})))))
  (testing "and differ from the default, so the test cannot pass vacuously"
    (is (not= (seq (boring/encode-into! (boring/writer 4096 {:stringref false}) value))
              (seq (boring/encode-into! (boring/writer 4096) value))))))

(deftest writer-opts-apply-to-every-2-arity-entry-point
  (let [w (boring/writer 4096 {:stringref false})
        expect (seq (boring/encode value {:stringref false}))]
    (testing "encode-into!"
      (is (= expect (seq (boring/encode-into! w value)))))
    (testing "encode-buffered! + buffer"
      (let [n (boring/encode-buffered! w value)]
        (is (= expect (seq (java.util.Arrays/copyOf (boring/buffer w) (int n)))))))
    (testing "write-to!"
      (let [baos (java.io.ByteArrayOutputStream.)]
        (boring/write-to! w value baos)
        (is (= expect (seq (.toByteArray baos))))))))

(deftest explicit-opts-replace-rather-than-merge
  (testing "a 3-arity call REPLACES the writer's opts -- one place to look for
            what a call used, rather than a precedence rule to reason about"
    (let [w (boring/writer 4096 {:stringref false})]
      (is (= (seq (boring/encode-into! w value {}))
             (seq (boring/encode value {})))
          "explicit {} must mean the defaults, not the writer's :stringref false")
      (testing "and the writer is not mutated by that call"
        (is (= (seq (boring/encode-into! w value))
               (seq (boring/encode value {:stringref false}))))))))

(deftest writer-opts-are-validated-at-construction
  (testing "resolving once means conflicts are caught when the writer is made,
            not on the first encode -- a better place to find out"
    (is (thrown? clojure.lang.ExceptionInfo
                 (boring/writer 4096 {:profile :canonical :stringref true})))))

(deftest a-plain-writer-still-uses-the-defaults
  (testing "the 1- and 0-arity writers are unchanged"
    (is (= (seq (boring/encode-into! (boring/writer) value))
           (seq (boring/encode value))))
    (is (= (seq (boring/encode-into! (boring/writer 4096) value))
           (seq (boring/encode value))))))

(deftest a-raw-java-writer-has-no-opts
  (testing "constructing the Java class directly leaves opts null, which must
            fall back to the defaults rather than NPE -- the benchmarks and
            konserve both do this"
    (let [w (org.replikativ.boring.Writer. 4096)]
      (is (= (seq (boring/encode-into! w value))
             (seq (boring/encode value)))))))
