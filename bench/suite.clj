(ns suite
  "One command for the numbers boring claims in public.

  Everything here already existed as a separate namespace. What did NOT exist
  was a way to produce the whole set in one run, on one machine, in one state
  -- so the published figures came from several sessions and nobody could
  reproduce the table as a table. `bin/bench` runs this and writes the output
  to a file with the machine's own description at the top, because a benchmark
  number without its hardware is a rumour.

  Order is deliberate:

    1. SIZE first. Sizes are deterministic, so they are the one section that
       is trustworthy on a busy machine -- if the run is going to be thrown
       away for noise, at least this part survives.
    2. A/B interleaved timing next (`ab`). Short alternating bursts, min over
       many rounds. This is the comparison to trust when the machine is not
       quiet: criterium measures A for ~7s and then B for ~7s, and any drift
       in background load lands entirely on one side.
    3. criterium last (`bench`), for the absolute µs/op figures. Slowest, and
       the most sensitive to a noisy machine.
    4. navigation (`nav`) and mmap (`mmap`) last. Both compare boring against
       ITSELF -- a walk that builds nothing versus a full decode, a mapping
       versus a heap array -- so neither depends on how the other codecs
       happened to warm up, and both bring their own harness.

  One global warmup covers `ab` and `bench`, because per-cell warmup is not
  enough: hako's small-map encode measured 2.52 / 1.30 / 1.15 / 1.08 µs across
  four consecutive runs of the same cell. `skip` and `nav` warm themselves --
  they compare boring against boring, so they do not need every codec exercised
  first."
  (:require [ab]
            [bench]
            [published]
            [boring.core :as boring]
            [compressors]
            [sizes])
  (:import (org.replikativ.boring Reader)))

(defn- machine []
  (let [rt (Runtime/getRuntime)]
    (format "%s %s / %s %s / %d cores / %d MB max heap"
            (System/getProperty "os.name") (System/getProperty "os.version")
            (System/getProperty "java.vm.name") (System/getProperty "java.version")
            (.availableProcessors rt) (quot (.maxMemory rt) (* 1024 1024)))))

(defn- banner [title]
  (println)
  (println (apply str (repeat 78 \=)))
  (println title)
  (println (apply str (repeat 78 \=))))

(defn -main [& args]
  (let [only (set args)
        run? (fn [k] (or (empty? only) (contains? only (name k))))]
    (println "boring benchmark suite")
    (println "machine:" (machine))
    (println)
    (println "Timing sections are comparative. Absolute µs/op moves with the")
    (println "machine; the RATIOS are the claim, and they are what to compare")
    (println "across runs.")

    ;; First: the tables README.md and doc/PERFORMANCE.md actually publish.
    ;; They had no harness at all until this existed, so the figures could not
    ;; be checked against the code that produced them.
    (when (run? :published)
      (banner "PUBLISHED TABLES — what README.md and PERFORMANCE.md claim")
      (published/-main (if (run? :criterium) "timing" "size")))

    (when (run? :size)
      (banner "SIZE — deterministic, trustworthy even on a busy machine")
      (sizes/-main))

    (when (run? :compress)
      (banner "SIZE under compression — is CBOR's overhead redundancy or information?")
      (compressors/-main))

    ;; The warmup belongs to the timing sections only, and it has to happen
    ;; before the FIRST of them rather than inside each: the process-wide
    ;; speedup it is compensating for does not reset between namespaces.
    (when (or (run? :ab) (run? :criterium))
      (banner "warming up")
      (let [w (boring/writer 65536)
            rdr (Reader. (byte-array 1))]
        (print "exercising every payload through every codec... ") (flush)
        (ab/global-warmup! w rdr)
        (println "done")))

    (when (run? :ab)
      (banner "TIMING, A/B interleaved — the comparison to trust")
      (ab/-main "no-warmup"))

    (when (run? :criterium)
      (banner "TIMING, criterium — absolute µs/op, slowest and noisiest")
      (bench/-main))

    ;; The last two sections are resolved LAZILY rather than required at the top
    ;; of this ns. Both touch java.lang.foreign and so need JDK 22+: skip.clj
    ;; compares its scanner across byte[], a heap segment and an mmap'ed one,
    ;; and nav.clj pulls in boring.mmap.
    ;;
    ;; Honest note on what that does and does not buy. It does NOT make this
    ;; suite runnable on an older JVM -- the :bench alias already pins JDK 25,
    ;; because hako's classes are class-file version 69 and nothing here loads
    ;; without them. What it buys is that these sections degrade to a printed
    ;; skip instead of an UnsupportedClassVersionError, and that this ns keeps
    ;; no load-time dependency on FFM if the hako peer ever becomes optional.
    ;; Do not read it as JDK 21 support for the suite.
    (when (run? :nav)
      (banner "NAVIGATION — skipping, and cursor vs decode-then-get-in")
      (if-let [f (try (requiring-resolve 'nav/-main) (catch Throwable _ nil))]
        (f)
        (println "skipped: this section needs JDK 22+, and this JVM cannot load it")))

    (when (run? :mmap)
      (banner "MMAP — selective read, append, and chunked compression")
      (if-let [f (try (requiring-resolve 'mmap/-main) (catch Throwable _ nil))]
        (f)
        (println "skipped: this section needs JDK 22+, and this JVM cannot load it")))

    (println)
    (println "done.")
    (flush)))
