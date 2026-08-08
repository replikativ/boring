(ns cljsbench.runner
  "Runs the SAME conformance suite as the JVM — that is the point of keeping it
  in .cljc. Anything the JVM asserts, CLJS must assert too."
  (:require [cljs.test :as t]
            [goog.object :as gobj]
            [boring.canonical-parity-test]
            [boring.cljs-index-test]
            [boring.cljs-writer-opts-test]
            [boring.conformance-test]
            [boring.generative-test]
            [boring.golden-test]
            [boring.option-matrix-test]
            [boring.skip-parity-test]
            [boring.streaming-test]))

(enable-console-print!)

(defmethod t/report [::t/default :end-run-tests] [m]
  (println (str "\n" (:test m) " tests, " (+ (:pass m) (:fail m) (:error m))
                " assertions, " (:fail m) " failures, " (:error m) " errors"))
  ;; `goog.object/set` with a STRING name, not `(set! (.-exitCode js/process))`.
  ;; The property form compiles and does nothing under `-O advanced`: `process`
  ;; is not in this build's externs, so Closure renames `exitCode` and node
  ;; never sees it. This suite has therefore always exited 0, including on real
  ;; failures -- visible in `bin/ci`, which reported `FAILED (non-zero failure
  ;; count)` rather than `FAILED (exit 1)`, that being the branch it takes only
  ;; when the command SUCCEEDED and the log had to be grepped.
  ;;
  ;; The grep is why nothing broke; it is not why this should stay wrong.
  ;; Anyone running `node target/cljs-test.js` outside `bin/ci` got a green
  ;; shell on a red suite.
  (gobj/set js/process "exitCode" (if (t/successful? m) 0 1)))

(defn -main [& _]
  ;; THE LIST IS HAND-MAINTAINED, which is a trap worth naming: writing a test
  ;; in .cljc is not enough to make ClojureScript run it, so a portable test
  ;; can silently cover one platform. That is the same gap this file's
  ;; docstring promises against, one level up.
  (t/run-tests 'boring.canonical-parity-test
               'boring.cljs-index-test
               'boring.cljs-writer-opts-test
               'boring.conformance-test 'boring.generative-test 'boring.golden-test
               'boring.option-matrix-test 'boring.skip-parity-test
               'boring.streaming-test))

(set! *main-cli-fn* -main)
