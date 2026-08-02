(ns build
  (:require [clojure.java.shell]
            [borkdude.gh-release-artifact :as gh]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd])
  (:import (clojure.lang ExceptionInfo)))

(def org "replikativ")
(def lib 'org.replikativ/boring)
(def current-commit (b/git-process {:git-args "rev-parse HEAD"}))

;; The commit count as the patch component, matching konserve (0.9.x), hasch
;; (0.4.x) and the rest of the stack. Yes, this means a documentation commit
;; mints a new release number -- that is the deliberate replikativ convention,
;; and the CircleCI `tools/deploy` orb depends on it: it runs `clj -T:build
;; deploy` with no version environment of its own.
;;
;; This was briefly a `-SNAPSHOT` scheme gated on BORING_VERSION. That was a
;; release blocker rather than a refinement: with BORING_VERSION unset in CI --
;; and nothing sets it -- every push to main would have published a snapshot,
;; which `{:mvn/version "RELEASE"}` in the README does not resolve.
;;
;; BORING_VERSION remains as an override for cutting an out-of-band build.
(def version
  (or (System/getenv "BORING_VERSION")
      (format "0.1.%s" (b/git-count-revs nil))))
;; javac output ONLY. `deps.edn` puts this directory on the classpath so tests
;; and the REPL see the compiled Java, which means anything else that lands here
;; is also on the classpath. `jar` therefore stages into a SEPARATE directory:
;; copying `src` in here left a stale `boring/core.clj` that shadowed the real
;; source, so `bin/ci` silently tested whatever was current at the last `jar`.
(def class-dir "target/classes")
(def jar-dir "target/jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))
;; Its own directory, emptied on every build. Jars used to accumulate in
;; target/ across versions, so "upload the jar in target/" was ambiguous
;; exactly when it mattered.
(def release-dir "target/release")
(def jar-file (format "%s/%s-%s.jar" release-dir (name lib) version))

(defn clean [_] (b/delete {:path "target"}))

(defn javac
  "Compile the Java hot path — TWO source sets at TWO release levels.

  `src/java` targets JDK 9: the codec, and a `ByteSource` interface that names
  no FFM type. `src/java22` targets JDK 22: `SegmentSource`, the MemorySegment
  implementation of that interface, for mmap'ed and off-heap input.

  One jar can hold class files of mixed versions, because the JVM rejects a
  class only when it LOADS it. A JDK 9 process runs the codec and never
  touches SegmentSource; ask it for a segment and you get NoClassDefFoundError
  at that call rather than a jar that will not load at all. This is what keeps
  JDK 21 LTS — the incumbent, since 22/23/24 are non-LTS and already EOL — a
  supported runtime while 22+ additionally gets mmap.

  --release, NOT -source/-target. -target alone still compiles against the
  BUILD JDK's class library, so a method added after the target release links
  fine here and throws NoSuchMethodError on the user's JVM. --release pins the
  API too. Without it the jar carried class-file major version 69 (Java 25, the
  build JDK) while the README promised JDK 9+, so every advertised runtime
  except 25 failed with UnsupportedClassVersionError. Nothing caught it because
  CI tested SOURCES and never built or loaded the jar."
  [_]
  (b/javac {:src-dirs ["src/java"]
            :class-dir class-dir
            :basis @basis
            :javac-opts ["--release" "9" "-Xlint:-options"]})
  ;; Second, against JDK 22, with the release-9 output on the classpath so
  ;; SegmentSource can see the ByteSource interface it implements.
  (b/javac {:src-dirs ["src/java22"]
            :class-dir class-dir
            :basis (update @basis :classpath assoc class-dir {:path-key :none})
            :javac-opts ["--release" "22" "-Xlint:-options"]}))

(defn jar
  "Ships the compiled Java classes alongside the Clojure and ClojureScript
  sources, so consumers need no javac step of their own."
  [_]
  (javac nil)
  (b/delete {:path jar-dir})
  (b/delete {:path release-dir})
  (b/write-pom {:class-dir jar-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/replikativ/boring"
                      :connection "scm:git:git://github.com/replikativ/boring.git"
                      :developerConnection "scm:git:ssh://git@github.com/replikativ/boring.git"
                      :tag (b/git-process {:git-args "rev-parse HEAD"})}
                ;; Maven Central rejects a publish with no license metadata, and
                ;; a consumer's dependency scanner reads this rather than the
                ;; LICENSE file. See NOTICE for the third-party terms that apply
                ;; to test- and bench-scope dependencies only.
                ;; Maven Central rejects a publish without name, description,
                ;; url, licence, scm and developer. A consumer's dependency
                ;; scanner reads this rather than the LICENSE file.
                :pom-data
                [[:description
                  (str "Fast, portable CBOR serialization for Clojure and "
                       "ClojureScript, in a format other languages can read.")]
                 [:url "https://github.com/replikativ/boring"]
                 [:licenses
                  [:license
                   [:name "Apache License, Version 2.0"]
                   [:url "https://www.apache.org/licenses/LICENSE-2.0.txt"]
                   [:distribution "repo"]]]
                 [:developers
                  [:developer
                   [:name "Christian Weilbach"]]]]})
  (b/copy-dir {:src-dirs [class-dir "src"] :target-dir jar-dir})
  ;; The legal files belong INSIDE the artifact. The README directs consumers
  ;; to both and NOTICE is part of the distribution terms, but neither was
  ;; shipped -- a jar is what a consumer actually receives.
  (b/copy-file {:src "LICENSE" :target (str jar-dir "/META-INF/LICENSE")})
  (b/copy-file {:src "NOTICE"  :target (str jar-dir "/META-INF/NOTICE")})
  (b/jar {:class-dir jar-dir :jar-file jar-file})
  (println "built" jar-file))

(defn deploy
  "Push the jar to Clojars. CircleCI supplies CLOJARS_USERNAME and
  CLOJARS_PASSWORD from the clojars-deploy context, the same as konserve.

  Runs `check-artifact` first rather than trusting the jar: the class-file
  version, the legal files and the POM metadata are the properties a consumer
  actually receives, and they were all wrong at one point while every test was
  green."
  [_]
  (jar nil)
  (let [{:keys [exit]} (clojure.java.shell/sh "bin/check-artifact")]
    (when-not (zero? exit)
      (throw (ex-info "boring: refusing to deploy, bin/check-artifact failed" {}))))
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir jar-dir})}))

