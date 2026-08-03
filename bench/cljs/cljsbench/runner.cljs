(ns cljsbench.runner
  "Runs the SAME conformance suite as the JVM — that is the point of keeping it
  in .cljc. Anything the JVM asserts, CLJS must assert too."
  (:require [cljs.test :as t]
            [boring.canonical-parity-test]
            [boring.conformance-test]
            [boring.generative-test]
            [boring.golden-test]
            [boring.streaming-test]))

(enable-console-print!)

(defmethod t/report [::t/default :end-run-tests] [m]
  (println (str "\n" (:test m) " tests, " (+ (:pass m) (:fail m) (:error m))
                " assertions, " (:fail m) " failures, " (:error m) " errors"))
  (set! (.-exitCode js/process) (if (t/successful? m) 0 1)))

(defn -main [& _]
  ;; THE LIST IS HAND-MAINTAINED, which is a trap worth naming: writing a test
  ;; in .cljc is not enough to make ClojureScript run it, so a portable test
  ;; can silently cover one platform. That is the same gap this file's
  ;; docstring promises against, one level up.
  (t/run-tests 'boring.canonical-parity-test
               'boring.conformance-test 'boring.generative-test 'boring.golden-test
               'boring.streaming-test))

(set! *main-cli-fn* -main)
