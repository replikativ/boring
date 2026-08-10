(ns boring.build-freshness-test
  "One assertion: the compiled Java on the classpath is not older than its
  source.

  `clojure -M:test` DOES NOT RUN `javac`. `:paths` carries \"target/classes\",
  which `clojure -T:build javac` builds and which is not checked in, so a test
  run silently exercises whatever classes were last compiled. Editing
  `Reader.java` or `Writer.java` and running the suite tests the OLD code.

  Observed, and it is the reason this file exists: a session reported 29
  failures that were entirely stale classes and took two full suite runs to
  attribute. They did not look like staleness -- they looked like genuine
  regressions in the feature under development, a `:boring/stringref-not-
  navigable` from a document that should have been navigable, which sent me
  into the reader for the better part of an hour. The only signal anywhere was
  a reflection warning in an unrelated `-M:dev` run, and only because the
  member in question was NEW; a change to an existing method's body gives no
  warning at all.

  Same class as the fixtures problem this suite already worries about: the run
  completed, reported, and was measuring something other than what it claimed.
  See #42."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io])
  (:import (java.io File)))

(defn- newest
  "Modification time of the most recently touched file under `dir` matching
  `ext`, or nil when there are none."
  [dir ext]
  (let [^File d (io/file dir)]
    (when (.isDirectory d)
      (->> (file-seq d)
           (filter #(and (.isFile ^File %) (.endsWith (.getName ^File %) ext)))
           (map #(.lastModified ^File %))
           (reduce max 0)
           (#(when (pos? %) %))))))

(deftest the-compiled-java-is-not-older-than-its-source
  (testing "a stale target/classes makes every other test in this suite a
            statement about code that is no longer in the tree"
    (let [src (newest "src/java" ".java")
          cls (newest "target/classes" ".class")]
      (is (some? src) "src/java must hold sources, or this asserts nothing")
      (is (some? cls)
          "target/classes must hold compiled classes -- run `clojure -T:build javac`")
      (when (and src cls)
        ;; A ONE-SECOND SLACK, because some filesystems store mtimes at second
        ;; granularity and a class compiled in the same second as its source
        ;; edit is fresh, not stale. Being off by a second in the LENIENT
        ;; direction costs nothing; the failure this catches is minutes to
        ;; hours old.
        (is (<= (- (long src) 1000) (long cls))
            (str "target/classes is STALE: the newest .java is "
                 (quot (- (long src) (long cls)) 1000)
                 "s newer than the newest .class. Run `clojure -T:build javac` "
                 "and re-run -- until you do, every Java-backed assertion in "
                 "this suite is about the previous build."))))))