(defn install
  "Install to ~/.m2 so ../konserve-sync and ../datahike can depend on it by
  coordinate rather than :local/root."
  [_]
  (jar nil)
  (b/install {:basis @basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir jar-dir}))

;; ---------------------------------------------------------------------------
;; GitHub release, called by the clj-tools orb's `tools/release` job as
;; `clojure -T:build release`. Mirrors konserve's, including the backoff: the
;; asset upload races the release creation often enough that a single attempt
;; fails intermittently, which is worse than slow.

(defn- fib [a b] (lazy-seq (cons a (fib b (+ a b)))))

(defn- retry-with-fib-backoff [retries exec-fn test-fn]
  (loop [idle-times (take retries (fib 1 2))]
    (let [result (exec-fn)]
      (if (test-fn result)
        (do (println "Returned: " result)
            (if-let [sleep-ms (first idle-times)]
              (do (println "Retrying with remaining back-off times (in s): " idle-times)
                  (Thread/sleep (* 1000 ^long sleep-ms))
                  (recur (rest idle-times)))
              result))
        result))))

(defn- try-release []
  (try (gh/overwrite-asset {:org org
                            :repo (name lib)
                            :tag version
                            :commit current-commit
                            :file jar-file
                            :content-type "application/java-archive"
                            :draft false})
       (catch ExceptionInfo e
         (assoc (ex-data e) :failure? true))))

(defn release
  "Attach the built jar to a GitHub release. Needs GITHUB_TOKEN."
  [_]
  (jar nil)
  (println "Trying to release artifact...")
  (let [ret (retry-with-fib-backoff 10 try-release :failure?)]
    (if (:failure? ret)
      (do (println "GitHub release failed!") (System/exit 1))
      (println (:url ret)))))
